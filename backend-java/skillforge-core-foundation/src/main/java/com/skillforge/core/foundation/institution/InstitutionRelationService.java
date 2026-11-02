package com.skillforge.core.foundation.institution;

import static com.skillforge.core.foundation.institution.InstitutionRelationContracts.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.IntegrationContracts.ProvisionInstitution;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.AuditService;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 单父机构树与精确管理目录；树关系不传递业务、成员、执行或费用权限。 */
@Service
public class InstitutionRelationService {
    private final InstitutionRelationMapper mapper;
    private final PlatformManagementService management;
    private final FoundationAccess foundation;
    private final InstitutionIntegrationService institutions;
    private final ResourceQuota quota;
    private final AuditService audit;
    private final ObjectMapper json;

    public InstitutionRelationService(InstitutionRelationMapper mapper,PlatformManagementService management,FoundationAccess foundation,
            InstitutionIntegrationService institutions,ResourceQuota quota,AuditService audit,ObjectMapper json) {
        this.mapper=mapper;this.management=management;this.foundation=foundation;this.institutions=institutions;
        this.quota=quota;this.audit=audit;this.json=json;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page platformList(Long parentTenantId,int page,int pageSize) {
        var scope=management.authorizedTenantScope("platform.institution_relations.read");
        paging(page,pageSize);
        if(parentTenantId!=null)requireTarget(scope.tenantIds(),parentTenantId);
        long tree=mapper.treeRevision();
        var items=mapper.platformRows(scope.tenantIds(),parentTenantId,pageSize,((long)page-1)*pageSize).stream().map(row->view(row,tree)).toList();
        return new Page(items,mapper.platformCount(scope.tenantIds(),parentTenantId),page,pageSize,tree);
    }

    @Transactional(rollbackFor=Exception.class)
    public Relation attach(Change command,String key) {return mutate("attach",command,key);}
    @Transactional(rollbackFor=Exception.class)
    public Relation move(Change command,String key) {return mutate("move",command,key);}
    @Transactional(rollbackFor=Exception.class)
    public Relation detach(Change command,String key) {return mutate("detach",command,key);}

    private Relation mutate(String operation,Change command,String requestKey) {
        if(command==null||command.childTenantId()==null||command.childTenantId()<1||command.expectedRevision()<1)
            throw bad("child_tenant_id与expected_revision必须为正数");
        String action="platform.institution_relations."+operation;
        var scope=management.authorizedTenantScope(action);
        requireTarget(scope.tenantIds(),command.childTenantId());
        Long parent=command.parentTenantId();
        if("detach".equals(operation)) {if(parent!=null)throw bad("解除关系不能指定parent_tenant_id");}
        else {if(parent==null)throw bad("parent_tenant_id必填");requireTarget(scope.tenantIds(),parent);}
        if(Objects.equals(command.childTenantId(),parent))throw conflict("机构不能成为自己的上级");
        String key=key(requestKey),fingerprint=fingerprint(command),actor=scope.actorRef();
        mapper.lockRequest(actor,operation,key);
        Relation prior=prior(actor,operation,key,fingerprint);
        if(prior!=null)return prior;
        mapper.lockTree();
        var child=required(command.childTenantId());
        if(child.revision()!=command.expectedRevision())throw conflict("机构关系修订已变化");
        if("attach".equals(operation)&&child.parentTenantId()!=null)throw conflict("机构已有上级，请使用迁移");
        if(!"attach".equals(operation)&&child.parentTenantId()==null)throw conflict("机构当前没有上级");
        if(child.parentTenantId()!=null)requireTarget(scope.tenantIds(),child.parentTenantId());
        if(Objects.equals(child.parentTenantId(),parent))throw conflict("关系目标未变化");
        if(parent!=null) {required(parent);if(mapper.wouldCycle(child.tenantId(),parent))throw conflict("机构关系不能形成循环");}
        if(mapper.change(child.tenantId(),parent,command.expectedRevision())!=1)throw conflict("机构关系修订竞争");
        var result=view(required(child.tenantId()),mapper.treeRevision());
        append(result,actor,key);
        record(actor,operation,key,fingerprint,result,null);
        return result;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page subInstitutions(int page,int pageSize) {
        var actor=headquarters("read");paging(page,pageSize);
        long parent=Long.parseLong(actor.tenantId());requiredAvailable(parent);
        long tree=mapper.treeRevision();
        var rows=mapper.subRows(parent,pageSize,((long)page-1)*pageSize);
        return new Page(rows.stream().map(row->view(row,tree)).toList(),mapper.subCount(parent),page,pageSize,tree);
    }

    /** 开通选项复用同一总部主体、当前动作和总部状态，不要求下级目录读取权。 */
    @Transactional(readOnly=true)
    public void requireProvisionOptions() {
        var subject=headquarters("provision");
        requiredAvailable(Long.parseLong(subject.tenantId()));
    }

    /** 父Tenant/动作/容量均来自可信HQ人员或固定机构管理凭据；不创建成员、凭据或Agent授权。 */
    @Transactional(rollbackFor=Exception.class)
    public Relation provisionSubInstitution(ProvisionInstitution request,String requestKey) {
        var subject=headquarters("provision");
        if(request==null)throw bad("机构开通请求不能为空");
        var normalized=new ProvisionInstitution(text(request.namespace(),"namespace",128),text(request.externalInstitutionRef(),"external_institution_ref",256),
                text(request.name(),"name",200),text(request.defaultPlanCode(),"default_plan_code",64),request.quotaApiCallsPerMonth(),
                request.quotaMaxKnowledgeBases(),request.quotaMaxMembers(),request.quotaMaxChannels(),request.rateLimitPerMinute());
        long parent=Long.parseLong(subject.tenantId());String actor=subject.actorRef();
        String operation="provision",key=key(requestKey),fingerprint=fingerprint(normalized);
        mapper.lockRequest(actor,operation,key);
        // 当前动作及总部状态仍检查，原回执不改写后来迁移/解除的关系。
        requiredAvailable(parent);
        var prior=prior(actor,operation,key,fingerprint);if(prior!=null)return prior;
        mapper.lockTree();requiredAvailable(parent);
        quota.requireSubInstitutionCapacity(subject.tenantId(),1);
        var institution=institutions.createSubInstitution(normalized,parent,actor);
        var result=view(required(Long.parseLong(institution.tenantId())),mapper.treeRevision());
        if(!Long.toString(parent).equals(result.parentTenantId()))throw new IllegalStateException("sub_institution_parent_mismatch");
        long relation=append(result,actor,key);
        if(mapper.grantRead(SnowflakeIds.nextId(),parent,Long.parseLong(result.tenantId()),relation)!=1)
            throw new IllegalStateException("sub_institution_read_grant_failed");
        record(actor,operation,key,fingerprint,result,subject.tenantId());
        return result;
    }

    private Headquarters headquarters(String operation) {
        if(ManagementContext.get()!=null)throw denied("平台管理身份不能进入机构管理域");
        var principal=ServicePrincipalContext.get();
        if(principal!=null) {
            if(!principal.tenantId().equals(TenantContext.getTenantId()))throw denied("机构管理凭据与当前机构不一致");
            institutions.requirePrincipal(principal,"tenant.sub_institutions."+operation);
            return new Headquarters(principal.tenantId(),"service_principal:"+principal.principalId());
        }
        var actor=foundation.currentSubject();
        if(actor.platform()||actor.tenantId()==null||!actor.tenantId().equals(TenantContext.getTenantId()))throw denied("直属机构管理必须使用可信当前机构");
        foundation.require(actor,"tenant.sub_institutions."+operation);
        return new Headquarters(actor.tenantId(),"tenant:"+actor.tenantId()+":member:"+actor.memberId());
    }
    private record Headquarters(String tenantId,String actorRef) { }
    private InstitutionRelationMapper.Row required(long id) {
        var row=mapper.find(id);if(row==null)throw new ApiException(HttpStatus.NOT_FOUND,"机构不存在");return row;
    }
    private void requiredAvailable(long id) {
        var row=required(id);if(!Set.of("active","warning").contains(row.status()))throw denied("总部机构当前不可管理");
    }
    private long append(Relation relation,String actor,String key) {
        long id=SnowflakeIds.nextId();
        if(mapper.append(id,Long.parseLong(relation.tenantId()),relation.parentTenantId()==null?null:Long.parseLong(relation.parentTenantId()),
                relation.revision(),relation.treeRevision(),actor,key)!=1)throw new IllegalStateException("institution_relation_history_failed");
        return id;
    }
    private Relation prior(String actor,String operation,String key,String fingerprint) {
        var prior=mapper.request(actor,operation,key);if(prior==null)return null;
        if(!fingerprint.equals(prior.fingerprint()))throw conflict("同一幂等键参数不一致");
        try{
            Relation result=json.readValue(prior.result(),Relation.class);
            if(result==null)throw new IllegalStateException("institution_relation_receipt_invalid");
            return result;
        }catch(JsonProcessingException failure){throw new IllegalStateException("institution_relation_receipt_invalid",failure);}
    }
    private void record(String actor,String operation,String key,String fingerprint,Relation result,String auditTenant) {
        if(mapper.receipt(actor,operation,key,fingerprint,Long.parseLong(result.tenantId()),encode(result))!=1)
            throw new IllegalStateException("institution_relation_receipt_failed");
        audit.record(auditTenant,actor,auditTenant==null?"platform.institution_relations."+operation:"tenant.sub_institutions.provision",
                "institution_relation",result.tenantId(),"机构关系已提交",Map.of("revision",result.revision(),"tree_revision",result.treeRevision()));
    }
    private Relation view(InstitutionRelationMapper.Row row,long tree) {
        return new Relation(row.tenantId().toString(),row.parentTenantId()==null?null:row.parentTenantId().toString(),row.name(),row.status(),row.revision(),tree);
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException failure){throw new IllegalStateException(failure);}}
    private String fingerprint(Object value){return TokenHash.sha256Hex(encode(value));}
    private String key(String key){return text(key,"Idempotency-Key",256);}
    private String text(String value,String field,int max){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isISOControl))throw bad(field+"格式非法");return value.trim();}
    private void paging(int page,int size){if(page<1||size<1||size>200)throw bad("page/page_size非法");}
    private void requireTarget(Set<Long> allowed,long id){if(id<1||!allowed.contains(id))throw denied("没有目标机构权限");}
    private ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,message);}
    private ApiException denied(String message){return new ApiException(HttpStatus.FORBIDDEN,message);}
    private ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,message);}
}

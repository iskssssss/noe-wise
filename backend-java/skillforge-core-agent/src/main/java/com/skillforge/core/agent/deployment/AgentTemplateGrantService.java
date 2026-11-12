package com.skillforge.core.agent.deployment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper.GrantRow;
import com.skillforge.core.agent.dto.response.AgentTemplateItemResponse;
import com.skillforge.core.agent.dto.response.AgentTemplateListResponse;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.application.AuditService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.skillforge.core.agent.deployment.AgentDeploymentContracts.*;

/** 固定单链消费授权；源所有权、直属关系和管理动作分别当前核验。 */
@Service
public class AgentTemplateGrantService {
    private final AgentDeploymentMapper mapper;
    private final AgentTemplateService templates;
    private final PlatformManagementService management;
    private final FoundationAccess foundation;
    private final InstitutionIntegrationService institutions;
    private final AuditService audit;
    private final ObjectMapper json;
    public AgentTemplateGrantService(AgentDeploymentMapper mapper,AgentTemplateService templates,PlatformManagementService management,
            FoundationAccess foundation,InstitutionIntegrationService institutions,AuditService audit,ObjectMapper json) {
        this.mapper=mapper;this.templates=templates;this.management=management;this.foundation=foundation;
        this.institutions=institutions;this.audit=audit;this.json=json;
    }
    /** 原创建键只固定stable ID；重试读取该ID当前收窄/撤销事实，绝不复活或回显宽scope。 */
    @Transactional(rollbackFor=Exception.class)
    public GrantView grant(Grant command,String requestKey) {
        management.requireTenant("platform.agent_grants.create",command.targetTenantId());
        management.requireDefinition("platform.agent_grants.create",command.definitionId());
        if(command.parentGrantId()!=null)throw bad("平台原授不能指定父授权");
        return create(command,requireKey(requestKey),new Actor(management.actorRef(),null,null));
    }
    @Transactional(rollbackFor=Exception.class)
    public GrantView tenantGrant(Grant command,String requestKey) {
        return create(command,requireKey(requestKey),tenantActor("create"));
    }
    /** 授权目标候选复用正式创建的当前主体/动作和机构状态，不要求目录读取权。 */
    @Transactional(readOnly=true)
    public long requireTenantGrantTargetOptions() {
        return tenantActor("create").tenant();
    }
    private GrantView create(Grant command,String key,Actor actor) {
        String fingerprint=grantFingerprint(command);
        String receiptKey="create:"+digest(key);
        mapper.lockExternal("grant:"+actor.ref(),0L,receiptKey);
        var existing=mapper.findGrantRequest(actor.ref(),key);
        if(existing!=null) {
            if(!fingerprint.equals(existing.fingerprint()))throw conflict("授权创建键已用于不同参数");
            return view(existing);
        }
        validate(command.actions(),command.expiresAt(),command.allowRegrant(),command.remainingDepth());
        var identity=templates.getById(command.definitionId());
        if(identity==null)throw new ApiException(HttpStatus.NOT_FOUND,"定义不存在");
        templates.lockFamily(identity.getTemplateKey());identity=templates.lockTemplate(command.definitionId());
        if(identity==null||!"published".equals(identity.getStatus()))throw forbidden("定义尚未发布或已下架");
        templates.requireAuthorizedDeployment(identity);templates.requireSourceAvailable(identity);
        GrantRow parent=null;
        if(actor.tenant()==null) {if(!"platform".equals(identity.getOwnerKind()))throw forbidden("平台原授必须拥有平台源");}
        else if(command.parentGrantId()==null) {
            if(!"tenant".equals(identity.getOwnerKind())||!Objects.equals(identity.getOwnerTenantId(),actor.tenant()))throw forbidden("机构原授必须拥有当前机构源");
        } else {
            var parentChain=chain(command.parentGrantId(),actor.tenant(),command.definitionId(),true);
            // 转授本身不是deploy或execute；完整链仍须至少覆盖本次明确动作。
            for(String action:command.actions())requireFrozenChainForAdmission(parentChain,actor.tenant(),command.definitionId(),action);
            parent=mapper.findGrant(command.parentGrantId());
            if(parent==null||!parent.allowRegrant()||parent.remainingDepth()<1||command.remainingDepth()>=parent.remainingDepth()
                    ||!actions(parent).containsAll(command.actions())||!expiryWithin(command.expiresAt(),parent.expiresAt()))throw forbidden("转授超出父授权范围");
        }
        var tenantIds=new TreeSet<Long>();tenantIds.add(command.targetTenantId());
        if(actor.tenant()!=null)tenantIds.add(actor.tenant());if(identity.getOwnerTenantId()!=null)tenantIds.add(identity.getOwnerTenantId());
        for(long id:tenantIds)availableTenant(id,true);
        Long relation=null,relationRevision=null;
        if(actor.tenant()!=null) {
            var current=mapper.grantRelation(command.targetTenantId(),actor.tenant());
            if(current==null)throw forbidden("授权目标必须属于当前直属关系");
            relation=current.relationRef();relationRevision=current.revision();
        }
        var row=new GrantRow(SnowflakeIds.nextId(),command.definitionId(),command.targetTenantId(),String.join(",",new TreeSet<>(command.actions())),
                command.expiresAt(),"active",1,actor.ref(),key,fingerprint,null,null,Instant.now(),identity.getOwnerKind(),identity.getOwnerTenantId(),
                actor.tenant()==null?"platform":"tenant",actor.tenant(),parent==null?null:parent.grantId(),parent==null?null:parent.revision(),relation,relationRevision,command.allowRegrant(),command.remainingDepth());
        if(mapper.insertGrant(row)!=1)throw new IllegalStateException("agent_grant_create_failed");
        return record(row,actor,"create",receiptKey,fingerprint);
    }
    /** 只核对原创建身份；不取得创建权限，也不派发创建。 */
    @Transactional(readOnly=true)
    public Receipt<GrantView> grantReceipt(Grant command,String requestKey) {
        management.requireTenant("platform.agent_grants.read",command.targetTenantId());
        management.requireDefinition("platform.agent_grants.read",command.definitionId());
        var existing=mapper.findGrantRequest(management.actorRef(),requireKey(requestKey));
        if(existing==null)return new Receipt<>("NOT_FOUND",null);
        if(!grantFingerprint(command).equals(existing.fingerprint()))throw conflict("授权创建键已用于不同参数");
        return new Receipt<>("FOUND",view(existing));
    }
    private static String grantFingerprint(Grant command) {
        return digest(command.definitionId()+"|"+command.targetTenantId()+"|"+new TreeSet<>(command.actions())+"|"+command.expiresAt()+"|"+command.allowRegrant()+"|"+command.remainingDepth()+(command.parentGrantId()==null?"":"|"+command.parentGrantId()));
    }
    @Transactional(rollbackFor=Exception.class)
    public GrantView revoke(Revoke command,String key) {return change(command.grantId(),command.expectedRevision(),null,requireKey(key),platformActor("revoke"),"revoke");}
    @Transactional(rollbackFor=Exception.class)
    public GrantView tenantRevoke(Revoke command,String key) {return change(command.grantId(),command.expectedRevision(),null,requireKey(key),tenantActor("revoke"),"revoke");}
    @Transactional(rollbackFor=Exception.class)
    public GrantView narrow(Narrow command,String key) {return change(command.grantId(),command.expectedRevision(),command,requireKey(key),platformActor("narrow"),"narrow");}
    @Transactional(rollbackFor=Exception.class)
    public GrantView tenantNarrow(Narrow command,String key) {return change(command.grantId(),command.expectedRevision(),command,requireKey(key),tenantActor("narrow"),"narrow");}
    private Actor platformActor(String operation) {
        // 先建立当前动作目标范围，再读取请求中指定对象。
        var scope=management.authorizedTenantScope("platform.agent_grants."+operation);
        return new Actor(scope.actorRef(),null,scope.tenantIds());
    }
    private GrantView change(long id,long expected,Narrow command,String key,Actor actor,String operation) {
        var row=mapper.findManagedGrant(id,actor.targets(),actor.tenant());if(row==null)throw forbidden("当前主体不能管理该授权");authorizeIssuer(row,actor,operation);
        String fingerprint=digest(operation+"|"+id+"|"+expected+"|"+(command==null?"":new TreeSet<>(command.actions())+"|"+command.expiresAt()+"|"+command.allowRegrant()+"|"+command.remainingDepth()));
        String receiptKey=operation+":"+id+":"+digest(key);
        mapper.lockExternal("grant:"+actor.ref(),row.targetTenantId(),receiptKey);
        var previous=receipt(actor.ref(),receiptKey,fingerprint);if(previous!=null)return previous;
        int changed;
        if(command==null)changed=mapper.revokeGrant(id,expected,actor.ref(),key,fingerprint);
        else {
            validate(command.actions(),command.expiresAt(),command.allowRegrant(),command.remainingDepth());
            if(!actions(row).containsAll(command.actions())||!expiryWithin(command.expiresAt(),row.expiresAt())
                    ||(command.allowRegrant()&&!row.allowRegrant())||command.remainingDepth()>row.remainingDepth())throw forbidden("授权只允许收窄");
            changed=mapper.narrowGrant(id,expected,String.join(",",new TreeSet<>(command.actions())),command.expiresAt(),command.allowRegrant(),command.remainingDepth());
        }
        if(changed!=1)throw conflict("授权修订已变化或已经撤销");
        return record(required(id),actor,operation,receiptKey,fingerprint);
    }
    private void authorizeIssuer(GrantRow row,Actor actor,String operation) {
        if(actor.tenant()==null) {
            management.requireTenant("platform.agent_grants."+operation,row.targetTenantId());
            management.requireDefinition("platform.agent_grants."+operation,row.definitionId());
            if(!"platform".equals(row.grantorKind()))throw forbidden("只能管理本域原授");
        } else if(!"tenant".equals(row.grantorKind())||!Objects.equals(actor.tenant(),row.grantorTenantId()))throw forbidden("只能管理当前机构发出的授权");
    }
    @Transactional(readOnly=true)
    public Grants list(long tenantId) {
        management.requireTenant("platform.agent_grants.read",tenantId);
        var definitions=management.allowedDefinitionIds("platform.agent_grants.read");
        var items=listViews(mapper.grants(tenantId).stream().filter(g->definitions.contains(g.definitionId())).toList());
        return new Grants(items.size(),items);
    }
    @Transactional(readOnly=true)
    public Grants tenantList() {
        var actor=tenantActor("read");availableTenant(actor.tenant(),false);
        var items=listViews(mapper.issuedGrants(actor.tenant()));return new Grants(items.size(),items);
    }
    @Transactional(readOnly=true)
    public Grants targetGrants(long tenantId) {
        var items=mapper.grants(tenantId).stream().filter(g->currentlyAvailable(g,"deploy")||currentlyAvailable(g,"execute")).map(this::view).toList();
        return new Grants(items.size(),items);
    }
    /** 可消费目录先验证当前授权链；随后才读取安全发布投影。 */
    @Transactional(readOnly=true)
    public AgentTemplateListResponse available(long tenantId) {
        var allowed=new HashSet<Long>();
        mapper.grants(tenantId).stream().filter(g->currentlyAvailable(g,"deploy")&&currentlyAvailable(g,"execute")).forEach(g->allowed.add(g.definitionId()));
        var items=allowed.stream().sorted().map(templates::requireCurrentDefinitionAvailable).map(t->{var d=t.getDefinition();
            var safe=new com.skillforge.core.agent.model.AgentTemplateDefinition("",d.roleDescription(),null,null,List.of(),d.resourceSlots(),d.delegationSlots());
            return new AgentTemplateItemResponse(t.getId(),t.getTemplateKey(),t.getName(),t.getVersion(),t.getDescription(),t.getIconUrl(),AgentTemplateItemResponse.TemplateSummary.from(safe));}).toList();
        return new AgentTemplateListResponse(items.size(),items);
    }
    private boolean currentlyAvailable(GrantRow row,String action) {
        try {requireChain(row.grantId(),row.targetTenantId(),row.definitionId(),action);return true;}
        catch(ApiException unavailable){return false;}
    }
    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public GrantChain requireChain(long grantId,long tenantId,long definitionId,String action) {
        var result=chain(grantId,tenantId,definitionId,false);requireFrozenChain(result,tenantId,definitionId,action);return result;
    }
    @Transactional
    public GrantChain requireChainForAdmission(long grantId,long tenantId,long definitionId,String action) {
        var result=chain(grantId,tenantId,definitionId,true);requireFrozenChainForAdmission(result,tenantId,definitionId,action);return result;
    }
    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public GrantRow requireGranted(long grantId,long tenantId,long definitionId,String action) {requireChain(grantId,tenantId,definitionId,action);return required(grantId);}
    @Transactional
    public GrantRow requireGrantedForAdmission(long grantId,long tenantId,long definitionId,String action) {requireChainForAdmission(grantId,tenantId,definitionId,action);return required(grantId);}
    /** 预期授权拒绝可转为未就绪投影，不污染调用者事务；技术异常继续回滚。 */
    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public void requireFrozenChain(GrantChain chain,long tenantId,long definitionId,String action) {
        if(chain==null||!Set.of("deploy","execute").contains(action)||!mapper.chainAvailable(encode(chain),tenantId,definitionId,action))throw forbidden("原授权链已失效或不包含所需动作");
    }
    @Transactional
    public void requireFrozenChainForAdmission(GrantChain chain,long tenantId,long definitionId,String action) {
        if(chain==null||chain.hops().isEmpty())throw forbidden("授权链为空");
        // 所有调用统一祖先到叶锁grant，之后按Tenant ID锁关系行；不锁全树。
        for(var hop:chain.hops())if(mapper.lockGrant(hop.grantId())==null)throw forbidden("授权不存在");
        lockTenants(chain);requireFrozenChain(chain,tenantId,definitionId,action);
    }
    private GrantChain chain(long grantId,long tenantId,long definitionId,boolean lock) {
        var rows=new ArrayList<GrantRow>();var visited=new HashSet<Long>();Long id=grantId;
        while(id!=null) {
            if(!visited.add(id))throw forbidden("授权链存在循环");
            var row=mapper.findGrant(id);if(row==null)throw forbidden("授权不存在");rows.add(row);id=row.parentGrantId();
        }
        Collections.reverse(rows);
        if(lock)for(int i=0;i<rows.size();i++) {
            var current=mapper.lockGrant(rows.get(i).grantId());
            if(current==null||!Objects.equals(current.parentGrantId(),rows.get(i).parentGrantId()))throw forbidden("授权链已变化");rows.set(i,current);
        }
        var leaf=rows.getLast();if(leaf.targetTenantId()!=tenantId||leaf.definitionId()!=definitionId)throw forbidden("授权不属于目标机构或源");
        var hops=rows.stream().map(r->new GrantHop(r.grantId(),r.revision(),r.grantorKind(),r.grantorTenantId(),r.targetTenantId(),r.parentGrantId(),r.parentGrantRevision(),r.relationRef(),r.relationRevision())).toList();
        var result=new GrantChain(definitionId,leaf.ownerKind(),leaf.ownerTenantId(),hops);if(lock)lockTenants(result);return result;
    }
    private void lockTenants(GrantChain chain) {
        var ids=new TreeSet<Long>();if(chain.ownerTenantId()!=null)ids.add(chain.ownerTenantId());
        for(var hop:chain.hops()){ids.add(hop.targetTenantId());if(hop.grantorTenantId()!=null)ids.add(hop.grantorTenantId());}
        for(long id:ids)availableTenant(id,true);
    }
    private void availableTenant(long id,boolean lock) {
        var row=lock?mapper.lockGrantTenant(id):mapper.grantTenant(id);
        if(row==null||!Set.of("active","warning").contains(row.status()))throw forbidden("授权链机构已停用或不存在");
    }
    private Actor tenantActor(String operation) {
        if(ManagementContext.get()!=null)throw forbidden("平台身份不能进入机构授权域");
        var principal=ServicePrincipalContext.get();
        if(principal!=null) {
            if(!Objects.equals(principal.tenantId(),TenantContext.getTenantId()))throw forbidden("授权管理凭据与当前机构不一致");
            institutions.requirePrincipal(principal,"tenant.agent_grants."+operation);
            availableTenant(Long.parseLong(principal.tenantId()),false);
            return new Actor("service_principal:"+principal.principalId(),Long.parseLong(principal.tenantId()),null);
        }
        var subject=foundation.currentSubject();
        if(subject.platform()||subject.tenantId()==null||!subject.tenantId().equals(TenantContext.getTenantId()))throw forbidden("机构授权须使用当前可信主体");
        foundation.require(subject,"tenant.agent_grants."+operation);
        availableTenant(Long.parseLong(subject.tenantId()),false);
        return new Actor("tenant:"+subject.tenantId()+":member:"+subject.memberId(),Long.parseLong(subject.tenantId()),null);
    }
    private record Actor(String ref,Long tenant,Set<Long> targets) { }
    private GrantView receipt(String actor,String key,String fingerprint) {
        var receipt=mapper.grantReceipt(actor,key);if(receipt==null)return null;
        if(!fingerprint.equals(receipt.fingerprint()))throw conflict("授权幂等键已用于不同参数");
        try{return json.readValue(receipt.response(),GrantView.class);}catch(JsonProcessingException failure){throw new IllegalStateException("agent_grant_receipt_invalid",failure);}
    }
    private GrantView record(GrantRow row,Actor actor,String operation,String key,String fingerprint) {
        if(mapper.appendGrantRevision(row.grantId(),actor.ref(),operation,key)!=1)throw new IllegalStateException("agent_grant_history_failed");
        var result=view(row);
        if(!"create".equals(operation)&&mapper.saveGrantReceipt(actor.ref(),key,fingerprint,row.grantId(),encode(result))!=1)throw new IllegalStateException("agent_grant_receipt_failed");
        audit.record(actor.tenant()==null?null:actor.tenant().toString(),actor.ref(),"permission.agent_grant."+operation,"agent_definition_grant",row.grantId().toString(),"定义消费授权"+operation,
                Map.of("definition_id",row.definitionId().toString(),"target_tenant_id",row.targetTenantId().toString(),"revision",row.revision(),"command_ref",key));
        return result;
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException failure){throw new IllegalStateException("agent_grant_json_invalid",failure);}}
    private GrantRow required(long id) {var row=mapper.findGrant(id);if(row==null)throw new ApiException(HttpStatus.NOT_FOUND,"授权不存在");return row;}
    private List<GrantView> listViews(List<GrantRow> rows) {
        if (rows.isEmpty()) return List.of();
        var displays=templates.displays(rows.stream().map(GrantRow::definitionId).distinct().toList());
        return rows.stream().map(row->{var display=displays.get(row.definitionId());
            return view(row,display==null?null:display.name(),display==null?null:display.version());}).toList();
    }
    private GrantView view(GrantRow row) {
        var definition=templates.getById(row.definitionId());
        return view(row,definition==null?null:definition.getName(),definition==null?null:definition.getVersion());
    }
    private GrantView view(GrantRow row,String name,String version) {
        return new GrantView(row.grantId(),row.definitionId(),row.targetTenantId(),List.of(row.actions().split(",")),row.expiresAt(),row.status(),row.revision(),
                name,version,row.ownerKind(),row.ownerTenantId(),row.grantorKind(),row.grantorTenantId(),
                row.parentGrantId(),row.parentGrantRevision(),row.relationRef(),row.relationRevision(),row.allowRegrant(),row.remainingDepth());
    }
    private static Set<String> actions(GrantRow row){return Set.of(row.actions().split(","));}
    private static boolean expiryWithin(Instant child,Instant parent){return parent==null||(child!=null&&!child.isAfter(parent));}
    private static void validate(List<String> actions,Instant expiry,boolean regrant,int depth) {
        if(actions==null||actions.isEmpty()||!Set.of("deploy","execute").containsAll(actions)||new HashSet<>(actions).size()!=actions.size()
                ||depth<0||(!regrant&&depth!=0)||(regrant&&depth<1))throw bad("actions须为deploy/execute非空集合，转授深度须与allow_regrant一致");
        if(expiry!=null&&!expiry.isAfter(Instant.now()))throw bad("授权到期时间必须在未来");
    }
    public static String requireKey(String key) {
        if(key==null||key.isBlank()||key.length()>256||key.chars().anyMatch(Character::isISOControl))throw bad("Idempotency-Key 格式非法");return key;
    }
    private static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message,false);}
    private static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,"CONFLICT",message,false);}
    private static ApiException forbidden(String message){return new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN",message,false);}
}

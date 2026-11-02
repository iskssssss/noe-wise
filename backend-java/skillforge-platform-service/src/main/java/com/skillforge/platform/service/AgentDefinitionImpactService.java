package com.skillforge.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.AgentDefinitionLifecycleService;
import com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse;
import com.skillforge.core.agent.deployment.AgentBindingDeclarations;
import com.skillforge.core.agent.deployment.AgentBindingDeclarations.BindingShape;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.agent.dto.response.AgentDefinitionImpactResponse;
import com.skillforge.core.agent.dto.response.AgentDefinitionImpactResponse.Changes;
import com.skillforge.core.agent.dto.response.AgentDefinitionImpactResponse.DeploymentImpact;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 在一个只读数据库快照中读取源与合法目标的声明结构，不准备或恢复执行。 */
@Service
public class AgentDefinitionImpactService {
    private final AgentTemplateService definitions;
    private final AgentDefinitionLifecycleService lifecycle;
    private final AgentDeploymentMapper deployments;
    private final PlatformManagementService management;
    private final CapabilityCatalogDatabaseContext database;
    private final ObjectMapper json;

    public AgentDefinitionImpactService(AgentTemplateService definitions, AgentDeploymentMapper deployments,
            PlatformManagementService management, CapabilityCatalogDatabaseContext database, ObjectMapper json,
            AgentDefinitionLifecycleService lifecycle) {
        this.definitions=definitions; this.deployments=deployments; this.management=management;
        this.database=database; this.json=json; this.lifecycle=lifecycle;
    }

    /** 授权先于源读取；固定精确草稿、当前发布与各实例绑定修订，预览不构成发布许可。 */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public AgentDefinitionImpactResponse impact(Long definitionId, long draftRevision) {
        return impact(definitionId, draftRevision, null);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public CandidatePreview preview(Long definitionId, long draftRevision, AgentTemplateDefinition candidate) {
        lifecycle.requirePlatformOwner();
        var validation=lifecycle.validateCandidate(definitionId,draftRevision,candidate);
        return new CandidatePreview(validation,validation.valid()?impact(definitionId,draftRevision,candidate):null);
    }
    /** 已保存修订的校验和影响共享同一数据库快照。 */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public CandidatePreview previewSaved(Long definitionId, long draftRevision) {
        lifecycle.requirePlatformOwner();
        var draft=lifecycle.detail(definitionId);
        var validation=lifecycle.validateSaved(definitionId,draftRevision);
        return new CandidatePreview(validation,validation.valid()?impact(definitionId,draftRevision,draft.definition()):null);
    }
    public record CandidatePreview(AgentTemplateValidationResponse validation,AgentDefinitionImpactResponse impact) { }

    private AgentDefinitionImpactResponse impact(Long definitionId, long draftRevision, AgentTemplateDefinition proposed) {
        if(definitionId==null || definitionId<1) throw new ApiException(HttpStatus.BAD_REQUEST,"definition_id必须为正数");
        var scope=management.authorizedDefinitionReadScope(definitionId);
        if(draftRevision<1) throw new ApiException(HttpStatus.BAD_REQUEST,"draft_revision必须为正数");
        AgentTemplateDO draft=definitions.getById(definitionId);
        if(draft==null || !"platform".equals(draft.getOwnerKind())) throw new ApiException(HttpStatus.NOT_FOUND,"公共Agent定义不存在");
        if(draft.getDraftRevision()==null || draft.getDraftRevision()!=draftRevision)
            throw new ApiException(HttpStatus.CONFLICT,"草稿修订已变化");
        AgentTemplateDO publication=draft.getPublishedAt()==null?null:definitions.currentPublished(definitionId);
        AgentTemplateDefinition current=publication==null?null:publication.getDefinition();
        var candidate=proposed==null?draft.getDefinition():proposed;
        return TenantRlsInterceptor.withDefinitionImpactTargets(definitionId,scope.tenantIds(),()->{
        List<DeploymentImpact> impacts=new ArrayList<>();
        for(long tenant:scope.tenantIds().stream().sorted().toList()) {
            impacts.addAll(inTenant(tenant,()->deployments.impact(tenant,definitionId).stream().map(row->{
                if(!Long.valueOf(tenant).equals(row.tenantId())) throw new SecurityException("impact_tenant_scope_mismatch");
                BindingShape local=decode(row.bindingShape());
                var previous=current==null?List.<com.skillforge.core.agent.deployment.AgentDeploymentContracts.Issue>of():AgentBindingDeclarations.assess(current,local);
                var next=AgentBindingDeclarations.assess(candidate,local);
                return new DeploymentImpact(row.tenantId(),row.deploymentId(),row.agentId(),row.revision(),
                        current==null?null:previous.isEmpty(),next.isEmpty(),previous,next);
            }).toList()));
        }
        return new AgentDefinitionImpactResponse(definitionId,draftRevision,publication==null?null:publication.getVersion(),
                changes(current,candidate),impacts.size(),impacts);
        });
    }

    private Changes changes(AgentTemplateDefinition current,AgentTemplateDefinition draft) { return com.skillforge.core.agent.AgentDefinitionChanges.between(current,draft); }

    private BindingShape decode(String shape) {
        if(shape==null) throw new IllegalStateException("部署声明结构不完整");
        try {
            BindingShape result=json.readValue(shape,BindingShape.class);
            if(result==null) throw new IllegalStateException("部署声明结构不完整");
            return result;
        }
        catch(JsonProcessingException failure) { throw new IllegalStateException("部署声明结构解码失败",failure); }
    }

    /** 授权目标只是读取作用域，退出时恢复原线程及连接上下文，不生成机构执行身份。 */
    private <T>T inTenant(long tenant,Supplier<T> work) {
        String previous=TenantContext.getTenantId();
        if(previous!=null && !previous.equals(Long.toString(tenant))) throw new SecurityException("database_tenant_scope_mismatch");
        var original=deployments.databaseContext();
        TenantContext.setTenantId(Long.toString(tenant));
        Throwable failure=null;
        try {database.bindTenant(tenant);return work.get();}
        catch(RuntimeException|Error rejected) {failure=rejected;throw rejected;}
        finally {
            try {if(original!=null) deployments.restoreDatabaseContext(original.tenant(),original.platformAdmin());}
            catch(RuntimeException|Error restoreFailure) {if(failure!=null) failure.addSuppressed(restoreFailure);else throw restoreFailure;}
            finally {if(previous==null) TenantContext.clear();else TenantContext.setTenantId(previous);}
        }
    }
}

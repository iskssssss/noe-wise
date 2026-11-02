package com.skillforge.institution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentDefinitionChanges;
import com.skillforge.core.agent.AgentDefinitionLifecycleService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.deployment.AgentBindingDeclarations;
import com.skillforge.core.agent.deployment.AgentBindingDeclarations.BindingShape;
import com.skillforge.core.agent.dto.response.AgentDefinitionImpactResponse;
import com.skillforge.core.agent.dto.response.AgentDefinitionImpactResponse.DeploymentImpact;
import com.skillforge.core.agent.dto.response.AgentTemplateValidationResponse;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 机构源维护者的精确只读预览；仅按明确管理目标读取声明结构，不披露目标业务和实际绑定值。 */
@Service
public class TenantAgentDefinitionImpactService {
    private final AgentDefinitionLifecycleService lifecycle;
    private final AgentTemplateService templates;
    private final AgentDeploymentMapper deployments;
    private final InstitutionDeploymentManagementService management;
    private final CapabilityCatalogDatabaseContext database;
    private final ObjectMapper json;
    /** 授权、纯差量和受控数据库窗口保持独立职责。 */
    public TenantAgentDefinitionImpactService(AgentDefinitionLifecycleService lifecycle,AgentTemplateService templates,
            AgentDeploymentMapper deployments,InstitutionDeploymentManagementService management,CapabilityCatalogDatabaseContext database,ObjectMapper json){
        this.lifecycle=lifecycle;this.templates=templates;this.deployments=deployments;this.management=management;this.database=database;this.json=json;
    }
    /** 当前源read/owner/revision先于窗口；跨目标清单来自精确当前管理grant而非祖先关系。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AgentDefinitionImpactResponse impact(Long id,long expected) {
        return impact(id, expected, null);
    }
    /** 预览编辑器内容，结果只属于当前输入，不可作为已保存修订的发布凭据。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CandidatePreview preview(Long id, long expected, AgentTemplateDefinition candidate) {
        lifecycle.requireTenantOwner();
        var validation = lifecycle.validateCandidate(id, expected, candidate);
        return new CandidatePreview(validation, validation.valid() ? impact(id, expected, candidate) : null);
    }
    /** 在同一只读快照校验已保存草稿并计算影响。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CandidatePreview previewSaved(Long id, long expected) {
        lifecycle.requireTenantOwner();
        var draft=lifecycle.detail(id);
        var validation=lifecycle.validateSaved(id,expected);
        return new CandidatePreview(validation,validation.valid()?impact(id,expected,draft.definition()):null);
    }
    public record CandidatePreview(AgentTemplateValidationResponse validation, AgentDefinitionImpactResponse impact) { }

    private AgentDefinitionImpactResponse impact(Long id,long expected,AgentTemplateDefinition candidate) {
        lifecycle.requireTenantOwner();var draft=lifecycle.detail(id);
        if(expected<1)throw new ApiException(HttpStatus.BAD_REQUEST,"draft_revision必须为正数");
        if(draft.draftRevision()!=expected)throw new ApiException(HttpStatus.CONFLICT,"草稿修订已变化");
        var published=draft.publishedAt()==null?null:templates.currentPublished(id);
        var proposed = candidate == null ? draft.definition() : candidate;
        String owner=TenantContext.getTenantId();
        var targets=new HashSet<>(management.authorizedTargets("tenant.agent_deployments.read"));targets.add(Long.valueOf(owner));
        return TenantRlsInterceptor.withDefinitionImpactTargets(id,targets,owner,()->{
            var result=new ArrayList<DeploymentImpact>();
            for(long tenant:targets.stream().sorted().toList()) {
                String previous=TenantContext.getTenantId();var original=deployments.databaseContext();
                try {
                    TenantContext.setTenantId(Long.toString(tenant));database.bindTenant(tenant);
                    for(var row:deployments.impact(tenant,id)) {
                        if(!Long.valueOf(tenant).equals(row.tenantId()))throw new SecurityException("impact_tenant_scope_mismatch");
                        BindingShape local=decode(row.bindingShape());
                        var before=published==null?List.<com.skillforge.core.agent.deployment.AgentDeploymentContracts.Issue>of():AgentBindingDeclarations.assess(published.getDefinition(),local);
                        var after=AgentBindingDeclarations.assess(proposed,local);
                        result.add(new DeploymentImpact(row.tenantId(),row.deploymentId(),row.agentId(),row.revision(),published==null?null:before.isEmpty(),after.isEmpty(),before,after));
                    }
                } finally {
                    try {if(original!=null)deployments.restoreDatabaseContext(original.tenant(),original.platformAdmin());}
                    finally {if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);}
                }
            }
            return new AgentDefinitionImpactResponse(id,expected,published==null?null:published.getVersion(),
                    AgentDefinitionChanges.between(published==null?null:published.getDefinition(),proposed),result.size(),result);
        });
    }
    /** Mapper只交付声明结构投影，坏数据不能解释成全部就绪。 */
    private BindingShape decode(String value){try{var shape=json.readValue(value,BindingShape.class);if(shape==null)throw new IllegalStateException("部署声明结构缺失");return shape;}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("部署声明结构无法读取",failure);}}
}

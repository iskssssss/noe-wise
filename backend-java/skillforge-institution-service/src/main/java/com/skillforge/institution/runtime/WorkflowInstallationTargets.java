package com.skillforge.institution.runtime;

import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.core.capability.KnowledgeSourceDescriptor;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.WorkflowInstallationTargetAccess;
import com.skillforge.institution.service.TenantToolDeploymentProviderValidator;
import com.skillforge.institution.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 经领域公开入口校验安装目标，不从 Workflow 模块越界读取其他领域的 Mapper。 */
@Component
public class WorkflowInstallationTargets implements WorkflowInstallationTargetAccess {
    private final TenantToolDeploymentProviderValidator providers;
    private final KnowledgeBaseRevisionService knowledge;
    private final TeamService agents;
    public WorkflowInstallationTargets(TenantToolDeploymentProviderValidator providers, KnowledgeBaseRevisionService knowledge, TeamService agents) {
        this.providers = providers; this.knowledge = knowledge; this.agents = agents;
    }
    @Override public void requireProvider(long tenant, ToolDeployment deployment) {
        providers.validate(tenant, deployment.providerType(), deployment.providerRef(), deployment.environment());
    }
    @Override public void requireKnowledge(long tenant, KnowledgeSourceDescriptor source, long revision) {
        var found = knowledge.list(Long.toString(tenant), Long.valueOf(source.sourceId())).items().stream()
                .anyMatch(value -> value.revision() == revision && "published".equals(value.status())
                        && value.capabilityVersionId() != null && value.capabilityVersionId().toString().equals(source.metadata().ref().versionId()));
        if (!found) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "知识修订未发布或与 CapabilityVersion 不一致");
    }
    @Override public long currentKnowledgeRevision(long tenant, KnowledgeSourceDescriptor source) {
        return knowledge.list(Long.toString(tenant), Long.valueOf(source.sourceId())).items().stream()
                .filter(value -> "published".equals(value.status()) && value.capabilityVersionId() != null
                        && value.capabilityVersionId().toString().equals(source.metadata().ref().versionId()))
                .mapToLong(value -> value.revision()).max()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "知识修订未发布或与当前 CapabilityVersion 不一致"));
    }
    @Override public void requireAgent(long tenant, String agentId) { agents.freezeAgent(Long.toString(tenant), agentId); }
}

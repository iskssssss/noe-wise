package com.skillforge.institution.service;

import com.skillforge.core.agent.management.AgentManagementApplicationService;
import com.skillforge.core.agent.management.AgentManagementContracts;
import com.skillforge.core.agent.constant.AgentConstants;
import com.skillforge.core.agent.dto.request.CreateAgentRequest;
import com.skillforge.core.agent.dto.request.UpdateAgentRequest;
import com.skillforge.core.agent.dto.response.AgentDetailResponse;
import com.skillforge.core.agent.dto.response.AgentItemResponse;
import com.skillforge.core.agent.dto.response.AgentListResponse;
import com.skillforge.core.agent.dto.response.AgentPublishResponse;
import com.skillforge.core.agent.dto.response.AgentSaveAndPublishResponse;
import java.util.UUID;
import com.skillforge.core.agent.dto.response.AgentRevisionListResponse;
import com.skillforge.core.agent.dto.response.AgentTemplateItemResponse;
import com.skillforge.core.agent.dto.response.AgentTemplateListResponse;
import com.skillforge.core.agent.dto.response.CapabilityMountResponse;
import com.skillforge.core.shared.dto.resource.DeleteResponse;
import com.skillforge.institution.common.TenantIds;
import org.springframework.stereotype.Service;

/** 机构 Agent HTTP 适配：读取可信上下文并转换传输契约。 */
@Service
public class AgentManagementService {
    private final AgentManagementApplicationService agents;
    private final InstitutionResourceAccess access;

    public AgentManagementService(AgentManagementApplicationService agents, InstitutionResourceAccess access) {
        this.agents = agents;
        this.access = access;
    }

    public AgentTemplateListResponse listTemplates() {
        var templates = agents.listTemplates(caller());
        return new AgentTemplateListResponse(templates.size(), templates.stream().map(this::templateItem).toList());
    }

    public AgentTemplateItemResponse templateDetail(Long id) {
        return templateItem(agents.templateDetail(caller(), id));
    }

    public AgentListResponse listAgents() {
        var items = agents.list(caller());
        return new AgentListResponse(items.size(), items.stream().map(this::agentItem).toList());
    }

    public AgentItemResponse createAgent(CreateAgentRequest request) {
        return agentItem(agents.create(caller(), new AgentManagementContracts.Create(request.agentKey(), request.name(),
                request.systemPrompt(), request.modelConfigId(), request.config())));
    }

    public AgentDetailResponse getAgent(String agentId) {
        return agentDetail(agents.detail(caller(), agentId));
    }

    /** 安装回执复用同一当前授权与管理投影，不暴露实体给宿主调用方。 */
    AgentItemResponse getAgentItem(String agentId) {
        return agentItem(agents.get(caller(), agentId));
    }

    public AgentItemResponse updateAgent(String agentId, UpdateAgentRequest request) {
        return agentItem(agents.update(caller(), agentId, new AgentManagementContracts.Update(
                request.name(), request.status(), request.systemPrompt(), request.roleDescription(), request.executionConfig(),
                request.modelConfigId(), request.config(), request.delegationConfig(), request.iconUrl())));
    }

    public AgentSaveAndPublishResponse saveAndPublish(String agentId, UUID requestId, UpdateAgentRequest request) {
        return saveAndPublishReceipt(agents.saveAndPublish(caller(), agentId, requestId,
                new AgentManagementContracts.Update(request.name(), request.status(), request.systemPrompt(),
                        request.roleDescription(), request.executionConfig(), request.modelConfigId(), request.config(),
                        request.delegationConfig(), request.iconUrl())));
    }

    public AgentSaveAndPublishResponse publishWithReceipt(String agentId, UUID requestId) {
        return saveAndPublishReceipt(agents.publishWithReceipt(caller(), agentId, requestId));
    }

    public AgentSaveAndPublishResponse saveAndPublishResult(String agentId, UUID requestId) {
        var receipt = agents.saveAndPublishResult(caller(), agentId, requestId);
        return receipt == null ? null : saveAndPublishReceipt(receipt);
    }

    private AgentSaveAndPublishResponse saveAndPublishReceipt(AgentManagementContracts.SaveAndPublishReceipt receipt) {
        return new AgentSaveAndPublishResponse(receipt.requestId(), receipt.agentId(), receipt.publicationId(),
                receipt.revision(), receipt.publishedAt());
    }

    public DeleteResponse deleteAgent(String agentId) {
        agents.delete(caller(), agentId);
        return new DeleteResponse(AgentConstants.STATUS_DELETED);
    }

    public AgentPublishResponse publish(String agentId) {
        return new AgentPublishResponse(AgentConstants.ACTION_PUBLISHED, agents.publish(caller(), agentId));
    }

    public AgentItemResponse revise(String agentId) {
        return agentItem(agents.revise(caller(), agentId));
    }

    public AgentRevisionListResponse revisions(String agentId) {
        var items = agents.revisions(caller(), agentId).stream()
                .map(row -> new AgentRevisionListResponse.Item(row.id(), row.revision(), row.publishedAt(), row.name(), row.current()))
                .toList();
        return new AgentRevisionListResponse(items.size(), items);
    }

    public AgentDetailResponse revisionDetail(String agentId, int revision) {
        return agentDetail(agents.revisionDetail(caller(), agentId, revision));
    }

    public AgentItemResponse restoreRevision(String agentId, int revision) {
        return agentItem(agents.restoreRevision(caller(), agentId, revision));
    }

    private AgentManagementContracts.Caller caller() {
        return new AgentManagementContracts.Caller(tenantId(), access.external());
    }

    private long tenantId() { return Long.parseLong(TenantIds.requireTenantId()); }

    private AgentTemplateItemResponse templateItem(AgentManagementContracts.Template template) {
        return new AgentTemplateItemResponse(template.id(), template.templateKey(), template.name(), template.version(),
                template.description(), template.iconUrl(), AgentTemplateItemResponse.TemplateSummary.from(template.definition()));
    }

    private AgentItemResponse agentItem(AgentManagementContracts.Agent agent) {
        return new AgentItemResponse(agent.id(), agent.agentKey(), agent.name(), agent.templateId(), agent.templateName(),
                agent.status(), agent.createdAt(), agent.delegationConfig(), agent.iconUrl(), agent.roleDescription(),
                agent.revision(), agent.publishedRevisionId(), agent.publishedRevisionId() == null ? null : agent.revision(),
                agent.hasDraftChanges(), agent.definitionManaged());
    }

    private AgentDetailResponse agentDetail(AgentManagementContracts.Detail detail) {
        var agent = detail.agent();
        return new AgentDetailResponse(agent.id(), agent.agentKey(), agent.name(), agent.templateId(), agent.templateName(),
                agent.status(), agent.createdAt(), agent.systemPrompt(), agent.roleDescription(), agent.executionConfig(),
                agent.modelConfigId() == null ? null : agent.modelConfigId().toString(), agent.config(), agent.publishedAt(),
                detail.mountSet().generation(), detail.mountSet().mounts().stream().map(CapabilityMountResponse::from).toList(),
                agent.delegationConfig(), agent.iconUrl(), agent.revision(), agent.publishedRevisionId(),
                agent.publishedRevisionId() == null ? null : agent.revision(), agent.hasDraftChanges(), agent.definitionManaged());
    }
}

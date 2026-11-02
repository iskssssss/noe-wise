package com.skillforge.institution.service;

import com.skillforge.core.workflow.WorkflowDraftLifecycleService;
import com.skillforge.core.workflow.WorkflowInstanceService;
import com.skillforge.core.workflow.dto.response.BuilderWorkflowInstanceResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftListResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftResponse;
import com.skillforge.core.workflow.dto.response.WorkflowPublicationHistoryResponse;
import com.skillforge.core.workflow.dto.response.WorkflowPublicationDetailResponse;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceKind;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 机构 Workflow 管理的对象权限和配额接线，不改变已冻结执行与恢复。 */
@Service
public class WorkflowManagementService {
    private final WorkflowDraftLifecycleService lifecycle;
    private final WorkflowInstanceService instances;
    private final InstitutionResourceAccess resources;
    private final FoundationOrganizationService organization;
    private final FeatureGate features;
    private final ResourceQuota quota;

    public WorkflowManagementService(WorkflowDraftLifecycleService lifecycle, WorkflowInstanceService instances,
            InstitutionResourceAccess resources, FoundationOrganizationService organization,
            FeatureGate features, ResourceQuota quota) {
        this.lifecycle = lifecycle; this.instances = instances; this.resources = resources;
        this.organization = organization; this.features = features; this.quota = quota;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowDraftResponse create(long tenantId, String workflowId, Map<String, Object> definition) {
        features.require(Long.toString(tenantId), "workflows");
        quota.requireCapacity(Long.toString(tenantId), ResourceKind.WORKFLOW, 1);
        var created = lifecycle.create(tenantId, workflowId, definition);
        if (!resources.external()) organization.registerResource("workflow", Long.toString(lifecycle.containerId(tenantId, workflowId)));
        return created;
    }

    public WorkflowDraftListResponse list(long tenantId) {
        var filter = resources.filter("workflow", "read");
        return lifecycle.list(tenantId, filter.all() ? null : filter.resourceIds().stream().map(Long::valueOf).toList());
    }

    public List<BuilderWorkflowInstanceResponse> instances(long tenantId) {
        var filter = resources.filter("workflow", "read");
        return instances.listBuilderInstances(tenantId, filter.all() ? null : filter.resourceIds().stream().map(Long::valueOf).toList());
    }

    public WorkflowPublicationHistoryResponse history(long tenantId, String workflowId) {
        require(tenantId, workflowId, "read");
        return lifecycle.history(tenantId, workflowId);
    }

    public WorkflowPublicationDetailResponse publicationDetail(long tenantId, String workflowId, long definitionVersionId) {
        require(tenantId, workflowId, "read");
        return lifecycle.publicationDetail(tenantId, workflowId, definitionVersionId);
    }

    public void require(long tenantId, String workflowId, String action) {
        resources.require("workflow", Long.toString(lifecycle.containerId(tenantId, workflowId)), action);
        if (!"read".equals(action)) features.require(Long.toString(tenantId), "workflows");
    }
}

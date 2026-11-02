package com.skillforge.institution.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.institution.common.TenantIds;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工作流安装实例按已发布能力稳定定义归组，只返回挂载选择所需身份。 */
@Service
public class WorkflowMountOptionService {
    private final FoundationAccess access;
    private final WorkflowManagementService workflows;
    private final CapabilityCatalogService catalog;

    public WorkflowMountOptionService(FoundationAccess access, WorkflowManagementService workflows,
            CapabilityCatalogService catalog) {
        this.access = access;
        this.workflows = workflows;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<Option> list() {
        access.require("foundation.capabilities.read");
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        var instances = workflows.instances(tenant).stream()
                .filter(item -> "published".equals(item.status()) && item.installationRevision() != null
                        && item.installationRevision() > 0 && item.capabilityVersionId() != null).toList();
        var ids = instances.stream().map(item -> item.capabilityVersionId()).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        var versions = catalog.publishedVersions(CapabilityCatalogScope.tenant(tenant), ids).stream()
                .filter(item -> item.capabilityType() == CapabilityType.WORKFLOW)
                .collect(Collectors.toMap(item -> item.id(), Function.identity()));
        return instances.stream().filter(item -> versions.containsKey(item.capabilityVersionId()))
                .map(item -> new Option(item.id(), item.instanceId(), item.workflowId(),
                        item.installationRevision(), versions.get(item.capabilityVersionId()).definitionId()))
                .toList();
    }

    public record Option(
            @JsonProperty("id") @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonProperty("instance_id") String instanceId,
            @JsonProperty("workflow_id") String workflowId,
            @JsonProperty("installation_revision") @JsonSerialize(using = ToStringSerializer.class) Long installationRevision,
            @JsonProperty("definition_id") @JsonSerialize(using = ToStringSerializer.class) Long definitionId) { }
}

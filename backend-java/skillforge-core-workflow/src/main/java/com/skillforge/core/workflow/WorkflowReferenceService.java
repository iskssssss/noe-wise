package com.skillforge.core.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.workflow.mapper.*;
import com.skillforge.core.workflow.model.*;
import org.springframework.stereotype.Service;

/** 挂载边界的同租户身份视图；不向宿主开放存储查询或写权限。 */
@Service
public class WorkflowReferenceService {
    private final WorkflowInstanceMapper instances;
    private final WorkflowDefinitionVersionMapper definitions;
    private final WorkflowContainerMapper containers;

    public WorkflowReferenceService(WorkflowInstanceMapper instances,
            WorkflowDefinitionVersionMapper definitions, WorkflowContainerMapper containers) {
        this.instances = instances;
        this.definitions = definitions;
        this.containers = containers;
    }

    public Instance instance(long tenantId, long id) {
        var row = instances.selectOne(new LambdaQueryWrapper<WorkflowInstanceDO>()
                .eq(WorkflowInstanceDO::getTenantId, tenantId).eq(WorkflowInstanceDO::getId, id));
        return row == null ? null : new Instance(row.getId(), row.getTenantId(), row.getStatus(),
                row.getDefinitionVersionId(), row.getInstallationRevision());
    }

    public Definition definition(long tenantId, long id) {
        return view(definitions.selectOne(new LambdaQueryWrapper<WorkflowDefinitionVersionDO>()
                .eq(WorkflowDefinitionVersionDO::getTenantId, tenantId).eq(WorkflowDefinitionVersionDO::getId, id)));
    }

    public Definition capabilityDefinition(long tenantId, long capabilityVersionId) {
        return view(definitions.selectOne(new LambdaQueryWrapper<WorkflowDefinitionVersionDO>()
                .eq(WorkflowDefinitionVersionDO::getTenantId, tenantId)
                .eq(WorkflowDefinitionVersionDO::getCapabilityVersionId, capabilityVersionId)));
    }

    public Container container(long tenantId, long id) {
        var row = containers.selectOne(new LambdaQueryWrapper<WorkflowContainerDO>()
                .eq(WorkflowContainerDO::getTenantId, tenantId).eq(WorkflowContainerDO::getId, id));
        return row == null ? null : new Container(row.getId(), row.getTenantId(), row.getStatus(), row.getActiveDefinitionVersionId());
    }

    private static Definition view(WorkflowDefinitionVersionDO row) {
        return row == null ? null : new Definition(row.getId(), row.getTenantId(), row.getContainerId(), row.getCapabilityVersionId());
    }

    public record Instance(Long id, Long tenantId, String status, Long definitionVersionId, Long installationRevision) {}
    public record Definition(Long id, Long tenantId, Long containerId, Long capabilityVersionId) {}
    public record Container(Long id, Long tenantId, String status, Long activeDefinitionVersionId) {}
}

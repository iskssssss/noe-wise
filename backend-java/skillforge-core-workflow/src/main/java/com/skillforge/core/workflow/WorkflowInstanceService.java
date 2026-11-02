package com.skillforge.core.workflow;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.dto.response.BuilderWorkflowInstanceResponse;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper.BuilderRead;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper.InstallationIdentity;
import com.skillforge.core.workflow.mapper.WorkflowInstanceMapper;
import com.skillforge.core.workflow.model.WorkflowInstanceDO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
/** 当前不可变安装修订的管理查询入口。 */
@Service
public class WorkflowInstanceService {
    private final WorkflowInstanceMapper instances;
    private final WorkflowInstallationMapper installations;
    private final WorkflowInstallationDefinitionService definitions;
    private static final int BUILDER_READ_BATCH_SIZE = 256;

    public WorkflowInstanceService(WorkflowInstanceMapper instances, WorkflowInstallationMapper installations,
            WorkflowInstallationDefinitionService definitions) {
        this.instances = instances;
        this.installations = installations;
        this.definitions = definitions;
    }

    /** 管理面只返回当前不可变安装修订的配置、提示词和定义身份。 */
    public List<BuilderWorkflowInstanceResponse> listBuilderInstances(long tenantId) {
        return listBuilderInstances(tenantId, null);
    }

    /** 实例按同租户不可变定义所属容器先过滤，避免配置和提示词越过资源范围。 */
    public List<BuilderWorkflowInstanceResponse> listBuilderInstances(long tenantId, List<Long> permittedContainerIds) {
        if (permittedContainerIds != null && permittedContainerIds.isEmpty()) return List.of();
        var query = new LambdaQueryWrapper<WorkflowInstanceDO>()
                .apply("NOT EXISTS (SELECT 1 FROM nw_workflow_test_sessions wt WHERE wt.tenant_id=nw_workflow_instances.tenant_id AND wt.instance_id=nw_workflow_instances.id)")
                .eq(WorkflowInstanceDO::getTenantId, tenantId);
        if (permittedContainerIds != null) {
            query.apply("definition_version_id IN (SELECT id FROM nw_workflow_definition_versions WHERE tenant_id = {0} AND container_id IN ("
                    + permittedContainerIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + "))", tenantId);
        }
        List<WorkflowInstanceDO> instances = this.instances.selectList(query.orderByDesc(WorkflowInstanceDO::getCreatedAt));
        List<BuilderWorkflowInstanceResponse> result = new ArrayList<>(instances.size());
        for (int offset = 0; offset < instances.size(); offset += BUILDER_READ_BATCH_SIZE) {
            var batch = instances.subList(offset, Math.min(offset + BUILDER_READ_BATCH_SIZE, instances.size()));
            var reads = builderReads(tenantId, batch);
            // 先批读，再逐实例校验，保留原列表中首个坏实例及其拒绝原因。
            for (var instance : batch) {
                result.add(builderInstance(tenantId, instance, reads.get(identity(instance))));
            }
        }
        return List.copyOf(result);
    }

    /** 当前实例指针必须与对应安装修订匹配，避免可变实例字段成为管理读权威。 */
    BuilderWorkflowInstanceResponse builderInstance(long tenantId, WorkflowInstanceDO instance) {
        if (!hasInstallationPointer(instance)) {
            throw unavailable(instance);
        }
        return builderInstance(tenantId, instance, builderReads(tenantId, List.of(instance)).get(identity(instance)));
    }

    private BuilderWorkflowInstanceResponse builderInstance(long tenantId, WorkflowInstanceDO instance, BuilderRead read) {
        if (!hasInstallationPointer(instance) || read == null || !Objects.equals(read.tenantId(), tenantId)
                || !Objects.equals(read.instanceId(), instance.getId())
                || !Objects.equals(read.installationRevision(), instance.getInstallationRevision())
                || !Objects.equals(read.definitionVersionId(), instance.getDefinitionVersionId())) {
            throw unavailable(instance);
        }
        WorkflowInstallationDefinitionService.WorkflowInstanceBinding binding = definitions.binding(tenantId, instance, read);
        return new BuilderWorkflowInstanceResponse(instance.getId(), instance.getInstanceId(),
                binding.workflowId(), binding.definitionVersionId(), read.installationRevision(),
                binding.capabilityVersionId(), instance.getStatus(), immutableMap(read.config()),
                read.customPrompt(), instance.getCreatedAt());
    }

    private Map<InstallationIdentity, BuilderRead> builderReads(long tenantId, List<WorkflowInstanceDO> instances) {
        // 身份仅取自首次实例读取的快照；不重新连接或查询可变当前指针。
        var identities = instances.stream().filter(WorkflowInstanceService::hasInstallationPointer)
                .map(WorkflowInstanceService::identity).toList();
        Map<InstallationIdentity, BuilderRead> result = new HashMap<>();
        if (!identities.isEmpty()) {
            for (var read : installations.findBuilderReads(tenantId, identities)) {
                result.put(new InstallationIdentity(read.instanceId(), read.installationRevision()), read);
            }
        }
        return result;
    }

    private static boolean hasInstallationPointer(WorkflowInstanceDO instance) {
        return instance.getId() != null && instance.getInstallationRevision() != null
                && instance.getInstallationRevision() > 0;
    }

    private static InstallationIdentity identity(WorkflowInstanceDO instance) {
        return new InstallationIdentity(instance.getId(), instance.getInstallationRevision());
    }

    private static Map<String, Object> immutableMap(Map<String, Object> config) {
        return Map.copyOf(config == null ? Map.of() : config);
    }

    private static ApiException unavailable(WorkflowInstanceDO instance) {
        return new ApiException(HttpStatus.CONFLICT,
                "Workflow 实例缺少当前不可变安装修订，请重新安装或升级: " + instance.getId());
    }
}

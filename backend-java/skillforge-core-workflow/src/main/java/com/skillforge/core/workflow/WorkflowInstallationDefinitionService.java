package com.skillforge.core.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.mapper.WorkflowContainerMapper;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper.BuilderRead;
import com.skillforge.core.workflow.model.WorkflowContainerDO;
import com.skillforge.core.workflow.model.WorkflowDefinitionVersionDO;
import com.skillforge.core.workflow.model.WorkflowInstanceDO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Builder 安装选版、精确定义读取与管理身份校验；执行只走冻结 Runtime binding。 */
@Service
public class WorkflowInstallationDefinitionService {

    private final WorkflowContainerMapper containerMapper;
    private final WorkflowDefinitionVersionMapper versionMapper;

    public WorkflowInstallationDefinitionService(
            WorkflowContainerMapper containerMapper,
            WorkflowDefinitionVersionMapper versionMapper) {
        this.containerMapper = containerMapper;
        this.versionMapper = versionMapper;
    }

    /** 工作草稿编辑不影响当前发布版本；安装和新执行均可继续选择该发布快照。 */
    public long requirePublishedDefinitionVersion(long tenantId, String workflowId) {
        WorkflowContainerDO container = findContainer(tenantId, workflowId);
        if (container == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Builder 工作流不存在: " + workflowId);
        }
        if ("archived".equals(container.getStatus()) || container.getActiveDefinitionVersionId() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "工作流尚未发布，不能安装或升级: " + workflowId);
        }
        WorkflowDefinitionVersionDO version = versionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowDefinitionVersionDO>()
                        .eq(WorkflowDefinitionVersionDO::getId, container.getActiveDefinitionVersionId())
                        .eq(WorkflowDefinitionVersionDO::getContainerId, container.getId())
                        .eq(WorkflowDefinitionVersionDO::getTenantId, tenantId));
        if (version == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Builder 工作流缺少可安装的 DefinitionVersion: " + workflowId);
        }
        return version.getId();
    }

    /** 与发布共享容器行锁，在组合安装期间固定当前发布身份。 */
    public long lockPublishedDefinitionVersion(long tenantId, String workflowId) {
        if (containerMapper.lockContainer(tenantId, workflowId) == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "Builder 工作流不存在: " + workflowId);
        return requirePublishedDefinitionVersion(tenantId, workflowId);
    }

    /** 安装验证只读取精确版本，不在验证后再解析 active 指针。 */
    public java.util.Map<String, Object> definition(long tenantId, long definitionVersionId) {
        var version = versionMapper.selectOne(new LambdaQueryWrapper<WorkflowDefinitionVersionDO>()
                .eq(WorkflowDefinitionVersionDO::getTenantId, tenantId)
                .eq(WorkflowDefinitionVersionDO::getId, definitionVersionId));
        if (version == null || version.getDefinition() == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "Workflow DefinitionVersion 不存在");
        return version.getDefinition();
    }

    /** 管理读校验实例与已加载定义身份；仅判断正文存在，不解析运行图。 */
    WorkflowInstanceBinding binding(long tenantId, WorkflowInstanceDO instance, BuilderRead read) {
        requireBoundInstance(tenantId, instance);
        if (read.boundDefinitionVersionId() == null || !read.definitionPresent()) {
            throw unavailableDefinition(instance);
        }
        if (read.containerId() == null) {
            throw unavailableContainer(instance);
        }
        return new WorkflowInstanceBinding(read.workflowId(), read.boundDefinitionVersionId(), read.capabilityVersionId());
    }

    private static void requireBoundInstance(long tenantId, WorkflowInstanceDO instance) {
        if (instance.getTenantId() == null || instance.getTenantId() != tenantId) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Workflow 实例不属于当前租户: " + instance.getId());
        }
        if (instance.getDefinitionVersionId() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Workflow 实例未绑定不可变 DefinitionVersion，请重新安装或升级: " + instance.getId());
        }
    }

    private static ApiException unavailableDefinition(WorkflowInstanceDO instance) {
        return new ApiException(HttpStatus.CONFLICT,
                "Workflow 实例绑定的 DefinitionVersion 不可用，请重新安装或升级: " + instance.getId());
    }

    private static ApiException unavailableContainer(WorkflowInstanceDO instance) {
        return new ApiException(HttpStatus.CONFLICT,
                "Workflow 实例绑定的 DefinitionVersion 容器不可用: " + instance.getId());
    }

    private WorkflowContainerDO findContainer(long tenantId, String workflowId) {
        return containerMapper.selectOne(new LambdaQueryWrapper<WorkflowContainerDO>()
                .eq(WorkflowContainerDO::getTenantId, tenantId)
                .eq(WorkflowContainerDO::getWorkflowId, workflowId));
    }

    /** 实例绑定的当前架构身份。 */
    public record WorkflowInstanceBinding(
            String workflowId, long definitionVersionId, Long capabilityVersionId) {
    }
}

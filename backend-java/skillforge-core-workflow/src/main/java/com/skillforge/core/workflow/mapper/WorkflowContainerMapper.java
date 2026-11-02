package com.skillforge.core.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.workflow.model.WorkflowContainerDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Builder 工作流容器映射器。 */
@Mapper
public interface WorkflowContainerMapper extends BaseMapper<WorkflowContainerDO> {

    /** 发布、恢复和布局写入共享容器行锁，避免跨草稿游标写入。 */
    WorkflowContainerDO lockContainer(@Param("tenantId") long tenantId, @Param("workflowId") String workflowId);

    /** 工作草稿推进 CAS 游标时，布局仍属于同一工作草稿。 */
    int advanceBuilderSnapshot(@Param("containerId") long containerId, @Param("tenantId") long tenantId,
            @Param("expectedRevision") long expectedRevision, @Param("nextRevision") long nextRevision);

    /** 仅当客户端持有当前 revision 时推进草稿游标，避免最后写入者静默获胜。 */
    int advanceDraftRevision(
            @Param("containerId") long containerId,
            @Param("tenantId") long tenantId,
            @Param("expectedRevision") long expectedRevision,
            @Param("nextRevision") long nextRevision);

    /** 已发布 DefinitionVersion 只能经此 CAS 派生下一份可变草稿，不能原地编辑。 */
    int forkPublishedDraft(
            @Param("containerId") long containerId,
            @Param("tenantId") long tenantId,
            @Param("expectedRevision") long expectedRevision,
            @Param("nextRevision") long nextRevision);

    /** 发布时以相同 revision CAS 更新 active 版本，避免旧草稿覆盖新草稿。 */
    int activateDefinitionVersion(
            @Param("containerId") long containerId,
            @Param("tenantId") long tenantId,
            @Param("expectedRevision") long expectedRevision,
            @Param("definitionVersionId") long definitionVersionId);
}

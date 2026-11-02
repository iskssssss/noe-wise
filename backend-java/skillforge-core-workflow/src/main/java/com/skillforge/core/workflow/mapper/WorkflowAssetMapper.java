package com.skillforge.core.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.workflow.model.WorkflowAssetDO;
import com.skillforge.core.workflow.model.WorkflowTemplateDO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 工作流资源资产 MyBatis-Plus 映射接口（读写经 WorkflowAssetService，不直接调用）。 */
public interface WorkflowAssetMapper extends BaseMapper<WorkflowAssetDO> {
    WorkflowTemplateDO lockTemplate(@Param("workflowId") String workflowId);
    /** 模板草稿行锁下按唯一资源名一次写入，冲突保留原身份和创建时间。 */
    int upsertBatch(@Param("assets") List<WorkflowAssetDO> assets);
    int touchTemplate(@Param("id") long id);
}

package com.skillforge.core.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.workflow.model.WorkflowBuilderSnapshotDO;
import org.apache.ibatis.annotations.Mapper;

/** Builder draft snapshot 映射器。 */
@Mapper
public interface WorkflowBuilderSnapshotMapper extends BaseMapper<WorkflowBuilderSnapshotDO> {
}

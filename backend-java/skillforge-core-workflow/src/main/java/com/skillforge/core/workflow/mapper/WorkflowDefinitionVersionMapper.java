package com.skillforge.core.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.workflow.model.WorkflowDefinitionVersionDO;
import org.apache.ibatis.annotations.Mapper;

/** 已发布不可变 DefinitionVersion 映射器。 */
@Mapper
public interface WorkflowDefinitionVersionMapper extends BaseMapper<WorkflowDefinitionVersionDO> {
}

package com.skillforge.core.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.skillforge.core.workflow.model.WorkflowDraftRevisionDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Builder 草稿 revision 映射器。 */
@Mapper
public interface WorkflowDraftRevisionMapper extends BaseMapper<WorkflowDraftRevisionDO> {
    /** 列表只读取精确草稿游标和meta；definition仅含meta，不加载运行图。 */
    List<WorkflowDraftRevisionDO> selectListMetadata(@Param(Constants.WRAPPER) Wrapper<WorkflowDraftRevisionDO> query);
}

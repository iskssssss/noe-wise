package com.skillforge.institution.runtime;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只发现已取消typed父Run与仍未终结的真实Execution；候选不签发清理或执行权。 */
@Mapper
public interface WorkflowCancellationMapper {
    List<String> cancelledParents(@Param("tenant")String tenant,@Param("limit")int limit);
}

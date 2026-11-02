package com.skillforge.institution.runtime;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只发现当前活动节点的typedRun地址；候选不签发执行或恢复权。 */
@Mapper
public interface WorkflowNodeRecoveryMapper {
    List<String> pendingParents(@Param("tenant")String tenant,@Param("limit")int limit);
}

package com.skillforge.institution.workflow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowInstallReceiptMapper {
    int insert(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("workflowId") String workflowId,
            @Param("instanceId") String instanceId, @Param("revision") long revision);
    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("workflowId") String workflowId);
    record Row(String instanceId, long revision) { }
}

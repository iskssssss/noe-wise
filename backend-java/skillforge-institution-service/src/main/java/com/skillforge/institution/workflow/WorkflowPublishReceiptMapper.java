package com.skillforge.institution.workflow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowPublishReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("workflowId") String workflowId,
            @Param("fingerprint") String fingerprint);
    int complete(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("status") String status, @Param("response") String response);
    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId);
    record Row(String actorRef, String workflowId, String fingerprint, String status, String responseJson) { }
}

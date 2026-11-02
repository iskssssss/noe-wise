package com.skillforge.institution.tool;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowOnboardingReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("fingerprint") String fingerprint,
            @Param("agentId") String agentId, @Param("workflowId") String workflowId);
    int complete(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("status") String status, @Param("response") String response);
    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId);
    record Row(String actorRef, String fingerprint, String agentId, String workflowId, String status, String responseJson) {}
}

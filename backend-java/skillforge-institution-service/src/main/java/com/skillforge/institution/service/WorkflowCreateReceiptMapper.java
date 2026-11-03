package com.skillforge.institution.service;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowCreateReceiptMapper {
    int claim(@Param("requestId") String requestId, @Param("tenant") long tenant,
            @Param("actor") String actor, @Param("fingerprint") String fingerprint);
    int complete(@Param("requestId") String requestId, @Param("workflowId") String workflowId);
    Row find(@Param("requestId") String requestId);
    record Row(String actorRef, String fingerprint, String workflowId) { }
}

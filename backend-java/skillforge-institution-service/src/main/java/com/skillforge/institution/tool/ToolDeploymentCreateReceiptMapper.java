package com.skillforge.institution.tool;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ToolDeploymentCreateReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("fingerprint") String fingerprint);
    int complete(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("deploymentId") long deploymentId, @Param("deploymentKey") String deploymentKey);
    int reject(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("deploymentKey") String deploymentKey);
    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId);
    record Row(String actorRef, String fingerprint, String outcome, Long deploymentId, String deploymentKey) { }
}

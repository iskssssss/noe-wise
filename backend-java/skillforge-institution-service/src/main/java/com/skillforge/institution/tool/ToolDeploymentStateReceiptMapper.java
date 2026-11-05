package com.skillforge.institution.tool;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ToolDeploymentStateReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("fingerprint") String fingerprint,
            @Param("deploymentKey") String deploymentKey, @Param("expectedRevision") long expectedRevision,
            @Param("active") boolean active);
    int complete(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("status") String status, @Param("revision") long revision);
    int reject(@Param("tenant") long tenant, @Param("requestId") String requestId);
    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId);
    record Row(String actorRef, String fingerprint, String deploymentKey, String outcome,
            String deploymentStatus, Long resultRevision) { }
}

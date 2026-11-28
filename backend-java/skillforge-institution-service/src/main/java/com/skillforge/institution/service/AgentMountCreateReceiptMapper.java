package com.skillforge.institution.service;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentMountCreateReceiptMapper {
    int claim(@Param("requestId") String requestId, @Param("tenant") long tenant,
            @Param("actor") String actor, @Param("agentId") long agentId,
            @Param("fingerprint") String fingerprint);
    int complete(@Param("requestId") String requestId, @Param("mountId") long mountId);
    Row find(@Param("requestId") String requestId);
    record Row(String actorRef, long agentId, String fingerprint, String mountId) { }
}

package com.skillforge.institution.service;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentCreateReceiptMapper {
    int claim(@Param("requestId") String requestId, @Param("tenant") long tenant,
            @Param("actor") String actor, @Param("fingerprint") String fingerprint);
    int complete(@Param("requestId") String requestId, @Param("agentId") Long agentId);
    Row find(@Param("requestId") String requestId);
    record Row(String actorRef, String fingerprint, String agentId) { }
}

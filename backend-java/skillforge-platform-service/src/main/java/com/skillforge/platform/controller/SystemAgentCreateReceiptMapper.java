package com.skillforge.platform.controller;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SystemAgentCreateReceiptMapper {
    int claim(@Param("requestId") String requestId, @Param("actor") String actor, @Param("fingerprint") String fingerprint);
    int complete(@Param("requestId") String requestId, @Param("agentId") String agentId);
    Row find(@Param("requestId") String requestId);
    record Row(String actorRef, String fingerprint, String agentId) { }
}

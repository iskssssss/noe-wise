package com.skillforge.institution.controller;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeRevisionPublishReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("requestId") String requestId, @Param("kbId") long kbId,
            @Param("actor") String actor, @Param("fingerprint") String fingerprint);
    int complete(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("status") String status, @Param("response") String response);
    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId);
    record Row(long kbId, String actorRef, String fingerprint, String status, String responseJson) { }
}

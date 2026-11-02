package com.skillforge.institution.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeCreateReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("fingerprint") String fingerprint);
    int complete(@Param("tenant") long tenant, @Param("requestId") String requestId, @Param("kbId") long kbId);
    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId);
    record Row(String actorRef, String fingerprint, Long kbId) { }
}

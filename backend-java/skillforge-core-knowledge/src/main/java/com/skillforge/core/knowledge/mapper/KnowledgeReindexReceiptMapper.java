package com.skillforge.core.knowledge.mapper;

import org.apache.ibatis.annotations.Param;

/** 重新索引原命令只登记一次，成功回执与来源版本切换共用写事务。 */
public interface KnowledgeReindexReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("requestId") String requestId,
              @Param("documentId") long documentId, @Param("actor") String actor,
              @Param("fingerprint") String fingerprint);

    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId);

    int succeed(@Param("tenant") long tenant, @Param("requestId") String requestId,
                @Param("sourceVersion") String sourceVersion, @Param("chunks") int chunks);

    int reject(@Param("tenant") long tenant, @Param("requestId") String requestId,
               @Param("message") String message);

    record Row(long documentId, String actorRef, String fingerprint, String status,
               String sourceVersion, Integer chunks, String errorMessage) { }
}

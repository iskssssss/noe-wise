package com.skillforge.core.knowledge.mapper;

import org.apache.ibatis.annotations.Param;

/** 上传原命令只登记一次，成功回执与文档共用写事务。 */
public interface KnowledgeUploadReceiptMapper {
    int claim(@Param("tenant") long tenant, @Param("requestId") String requestId, @Param("kbId") long kbId,
              @Param("actor") String actor, @Param("fingerprint") String fingerprint);

    Row find(@Param("tenant") long tenant, @Param("requestId") String requestId);

    int succeed(@Param("tenant") long tenant, @Param("requestId") String requestId,
                @Param("documentId") long documentId, @Param("sourceVersion") String sourceVersion, @Param("chunks") int chunks);

    int reject(@Param("tenant") long tenant, @Param("requestId") String requestId, @Param("message") String message);

    record Row(long kbId, String actorRef, String fingerprint, String status, Long documentId,
               String sourceVersion, Integer chunks, String errorMessage) { }
}

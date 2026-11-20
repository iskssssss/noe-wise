package com.skillforge.core.knowledge.mapper;

import org.apache.ibatis.annotations.Param;

/** 重建发布原请求的锁定、成功回执与封存记录，共用调用方事务。 */
public interface KnowledgeRebuildPublishReceiptMapper {
    void lockRequest(@Param("key") String key);

    Row find(@Param("tenant") long tenant, @Param("actor") String actor,
            @Param("requestId") String requestId);

    int insert(@Param("tenant") long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("kbId") long kbId, @Param("jobId") long jobId,
            @Param("fingerprint") String fingerprint, @Param("status") String status,
            @Param("snapshot") String snapshot);

    record Row(long kbId, long jobId, String actor, String fingerprint, String status, String snapshot) { }
}

package com.skillforge.institution.group;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 仅查询原群来源与唯一Outbox；不建立另一份历史权威或执行状态。 */
@Mapper
public interface GroupHistoryMapper {
    List<Snapshot> messagePage(@Param("tenant")long tenant,@Param("session")String session,
            @Param("through")long through,@Param("before")long before,@Param("limit")int limit);
    Snapshot exactMessage(@Param("tenant")long tenant,@Param("session")String session,
            @Param("message")String message,@Param("version")long version);
    Source source(@Param("tenant")long tenant,@Param("integration")String integration,
            @Param("group")long group,@Param("id")String id);
    List<Source> unpublishedSources(@Param("tenant")long tenant,@Param("integration")String integration,
            @Param("group")long group,@Param("limit")int limit);

    record Snapshot(Long tenantId,String sessionId,String eventId,String runtimeRunId,long sessionSequence,
            String eventType,String payloadJson,Instant createdAt,long firstSequence,Instant firstCreatedAt) { }
    record Source(Long tenantId,String integrationId,Long groupBindingId,String sessionId,String sourceMessageId,
            long sourceSequence,String authorKind,String authorJson,String contentJson,Instant createdAt) { }
}

package com.skillforge.core.agent.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 智能体发布历史；所有查询同时限定租户和稳定身份，发布行只追加不更新。 */
public interface AgentPublicationMapper {
    int insert(@Param("id") long id, @Param("tenantId") long tenantId,
            @Param("agentId") long agentId, @Param("revision") int revision,
            @Param("snapshot") String snapshot, @Param("publishedAt") Instant publishedAt);
    int insertRequest(@Param("id") long id, @Param("tenantId") long tenantId,
            @Param("agentId") long agentId, @Param("revision") int revision,
            @Param("snapshot") String snapshot, @Param("publishedAt") Instant publishedAt,
            @Param("requestId") java.util.UUID requestId, @Param("fingerprint") String fingerprint,
            @Param("actor") String actor);
    RequestRow findRequest(@Param("tenantId") long tenantId, @Param("agentId") long agentId,
            @Param("requestId") java.util.UUID requestId);
    Row find(@Param("tenantId") long tenantId, @Param("agentId") long agentId,
            @Param("revisionId") long revisionId);
    Row findRevision(@Param("tenantId") long tenantId, @Param("agentId") long agentId,
            @Param("revision") int revision);
    List<History> history(@Param("tenantId") long tenantId, @Param("agentId") long agentId);
    List<Row> current(@Param("tenantId") long tenantId, @Param("agentIds") List<Long> agentIds);
    record RequestRow(Long id, Long agentId, int revision, Instant publishedAt,
            java.util.UUID requestId, String requestFingerprint, String requestActorRef) { }
    record Row(Long id, Long agentId, int revision, String snapshot, Instant publishedAt) { }
    record History(Long id, int revision, String name, Instant publishedAt) { }
}

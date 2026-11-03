package com.skillforge.institution.memory;

import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 领域内部持久化入口；租户由拦截器在当前事务连接上绑定。 */
@Mapper
public interface MemoryGovernanceMapper {
    /** 读取当前事实所有者及宿主衰减投影；可空过滤项表示不限制，limit 已由入口约束。 */
    List<Owner> owners(@Param("tenant") String tenant, @Param("status") String status,
            @Param("scope") String scope, @Param("type") String type,
            @Param("agent") String agent, @Param("endUser") Long endUser, @Param("limit") int limit);
    List<PageOwner> pageOwners(@Param("tenant") String tenant, @Param("status") String status,
            @Param("scope") String scope, @Param("type") String type, @Param("agent") String agent,
            @Param("endUser") Long endUser, @Param("second") Long second, @Param("nano") Integer nano,
            @Param("fact") String fact, @Param("limit") int limit);
    /** 读取指定租户事实所有者；不存在或 RLS 不可见时返回 null。 */
    Owner owner(@Param("tenant") String tenant, @Param("fact") String fact);

    /** 首次受理固定原治理请求；精确revision不存在时不登记回执。 */
    int insertGovernanceReceipt(@Param("request") MemoryGovernanceRequest request, @Param("key") String key);

    /** 重读原受理身份与时间，不从当前head或重投时间重建。 */
    GovernanceReceiptRow governanceReceipt(@Param("tenant") String tenant, @Param("key") String key);

    /** 提案的冻结精确目标；结果revision推进后仍保留原关联用于回执重放。 */
    List<RevisionTargetRow> revisionTargets(@Param("tenant") String tenant,
            @Param("proposalFactId") String proposalFactId);

    record GovernanceReceiptRow(String requestKey, String factId, long expectedRevision,
            MemoryGovernanceRequest.Operation operation, String requestFingerprint, Instant acceptedAt) { }
    record RevisionTargetRow(String targetFactId, long targetRevision) { }
    record Owner(String factId, long endUserId, String agentId, String sessionId, double decayScore) { }
    record PageOwner(String factId, long endUserId, String agentId, String sessionId, double decayScore,
            long createdEpochSecond, int createdNano) {
        Owner owner() { return new Owner(factId, endUserId, agentId, sessionId, decayScore); }
    }
}

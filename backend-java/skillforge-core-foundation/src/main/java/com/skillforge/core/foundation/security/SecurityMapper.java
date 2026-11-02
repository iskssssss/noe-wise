package com.skillforge.core.foundation.security;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 安全表持久化；范围始终由服务已验证的主体构造，不从请求 DTO 继承。 */
@Mapper
public interface SecurityMapper {
    SecurityRows.Account accountByName(@Param("platform") boolean platform, @Param("tenantId") Long tenantId, @Param("username") String username);
    SecurityRows.Account accountById(@Param("platform") boolean platform, @Param("tenantId") Long tenantId, @Param("id") long id);
    Long tenantBySlug(@Param("slug") String slug);
    String tenantSlug(@Param("id") Long id);
    int password(@Param("platform") boolean platform, @Param("tenantId") Long tenantId, @Param("id") long id, @Param("hash") String hash, @Param("activate") boolean activate);
    int invite(@Param("platform") boolean platform, @Param("tenantId") Long tenantId, @Param("id") long id);
    void insertSession(@Param("row") SecurityRows.Session row);
    SecurityRows.Session session(@Param("scope") String scope, @Param("id") long id);
    SecurityRows.Session refresh(@Param("scope") String scope, @Param("hash") String hash);
    int rotate(@Param("scope") String scope, @Param("id") long id, @Param("oldHash") String oldHash, @Param("newHash") String newHash);
    List<SecurityRows.Session> sessions(@Param("scope") String scope, @Param("memberId") long memberId);
    int revoke(@Param("scope") String scope, @Param("memberId") long memberId, @Param("id") Long id);
    void verifySession(@Param("scope") String scope, @Param("id") long id);
    void extendSession(@Param("scope") String scope, @Param("id") long id, @Param("expiresAt") Instant expiresAt);
    SecurityRows.Mfa mfa(@Param("scope") String scope, @Param("memberId") long memberId, @Param("lock") boolean lock);
    void insertMfa(@Param("scope") String scope, @Param("memberId") long memberId);
    int seed(@Param("scope") String scope, @Param("memberId") long memberId, @Param("secret") String secret);
    int consumeTotp(@Param("scope") String scope, @Param("memberId") long memberId, @Param("counter") long counter, @Param("enable") boolean enable);
    void deleteCodes(@Param("scope") String scope, @Param("memberId") long memberId);
    void insertCode(@Param("scope") String scope, @Param("memberId") long memberId, @Param("hash") String hash);
    int consumeCode(@Param("scope") String scope, @Param("memberId") long memberId, @Param("hash") String hash);
    int remainingCodes(@Param("scope") String scope, @Param("memberId") long memberId);
    Boolean requireAll(@Param("scope") String scope);
    void policy(@Param("scope") String scope, @Param("all") boolean all);
    void insertRecovery(@Param("scope") String scope, @Param("memberId") long memberId, @Param("purpose") String purpose, @Param("hash") String hash, @Param("expiresAt") Instant expiresAt);
    SecurityRows.Recovery consumeRecovery(@Param("scope") String scope, @Param("purpose") String purpose, @Param("hash") String hash);
    void deleteRecovery(@Param("scope") String scope, @Param("memberId") long memberId);
    void disableMfa(@Param("scope") String scope,@Param("memberId") long memberId);
    /** 会话调查保留期，批准默认90日；NULL只来自管理员显式关闭。 */ Integer sessionRetention(@Param("scope") String scope);
    /** 发布保留策略并唤醒有界扫描，未变化不UPDATE。 */ int setSessionRetention(@Param("scope") String scope,@Param("days") Integer days);
    /** 调查引用在会话行锁上竞争；1为变化、2为已相同、0为不存在，不重复写审计或UPDATE。 */ int protectSession(@Param("scope") String scope,@Param("id") long id,@Param("protect") boolean protect);
    /** 只枚举策略已启用的公平待扫描作用域，不返回凭据正文。 */ List<String> sessionCleanupScopes();
    /** 有界删除到期且无调查保护会话；与refresh/撤销/保护竞争同一行锁。 */ int cleanupSessions(@Param("scope") String scope,@Param("size") int size);
}

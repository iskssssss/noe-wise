package com.skillforge.core.foundation.security;

import java.time.Instant;

/** 安全持久化投影；只在所属应用服务内使用，不直接序列化凭据。 */
public final class SecurityRows {
    private SecurityRows() { }
    /** 账号当前状态和密码指纹来源；role 仅用于既有 JWT 角色消费者。 */
    public record Account(long id, Long tenantId, String username, String passwordHash,
                          String status, String email, String role) { }
    /** 权威设备会话；refreshHash 永不作为响应字段。 */
    public record Session(long id, String scopeKey, long memberId, String credentialHash,
                          String refreshHash, String state, String deviceLabel, Instant createdAt,
                          Instant expiresAt, Instant verifiedAt, Instant revokedAt) { }
    /** 加密 MFA 种子和已用计数器；未绑定时 enabled=false。 */
    public record Mfa(String scopeKey, long memberId, String secretEncrypted, boolean enabled,
                      long lastCounter) { }
    /** 一次性密码恢复或邀请凭据，仅允许原子消费。 */
    public record Recovery(String scopeKey, long memberId, String purpose, Instant expiresAt) { }
}

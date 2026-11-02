package com.skillforge.core.foundation.security;
import java.time.Instant;
import java.util.List;
/** 安全设置公开投影，不暴露持久化凭据。 */
public final class SecurityViews {
 private SecurityViews() { }
 /** 绑定发起时一次性交付，后续查询不提供种子。 */
 public record Enrollment(String secret,String otpauthUri) { }
 /** 启用时一次性交付恢复码，后续无法查询。 */
 public record Enabled(List<String> recoveryCodes,com.skillforge.core.foundation.dto.auth.AuthResponse authentication) { }
 /** 当前 MFA 状态、恢复码剩余量和强化认证时刻。 */
 public record Status(boolean enabled,boolean required,String mfaState,int recoveryCodesRemaining,Instant verifiedAt) { }
 /** 无凭据设备投影；ID按字符串传输。 */
 public record Device(String id,String deviceLabel,Instant createdAt,Instant expiresAt,Instant verifiedAt,boolean current) { }
 /** 全员 MFA 策略；默认关闭，发布后当前会话实时生效。 */
 public record Policy(boolean requireAllMfa) { }
 /** 会话权威撤销或到期后的调查保留天数，批准默认90日，NULL仅为管理员显式关闭。 */
 public record SessionRetention(Integer days) { }
}

package com.skillforge.core.foundation.integration.runtime.identity;

/**
 * 当前服务主体或会话目标授权已失效，受信认证事实尚未建立。
 * 宿主可将此确定性拒绝映射为403；存储、连接和编码故障不使用此类型。
 */
public final class TrustedAuthenticationRejectedException extends SecurityException {
    /** 固定安全原因不包含凭据、外部正文或目标敏感信息。 */
    public TrustedAuthenticationRejectedException() { super("trusted_authentication_rejected"); }
}

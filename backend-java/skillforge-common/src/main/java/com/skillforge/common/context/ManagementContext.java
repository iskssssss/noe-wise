package com.skillforge.common.context;

/** 平台独立管理机器的认证上下文；不绑定 Tenant，也不能代替机构执行身份。 */
public final class ManagementContext {
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();
    /** 仅允许正式认证链建立身份。 */
    private ManagementContext() { }
    /** 保存已经过 HMAC 和当前凭据校验的稳定管理身份。 */
    public static void set(Identity identity) { CURRENT.set(identity); }
    /** 未经过管理认证时返回 null。 */
    public static Identity get() { return CURRENT.get(); }
    /** 请求完成或异常时清理，防止线程复用串主体。 */
    public static void clear() { CURRENT.remove(); }
    /**
     * 认证事实只携带身份和版本，动作及目标由权威管理记录复验。
     * @param principalId 管理主体十进制 ID
     * @param credentialVersion 本次验签凭据版本
     * @param authorizationVersion 本次验签主体授权修订
     */
    public record Identity(String principalId, long credentialVersion, long authorizationVersion) { }
}

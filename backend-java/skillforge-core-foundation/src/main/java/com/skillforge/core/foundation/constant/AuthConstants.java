package com.skillforge.core.foundation.constant;

/**
 * 认证与租户成员常量。
 *
 * <p>定义成员状态、令牌类型、刷新令牌 Redis 键分隔符等认证域取值，
 * 与数据库列取值及 Redis 键格式保持一致。
 */
public final class AuthConstants {

    /** 租户成员状态：启用。 */
    public static final String MEMBER_STATUS_ACTIVE = "active";

    /** 成员状态：禁用。 */
    public static final String MEMBER_STATUS_DISABLED = "disabled";

    /** JWT 令牌类型：bearer。 */
    public static final String TOKEN_TYPE_BEARER = "bearer";

    /** 刷新令牌 Redis 键分隔符，键格式为「租户ID:refresh:SHA256」。 */
    public static final String REFRESH_KEY_SEPARATOR = ":refresh:";

    /** JWT 扩展字段：套餐 ID 默认空值。 */
    public static final String DEFAULT_PLAN_ID = "";

    /** 平台刷新令牌 Redis 键前缀，键格式为「platform:refresh:SHA256」。 */
    public static final String PLATFORM_REFRESH_PREFIX = "platform";

    private AuthConstants() {
    }
}

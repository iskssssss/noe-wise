package com.skillforge.core.foundation.constant;

/**
 * 安全与链路追踪常量。
 *
 * <p>统一存放 HTTP 头名、令牌前缀与追踪字段名，保证网关、过滤器与
 * 业务代码使用同一套命名。
 */
public final class SecurityConstants {

    /** 认证请求头名。 */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer 令牌前缀。 */
    public static final String BEARER_PREFIX = "Bearer";

    /** JWT 租户 ID 字段名。 */
    public static final String CLAIM_TENANT_ID = "tenant_id";

    /** JWT 角色字段名。 */
    public static final String CLAIM_ROLE = "role";

    /** JWT 用户名字段名。 */
    public static final String CLAIM_USERNAME = "username";

    /** JWT 套餐 ID 字段名。 */
    public static final String CLAIM_PLAN_ID = "plan_id";

    /** SHA-256 摘要算法名。 */
    public static final String SHA_256 = "SHA-256";

    private SecurityConstants() {
    }
}

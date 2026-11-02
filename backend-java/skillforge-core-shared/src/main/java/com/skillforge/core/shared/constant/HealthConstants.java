package com.skillforge.core.shared.constant;

/**
 * 健康检查常量。
 *
 * <p>定义健康检查响应的状态取值、服务名、版本号与依赖名，与 Python
 * 后端健康检查契约保持一致。
 */
public final class HealthConstants {

    /** 健康状态：正常。 */
    public static final String STATUS_OK = "ok";

    /** 健康状态：依赖降级。 */
    public static final String STATUS_DEGRADED = "degraded";

    /** 服务名称。 */
    public static final String SERVICE_NAME = "skillforge-service";

    /** 当前服务版本号。 */
    public static final String VERSION = "0.2.0";

    /** PostgreSQL 依赖名。 */
    public static final String DEP_POSTGRES = "postgres";

    /** Redis 依赖名。 */
    public static final String DEP_REDIS = "redis";

    private HealthConstants() {
    }
}

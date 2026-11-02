package com.skillforge.core.capability.constant;

/**
 * 连接器管理常量。
 *
 * <p>定义连接器实例状态、执行策略、认证类型与凭证脱敏值，
 * 与 {@code connector_instances} 列取值保持一致。
 */
public final class ConnectorConstants {

    /** 连接器实例状态：启用。 */
    public static final String STATUS_ACTIVE = "active";

    /** 连接器实例状态：停用。 */
    public static final String STATUS_DISABLED = "disabled";

    /** 创建成功状态。 */
    public static final String STATUS_CREATED = "created";

    /** 删除成功状态。 */
    public static final String STATUS_DELETED = "deleted";

    /** 执行策略：Mock（示例数据）。 */
    public static final String EXECUTOR_TYPE_MOCK = "mock";

    /** 执行策略：MCP 协议。 */
    public static final String EXECUTOR_TYPE_MCP = "mcp";

    /** 执行策略：HTTP（兜底）。 */
    public static final String EXECUTOR_TYPE_HTTP = "http";

    /** 实例业务 ID 前缀（后端生成，形如 inst-<雪花>）。 */
    public static final String INSTANCE_ID_PREFIX = "inst-";

    /** 默认认证类型：Bearer Token。 */
    public static final String DEFAULT_AUTH_TYPE = "bearer";
    /** 认证类型：Bearer Token。 */
    public static final String AUTH_TYPE_BEARER = "bearer";

    /** 实例可见性：租户内。 */
    public static final String VISIBILITY_TENANT = "tenant";

    /** 凭证脱敏值。 */
    public static final String MASKED_CREDENTIAL = "***";

    private ConnectorConstants() {
    }
}

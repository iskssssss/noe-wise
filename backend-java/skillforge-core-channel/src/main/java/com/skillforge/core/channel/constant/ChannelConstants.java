package com.skillforge.core.channel.constant;

/**
 * 渠道管理常量。
 *
 * <p>定义令牌前缀、绑定结果与默认值；渠道类型和状态分别由 ChannelType、ChannelStatus 定义。
 */
public final class ChannelConstants {

    /** 默认渠道名称。 */
    public static final String DEFAULT_NAME = "未命名渠道";

    /** 渠道令牌前缀。 */
    public static final String TOKEN_PREFIX = "ch_";

    /** OpenAPI API Key 前缀。 */
    public static final String API_KEY_PREFIX = "ak_";

    /** 渠道 config 键：可选 IP 白名单（逗号分隔字符串）。 */
    public static final String IP_WHITELIST = "ip_whitelist";

    /** 绑定成功状态。 */
    public static final String STATUS_OK = "ok";

    /** 解绑成功状态。 */
    public static final String STATUS_UNBOUND = "unbound";

    /** 吊销成功状态。 */
    public static final String STATUS_REVOKED = "revoked";

    /** 默认流量百分比。 */
    public static final int DEFAULT_TRAFFIC_PERCENTAGE = 100;

    /** 流量百分比最小值；零流量的绑定仍允许显式选择。 */
    public static final int MIN_TRAFFIC_PERCENTAGE = 0;

    /** 默认优先级。 */
    public static final int DEFAULT_PRIORITY = 0;

    private ChannelConstants() {
    }
}

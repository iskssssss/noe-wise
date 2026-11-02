package com.skillforge.core.billing.metering;

/**
 * 用量数值的来源与可信度。
 *
 * <p>缺失用量必须标记为 {@link #UNKNOWN}，不能用零伪装成供应商的精确计量。
 */
public enum UsageSource {

    /** 服务商响应直接携带的用量。 */
    PROVIDER_REPORTED,

    /** 平台按已记录的估算规则计算的用量。 */
    ESTIMATED,

    /** 服务商未返回、调用未完成或当前无法可靠估算。 */
    UNKNOWN
}

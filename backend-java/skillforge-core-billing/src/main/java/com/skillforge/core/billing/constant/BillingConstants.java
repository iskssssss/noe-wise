package com.skillforge.core.billing.constant;

/**
 * 计费与账单常量。
 *
 * <p>定义账单状态与响应字段名，与 {@code invoices} 列取值保持一致。
 */
public final class BillingConstants {

    /** 账单状态：待支付。 */
    public static final String STATUS_OPEN = "open";

    /** 状态机扫描统计字段：扫描租户数。 */
    public static final String STATE_SCANNED = "scanned";
    /** 状态机扫描统计字段：欠费预警数。 */
    public static final String STATE_WARNING = "warning";
    /** 状态机扫描统计字段：正常租户数。 */
    public static final String STATE_ACTIVE = "active";
    /** 租户状态：正常。 */
    public static final String TENANT_STATUS_ACTIVE = "active";
    /** 租户状态：欠费预警。 */
    public static final String TENANT_STATUS_WARNING = "warning";

    private BillingConstants() {
    }
}

package com.skillforge.core.billing;

/**
 * 欠费状态机扫描响应 DTO。
 *
 * @param scanned 扫描租户数
 * @param warning 欠费预警租户数
 * @param active  正常租户数
 */
public record BillingStateProcessResponse(
        int scanned,
        int warning,
        int active) {
}

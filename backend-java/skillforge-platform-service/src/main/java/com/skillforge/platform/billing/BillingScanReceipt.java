package com.skillforge.platform.billing;

import java.time.Instant;

/** 已提交扫描回执；计数与机构状态在同一事务提交。 */
public record BillingScanReceipt(String operationId, int scanned, int warning, int active, Instant completedAt) {}

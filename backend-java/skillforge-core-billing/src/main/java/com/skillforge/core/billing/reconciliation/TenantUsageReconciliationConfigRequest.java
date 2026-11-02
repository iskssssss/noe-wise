package com.skillforge.core.billing.reconciliation;

import java.util.Map;

/** 租户账单 Provider 配置写入请求；apiKey 仅作为一次性明文输入。 */
public record TenantUsageReconciliationConfigRequest(
        String provider,
        String endpoint,
        String apiKey,
        String apiKeyHeader,
        Map<String, Object> responseSchema,
        boolean clearApiKey) {
}

package com.skillforge.core.billing.reconciliation;

import java.util.Map;

/** 租户账单 Provider 配置的安全投影；不包含明文或密文 API key。 */
public record TenantUsageReconciliationConfig(
        String provider,
        String endpoint,
        boolean apiKeyConfigured,
        String apiKeyHeader,
        Map<String, Object> responseSchema) {

    public TenantUsageReconciliationConfig {
        responseSchema = responseSchema == null ? Map.of() : Map.copyOf(responseSchema);
    }
}

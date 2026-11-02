package com.skillforge.core.billing.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** DashScope 账单 Provider 适配器；租户配置必须显式选择 {@code dashscope}。 */
@Component
public final class DashScopeUsageReconciliationProvider implements UsageReconciliationProvider {

    private final HttpUsageReconciliationProvider delegate;

    @Autowired
    public DashScopeUsageReconciliationProvider(
            ObjectMapper objectMapper,
            OperationalSettingsService settings,
            TenantUsageReconciliationConfigService tenantConfigService) {
        this.delegate = new HttpUsageReconciliationProvider(objectMapper, settings,
                null, tenantConfigService, "dashscope");
    }

    DashScopeUsageReconciliationProvider(HttpUsageReconciliationProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public String providerId() {
        return "dashscope";
    }

    @Override
    public Batch pull(String tenantId, Instant from, Instant to, String cursor) {
        return delegate.pull(tenantId, from, to, cursor);
    }
}

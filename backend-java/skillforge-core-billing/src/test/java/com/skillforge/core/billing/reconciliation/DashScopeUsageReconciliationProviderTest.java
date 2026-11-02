package com.skillforge.core.billing.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 第二个账单 Provider 的显式选择和游标回退测试。 */
class DashScopeUsageReconciliationProviderTest {

    @Test
    void exposesStableIdAndDelegatesOnlyToDashscopeSelectedTenantConfig() {
        TenantUsageReconciliationConfigService config = mock(TenantUsageReconciliationConfigService.class);
        when(config.resolveForProvider("7")).thenReturn(
                new TenantUsageReconciliationConfigService.ResolvedTenantConfig(
                        "dashscope", "http://127.0.0.1:1/not-called", "secret", "Authorization", Map.of()));
        HttpUsageReconciliationProvider http = new HttpUsageReconciliationProvider(
                new ObjectMapper(), settings(), RestClient.builder().build(), config, "dashscope");
        DashScopeUsageReconciliationProvider provider = new DashScopeUsageReconciliationProvider(http);

        UsageReconciliationProvider.Batch result = provider.pull(
                "7", Instant.parse("2026-08-25T00:00:00Z"), Instant.parse("2026-08-25T01:00:00Z"), "c1");

        assertEquals("dashscope", provider.providerId());
        assertTrue(result.facts().isEmpty());
        assertEquals("c1", result.nextCursor());
    }

    @Test
    void registryCanSelectHttpAndDashscopeWithoutImplicitFallback() {
        UsageReconciliationProvider http = mock(UsageReconciliationProvider.class);
        UsageReconciliationProvider dashscope = mock(UsageReconciliationProvider.class);
        when(http.providerId()).thenReturn("http");
        when(dashscope.providerId()).thenReturn("dashscope");
        UsageReconciliationProviderRegistry registry = new UsageReconciliationProviderRegistry(
                List.of(http, dashscope));

        assertEquals(2, registry.size());
        assertTrue(registry.find("dashscope").isPresent());
        assertTrue(registry.onlyProvider().isEmpty());
    }

    @Test
    void springConstructorFailsClosedWithoutExplicitTenantConfiguration() {
        DashScopeUsageReconciliationProvider provider = new DashScopeUsageReconciliationProvider(
                new ObjectMapper(), settings(), null);

        UsageReconciliationProvider.Batch result = provider.pull(
                "tenant-without-dashscope-config",
                Instant.parse("2026-08-25T00:00:00Z"),
                Instant.parse("2026-08-25T01:00:00Z"), null);

        assertEquals("dashscope", provider.providerId());
        assertTrue(result.facts().isEmpty());
        assertEquals("", result.nextCursor());
    }

    private OperationalSettingsService settings() {
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        when(settings.read("billing")).thenReturn(new Snapshot(1, Map.of(
                "usage_reconciliation_enabled", false,
                "usage_reconciliation_endpoint", "",
                "usage_reconciliation_api_key", "",
                "usage_reconciliation_api_key_header", "Authorization")));
        return settings;
    }
}

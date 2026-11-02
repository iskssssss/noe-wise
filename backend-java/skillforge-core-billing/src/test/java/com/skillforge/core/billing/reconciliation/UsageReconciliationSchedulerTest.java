package com.skillforge.core.billing.reconciliation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.TenantDirectoryService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UsageReconciliationSchedulerTest {

    /** 定时入口必须消费完整固定窗口，不能只追加第一页的账单事实。 */
    @Test
    void scheduledWindowConsumesAllProviderPages() {
        TenantDirectoryService tenants = mock(TenantDirectoryService.class);
        when(tenants.activeTenantIds()).thenReturn(List.of("7"));
        var cursors = new java.util.ArrayList<String>();
        var windows = new java.util.ArrayList<List<java.time.Instant>>();
        var recorder = mock(com.skillforge.core.billing.metering.CostRecorder.class);
        UsageReconciliationProvider provider = (tenant, from, to, cursor) -> {
            org.junit.jupiter.api.Assertions.assertEquals("7", tenant);
            org.junit.jupiter.api.Assertions.assertEquals("7", TenantContext.getTenantId());
            cursors.add(cursor);
            windows.add(List.of(from, to));
            int page = cursors.size();
            return new UsageReconciliationProvider.Batch(List.of(
                    new UsageReconciliationProvider.Fact("scheduled-" + page, 100L + page,
                            com.skillforge.core.billing.metering.AmountStatus.PRICED,
                            null, null, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                            "fx-1", "CNY", java.util.Map.of())), page == 1 ? "next-page" : "");
        };
        var scheduler = new UsageReconciliationScheduler(tenants,
                new UsageReconciliationService(recorder, provider));

        scheduler.pullRecentUsage();

        org.junit.jupiter.api.Assertions.assertEquals(2, cursors.size());
        org.junit.jupiter.api.Assertions.assertEquals(null, cursors.getFirst());
        org.junit.jupiter.api.Assertions.assertEquals("next-page", cursors.get(1));
        org.junit.jupiter.api.Assertions.assertEquals(windows.getFirst(), windows.get(1));
        org.junit.jupiter.api.Assertions.assertEquals(java.time.Duration.ofHours(1),
                java.time.Duration.between(windows.getFirst().getFirst(), windows.getFirst().getLast()));
        verify(recorder, org.mockito.Mockito.times(2)).recordAdjustment(any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(null, TenantContext.getTenantId());
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void scansOnlyActiveTenantsWithABoundedWindow() {
        TenantDirectoryService tenants = mock(TenantDirectoryService.class);
        UsageReconciliationService service = mock(UsageReconciliationService.class);
        when(tenants.activeTenantIds()).thenReturn(List.of("7"));
        when(service.reconcileWindow(any(), any(), any(), any()))
                .thenReturn(UsageReconciliationService.Result.completed(1, ""));
        UsageReconciliationScheduler scheduler = new UsageReconciliationScheduler(tenants, service);

        scheduler.pullRecentUsage();

        verify(service).reconcileWindow(any(), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(null, TenantContext.getTenantId());
    }
}

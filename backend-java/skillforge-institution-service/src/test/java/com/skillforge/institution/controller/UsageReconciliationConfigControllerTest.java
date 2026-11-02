package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.reconciliation.TenantUsageReconciliationConfig;
import com.skillforge.core.billing.reconciliation.TenantUsageReconciliationConfigRequest;
import com.skillforge.core.billing.reconciliation.TenantUsageReconciliationConfigService;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 账单 Provider API 只返回安全投影，并将当前租户 ID传给配置服务。 */
class UsageReconciliationConfigControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void readsAndWritesCurrentTenantSafeProjection() {
        TenantUsageReconciliationConfigService service = mock(TenantUsageReconciliationConfigService.class);
        TenantUsageReconciliationConfig safe = new TenantUsageReconciliationConfig(
                "http", "https://vendor.example", true, "X-Key", Map.of("facts_path", "records"));
        when(service.get("7")).thenReturn(safe);
        when(service.save(any(), any())).thenReturn(safe);
        UsageReconciliationConfigController controller = new UsageReconciliationConfigController(service);
        TenantContext.setTenantId("7");

        assertEquals(safe, controller.get().data());
        TenantUsageReconciliationConfigRequest request = new TenantUsageReconciliationConfigRequest(
                "http", "https://vendor.example", "secret", "X-Key", Map.of(), false);
        assertEquals(safe, controller.save(request).data());

        verify(service).get("7");
        verify(service).save("7", request);
    }
}

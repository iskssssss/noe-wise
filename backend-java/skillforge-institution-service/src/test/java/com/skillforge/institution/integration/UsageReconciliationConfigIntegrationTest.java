package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.reconciliation.TenantUsageReconciliationConfig;
import com.skillforge.core.billing.reconciliation.TenantUsageReconciliationConfigRequest;
import com.skillforge.core.billing.reconciliation.TenantUsageReconciliationConfigService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 租户账单 Provider 配置在真实迁移、加密和租户边界下的集成验证。 */
class UsageReconciliationConfigIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private PlanMapper planMapper;

    @Autowired
    private TenantUsageReconciliationConfigService configService;

    private String tenantA;
    private String tenantB;

    @BeforeEach
    void setUp() {
        ensureFreePlan(planMapper);
        tenantA = createTenant(tenantMapper, planMapper, "reconcile-config-a-" + SnowflakeIds.nextId());
        tenantB = createTenant(tenantMapper, planMapper, "reconcile-config-b-" + SnowflakeIds.nextId());
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void persistsEncryptedTenantProviderAndResolvesOnlyWithinThatTenant() {
        TenantContext.setTenantId(tenantA);
        TenantUsageReconciliationConfig saved = configService.save(tenantA,
                new TenantUsageReconciliationConfigRequest(
                        "http", "https://vendor.example/usage/{tenantId}", "integration-secret",
                        "X-Vendor-Key", Map.of("facts_path", "data.records", "event_id", "id"), false));

        assertEquals("http", saved.provider());
        assertTrueConfigured(saved);
        assertEquals(Map.of("facts_path", "data.records", "event_id", "id"), saved.responseSchema());
        TenantDO stored = tenantMapper.selectById(Long.parseLong(tenantA));
        assertFalse("integration-secret".equals(stored.getUsageReconciliationApiKeyEncrypted()));
        assertEquals("integration-secret", configService.resolveForProvider(tenantA).apiKey());

        TenantContext.setTenantId(tenantB);
        TenantUsageReconciliationConfig otherTenant = configService.get(tenantB);
        assertNull(otherTenant.provider());
        assertFalse(otherTenant.apiKeyConfigured());
        assertNull(configService.resolveForProvider(tenantB).apiKey());
    }

    private void assertTrueConfigured(TenantUsageReconciliationConfig config) {
        if (!config.apiKeyConfigured()) {
            throw new AssertionError("expected configured API key");
        }
    }
}

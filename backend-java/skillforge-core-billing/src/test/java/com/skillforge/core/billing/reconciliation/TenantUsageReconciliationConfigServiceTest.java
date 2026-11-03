package com.skillforge.core.billing.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.TenantBillingProfileService;
import com.skillforge.core.foundation.application.TenantBillingProfileService.ReconciliationProfile;
import com.skillforge.core.foundation.application.CryptoService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantUsageReconciliationConfigServiceTest {

    @Mock
    private TenantBillingProfileService profiles;

    private final java.util.concurrent.atomic.AtomicReference<ReconciliationProfile> tenant =
            new java.util.concurrent.atomic.AtomicReference<>();
    private TenantUsageReconciliationConfigService service;

    @BeforeEach
    void setUp() {
        tenant.set(new ReconciliationProfile(null, null, null, null, null));
        when(profiles.reconciliationProfile(7L)).thenAnswer(call -> tenant.get());
        org.mockito.Mockito.lenient().doAnswer(call -> {
            tenant.set(call.getArgument(1));
            return null;
        }).when(profiles).saveReconciliationProfile(org.mockito.ArgumentMatchers.eq(7L), any());
        var properties = new SkillForgeProperties();
        properties.setSecretKey("synthetic-reconciliation-master-key-32-bytes");
        service = new TenantUsageReconciliationConfigService(profiles, new CryptoService(properties));
    }

    @Test
    void savesEncryptedCredentialAndReturnsOnlySafeProjection() {
        var saved = service.save("7", new TenantUsageReconciliationConfigRequest(
                "http", "https://billing.example/{tenantId}", "secret", "X-Vendor-Key",
                Map.of("facts_path", "data.records", "event_id", "id"), false));

        assertEquals("http", saved.provider());
        assertEquals("https://billing.example/{tenantId}", saved.endpoint());
        assertTrueConfigured(saved);
        assertEquals("X-Vendor-Key", saved.apiKeyHeader());
        assertEquals("data.records", saved.responseSchema().get("facts_path"));
        assertFalse("secret".equals(tenant.get().apiKeyEncrypted()));
        verify(profiles).saveReconciliationProfile(7L, tenant.get());
    }

    @Test
    void providerCanDecryptOnlyInsideProviderResolution() {
        service.save("7", new TenantUsageReconciliationConfigRequest(
                "http", "https://billing.example", "secret", null, Map.of(), false));

        var resolved = service.resolveForProvider("7");

        assertEquals("secret", resolved.apiKey());
        assertEquals("Authorization", resolved.apiKeyHeader());
    }

    @Test
    void invalidProviderAndSchemaAreRejectedBeforePersistence() {
        assertThrows(ApiException.class, () -> service.save("7",
                new TenantUsageReconciliationConfigRequest(
                        "bad provider", "https://billing.example", null, null, Map.of(), false)));
        assertThrows(ApiException.class, () -> service.save("7",
                new TenantUsageReconciliationConfigRequest(
                        "http", "https://billing.example", null, null,
                        Map.of("script", "javascript:evil"), false)));
        verify(profiles, never()).saveReconciliationProfile(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void unregisteredProviderIsRejectedBeforeChangingExistingConfig() {
        service.save("7", new TenantUsageReconciliationConfigRequest(
                "http", "https://billing.example", "secret", null, Map.of(), false));
        var original = tenant.get();

        ApiException failure = assertThrows(ApiException.class, () -> service.save("7",
                new TenantUsageReconciliationConfigRequest(
                        "isolated_provider", "https://billing.example", "replacement", null, Map.of(), false)));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getStatus());
        assertEquals("RECONCILIATION_PROVIDER_UNSUPPORTED", failure.getCode());
        assertEquals(original, tenant.get());
        verify(profiles, org.mockito.Mockito.times(1)).saveReconciliationProfile(org.mockito.ArgumentMatchers.eq(7L), any());
    }

    @Test
    void registeredDashscopeProviderCanBeSaved() {
        var saved = service.save("7", new TenantUsageReconciliationConfigRequest(
                "dashscope", "https://billing.example", null, null, Map.of(), false));
        assertEquals("dashscope", saved.provider());
    }

    @Test
    void clearingProviderRemovesTenantOverrideButKeepsGlobalFallbackAvailable() {
        service.save("7", new TenantUsageReconciliationConfigRequest(
                "http", "https://billing.example", "secret", null, Map.of(), false));
        var cleared = service.save("7", new TenantUsageReconciliationConfigRequest(
                null, null, null, null, null, false));

        assertEquals(null, cleared.provider());
        assertFalse(cleared.apiKeyConfigured());
        assertEquals(null, tenant.get().apiKeyEncrypted());
    }

    @Test
    void clearApiKeyDoesNotReturnOrRetainPreviousSecret() {
        service.save("7", new TenantUsageReconciliationConfigRequest(
                "http", "https://billing.example", "secret", null, Map.of(), false));
        var cleared = service.save("7", new TenantUsageReconciliationConfigRequest(
                "http", "https://billing.example", null, null, Map.of(), true));

        assertFalse(cleared.apiKeyConfigured());
        assertEquals(null, tenant.get().apiKeyEncrypted());
    }

    private void assertTrueConfigured(TenantUsageReconciliationConfig config) {
        if (!config.apiKeyConfigured()) {
            throw new AssertionError("expected configured API key");
        }
    }
}

package com.skillforge.core.billing.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class UsageReconciliationProviderRegistryTest {

    @Test
    void resolvesConfiguredProviderAndDoesNotGuessWithMultipleImplementations() {
        UsageReconciliationProvider first = provider("http");
        UsageReconciliationProvider second = provider("dashscope");
        UsageReconciliationProviderRegistry registry =
                new UsageReconciliationProviderRegistry(List.of(first, second));

        assertEquals(second, registry.find("dashscope").orElseThrow());
        assertEquals(2, registry.size());
        assertEquals(java.util.Optional.empty(), registry.onlyProvider());
    }

    @Test
    void rejectsDuplicateStableIdsAndBlankIds() {
        assertThrows(IllegalArgumentException.class,
                () -> new UsageReconciliationProviderRegistry(List.of(provider("http"), provider("http"))));
        UsageReconciliationProvider blank = provider("");
        assertThrows(IllegalArgumentException.class,
                () -> new UsageReconciliationProviderRegistry(List.of(blank)));
    }

    private UsageReconciliationProvider provider(String id) {
        return new UsageReconciliationProvider() {
            @Override
            public String providerId() {
                return id;
            }

            @Override
            public Batch pull(String tenantId, java.time.Instant from, java.time.Instant to, String cursor) {
                return new Batch(List.of(), cursor);
            }
        };
    }
}

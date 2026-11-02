package com.skillforge.core.billing.reconciliation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 账单 Provider 注册表。
 *
 * <p>Provider 以稳定 ID 注册，租户配置只保存 ID，不保存实现类名或凭证。
 * 重复 ID 在启动时直接失败，未知 ID 在运行时 fail-closed。</p>
 */
@Component
public class UsageReconciliationProviderRegistry {

    private final Map<String, UsageReconciliationProvider> providers;

    public UsageReconciliationProviderRegistry(List<UsageReconciliationProvider> providers) {
        Map<String, UsageReconciliationProvider> indexed = new LinkedHashMap<>();
        if (providers != null) {
            for (UsageReconciliationProvider provider : providers) {
                if (provider == null || provider.providerId() == null
                        || provider.providerId().isBlank()) {
                    throw new IllegalArgumentException("usage reconciliation providerId is required");
                }
                UsageReconciliationProvider previous = indexed.putIfAbsent(
                        provider.providerId(), provider);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate usage reconciliation providerId: " + provider.providerId());
                }
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public Optional<UsageReconciliationProvider> find(String providerId) {
        return providerId == null || providerId.isBlank()
                ? Optional.empty() : Optional.ofNullable(providers.get(providerId));
    }

    /** 无租户覆盖时仅允许单实现自动回退，避免多实现配置下隐式选错供应商。 */
    public Optional<UsageReconciliationProvider> onlyProvider() {
        return providers.size() == 1
                ? Optional.of(providers.values().iterator().next()) : Optional.empty();
    }

    public int size() {
        return providers.size();
    }
}

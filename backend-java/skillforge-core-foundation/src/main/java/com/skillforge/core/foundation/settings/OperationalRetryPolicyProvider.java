package com.skillforge.core.foundation.settings;

import com.skillforge.core.shared.resilience.RetryPolicyProvider;
import org.springframework.stereotype.Component;

/** 将同一运营配置版本的外部调用策略提供给技术弹性组件。 */
@Component
public final class OperationalRetryPolicyProvider implements RetryPolicyProvider {
    private final OperationalSettingsService settings;

    public OperationalRetryPolicyProvider(OperationalSettingsService settings) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
    }

    @Override
    public RetryPolicy currentPolicy() {
        var calls = settings.read("calls");
        return new RetryPolicy(calls.integer("external_retry_attempts"),
                calls.longValue("external_retry_backoff_milliseconds"));
    }
}

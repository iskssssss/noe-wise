package com.skillforge.core.billing.settings;

import com.skillforge.core.foundation.settings.OperationalSettingsContributor;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry.GroupDefinition;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry.InvalidSettingsException;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.bool;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.group;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.integer;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.string;
import java.util.*;
import org.springframework.stereotype.Component;

/** 由所属领域注册运营配置定义和校验，基础存储不认识业务字段。 */
@Component
public final class BillingOperationalSettings implements OperationalSettingsContributor {
    @Override
    public List<GroupDefinition> groups() {
        var groups = new ArrayList<GroupDefinition>();
        groups.add(group("billing", "计费服务", values -> {
                    requireEndpointWhenEnabled(values, "exchange_rate");
                    requireEndpointWhenEnabled(values, "usage_reconciliation");
                }, stored -> false,
                bool("exchange_rate_enabled", "启用外部汇率服务", false),
                string("exchange_rate_endpoint", "汇率服务地址", "", true, false, 2_048),
                string("exchange_rate_api_key", "汇率服务 API Key", "", true, true, 8_192),
                string("exchange_rate_api_key_header", "汇率鉴权请求头", "Authorization", false, false, 128),
                integer("exchange_rate_connect_timeout_seconds", "汇率连接超时", "秒", 1, 3_600, 3),
                integer("exchange_rate_read_timeout_seconds", "汇率读取超时", "秒", 1, 3_600, 5),
                bool("usage_reconciliation_enabled", "启用用量对账服务", false),
                string("usage_reconciliation_endpoint", "用量对账服务地址", "", true, false, 2_048),
                string("usage_reconciliation_api_key", "用量对账 API Key", "", true, true, 8_192),
                string("usage_reconciliation_api_key_header", "用量对账鉴权请求头", "Authorization", false, false, 128),
                integer("usage_reconciliation_connect_timeout_seconds", "对账连接超时", "秒", 1, 3_600, 3),
                integer("usage_reconciliation_read_timeout_seconds", "对账读取超时", "秒", 1, 3_600, 10)));
        return List.copyOf(groups);
    }

    private static void requireEndpointWhenEnabled(Map<String, Object> values, String prefix) {
        if (Boolean.TRUE.equals(values.get(prefix + "_enabled")) && ((String) values.get(prefix + "_endpoint")).isBlank())
            throw new InvalidSettingsException(prefix + "_endpoint 在启用时不能为空");
    }
}

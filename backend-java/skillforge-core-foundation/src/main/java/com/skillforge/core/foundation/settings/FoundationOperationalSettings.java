package com.skillforge.core.foundation.settings;

import com.skillforge.core.foundation.settings.OperationalSettingsContributor;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry.GroupDefinition;
import com.skillforge.core.foundation.settings.OperationalSettingsDefaults;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 由所属领域注册运营配置定义和校验，基础存储不认识业务字段。 */
@Component
public final class FoundationOperationalSettings implements OperationalSettingsContributor {
    @Override
    public List<GroupDefinition> groups() {
        var groups = new ArrayList<GroupDefinition>();
        groups.add(group("calls", "外部调用",
                integer("llm_timeout_seconds", "前台辅助模型超时", "秒", 1, 3_600, OperationalSettingsDefaults.AUXILIARY_TIMEOUT_SECONDS),
                integer("chat_timeout_seconds", "对话模型调用超时", "秒", 1, 3_600, OperationalSettingsDefaults.CHAT_TIMEOUT_SECONDS),
                integer("http_tool_timeout_seconds", "HTTP 工具超时", "秒", 1, OperationalSettingsDefaults.HTTP_MAX_TIMEOUT_SECONDS, OperationalSettingsDefaults.HTTP_TIMEOUT_SECONDS),
                integer("http_tool_max_response_bytes", "HTTP 工具响应上限", "字节",
                        1_024, 100_000_000, OperationalSettingsDefaults.HTTP_RESPONSE_BYTES),
                integer("external_retry_attempts", "外部调用最大尝试次数", "次", 1, 10,
                        OperationalSettingsDefaults.EXTERNAL_RETRY_ATTEMPTS),
                integer("external_retry_backoff_milliseconds", "首次重试退避", "毫秒", 1, 60_000,
                        OperationalSettingsDefaults.EXTERNAL_RETRY_BACKOFF_MILLISECONDS)));
        groups.add(group("rate_limits", "访问限流",
                integer("requests_per_minute_default", "默认每分钟请求数", "次/分钟", 1, 1_000_000, 120)));
        return List.copyOf(groups);
    }
}

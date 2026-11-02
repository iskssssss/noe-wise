package com.skillforge.core.foundation.runtimeconfig;

import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Agent 保存入口的预算格式校验；受理时仍由运行预算契约校验实际场景与根额度。 */
public final class AgentBudgetValidation {
    private AgentBudgetValidation() { }
    public static void validate(AgentExecutionConfig config) {
        try { if (config != null) config.validate(); }
        catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getMessage());
        }
    }

    /**
     * 发布时仅排除连一次纯文本收尾都无法容纳的显式覆盖。
     * 输入、文件/浏览器收尾和实际场景根额度仍在 Run 受理与模型准备时核验，不能据挂载能力推定每次都会使用。
     */
    public static void validateModelMinimum(AgentExecutionConfig config, int outputTokens, long modelTimeoutSeconds) {
        validate(config);
        if (config == null) return;
        if (config.maxTotalTokens() != null && config.maxTotalTokens() <= outputTokens)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "agent_budget_cannot_fit_text_closing:max_total_tokens must exceed model max_output_tokens=" + outputTokens);
        long closingSeconds = com.skillforge.runtime.api.port.ExecutionBudgetPort.ClosingSpec.TEXT
                .activeSeconds(java.time.Duration.ofSeconds(modelTimeoutSeconds));
        if (config.maxWallTimeSeconds() != null && config.maxWallTimeSeconds() < closingSeconds)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "agent_budget_cannot_fit_text_closing:max_wall_time_seconds minimum=" + closingSeconds);
    }
}

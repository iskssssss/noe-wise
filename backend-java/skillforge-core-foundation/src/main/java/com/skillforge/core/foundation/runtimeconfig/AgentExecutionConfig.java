package com.skillforge.core.foundation.runtimeconfig;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 对平台运行预算的显式覆盖；空字段继承平台值，生效值随 Run 冻结。
 * @param maxBusinessModelCalls 业务模型调用次数；输出纠正的新调用也计次，辅助调用独立计量
 * @param maxWallTimeSeconds 累计活跃执行秒数，不包含持久人工等待；保留现有技术字段名
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AgentExecutionConfig(
        @Schema(types = {"integer", "null"}, minimum = "1") Integer maxBusinessModelCalls,
        Integer maxToolCalls, Integer maxKnowledgeCalls,
        Long maxTotalTokens,
        Long maxWallTimeSeconds) {

    /** 已删除的字段必须明确拒绝，不能被全局 Jackson 宽松读取忽略。 */
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown_agent_budget_field:" + name);
    }

    /** 显式覆盖只接受正整数；上界在可信场景受理时验证。 */
    public void validate() {
        for (var entry : overrides().entrySet()) {
            long value = ((Number) entry.getValue()).longValue();
            if (value <= 0 || (entry.getKey().equals("max_wall_time_seconds")
                    && value > ChatBudgetSettings.MAX_ACTIVE_SECONDS))
                throw new IllegalArgumentException("agent_budget_override_invalid:" + entry.getKey());
        }
    }

    public static AgentExecutionConfig inherited() {
        return new AgentExecutionConfig(null, null, null, null, null);
    }

    /** 仅返回已填写的覆盖项；保存时校验基础范围，受理时校验实际任务根额度。 */
    public Map<String, Object> overrides() {
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "max_business_model_calls", maxBusinessModelCalls);
        put(values, "max_tool_calls", maxToolCalls);
        put(values, "max_knowledge_calls", maxKnowledgeCalls);
        put(values, "max_total_tokens", maxTotalTokens);
        put(values, "max_wall_time_seconds", maxWallTimeSeconds);
        return Map.copyOf(values);
    }

    private static void put(Map<String, Object> values, String key, Number value) {
        if (value != null) values.put(key, value);
    }
}

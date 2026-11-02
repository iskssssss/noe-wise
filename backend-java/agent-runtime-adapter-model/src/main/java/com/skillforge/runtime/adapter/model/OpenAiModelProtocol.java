package com.skillforge.runtime.adapter.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.OptionalLong;

/** 模型输出与用量归一化；请求编码与响应读取由 Provider 传输实现拥有。 */
public final class OpenAiModelProtocol {
    static final String TEXT_SCHEMA = com.skillforge.runtime.api.port.ModelPort.TEXT_OUTPUT_SCHEMA;
    static final String JSON_SCHEMA = com.skillforge.runtime.api.port.ModelPort.JSON_OUTPUT_SCHEMA;
    private static final System.Logger log = System.getLogger(OpenAiModelProtocol.class.getName());
    /** 与 Runtime 规范形式同值的普通记数法展开上限；两侧不同值会让同一份字节一边接受一边拒绝。 */
    private static final int MAXIMUM_NUMBER_DIGITS = 1000;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            // 归一化前必须保留原始精度，不能先落到 double 再重建十进制。
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);

    static JsonNode parse(String value) throws IOException {
        try (var parser = JSON.createParser(value)) {
            JsonNode result = JSON.readTree(parser);
            if (result == null || parser.nextToken() != null) throw invalid();
            return result;
        }
    }
    /**
     * 兼容协议的缓存和推理字段有多套供应商别名；它们均是总输入/输出的子集。
     * 单个字段异常只把该字段保留为未知，不能丢弃同一 usage 报告中的其他计量事实。
     */
    public static ModelUsage usageObject(JsonNode value) {
        OptionalLong input = firstTokens(value, "prompt_tokens", "input_tokens");
        OptionalLong output = firstTokens(value, "completion_tokens", "output_tokens");
        OptionalLong cached = firstNestedTokens(value, new String[][] {
                { "prompt_tokens_details", "cached_tokens" },
                { "input_tokens_details", "cached_tokens" }, { "prompt_cache_hit_tokens" } });
        OptionalLong cacheWrite = firstNestedTokens(value, new String[][] {
                // 百炼 OpenAI-compatible 显式缓存报告；两者是同一写入量的别名，不累加。
                // https://help.aliyun.com/en/model-studio/qwen-api-via-openai-chat-completions
                { "prompt_tokens_details", "cache_creation_input_tokens" },
                { "prompt_tokens_details", "cache_creation", "ephemeral_5m_input_tokens" },
                { "prompt_tokens_details", "cache_creation_tokens" },
                { "prompt_tokens_details", "cache_write_tokens" },
                { "input_tokens_details", "cache_write_tokens" } });
        OptionalLong reasoning = firstNestedTokens(value, new String[][] {
                { "completion_tokens_details", "reasoning_tokens" },
                { "output_tokens_details", "reasoning_tokens" }, { "reasoning_tokens" } });
        return new ModelUsage(input, output,
                validatedSubset(input, cached, "cached_input"),
                validatedSubset(input, cacheWrite, "cache_write_input"),
                validatedSubset(output, reasoning, "reasoning_output"));
    }
    public static ModelUsage mergeUsage(ModelUsage earlier, ModelUsage latest) {
        OptionalLong input = latest.inputTokens().isPresent()
                ? latest.inputTokens() : earlier.inputTokens();
        OptionalLong output = latest.outputTokens().isPresent()
                ? latest.outputTokens() : earlier.outputTokens();
        OptionalLong cached = latest.cachedInputTokens().isPresent()
                ? latest.cachedInputTokens() : earlier.cachedInputTokens();
        OptionalLong cacheWrite = latest.cacheWriteInputTokens().isPresent()
                ? latest.cacheWriteInputTokens() : earlier.cacheWriteInputTokens();
        OptionalLong reasoning = latest.reasoningOutputTokens().isPresent()
                ? latest.reasoningOutputTokens() : earlier.reasoningOutputTokens();
        return new ModelUsage(input, output,
                validatedSubset(input, cached, "cached_input"),
                validatedSubset(input, cacheWrite, "cache_write_input"),
                validatedSubset(output, reasoning, "reasoning_output"));
    }
    public static boolean hasReportedTokens(ModelUsage usage) {
        return usage.inputTokens().isPresent() || usage.outputTokens().isPresent()
                || usage.cachedInputTokens().isPresent() || usage.cacheWriteInputTokens().isPresent()
                || usage.reasoningOutputTokens().isPresent();
    }
    public static ModelUsage emptyUsage() {
        return new ModelUsage(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty());
    }
    private static OptionalLong firstTokens(JsonNode value, String... names) {
        for (String name : names) {
            OptionalLong parsed = tokens(value.get(name));
            if (parsed.isPresent()) return parsed;
        }
        return OptionalLong.empty();
    }
    private static OptionalLong firstNestedTokens(JsonNode value, String[][] paths) {
        for (String[] path : paths) {
            JsonNode field = value;
            for (String name : path) {
                if (field == null || !field.isObject()) { field = null; break; }
                field = field.get(name);
            }
            OptionalLong parsed = tokens(field);
            if (parsed.isPresent()) return parsed;
        }
        return OptionalLong.empty();
    }
    private static OptionalLong tokens(JsonNode value) {
        if (value == null || value.isNull()) return OptionalLong.empty();
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) return OptionalLong.empty();
        return OptionalLong.of(value.longValue());
    }
    /** 当前适配器只处理 OpenAI-compatible 总输入/输出，不套用 Anthropic 的拆分输入语义。 */
    private static OptionalLong validatedSubset(OptionalLong total, OptionalLong subset,
            String field) {
        if (total.isPresent() && subset.isPresent() && subset.getAsLong() > total.getAsLong()) {
            // 供应商载荷不入日志；保留已确认总量，不能通过裁剪伪造可计费的子项。
            log.log(System.Logger.Level.WARNING, "模型用量子项超过总量 field={0}", field);
            return OptionalLong.empty();
        }
        return subset;
    }
    static ModelResult.Completed completed(ModelCall call, String content, String finish,
            ModelUsage usage, ResultRef ref, java.util.Optional<String> reasoningContent,
            java.util.Optional<AssistantTurn> assistantTurn,
            java.util.Optional<com.skillforge.runtime.api.port.ModelPort.NativeProtocolState> protocolState)
            throws IOException {
        if (finish == null || finish.isBlank()) throw invalid();
        // 只接受已知规范完成原因，供应商自由文本不能成为可观测 reasonCode。
        String reason = switch (finish) {
            case "stop" -> "stop";
            case "tool_calls" -> "tool_calls";
            case "length" -> "length";
            case "content_filter" -> "content_filter";
            default -> throw invalid();
        };
        // 确定终态的空文本使用合法的字符串载荷；调用方必须先判定完成原因。
        if (("length".equals(reason) || "content_filter".equals(reason)) && content == null
                && call.response().format() instanceof ResponseFormat.Text) content = "";
        TypedPayload output;
        try {
            output = output(call.response().format(), content);
        } catch (IOException | IllegalArgumentException invalidOutput) {
            if (!("length".equals(reason) || "content_filter".equals(reason))
                    || call.response().format() instanceof ResponseFormat.Text
                    || call.response().format() instanceof ResponseFormat.NativeConversation) throw invalidOutput;
            output = output(call.response().format(), "{}");
        }
        return new ModelResult.Completed(output, call.target().effectiveModelId(), usage,
                reason, ref, reasoningContent, assistantTurn, protocolState);
    }
    static TypedPayload output(ResponseFormat format, String content) throws IOException {
        TypedPayload output;
        if (format instanceof ResponseFormat.NativeConversation)
            output = new TypedPayload(TEXT_SCHEMA, JSON.writeValueAsString(content == null ? "" : content));
        else if (format instanceof ResponseFormat.Text) output = new TypedPayload(TEXT_SCHEMA, JSON.writeValueAsString(content));
        else {
            var value = parse(content); if (!value.isObject()) throw invalid();
            output = new TypedPayload(JSON_SCHEMA, JSON.writeValueAsString(canonical(value)));
        }
        return output;
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode(); var keys = new java.util.TreeSet<String>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, canonical(value.get(key)))); return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode(); value.forEach(item -> result.add(canonical(item))); return result;
        }
        if (value.isNumber()) { return DecimalNode.valueOf(plainDecimal(value.decimalValue())); }
        return value;
    }

    /**
     * number 在供应商响应入站时一次性归一化为 Runtime 规范形式：零折叠为 0、去尾随零、普通十进制不带指数。
     * 供应商写法不统一（1.0、1E+2、0.10 同值不同字节），保留原样会让下游按字节比较的边界拒绝合法决策。
     * 展开位数按精度与标度估算后越界即拒绝，避免短 token 膨胀成巨串。
     */
    private static BigDecimal plainDecimal(BigDecimal value) {
        if (value.signum() == 0) { return BigDecimal.ZERO; }
        BigDecimal stripped = value.stripTrailingZeros();
        long scale = stripped.scale();
        long precision = stripped.precision();
        long digits = scale <= 0 ? precision - scale : scale < precision ? precision + 1 : scale + 2;
        if (digits > MAXIMUM_NUMBER_DIGITS) { throw new IllegalArgumentException("model_number_too_long"); }
        return stripped;
    }
    static IOException invalid() { return new IOException("model_protocol_invalid"); }
}

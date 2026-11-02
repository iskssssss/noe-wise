package com.skillforge.runtime.core;

import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.util.List;
import java.util.OptionalLong;

/** 模型计量的唯一严格编码，五字段均保留 presence 和整数精度。 */
final class ModelUsageCodec {
    private ModelUsageCodec() { }

    static CanonicalJson.Value encode(ModelUsage usage) {
        return CanonicalJson.object(List.of(
                field("inputTokens", usage.inputTokens()), field("outputTokens", usage.outputTokens()),
                field("cachedInputTokens", usage.cachedInputTokens()),
                field("cacheWriteInputTokens", usage.cacheWriteInputTokens()),
                field("reasoningOutputTokens", usage.reasoningOutputTokens())));
    }

    static ModelUsage decode(CanonicalJson.Value value) {
        value.requireFields("inputTokens", "outputTokens", "cachedInputTokens", "cacheWriteInputTokens", "reasoningOutputTokens");
        return new ModelUsage(token(value.field("inputTokens")), token(value.field("outputTokens")),
                token(value.field("cachedInputTokens")), token(value.field("cacheWriteInputTokens")),
                token(value.field("reasoningOutputTokens")));
    }

    private static CanonicalJson.Field field(String name, OptionalLong value) {
        return new CanonicalJson.Field(name, value.isPresent() ? CanonicalJson.integer(value.getAsLong()) : CanonicalJson.nil());
    }

    private static OptionalLong token(CanonicalJson.Value value) {
        return value.isNull() ? OptionalLong.empty() : OptionalLong.of(value.longValue());
    }
}

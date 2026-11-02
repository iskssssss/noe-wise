package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import java.util.List;
import java.util.Objects;

/** 当前协议可编码的推理候选及依据；不等同于任意具体模型已通过兼容测试。 */
public record ReasoningOptions(List<ReasoningPolicy> options, Source source, String sourceUrl) {
    public enum Source { OFFICIAL_DOCUMENTATION, PROTOCOL_DEFINITION, UNVERIFIED }

    public ReasoningOptions {
        options = List.copyOf(options);
        if (options.isEmpty() || options.stream().distinct().count() != options.size())
            throw new IllegalArgumentException("model_reasoning_options_invalid");
        Objects.requireNonNull(source); sourceUrl = Objects.requireNonNullElse(sourceUrl, "");
        if (source == Source.OFFICIAL_DOCUMENTATION && !sourceUrl.startsWith("https://"))
            throw new IllegalArgumentException("model_reasoning_source_invalid");
    }

    /** 当前协议未声明可编码候选时保留供应商默认，不声称能切换或调节思考。 */
    public static ReasoningOptions unverified() {
        return new ReasoningOptions(List.of(ReasoningPolicy.DEFAULT), Source.UNVERIFIED, "");
    }
}

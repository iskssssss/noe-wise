package com.skillforge.core.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.UsageSource;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

/** Rerank 外部响应；百炼 output.results 与顶层 results 只在协议边界映射。 */
@JsonIgnoreProperties(ignoreUnknown = true)
record RerankProtocolResponse(List<Item> results, Output output, Usage usage) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Output(List<Item> results) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(@JsonDeserialize(using = IndexReader.class) Integer index,
            @JsonProperty("relevance_score") @JsonDeserialize(using = ScoreReader.class) Double relevanceScore) { }

    /** 排名身份和得分必须保持供应商的JSON数值类型，非法排名不抹掉同回执用量。 */
    public static final class IndexReader extends StdDeserializer<Integer> {
        public IndexReader() { super(Integer.class); }
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) { parser.skipChildren(); return null; }
            BigInteger value = parser.getBigIntegerValue();
            return value.signum() < 0 || value.bitLength() > 31 ? null : value.intValue();
        }
    }

    public static final class ScoreReader extends StdDeserializer<Double> {
        public ScoreReader() { super(Double.class); }
        @Override public Double deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.currentToken().isNumeric()) { parser.skipChildren(); return null; }
            return parser.getDoubleValue();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens") @JsonDeserialize(using = TokenCountReader.class) Long promptTokens,
            @JsonProperty("total_tokens") @JsonDeserialize(using = TokenCountReader.class) Long totalTokens) {
        /** 只映射明确报告字段，不以总量替代输入，也不将缺失输出补为零。 */
        LlmUsage measured() {
            if (promptTokens != null && totalTokens != null && totalTokens < promptTokens) {
                return LlmUsage.empty();
            }
            return new LlmUsage(promptTokens, null, null, null, null, totalTokens,
                    promptTokens == null && totalTokens == null ? UsageSource.UNKNOWN : UsageSource.PROVIDER_REPORTED,
                    null);
        }
    }

    /** Token 是离散计量；拒绝浮点、字符串、负值和溢出，排名载荷仍可独立使用。 */
    public static final class TokenCountReader extends StdDeserializer<Long> {
        public TokenCountReader() { super(Long.class); }

        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                parser.skipChildren();
                return null;
            }
            BigInteger value = parser.getBigIntegerValue();
            return value.signum() < 0 || value.bitLength() > 63 ? null : value.longValue();
        }
    }
}

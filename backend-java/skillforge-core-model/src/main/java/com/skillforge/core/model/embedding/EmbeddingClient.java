package com.skillforge.core.model.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.math.BigInteger;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.shared.resilience.Resilience;
import com.skillforge.core.shared.tracing.TraceContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 平台模型版本的共享 Embedding 单批次客户端。
 *
 * <p>按平台模型引用调用 OpenAI 兼容 {@code /embeddings} 接口；调用失败时
 * 返回明确结果；降级与后续索引治理由业务消费者决定。
 *
 */
@Component
public class EmbeddingClient {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(EmbeddingClient.class);
    private static final String VECTOR_ENCODING = "float";
    private static final Set<String> SAFE_FAILURE_CODES = Set.of(
            "platform_model_credential_unavailable", "platform_model_timeout_invalid",
            "budget_storage_failed", "budget_registration_conflict", "budget_root_missing",
            "budget_scope_missing", "budget_transaction_scope_conflict",
            "budget_activity_observation_required", "budget_limits_invalid",
            "embedding_response_empty", "embedding_response_incomplete",
            "embedding_response_index_invalid", "embedding_dimensions_mismatch",
            "embedding_vector_nonfinite", "embedding_vector_zero_norm");

    /** 单次接口调用最大输入条数（百炼 compatible-mode 上限 20，OpenAI 兼容标准）。 */
    public static final int MAX_BATCH_SIZE = 20;

    /** 冻结模型连接与凭据支撑。 */
    private final PlatformModelHttpSupport http;

    /** 弹性调用组件。 */
    private final Resilience resilience;

    /** 用量事件写入组件（Provider 自动计量）。 */
    private final CostRecorder costRecorder;

    /** 外部调用尝试守卫。 */
    private final ExternalInvocationGuard externalInvocationGuard;

    /**
     * 构造 Embedding 客户端。
     *
     * @param http            冻结模型 HTTP 支撑
     * @param resilience      弹性调用组件
     * @param costRecorder    用量事件写入组件
     * @param externalInvocationGuard 外部调用尝试守卫
     */
    public EmbeddingClient(
            PlatformModelHttpSupport http,
            Resilience resilience,
            CostRecorder costRecorder,
            ExternalInvocationGuard externalInvocationGuard) {
        this.http = http;
        this.resilience = resilience;
        this.costRecorder = costRecorder;
        this.externalInvocationGuard = externalInvocationGuard;
    }

    /** 单批次只派发一次；宿主提供冻结模型与稳定逻辑调用身份，并绑定原成本/预算作用域。 */
    public Outcome embedBatch(String tenantId, PlatformModelConfig config, String invocationId, List<String> texts) {
        Duration timeout = Duration.ofSeconds(config != null && config.timeoutSeconds() != null
                ? config.timeoutSeconds() : 30);
        return embedBatch(tenantId, config, invocationId, texts, timeout, () -> false);
    }

    /** 调用者提供当前剩余预算；冻结模型配置与调用身份不会为了超时控制而改变。 */
    public Outcome embedBatch(String tenantId, PlatformModelConfig config, String invocationId,
            List<String> texts, Duration remaining, BooleanSupplier cancelled) {
        if (tenantId == null || tenantId.isBlank() || invocationId == null || invocationId.isBlank()
                || texts == null || texts.size() > MAX_BATCH_SIZE || texts.stream().anyMatch(java.util.Objects::isNull)
                || remaining == null || remaining.isNegative() || remaining.isZero() || cancelled == null) {
            return new Outcome.Rejected("embedding_request_invalid");
        }
        if (config == null || !"EMBEDDING".equals(config.kind()) || !config.enabled()
                || config.revision() < 1 || config.dimensions() == null || config.dimensions() < 1) {
            return new Outcome.Rejected("embedding_model_invalid");
        }
        List<String> inputs = List.copyOf(texts);
        String stage = "transport_preflight";
        try {
            if (cancelled.getAsBoolean()) return new Outcome.Rejected("embedding_cancelled");
            if (inputs.isEmpty()) return new Outcome.Available(List.of());
            // 解密和连接参数准备在markSent前完成，配置失败不能伪装成已派发模型调用。
            RestClient transport = http.client(config, remaining);
            stage = "endpoint_preflight";
            String endpoint = http.endpoint(config, "/embeddings");
            if (cancelled.getAsBoolean()) return new Outcome.Rejected("embedding_cancelled");
            stage = "invocation";
            return new Outcome.Available(resilience.callOnce("embedding:" + tenantId + ":" + config.id()
                    + ":" + config.revision(), () -> invokeGuarded(config, tenantId, invocationId, inputs,
                            () -> postEmbeddingsUnsafe(config, inputs, tenantId, transport, endpoint))));
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ExternalInvocationGuard.OutcomeUnknownException
                        || cause instanceof ExternalInvocationGuard.DuplicateAttemptException) {
                    return new Outcome.OutcomeUnknown(invocationId);
                }
            }
            // 诊断仅包含本地阶段、闭集原因或SQLSTATE；异常正文可能含供应商载荷和凭据。
            LOG.warn("Embedding调用拒绝 stage={} reason={} errorType={} causeType={}",
                    stage, safeFailureCode(failure), failure.getClass().getSimpleName(), safeCauseType(failure));
            // 供应商正文/凭据不进入调用者反馈；原guard和usage仍保留真实结果。
            return new Outcome.Rejected("embedding_call_rejected");
        }
    }

    /** 仅提取明确允许的技术类别，未知异常消息不进入日志。 */
    static String safeFailureCode(Throwable failure) {
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        String code = "unclassified";
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().matches("[A-Z0-9]{5}")) return "database_" + sql.getSQLState();
            if (cause instanceof org.springframework.web.client.HttpStatusCodeException http)
                return "provider_http_" + http.getStatusCode().value();
            if (cause.getMessage() != null && SAFE_FAILURE_CODES.contains(cause.getMessage())) code = cause.getMessage();
        }
        return code;
    }

    private static String safeCauseType(Throwable failure) {
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        String type = failure.getClass().getSimpleName();
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause())
            type = cause.getClass().getSimpleName();
        return type;
    }

    public sealed interface Outcome permits Outcome.Available, Outcome.Rejected, Outcome.OutcomeUnknown {
        /** 每个位置严格对应原输入，复制可变向量以保护已验证结果。 */
        record Available(List<float[]> vectors) implements Outcome {
            public Available { vectors = vectors.stream().map(float[]::clone).toList(); }
            @Override public List<float[]> vectors() { return vectors.stream().map(float[]::clone).toList(); }
        }
        record Rejected(String reasonCode) implements Outcome { }
        record OutcomeUnknown(String invocationId) implements Outcome { }
    }

    /**
     * 调用 OpenAI 兼容 embeddings 接口。
     *
     * @param config 配置
     * @param texts  文本列表
     * @return 向量列表（按响应 index 与输入对齐）
     */
    private List<float[]> postEmbeddingsUnsafe(
            PlatformModelConfig config, List<String> texts, String tenantId, RestClient transport, String endpoint) {
        EmbeddingResponse response = transport.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbeddingRequest(config.modelId(), texts, config.dimensions()))
                .retrieve()
                .body(EmbeddingResponse.class);
        if (response == null) {
            throw new IllegalStateException("embedding_response_empty");
        }
        // 即使向量载荷不完整，也保留供应商实际报告的用量。
        LlmUsage usage = response.measuredUsage();
        // 只采纳供应商响应 usage；缺失时记录未知，禁止字符数估算进入账本。
        costRecorder.recordLlm(
                    tenantId,
                    CostType.LLM_EMBEDDING,
                    config.providerId(),
                    config.modelId(),
                    usage,
                    1,
                    new ModelRevisionRef(config.id(), config.revision()),
                    java.util.Map.of("inputs", texts.size()));
        com.skillforge.core.billing.budget.AuxiliaryModelBudgets.captureUsage(usage);
        return vectors(response, texts.size(), config.dimensions());
    }

    /** 探测与正式调用共用向量校验；该纯解析方法不发请求、不登记业务调用。 */
    public static List<float[]> vectors(EmbeddingResponse response, int inputCount, int dimensions) {
        if (response == null) throw new IllegalStateException("embedding_response_empty");
        List<EmbeddingItem> data = response.data();
        if (data == null || data.size() != inputCount) {
            throw new IllegalStateException("embedding_response_incomplete");
        }
        float[][] aligned = new float[inputCount][];
        boolean[] seen = new boolean[inputCount];
        for (EmbeddingItem item : data) {
            if (item == null || item.index() == null || item.index() < 0
                    || item.index() >= inputCount || seen[item.index()]) {
                throw new IllegalStateException("embedding_response_index_invalid");
            }
            seen[item.index()] = true;
            if (item.embedding() == null || item.embedding().size() != dimensions)
                throw new IllegalStateException("embedding_dimensions_mismatch");
            float[] vector = new float[item.embedding().size()];
            double squaredNorm = 0;
            for (int index = 0; index < vector.length; index++) {
                Number value = item.embedding().get(index);
                if (value == null || !Float.isFinite(value.floatValue())) {
                    throw new IllegalStateException("embedding_vector_nonfinite");
                }
                vector[index] = value.floatValue();
                squaredNorm += (double) vector[index] * vector[index];
            }
            if (!(squaredNorm > 0)) throw new IllegalStateException("embedding_vector_zero_norm");
            aligned[item.index()] = vector;
        }
        // 完整对齐且维度正确后才交给业务消费者，缺失不是可发布向量。
        List<float[]> result = new ArrayList<>(inputCount);
        java.util.Collections.addAll(result, aligned);
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingResponse(List<EmbeddingItem> data, EmbeddingUsage usage) {
        public LlmUsage measuredUsage() { return usage == null ? LlmUsage.empty() : usage.measured(); }
    }

    /** dimensions是本次期望输出维度，不能只在响应后检查而不发送；固定返回数值向量。 */
    public record EmbeddingRequest(String model, List<String> input, Integer dimensions) {
        public EmbeddingRequest { input = List.copyOf(input); }
        @JsonProperty("encoding_format") public String encodingFormat() { return VECTOR_ENCODING; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingUsage(
            @JsonProperty("prompt_tokens") @JsonDeserialize(using = TokenCountReader.class) Long promptTokens,
            @JsonProperty("total_tokens") @JsonDeserialize(using = TokenCountReader.class) Long totalTokens) {
        private LlmUsage measured() {
            if (promptTokens != null && totalTokens != null && totalTokens < promptTokens) return LlmUsage.empty();
            return new LlmUsage(promptTokens, null, null, null, null, totalTokens,
                    promptTokens == null && totalTokens == null ? UsageSource.UNKNOWN : UsageSource.PROVIDER_REPORTED, null);
        }
    }

    /** 供应商Token只接收非负JSON整数，缺失或非法字段保留未知。 */
    public static final class TokenCountReader extends StdDeserializer<Long> {
        public TokenCountReader() { super(Long.class); }
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) { parser.skipChildren(); return null; }
            BigInteger value = parser.getBigIntegerValue();
            return value.signum() < 0 || value.bitLength() > 63 ? null : value.longValue();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingItem(
            @JsonDeserialize(using = EmbeddingIndexDeserializer.class) Integer index,
            List<Double> embedding) { }

    /** index 是离散身份；禁止 Jackson 将小数或字符串静默强转为输入序号。 */
    public static final class EmbeddingIndexDeserializer extends StdDeserializer<Integer> {
        public EmbeddingIndexDeserializer() { super(Integer.class); }
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                throw JsonMappingException.from(parser, "Embedding index 必须为整数");
            }
            return parser.getIntValue();
        }
    }

    /** 每个 Embedding 批次都使用独立 attemptId，重试复用同一逻辑 invocationId。 */
    private List<float[]> invokeGuarded(
            PlatformModelConfig config,
            String tenantId,
            String invocationId,
            List<String> inputs,
            ExternalInvocationGuard.ExternalCall<List<float[]>> call) {
        CostContext context = MeterScope.current();
        String baseId = context != null && context.eventId() != null && !context.eventId().isBlank()
                ? context.eventId() : invocationId;
        TraceContext trace = context != null && context.traceContext() != null
                ? context.traceContext()
                : new TraceContext(baseId, baseId, baseId, baseId, baseId, null);
        ExternalInvocationRequest request = new ExternalInvocationRequest(
                invocationId,
                invocationId + ":attempt:" + SnowflakeIds.nextId(),
                ExternalOperationType.MODEL_EMBEDDING,
                config.providerId(),
                config.modelId(),
                baseId,
                trace,
                Map.of("input_count", inputs.size()));
        try {
            long bound = com.skillforge.runtime.api.port.ModelTokenBounds.textInputs(inputs);
            var budgetRequest = com.skillforge.core.billing.budget.AuxiliaryModelBudgets.Request.freeze(
                    bound, 0, ExternalOperationType.MODEL_EMBEDDING.name(),
                    config.id() + ":" + config.revision() + ":" + config.dimensions(), inputs);
            return externalInvocationGuard.invokeModel(tenantId, request, budgetRequest, () -> {
                try {
                    return call.call();
                } catch (ResourceAccessException transportFailure) {
                    throw new ExternalInvocationGuard.OutcomeUnknownException(
                            "Embedding transport outcome is unknown", transportFailure);
                }
            });
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Embedding invocation failed", exception);
        }
    }

}

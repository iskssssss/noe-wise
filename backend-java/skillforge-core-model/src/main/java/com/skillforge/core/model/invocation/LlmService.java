package com.skillforge.core.model.invocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.shared.resilience.Resilience;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ResolvedProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Selection;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 机构后台模型调用门面。
 *
 * <p>租户配置、策略、弹性、调用账本和计量仍由宿主负责；Chat Completions 的请求、
 * 传输、响应解析和供应商方言统一复用 Agent Runtime，宿主不再维护第二套模型协议栈。
 */
@Service
public class LlmService {
    private static final ObjectMapper SUMMARY_JSON = new ObjectMapper();
    private static final EncodingRegistry SUMMARY_ENCODINGS = Encodings.newLazyEncodingRegistry();

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final PlatformModelCatalogService catalog;
    private final LlmHttpSupport httpSupport;
    private final Resilience resilience;
    private final OperationalSettingsService settings;
    private final ModelInvocationPolicyChain policyChain;
    private final ModelProviderRuntime modelRuntime;
    private final ModelProviderCatalog modelProviders;
    private final ExternalInvocationGuard invocationGuard;
    private final CostRecorder costRecorder;
    private final ModelCallRecords callRecords;
    private final ModelCallRecordCodec callRecordCodec = new ModelCallRecordCodec();

    /** 构造机构后台模型调用门面。 */
    public LlmService(
            PlatformModelCatalogService catalog,
            LlmHttpSupport httpSupport,
            Resilience resilience,
            OperationalSettingsService settings,
            ModelInvocationPolicyChain policyChain,
            ModelProviderRuntime modelRuntime,
            ModelProviderCatalog modelProviders,
            ExternalInvocationGuard invocationGuard,
            CostRecorder costRecorder,
            ModelCallRecords callRecords) {
        this.catalog = catalog;
        this.httpSupport = httpSupport;
        this.resilience = resilience;
        this.settings = settings;
        this.policyChain = policyChain == null
                ? new DefaultModelInvocationPolicyChain() : policyChain;
        this.modelRuntime = modelRuntime;
        this.modelProviders = modelProviders;
        this.invocationGuard = invocationGuard;
        this.costRecorder = costRecorder;
        this.callRecords = java.util.Objects.requireNonNull(callRecords);
    }

    /** 使用当前租户配置生成短文本回复。 */
    public LlmResult generate(String systemPrompt, String userInput) {
        return generateForTenant(requireTenantContext(), systemPrompt, userInput,
                new ResponseFormat.Text(), settings.read("calls").integer("llm_timeout_seconds"));
    }

    /** 使用当前租户配置和历史消息生成长文本回复。 */
    public LlmResult generate(
            String systemPrompt, String userInput, List<LlmHistoryMessage> history) {
        String tenantId = requireTenantContext();
        return call(load(tenantId), tenantId, systemPrompt, userInput, history,
                new ResponseFormat.Text(), settings.read("calls").integer("chat_timeout_seconds"));
    }

    /** 按显式租户执行指定响应格式的模型调用。 */
    public LlmResult generateForTenant(
            String tenantId, String systemPrompt, String userInput,
            ResponseFormat format, int timeoutSeconds) {
        requireTenantId(tenantId);
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须为正数");
        }
        return call(load(tenantId), tenantId, systemPrompt, userInput, List.of(),
                format, timeoutSeconds);
    }

    /** 按显式租户执行文本模型调用。 */
    public LlmResult generateForTenant(
            String tenantId, String systemPrompt, String userInput, int timeoutSeconds) {
        return generateForTenant(tenantId, systemPrompt, userInput,
                new ResponseFormat.Text(), timeoutSeconds);
    }

    /** 系统任务使用受理时冻结的模型版本，计量与策略仍归实际执行机构。 */
    public LlmResult generatePinned(String tenantId, String modelId, long modelRevision,
            String systemPrompt, String userInput, ResponseFormat format, int timeoutSeconds) {
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds 必须为正数");
        return generatePinned(tenantId, modelId, modelRevision, systemPrompt, userInput, format,
                Duration.ofSeconds(timeoutSeconds), Integer.MAX_VALUE, () -> false);
    }

    /** 系统用途受控调用：输出上限、剩余时间和取消信号均进入实际 Provider 请求。 */
    public LlmResult generatePinned(String tenantId, String modelId, long modelRevision,
            String systemPrompt, String userInput, ResponseFormat format, Duration timeout,
            int maxOutputTokens, BooleanSupplier cancelled) {
        requireTenantId(tenantId);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
        if (maxOutputTokens <= 0) throw new IllegalArgumentException("maxOutputTokens 必须为正数");
        if (cancelled == null) throw new IllegalArgumentException("cancelled 不能为空");
        if (cancelled.getAsBoolean()) throw new IllegalStateException("system_agent_cancelled");
        PlatformModelConfig selected = catalog.readRevision(modelId, modelRevision);
        if (!"LLM".equals(selected.kind())) throw new IllegalArgumentException("system_agent_model_kind_invalid");
        return call(resolve(selected), tenantId, systemPrompt, userInput, List.of(), format,
                timeout, maxOutputTokens, cancelled);
    }

    /** 受独立系统用途窗口约束的摘要调用，按实际 tokenizer 选择可容纳的完整前缀。 */
    public SummaryResult generatePinnedSummary(String tenantId, String modelId, long modelRevision,
            String systemPrompt, String userInput, ResponseFormat format, Duration timeout,
            int maxOutputTokens, BooleanSupplier cancelled) {
        requireTenantId(tenantId);
        PlatformModelConfig selected = catalog.readRevision(modelId, modelRevision);
        JsonNode root;
        try { root = SUMMARY_JSON.readTree(userInput); }
        catch (Exception invalid) { throw new IllegalArgumentException("context_summary_input_invalid", invalid); }
        if (root == null || !root.isObject() || !root.path("messages").isArray())
            throw new IllegalArgumentException("context_summary_input_invalid");
        int output = Math.min(maxOutputTokens, selected.maxOutputTokens());
        int covered = fitSummaryPrefix(systemPrompt, root, selected.contextWindowTokens(), output);
        String input;
        try {
            var copy = root.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("coveredMessages", covered);
            var messages = SUMMARY_JSON.createArrayNode();
            for (int index = 0; index < covered; index++) messages.add(root.path("messages").get(index));
            ((com.fasterxml.jackson.databind.node.ObjectNode) copy).set("messages", messages);
            input = SUMMARY_JSON.writeValueAsString(copy);
        } catch (Exception invalid) { throw new IllegalArgumentException("context_summary_input_invalid", invalid); }
        return new SummaryResult(generatePinned(tenantId, modelId, modelRevision, systemPrompt, input,
                format, timeout, output, cancelled), covered);
    }

    public record SummaryResult(LlmResult result, int coveredMessages) {
        public SummaryResult {
            if (coveredMessages <= 0) throw new IllegalArgumentException("coveredMessages must be positive");
        }
    }

    private static int fitSummaryPrefix(String systemPrompt, JsonNode root, int contextWindow, int output) {
        if (contextWindow <= output) throw new IllegalArgumentException("context_summary_window_unavailable");
        var encoding = SUMMARY_ENCODINGS.getEncoding(EncodingType.fromName("cl100k_base")
                .orElseThrow(() -> new IllegalArgumentException("context_summary_tokenizer_unavailable")));
        var values = root.path("messages");
        long budget = (long) contextWindow - output;
        for (int count = values.size(); count > 0; count--) {
            JsonNode last = values.get(count - 1);
            if (!last.path("closed").asBoolean(false)) continue;
            try {
                var copy = root.deepCopy();
                var messages = SUMMARY_JSON.createArrayNode();
                for (int index = 0; index < count; index++) messages.add(values.get(index));
                ((com.fasterxml.jackson.databind.node.ObjectNode) copy).set("messages", messages);
                ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("coveredMessages", count);
                long framing = 4L + 1L + 3L + 2L + 3L;
                long tokens = framing + encoding.encodeOrdinary(systemPrompt).size()
                        + encoding.encodeOrdinary(SUMMARY_JSON.writeValueAsString(copy)).size();
                if (tokens <= budget) return count;
            } catch (Exception invalid) { throw new IllegalArgumentException("context_summary_input_invalid", invalid); }
        }
        throw new IllegalArgumentException("context_summary_window_unavailable");
    }

    /** 判断机构模型配置是否满足后台调用前置条件。 */
    public boolean institutionModelConfigured(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        try {
            return load(tenantId).usable();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** 使用已解析的 Runtime 供应商档案测试连接，不写调用账本和计量流水。 */
    public TestResult testConnection(ResolvedProfile profile, String storedApiKey) {
        long startedAt = System.currentTimeMillis();
        try {
            modelProviders.validateReasoning(profile, ReasoningPolicy.DEFAULT);
            String apiKey = httpSupport.resolveApiKey(storedApiKey);
            ModelProvider.InvocationResult result = modelRuntime.generate(
                    profile.providerId(), providerRequest(profile, apiKey,
                            List.of(new ModelMessage("connection-test", ModelRole.USER, "ping")),
                            new ResponseFormat.Text(), ReasoningPolicy.DEFAULT,
                            Duration.ofSeconds(settings.read("calls").integer("llm_timeout_seconds")),
                            new GenerationOptions(OptionalInt.of(64), OptionalDouble.empty()), () -> false));
            if (result instanceof ModelProvider.InvocationResult.Completed completed) {
                if ("length".equals(completed.finishReasonCode())) return TestResult.failure("model_output_length");
                if ("content_filter".equals(completed.finishReasonCode())) return TestResult.failure("model_content_filtered");
                return completed.content() == null
                        ? TestResult.failure("接口未返回回复内容")
                        : TestResult.success(System.currentTimeMillis() - startedAt);
            }
            String reason = result instanceof ModelProvider.InvocationResult.Rejected rejected
                    ? rejected.reasonCode()
                    : ((ModelProvider.InvocationResult.OutcomeUnknown) result).reasonCode();
            return TestResult.failure(reason);
        } catch (Exception failure) {
            return TestResult.failure(httpSupport.friendlyError(failure));
        }
    }

    private LlmResult call(
            CachedModelConfig config, String tenantId, String systemPrompt,
            String userInput, List<LlmHistoryMessage> history,
            ResponseFormat format, int timeoutSeconds) {
        return call(config, tenantId, systemPrompt, userInput, history, format,
                Duration.ofSeconds(timeoutSeconds), Integer.MAX_VALUE, () -> false);
    }

    private LlmResult call(
            CachedModelConfig config, String tenantId, String systemPrompt,
            String userInput, List<LlmHistoryMessage> history,
            ResponseFormat format, Duration timeout, int maxOutputTokens,
            BooleanSupplier cancelled) {
        if (!config.usable()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "机构未选择可用的模型，请在机构设置中选择平台模型");
        }
        // 后台用途逐次声明输出契约，不要求所有可选对话模型都支持 JSON。
        try {
            var kind = format instanceof ResponseFormat.JsonObject
                    ? com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind.JSON_OBJECT
                    : format instanceof ResponseFormat.JsonSchema
                    ? com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind.JSON_SCHEMA
                    : com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind.TEXT;
            modelProviders.validateCapabilities(config.profile(), java.util.Set.of(kind), false, false,
                    false);
            modelProviders.validateReasoning(config.profile(), ReasoningPolicy.valueOf(config.model().reasoning()));
        } catch (IllegalArgumentException unsupported) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "model_call_capability_unsupported");
        }
        authorize(config, tenantId, systemPrompt, userInput, history);
        List<ModelMessage> messages = messages(systemPrompt, userInput, history);
        Duration effectiveTimeout = timeout.compareTo(Duration.ofSeconds(config.model().timeoutSeconds())) > 0
                ? Duration.ofSeconds(config.model().timeoutSeconds()) : timeout;
        if (cancelled.getAsBoolean()) throw new IllegalStateException("system_agent_cancelled");
        String previousTenant = TenantContext.getTenantId();
        TenantContext.setTenantId(tenantId);
        try {
            var action = (java.util.concurrent.Callable<LlmResult>) () -> invokeAttempt(config, tenantId, messages,
                    format, effectiveTimeout, maxOutputTokens, cancelled);
            // 模型请求没有跨调用幂等保证；有限重试仅由 Runtime 根据已落账的拒绝事实协调。
            return resilience.callOnce("llm:" + tenantId, action);
        } catch (TruncatedOutputException failure) {
            throw failure;
        } catch (ContentFilteredException failure) {
            throw failure;
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception failure) {
            if (failure instanceof com.skillforge.core.shared.resilience.ResilienceException
                    && failure.getCause() instanceof TruncatedOutputException truncated) throw truncated;
            if (failure instanceof com.skillforge.core.shared.resilience.ResilienceException
                    && failure.getCause() instanceof ContentFilteredException filtered) throw filtered;
            var diagnostic = failure instanceof com.skillforge.core.shared.resilience.ResilienceException
                    && failure.getCause() != null ? failure.getCause() : failure;
            if (diagnostic instanceof ExternalInvocationGuard.OutcomeUnknownException unknown) {
                throw new ModelOutcomeUnknownException(unknown);
            }
            log.warn("LLM 调用失败 tenant={} category=upstream_failure diagnostic={}", tenantId,
                    com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(diagnostic));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM 服务调用失败");
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previousTenant);
            }
        }
    }

    private LlmResult invokeAttempt(
            CachedModelConfig config, String tenantId, List<ModelMessage> messages,
            ResponseFormat format, Duration timeout, int maxOutputTokens,
            BooleanSupplier cancelled) throws Exception {
        CostContext context = MeterScope.current();
        String invocationId = context == null || context.traceContext() == null
                ? "auxiliary:" + SnowflakeIds.nextId()
                : context.eventId() == null || context.eventId().isBlank()
                ? context.traceContext().inputId() + ":model.chat" : context.eventId();
        var trace = context == null || context.traceContext() == null
                ? new com.skillforge.core.shared.tracing.TraceContext(invocationId,invocationId,invocationId,invocationId,invocationId,null)
                : context.traceContext();
        ExternalInvocationRequest request = new ExternalInvocationRequest(
                invocationId,
                invocationId + ":attempt:" + SnowflakeIds.nextId(),
                ExternalOperationType.MODEL_CHAT,
                config.profile().providerId(),
                config.profile().serviceId(),
                invocationId,
                trace,
                Map.of("stream", false));
        long inputBound = com.skillforge.runtime.api.port.ModelTokenBounds.input(messages, List.of(), config.model().imagePolicy());
        long outputLimit = generation(config.model(), maxOutputTokens).maxOutputTokens().orElseThrow();
        var frozenContent = new java.util.ArrayList<String>();
        frozenContent.add(format.toString());
        frozenContent.add(config.model().reasoning());
        frozenContent.add(generation(config.model(), maxOutputTokens).toString());
        frozenContent.add(timeout.toString());
        messages.forEach(message -> frozenContent.add(message.accountingText()));
        var resources = com.skillforge.core.billing.budget.AuxiliaryModelBudgets.Request.freeze(
                inputBound, outputLimit, ExternalOperationType.MODEL_CHAT.name(),
                config.profile().toString(), frozenContent);
        return invocationGuard.invokeModel(tenantId, request, resources,
                () -> execute(config, tenantId, messages, format, timeout, maxOutputTokens, cancelled, true, request));
    }

    private LlmResult execute(
            CachedModelConfig config, String tenantId, List<ModelMessage> messages,
            ResponseFormat format, Duration timeout, int maxOutputTokens,
            BooleanSupplier cancelled, boolean guarded, ExternalInvocationRequest invocation) throws Exception {
        var actual = providerRequest(config.profile(),
                httpSupport.resolveApiKey(config.model().apiKeyEncrypted()), messages,
                format, ReasoningPolicy.valueOf(config.model().reasoning()), timeout,
                generation(config.model(), maxOutputTokens), cancelled);
        boolean requestRecorded = recordRequest(tenantId, invocation, config.profile().providerId(), actual);
        ModelProvider.InvocationResult result = modelRuntime.generate(config.profile().providerId(), actual);
        if (requestRecorded) recordOutcome(tenantId, invocation, result);
        if (result instanceof ModelProvider.InvocationResult.OutcomeUnknown unknown) {
            if (guarded) {
                var failure = new ExternalInvocationGuard.OutcomeUnknownException(
                        "模型调用结果无法确认：" + unknown.reasonCode(), null);
                LlmUsage usage = LlmUsageMapper.fromModel(unknown.usage());
                if (usage.usageSource() == com.skillforge.core.billing.metering.UsageSource.PROVIDER_REPORTED) {
                    try {
                        CostContext context = MeterScope.current();
                        if (context == null) {
                            var trace = (com.skillforge.core.shared.tracing.TraceContext) invocation.context();
                            context = CostContext.builder().callPoint("auxiliary.invocation")
                                    .traceContext(trace).requestId(trace.inputId()).build();
                        }
                        costRecorder.recordUsageReport(tenantId, context, invocation, actual.modelId(), 1, usage, Map.of());
                    } catch (RuntimeException accountingFailure) {
                        failure.addSuppressed(accountingFailure);
                        log.warn("模型未知结果用量未记录 tenant={} reason=MODEL_UNKNOWN_USAGE_NOT_RECORDED", tenantId);
                    }
                }
                throw failure;
            }
            throw new IllegalStateException(unknown.reasonCode());
        }
        if (result instanceof ModelProvider.InvocationResult.Rejected rejected) {
            var failure = new IllegalStateException(rejected.reasonCode());
            recordFailedUsage(config, tenantId, actual.modelId(), LlmUsageMapper.fromModel(rejected.usage()), failure);
            throw failure;
        }
        ModelProvider.InvocationResult.Completed completed =
                (ModelProvider.InvocationResult.Completed) result;
        LlmUsage usage = LlmUsageMapper.fromModel(completed.usage());
        if (!completed.toolCalls().isEmpty() && !"length".equals(completed.finishReasonCode())
                && !"content_filter".equals(completed.finishReasonCode())) {
            var failure = new IllegalStateException("model_unexpected_tool_calls");
            recordFailedUsage(config, tenantId, completed.modelId(), usage, failure);
            throw failure;
        }
        recordUsage(config, tenantId, completed.modelId(), usage);
        if ("length".equals(completed.finishReasonCode())) {
            throw new TruncatedOutputException();
        }
        if ("content_filter".equals(completed.finishReasonCode())) {
            throw new ContentFilteredException();
        }
        return new LlmResult(completed.content(), completed.modelId(), usage);
    }

    private void recordUsage(CachedModelConfig config, String tenantId, String model, LlmUsage usage) {
        costRecorder.recordLlm(tenantId, CostType.LLM_CHAT,
                config.profile().providerId(), model, usage, 1,
                new ModelRevisionRef(config.model().id(), config.model().revision()), null);
        com.skillforge.core.billing.budget.AuxiliaryModelBudgets.captureUsage(usage);
    }

    /** 保留已确定的拒绝；计量失败时预算维持未知，不能覆盖原结果或伪造零用量。 */
    private void recordFailedUsage(CachedModelConfig config, String tenantId, String model,
            LlmUsage usage, IllegalStateException failure) {
        if (usage.usageSource() != com.skillforge.core.billing.metering.UsageSource.PROVIDER_REPORTED) return;
        try {
            recordUsage(config, tenantId, model, usage);
        } catch (RuntimeException accountingFailure) {
            failure.addSuppressed(accountingFailure);
            log.warn("模型失败用量未记录 tenant={} reason=MODEL_FAILED_USAGE_NOT_RECORDED", tenantId);
        }
    }

    /** 观测采集不拥有派发权；保存失败不重发、不覆盖真实Provider结果，也不输出异常原文。 */
    private boolean recordRequest(String tenant, ExternalInvocationRequest invocation, String provider,
            ModelProvider.InvocationRequest actual) {
        try {
            callRecords.recordRequest(Long.parseLong(tenant), invocation.invocationId(), invocation.attemptId(),
                    callRecordCodec.request(provider, actual));
            return true;
        } catch (RuntimeException failure) {
            log.warn("模型请求材料未记录 tenant={} reason=MODEL_CALL_REQUEST_NOT_RECORDED", tenant);
            return false;
        }
    }

    private void recordOutcome(String tenant, ExternalInvocationRequest invocation, ModelProvider.InvocationResult actual) {
        try {
            callRecords.recordOutcome(Long.parseLong(tenant), invocation.invocationId(), invocation.attemptId(),
                    callRecordCodec.outcome(actual));
        } catch (RuntimeException failure) {
            log.warn("模型返回材料未记录 tenant={} reason=MODEL_CALL_OUTCOME_NOT_RECORDED", tenant);
        }
    }

    /** 供应商结果未确认，供系统任务保留未知状态；HTTP 边界仍返回网关错误。 */
    public static final class ModelOutcomeUnknownException extends ApiException {
        public ModelOutcomeUnknownException(Throwable cause) { super(HttpStatus.BAD_GATEWAY, "LLM 服务调用结果未知", cause); }
    }

    /** 已确定的截断结果不得作为完整摘要或系统动作正文采用；不携带供应商原文。 */
    public static final class TruncatedOutputException extends RuntimeException {
        public TruncatedOutputException() { super("system_agent_output_truncated"); }
    }

    /** 内容过滤是确定失败，不能采用供应商返回的部分正文。 */
    public static final class ContentFilteredException extends RuntimeException {
        public ContentFilteredException() { super("model_content_filtered"); }
    }

    private CachedModelConfig load(String tenantId) {
        PlatformModelConfig selected = catalog.resolveLlm(tenantId, null);
        return resolve(selected);
    }

    private CachedModelConfig resolve(PlatformModelConfig selected) {
        ResolvedProfile profile = modelProviders.resolve(new Selection(
                selected.providerId(), selected.serviceId(), selected.profileVersion(), selected.modelId(),
                ModelProtocol.fromId(selected.protocol()), AuthenticationScheme.fromId(selected.authenticationScheme()),
                selected.endpoint()));
        if (!profile.dialectVersion().equals(selected.dialectVersion())) throw new IllegalStateException("model_dialect_version_mismatch");
        return new CachedModelConfig(profile, selected);
    }

    private void authorize(
            CachedModelConfig config, String tenantId, String systemPrompt,
            String userInput, List<LlmHistoryMessage> history) {
        CostContext context = MeterScope.current();
        String eventId = context == null ? tenantId + ":llm:policy" : context.eventId();
        String callPoint = context == null || context.callPoint() == null
                || context.callPoint().isBlank() ? "llm.generate" : context.callPoint();
        ModelPolicyDecision decision = policyChain.evaluate(new ModelInvocationPolicyInput(
                eventId == null || eventId.isBlank() ? tenantId + ":llm:policy" : eventId,
                tenantId, config.profile().providerId(), config.profile().modelId(), callPoint,
                history == null ? 0 : history.size(),
                systemPrompt == null ? 0 : systemPrompt.length(),
                userInput == null ? 0 : userInput.length()));
        if (!decision.allowed()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "LLM 调用未通过策略：" + decision.reasonCode());
        }
    }

    private List<ModelMessage> messages(
            String systemPrompt, String userInput, List<LlmHistoryMessage> history) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage("system", ModelRole.SYSTEM,
                systemPrompt == null ? "" : systemPrompt));
        if (history != null) {
            int index = 0;
            for (LlmHistoryMessage record : history) {
                ModelRole role = switch (record.role()) {
                    case USER -> ModelRole.USER;
                    case ASSISTANT -> ModelRole.ASSISTANT;
                };
                messages.add(new ModelMessage("history-" + index++, role,
                        record.content() == null ? "" : record.content()));
            }
        }
        messages.add(new ModelMessage("user", ModelRole.USER,
                userInput == null ? "" : userInput));
        return List.copyOf(messages);
    }

    private ResponseContract responseContract(ResponseFormat format) {
        EnforcementLevel enforcement = format instanceof ResponseFormat.Text
                ? EnforcementLevel.BEST_EFFORT : EnforcementLevel.STRICT;
        return new ResponseContract(format, enforcement, "host_background_call");
    }

    private GenerationOptions generation(PlatformModelConfig model, int requestedMaxOutputTokens) {
        return new GenerationOptions(OptionalInt.of(Math.min(model.maxOutputTokens(), requestedMaxOutputTokens)),
                model.temperature() == null ? OptionalDouble.empty() : OptionalDouble.of(model.temperature()));
    }

    private ModelProvider.InvocationRequest providerRequest(
            ResolvedProfile profile, String credential, List<ModelMessage> messages,
            ResponseFormat format, ReasoningPolicy reasoning, Duration timeout,
            GenerationOptions generation, BooleanSupplier cancelled) {
        return new ModelProvider.InvocationRequest(
                profile.endpoint(), credential, profile.modelId(),
                new ModelProvider.InvocationProfile(profile.serviceId(),
                        profile.profileVersion(), profile.protocol(),
                        profile.authenticationScheme(), profile.dialectVersion()),
                messages.stream().map(message -> new ModelProvider.Message(
                        switch (message.role()) {
                            case SYSTEM -> ModelProvider.Role.SYSTEM;
                            case USER -> ModelProvider.Role.USER;
                            case ASSISTANT -> ModelProvider.Role.ASSISTANT;
                            case TOOL -> throw new IllegalArgumentException("direct_llm_tool_history_unsupported");
                        }, message.contents(), null, List.of(), null)).toList(),
                responseContract(format), generation, reasoning, List.of(),
                ModelProvider.ToolChoice.NONE, timeout, cancelled, () -> { });
    }

    private String requireTenantContext() {
        String tenantId = TenantContext.getTenantId();
        requireTenantId(tenantId);
        return tenantId;
    }

    private void requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
        }
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record CachedModelConfig(ResolvedProfile profile, PlatformModelConfig model) {
        private boolean usable() { return profile != null && model != null && model.enabled(); }
    }

    /** 连接测试结果。 */
    public record TestResult(boolean ok, long latencyMs, String error) {

        private static TestResult success(long latencyMs) {
            return new TestResult(true, latencyMs, null);
        }

        private static TestResult failure(String error) {
            return new TestResult(false, 0, error);
        }
    }
}

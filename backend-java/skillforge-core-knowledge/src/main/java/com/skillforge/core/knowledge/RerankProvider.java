package com.skillforge.core.knowledge;

import com.skillforge.core.model.embedding.PlatformModelHttpSupport;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.logging.SafeFailureDiagnostics;

import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.shared.tracing.TraceContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * 机构级 Rerank 客户端。
 *
 * <p>按租户配置调用 rerank 端点（{@code {base}{path}}），支持两种协议：
 * OpenAI 兼容（请求 {@code {model, query, documents}}，响应
 * {@code results[{index, relevance_score}]}）与
 * 阿里云百炼原生（请求 {@code {model, input:{query, documents}}}，响应
 * {@code output.results[{index, relevance_score}]}）；按冻结服务身份选择，
 * 协议分派见 {@link RerankClientFactory}（模板方法 {@link AbstractRerankClient}）。
 * 失败返回 null，调用方保留原序。
 */
@Component
public class RerankProvider {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(RerankProvider.class);

    /** 平台模型配置门面。 */
    private final KnowledgeProviderConfigService configService;

    /** 协议策略工厂（按冻结服务分派百炼 / OpenAI 兼容）。 */
    private final RerankClientFactory clientFactory;

    /** 用量事件写入组件（Provider 自动计量）。 */
    private final CostRecorder costRecorder;

    /** 外部调用尝试守卫。 */
    private final ExternalInvocationGuard externalInvocationGuard;

    /**
     * 构造 Rerank 客户端。
     *
     * @param configService   平台模型配置门面
     * @param http            平台知识模型 HTTP 支撑
     * @param costRecorder    用量事件写入组件
     * @param externalInvocationGuard 外部调用尝试守卫
     */
    @Autowired
    public RerankProvider(
            KnowledgeProviderConfigService configService,
            PlatformModelHttpSupport http,
            CostRecorder costRecorder,
            ExternalInvocationGuard externalInvocationGuard) {
        this(configService, costRecorder, externalInvocationGuard,
                new RerankClientFactory(http));
    }

    /** 供同包测试注入协议客户端工厂，生产使用公开构造器。 */
    RerankProvider(
            KnowledgeProviderConfigService configService,
            CostRecorder costRecorder,
            ExternalInvocationGuard externalInvocationGuard,
            RerankClientFactory clientFactory) {
        this.configService = configService;
        this.clientFactory = clientFactory;
        this.costRecorder = costRecorder;
        this.externalInvocationGuard = externalInvocationGuard;
    }

    /**
     * 对候选文档重排。
     *
     * @param tenantId   租户 ID
     * @param query      查询文本
     * @param documents 候选文本列表
     * @return 重排结果（index 对应候选下标，relevanceScore 为相关度）；失败返回 null
     */
    public List<RerankResult> rerank(String tenantId, String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return null;
        }
        CostContext captured = MeterScope.current();
        var budgetScope = com.skillforge.runtime.api.port.ExecutionBudgetScope.current();
        try {
            PlatformModelConfig config = config(tenantId);
            int timeoutSeconds = config.timeoutSeconds() == null ? 30 : config.timeoutSeconds();
            List<RerankResult> results = CompletableFuture
                    .supplyAsync(() -> {
                        // 模型选择已核验本次机构；在实际worker线程进入作用域，结束后恢复原线程身份。
                        return TenantContext.callWithTenant(tenantId, () -> {
                            try (var scope = budgetScope.map(com.skillforge.runtime.api.port.ExecutionBudgetScope::open).orElse(null)) {
                                return postRerankGuarded(tenantId, config, query, documents, captured);
                            }
                        });
                    })
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            return results;
        } catch (Exception e) {
            log.warn("Rerank调用失败，保留原序 reason=rerank_failed diagnostic={}", SafeFailureDiagnostics.of(e));
            return null;
        }
    }

    /**
     * 读取机构当前选择的已启用平台 Rerank 模型。
     *
     * @param tenantId 租户 ID
     * @return 配置
     */
    public PlatformModelConfig config(String tenantId) {
        return configService.rerank(tenantId);
    }

    /**
     * 调用 rerank 端点（按 Base URL 主机自动选择协议，见 {@link RerankClientFactory}）。
     *
     * @param config    配置
     * @param query     查询文本
     * @param documents 候选文本
     * @return 排名与实际用量；缺结果时 results 为 null
     */
    private RerankClient.Response postRerank(PlatformModelConfig config, String query, List<String> documents) {
        return clientFactory.forConfig(config).postRerank(config, query, documents);
    }

    /** 异步任务显式接收调用方的业务上下文，不能依赖 worker 线程的 ThreadLocal。 */
    private List<RerankResult> postRerankGuarded(
            String tenantId,
            PlatformModelConfig config,
            String query,
            List<String> documents,
            CostContext captured) {
        // 连接测试直接调用 postRerank，不进入此方法；一旦带有租户身份的
        // 业务调用到达这里，必须统一经过 ExternalInvocationGuard。
        if (tenantId == null || tenantId.isBlank()) {
            return postRerank(config, query, documents).results();
        }
        String baseId = captured != null && captured.eventId() != null && !captured.eventId().isBlank()
                ? captured.eventId()
                : captured != null && captured.traceContext() != null
                ? captured.traceContext().inputId() + ":model.rerank"
                : SnowflakeIds.nextId() + ":model.rerank";
        TraceContext trace = captured != null && captured.traceContext() != null
                ? captured.traceContext()
                : new TraceContext(baseId, baseId, baseId, baseId, baseId, null);
        String invocationId = baseId + ":rerank";
        ExternalInvocationRequest request = new ExternalInvocationRequest(
                invocationId,
                invocationId + ":attempt:" + SnowflakeIds.nextId(),
                ExternalOperationType.MODEL_RERANK,
                config.providerId(),
                config.modelId(),
                baseId,
                trace,
                Map.of("document_count", documents.size()));
        try (MeterScope ignored = MeterScope.open(captured)) {
            long bound = com.skillforge.runtime.api.port.ModelTokenBounds.textInputs(
                    java.util.stream.Stream.concat(java.util.stream.Stream.of(query),documents.stream()).toList());
            var budgetRequest = com.skillforge.core.billing.budget.AuxiliaryModelBudgets.Request.freeze(
                    bound, 0, ExternalOperationType.MODEL_RERANK.name(),
                    config.providerId() + ":" + config.modelId(),
                    java.util.stream.Stream.concat(java.util.stream.Stream.of(query), documents.stream()).toList());
            return externalInvocationGuard.invokeModel(tenantId, request, budgetRequest, () -> {
                try {
                    RerankClient.Response response = postRerank(config, query, documents);
                    // 计量必须发生在 Guard 的 attempt scope 内，才能把 UsageEvent 与真实网络尝试关联。
                    // 连接测试不经过本路径；无业务 MeterScope 的调用会在本方法入口直接短路。
                    costRecorder.recordLlm(
                            tenantId,
                            CostType.LLM_RERANK,
                            config.providerId(),
                            config.modelId(),
                            response.usage(),
                            documents.size(),
                            new ModelRevisionRef(config.id(), config.revision()),
                            null);
                    com.skillforge.core.billing.budget.AuxiliaryModelBudgets.captureUsage(response.usage());
                    return response.results();
                } catch (ResourceAccessException transportFailure) {
                    throw new ExternalInvocationGuard.OutcomeUnknownException(
                            "Rerank transport outcome is unknown", transportFailure);
                }
            });
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Rerank invocation failed", exception);
        }
    }

    /**
     * 重排结果。
     *
     * @param index          候选下标
     * @param relevanceScore 相关度得分
     */
    public record RerankResult(int index, double relevanceScore) {
    }

}

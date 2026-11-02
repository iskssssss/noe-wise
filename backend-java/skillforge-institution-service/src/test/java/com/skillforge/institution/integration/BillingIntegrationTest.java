package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.metering.AmountStatus;
import com.skillforge.core.billing.metering.UsageAdjustment;
import com.skillforge.core.billing.metering.UsageEventKind;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.invocation.ExternalInvocationDO;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.quota.QuotaService;
import com.skillforge.core.billing.reconciliation.UsageReconciliationProvider;
import com.skillforge.core.billing.reconciliation.UsageReconciliationService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.billing.persistence.ExternalInvocationMapper;
import com.skillforge.core.billing.persistence.PlanDO;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Message;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Role;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 计费配额与用量事件集成测试（usage_events 最小单元口径，N28）。
 *
 * <p>验证：事件幂等（同 event_id 只落一行）、MeterScope 关联维度与金额写库、
 * 无作用域调用不产生流水、配额基于 request 根事件计数、超配额 429。
 */
class BillingIntegrationTest extends AbstractIntegrationTest {

    /** 租户表映射器。 */
    @Autowired
    private TenantMapper tenantMapper;

    /** 套餐表映射器。 */
    @Autowired
    private PlanMapper planMapper;

    /** 用量事件表映射器。 */
    @Autowired
    private CostEventMapper costEventMapper;

    /** 外部调用尝试账本映射器。 */
    @Autowired
    private ExternalInvocationMapper externalInvocationMapper;

    /** 当前守卫构造器的真实辅助模型预算依赖；重启夹具复用宿主装配。 */
    @Autowired
    private com.skillforge.core.billing.budget.AuxiliaryModelBudgets auxiliaryModelBudgets;

    /** 配额服务。 */
    @Autowired
    private QuotaService quotaService;

    /** 用量事件写入组件。 */
    @Autowired
    private CostRecorder costRecorder;

    /** 测试租户 ID。 */
    private String tenantId;

    /** 免费套餐。 */
    private PlanDO freePlan;

    /**
     * 注册租户并确保免费套餐存在。
     */
    @BeforeEach
    void setUp() {
        freePlan = ensureFreePlan(planMapper);
        tenantId = createTenant(tenantMapper, planMapper, "billing-" + SnowflakeIds.nextId());
        TenantContext.clear();
        MeterScope.clear();
    }

    /**
     * 相同 event_id 重复写入只落一行（重试防双计）。
     */
    @Test
    void idempotentEventRecordedOnce() {
        try {
            TenantContext.setTenantId(tenantId);
            try (MeterScope scope = MeterScope.open(CostContext.builder()
                    .eventId("exec-1:agent.plan:1")
                    .requestId("req-1")
                    .callPoint("agent.plan")
                    .build())) {
                costRecorder.recordLlm(tenantId, CostType.LLM_CHAT, "openai_compatible",
                        "deepseek-chat", new LlmUsage(120, 80, 200), 1, null);
                costRecorder.recordLlm(tenantId, CostType.LLM_CHAT, "openai_compatible",
                        "deepseek-chat", new LlmUsage(120, 80, 200), 1, null);
            }
            Long count = costEventMapper.selectCount(
                    new LambdaQueryWrapper<CostEventDO>()
                            .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                            .eq(CostEventDO::getEventId, "exec-1:agent.plan:1"));
            assertEquals(1L, count);
        } finally {
            TenantContext.clear();
            MeterScope.clear();
        }
    }

    @Test
    void systemSummaryRequestIdentityIsStoredWithoutTruncation() {
        String requestId = "system-agent:context-summary:" + "a".repeat(99);
        try {
            TenantContext.setTenantId(tenantId);
            try (MeterScope scope = MeterScope.open(CostContext.builder()
                    .eventId("summary-usage").requestId(requestId).callPoint("system-agent.task").build())) {
                costRecorder.recordLlm(tenantId, CostType.LLM_CHAT, "openai_compatible",
                        "deepseek-chat", new LlmUsage(120, 80, 200), 1, null);
            }
            var recorded = costEventMapper.selectOne(new LambdaQueryWrapper<CostEventDO>()
                    .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                    .eq(CostEventDO::getEventId, "summary-usage"));
            assertEquals(requestId, recorded.getRequestId());
        } finally {
            TenantContext.clear();
            MeterScope.clear();
        }
    }

    /**
     * 用量达到配额后检查应抛出 429（request 根事件计数）。
     */
    @Test
    void quotaExceededThrows() {
        try {
            TenantContext.setTenantId(tenantId);
            freePlan.setQuotaApiCallsPerMonth(1);
            planMapper.updateById(freePlan);
            costRecorder.recordRequest(tenantId, "req-2:request", "req-2",
                    CostContext.builder().callPoint("chat.request").build(), Instant.now());
            assertThrows(ApiException.class, () -> quotaService.check(tenantId));
        } finally {
            TenantContext.clear();
            MeterScope.clear();
        }
    }

    /**
     * llm.chat 明细：关联维度（request_id/session/end_user/call_point）与
     * tokens 正确落库；parent_event_id 指向外层事件（Langfuse 父子树）。
     */
    @Test
    void recordLlmPersistsDimensionsAndParent() {
        try {
            TenantContext.setTenantId(tenantId);
            // 外层 scope 保持打开（真实场景：内层 scope 在外层未关闭时嵌套开启），
            // 内层事件 parent_event_id 才指向外层最近事件（Langfuse 父子树）
            MeterScope outer = MeterScope.open(CostContext.builder()
                    .eventId("req-3:plan:1")
                    .requestId("req-3")
                    .sessionId("sess-1")
                    .endUserId(1001L)
                    .runId("2001")
                    .callPoint("agent.plan")
                    .build());
            try {
                costRecorder.recordLlm(tenantId, CostType.LLM_CHAT, "openai_compatible",
                        "deepseek-chat", new LlmUsage(120, 80, 200), 1, null);
                // 内层调用（嵌套 scope）：parent_event_id 应指向外层最近事件
                try (MeterScope scope = MeterScope.open(CostContext.builder()
                        .eventId("req-3:node:1")
                        .requestId("req-3")
                        .sessionId("sess-1")
                        .executionId("3001")
                        .callPoint("workflow.llm_node")
                        .build())) {
                    costRecorder.recordLlm(tenantId, CostType.LLM_CHAT, "openai_compatible",
                            "deepseek-chat", new LlmUsage(30, 10, 40), 1, null);
                }
            } finally {
                outer.close();
            }
            List<CostEventDO> rows = costEventMapper.selectList(
                    new LambdaQueryWrapper<CostEventDO>()
                            .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                            .orderByAsc(CostEventDO::getOccurredAt));
            assertEquals(2, rows.size());
            CostEventDO outerRow = rows.get(0);
            assertEquals("llm.chat", outerRow.getEventType());
            assertEquals(200, outerRow.getTokens());
            assertEquals(UsageSource.PROVIDER_REPORTED.name(), outerRow.getUsageSource());
            assertEquals("req-3", outerRow.getRequestId());
            assertEquals("sess-1", outerRow.getSessionId());
            assertEquals(1001L, outerRow.getEndUserId());
            assertEquals("2001", outerRow.getRunId());
            assertEquals("agent.plan", outerRow.getCallPoint());
            CostEventDO innerRow = rows.get(1);
            assertEquals(outerRow.getId(), innerRow.getParentEventId());
            assertEquals("3001", innerRow.getExecutionId());
            assertEquals("workflow.llm_node", innerRow.getCallPoint());
        } finally {
            TenantContext.clear();
            MeterScope.clear();
        }
    }

    /**
     * 无计量作用域调用不产生流水（连接测试等场景）。
     */
    @Test
    void recordWithoutScopeSkipped() {
        try {
            TenantContext.setTenantId(tenantId);
            costRecorder.recordLlm(tenantId, CostType.LLM_CHAT, "openai_compatible",
                    "deepseek-chat", new LlmUsage(1, 1, 2), 1, null);
            Long count = costEventMapper.selectCount(
                    new LambdaQueryWrapper<CostEventDO>()
                            .eq(CostEventDO::getTenantId, Long.parseLong(tenantId)));
            assertEquals(0L, count);
        } finally {
            TenantContext.clear();
            MeterScope.clear();
        }
    }

    /**
     * request 根事件：quantity=1、tokens=0、无金额（只记原子事实，不携带聚合值）。
     */
    @Test
    void recordRequestAtomicFact() {
        try {
            TenantContext.setTenantId(tenantId);
            costRecorder.recordRequest(tenantId, "req-4:request", "req-4",
                    CostContext.builder().sessionId("sess-2").endUserId(1002L).build(),
                    Instant.now());
            CostEventDO row = costEventMapper.selectOne(
                    new LambdaQueryWrapper<CostEventDO>()
                            .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                            .eq(CostEventDO::getRequestId, "req-4"));
            assertEquals("request", row.getEventType());
            assertEquals(1, row.getQuantity());
            org.junit.jupiter.api.Assertions.assertNull(row.getTokens());
            assertEquals("not_applicable", row.getAmountStatus());
            assertEquals(UsageSource.UNKNOWN.name(), row.getUsageSource());
            assertEquals(null, row.getTotalAmountCents());
            assertEquals("sess-2", row.getSessionId());
        } finally {
            TenantContext.clear();
            MeterScope.clear();
        }
    }

    /** 对账只能追加新事实，原始用量的金额与价格快照保持不变。 */
    @Test
    void reconciliationAppendsWithoutMutatingTheOriginalEvent() {
        try {
            TenantContext.setTenantId(tenantId);
            try (MeterScope scope = MeterScope.open(CostContext.builder()
                    .eventId("reconcile-original")
                    .requestId("reconcile-request")
                    .callPoint("agent.plan")
                    .build())) {
                costRecorder.recordLlm(tenantId, CostType.LLM_CHAT, "openai_compatible",
                        "deepseek-chat", new LlmUsage(120, 80, 200), 1, null);
            }
            CostEventDO original = costEventMapper.selectOne(new LambdaQueryWrapper<CostEventDO>()
                    .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                    .eq(CostEventDO::getEventId, "reconcile-original"));
            assertEquals("measured", original.getUsageEventKind());
            String originalSnapshot = original.getPricingSnapshotVersion();
            Long originalTotal = original.getTotalAmountCents();

            CostEventDO adjustment = costRecorder.recordAdjustment(tenantId,
                    new UsageAdjustment("reconcile-adjustment", original.getId(),
                            UsageEventKind.RECONCILIATION, AmountStatus.PRICED,
                            null, null, new java.math.BigDecimal("-0.25"),
                            original.getExchangeRateToCny(), original.getPricingSnapshotVersion(),
                            original.getCurrency(), java.util.Map.of("source", "billing")));

            assertEquals("reconciliation", adjustment.getUsageEventKind());
            assertEquals(original.getId(), adjustment.getAdjustmentOfEventId());
            CostEventDO rereadOriginal = costEventMapper.selectById(original.getId());
            assertEquals(originalSnapshot, rereadOriginal.getPricingSnapshotVersion());
            assertEquals(originalTotal, rereadOriginal.getTotalAmountCents());
            assertEquals(2L, costEventMapper.selectCount(new LambdaQueryWrapper<CostEventDO>()
                    .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                    .in(CostEventDO::getEventId, "reconcile-original", "reconcile-adjustment")));
        } finally {
            TenantContext.clear();
            MeterScope.clear();
        }
    }

    /** 长窗口分页使用真实账本；页内冲突保留游标，重放不重复已成功事实。 */
    @Test
    void reconciliationWindowReplaysWholeFailedPageAndKeepsSnapshotsImmutable() {
        try {
            TenantContext.setTenantId(tenantId);
            CostEventDO first = measured("window-original-1", "window-request-1");
            CostEventDO second = measured("window-original-2", "window-request-2");
            String firstPricing = first.getPricingSnapshotVersion();
            BigDecimal firstRate = first.getExchangeRateToCny();

            costRecorder.recordAdjustment(tenantId, adjustment(
                    "window-conflict", first.getId(), "1.00", first));
            AtomicInteger pulls = new AtomicInteger();
            UsageReconciliationProvider failedPage = (tenant, from, to, cursor) -> {
                pulls.incrementAndGet();
                return new UsageReconciliationProvider.Batch(List.of(
                        fact("window-page-ok", first.getId(), "0.25", first),
                        fact("window-conflict", second.getId(), "9.99", second)),
                        "next-page");
            };
            UsageReconciliationService service = new UsageReconciliationService(
                    costRecorder, failedPage);
            Instant from = Instant.parse("2026-08-01T00:00:00Z");
            Instant to = Instant.parse("2026-09-01T00:00:00Z");

            var firstAttempt = service.reconcileWindow(tenantId, from, to, "page-1");
            var replay = service.reconcileWindow(tenantId, from, to, "page-1");

            assertEquals("failed", firstAttempt.status());
            assertEquals("page-1", firstAttempt.nextCursor());
            assertEquals("failed", replay.status());
            assertEquals("page-1", replay.nextCursor());
            assertEquals(2, pulls.get());
            assertEquals(1L, costEventMapper.selectCount(
                    new LambdaQueryWrapper<CostEventDO>()
                            .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                            .eq(CostEventDO::getEventId, "window-page-ok")));
            CostEventDO unchanged = costEventMapper.selectById(first.getId());
            assertEquals(firstPricing, unchanged.getPricingSnapshotVersion());
            assertEquals(firstRate, unchanged.getExchangeRateToCny());
        } finally {
            TenantContext.clear();
            MeterScope.clear();
        }
    }

    /** 真实 Provider 在副作用发生后断连；重建服务上下文仍拒绝重发同一尝试。 */
    @Test
    void providerDisconnectPersistsUnknownAndApplicationRestartDoesNotResend() throws Exception {
        AtomicInteger sideEffects = new AtomicInteger();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sideEffects.incrementAndGet();
            exchange.close();
        });
        provider.start();
        String endpoint = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
        String attemptId = "provider-disconnect:" + SnowflakeIds.nextId();
        ExternalInvocationRequest invocation = new ExternalInvocationRequest(
                "logical-action:" + attemptId, attemptId, ExternalOperationType.MODEL_CHAT,
                "custom", "custom-openai-compatible", "stable:" + attemptId,
                new TraceContext("input:" + attemptId, "input:" + attemptId,
                        "trace:" + attemptId, "span:" + attemptId,
                        "event:" + attemptId, null), Map.of("fixture", "disconnect"));
        try {
            TenantContext.setTenantId(tenantId);
            try (AnnotationConfigApplicationContext first = invocationContext()) {
                ExternalInvocationGuard guard = first.getBean(ExternalInvocationGuard.class);
                assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class,
                        () -> guard.invoke(tenantId, invocation, () -> {
                            ModelProvider.InvocationResult result = providerRuntime().generate(
                                    "custom", providerRequest(endpoint));
                            if (result instanceof ModelProvider.InvocationResult.OutcomeUnknown unknown) {
                                throw new ExternalInvocationGuard.OutcomeUnknownException(
                                        unknown.reasonCode(), null);
                            }
                            throw new AssertionError("断连必须产生未知结果，实际为 " + result);
                        }));
            }

            ExternalInvocationDO persisted = externalInvocationMapper.selectOne(
                    new LambdaQueryWrapper<ExternalInvocationDO>()
                            .eq(ExternalInvocationDO::getTenantId, Long.parseLong(tenantId))
                            .eq(ExternalInvocationDO::getAttemptId, attemptId));
            assertEquals(ExternalInvocationStatus.UNRESOLVED.name(), persisted.getStatus());
            assertEquals("EXTERNAL_OUTCOME_UNKNOWN", persisted.getErrorCode());
            assertEquals(1, sideEffects.get());

            try (AnnotationConfigApplicationContext restarted = invocationContext()) {
                ExternalInvocationGuard guard = restarted.getBean(ExternalInvocationGuard.class);
                assertThrows(ExternalInvocationGuard.DuplicateAttemptException.class,
                        () -> guard.invoke(tenantId, invocation, () -> {
                            sideEffects.incrementAndGet();
                            return "must-not-run";
                        }));
            }
            assertEquals(1, sideEffects.get());
        } finally {
            TenantContext.clear();
            provider.stop(0);
        }
    }

    private AnnotationConfigApplicationContext invocationContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ExternalInvocationMapper.class, () -> externalInvocationMapper);
        context.registerBean(CostRecorder.class, () -> costRecorder);
        context.registerBean(com.skillforge.core.billing.budget.AuxiliaryModelBudgets.class, () -> auxiliaryModelBudgets);
        context.registerBean(ExternalInvocationLedger.class);
        context.registerBean(ExternalInvocationGuard.class);
        context.refresh();
        return context;
    }

    private ModelProviderRuntime providerRuntime() {
        return ModelProviderRuntime.runtime(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC(), 100_000);
    }

    private InvocationRequest providerRequest(String endpoint) {
        return new InvocationRequest(endpoint, "fixture-key", "fixture-model",
                new InvocationProfile("custom-openai-compatible", "custom-v1",
                        ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                        AuthenticationScheme.BEARER_API_KEY,
                        "openai-compatible-chat-v1"),
                List.of(new Message(Role.USER, "commit one side effect", null, List.of())),
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT,
                        "p0-3-disconnect"),
                new GenerationOptions(OptionalInt.of(16), OptionalDouble.empty()),
                ReasoningPolicy.DISABLED, List.of(), ToolChoice.NONE,
                Duration.ofSeconds(3), () -> false, () -> { });
    }

    private CostEventDO measured(String eventId, String requestId) {
        try (MeterScope scope = MeterScope.open(CostContext.builder()
                .eventId(eventId).requestId(requestId).callPoint("agent.plan").build())) {
            costRecorder.recordLlm(tenantId, CostType.LLM_CHAT, "deepseek",
                    "deepseek-flash", new LlmUsage(10, 5, 15), 1, null);
        }
        return costEventMapper.selectOne(new LambdaQueryWrapper<CostEventDO>()
                .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                .eq(CostEventDO::getEventId, eventId));
    }

    private UsageAdjustment adjustment(
            String eventId, long originalId, String total, CostEventDO snapshot) {
        return new UsageAdjustment(eventId, originalId, UsageEventKind.RECONCILIATION,
                AmountStatus.PRICED, null, null, new BigDecimal(total),
                snapshot.getExchangeRateToCny(), snapshot.getPricingSnapshotVersion(),
                snapshot.getCurrency(), Map.of("source", "provider"));
    }

    private UsageReconciliationProvider.Fact fact(
            String eventId, long originalId, String total, CostEventDO snapshot) {
        return new UsageReconciliationProvider.Fact(eventId, originalId,
                AmountStatus.PRICED, null, null, new BigDecimal(total),
                snapshot.getExchangeRateToCny(), snapshot.getPricingSnapshotVersion(),
                snapshot.getCurrency(), Map.of("source", "provider"));
    }
}

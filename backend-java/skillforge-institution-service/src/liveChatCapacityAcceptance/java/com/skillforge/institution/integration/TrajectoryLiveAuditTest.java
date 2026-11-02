package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.billing.budget.AuxiliaryModelBudgets;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.ProviderCallContext;
import com.skillforge.runtime.api.port.ProviderDispatch;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

/** 不启Spring/PG/Provider，先保护真实live入口最关键的发送授权边界。 */
class TrajectoryLiveAuditTest {
    @Test void rootAndChildShareOneTwentyFourAttemptLimitBeforeProviderSendPermission() {
        var fixture = new Fixture();
        for (int index = 0; index < 24; index++) assertTrue(fixture.start(fixture.call(index, index % 2 == 0 ? "root" : "child")));
        assertFalse(fixture.start(fixture.call(24, "child")));
        verify(fixture.delegate, times(24)).start(any(), any());
    }

    @Test void unknownSupplierOutcomeClosesAllFurtherRootAndChildSendPermission() {
        var fixture = new Fixture();
        var first = fixture.call(0, "root");
        assertTrue(fixture.start(first));
        fixture.audit.unresolved(fixture.delegate, first, fixture.provider, "provider_result_unknown");
        var child = fixture.call(1, "child");
        assertFalse(fixture.start(child));
        verify(fixture.delegate, never()).start(child, fixture.provider);
    }

    @Test void successfulTransportWithoutSupplierUsageCannotAuthorizeAnotherSend() {
        var fixture = new Fixture();
        var first = fixture.call(0, "root");
        assertTrue(fixture.start(first));
        var usage = new ModelPort.ModelUsage(OptionalLong.empty(), OptionalLong.empty());
        fixture.audit.succeeded(fixture.delegate, first, fixture.provider, usage);
        var next = fixture.call(1, "root");
        assertFalse(fixture.start(next));
        verify(fixture.delegate, never()).start(next, fixture.provider);
    }

    @Test void businessSupplierUsageReachesMonitorBeforeExternalLedgerSuccess() {
        var fixture = new Fixture();
        var call = fixture.call(0, "root");
        var usage = new ModelPort.ModelUsage(OptionalLong.of(100), OptionalLong.of(10));
        assertTrue(fixture.start(call));

        fixture.audit.succeeded(fixture.delegate, call, fixture.provider, usage);

        var order = inOrder(fixture.businessUsage, fixture.delegate);
        order.verify(fixture.businessUsage).accept(call, usage);
        order.verify(fixture.delegate).succeeded(call, fixture.provider, usage);
    }

    @Test void sourceRevisionDriftAndUnboundAuxiliaryScopeCannotReachDelegate() {
        var fixture = new Fixture();
        when(fixture.binding.sourceReference()).thenReturn("platform-model:model:2");
        assertFalse(fixture.start(fixture.call(0, "root")));
        verify(fixture.delegate, never()).start(any(), any());
        var fresh = new Fixture();
        assertThrows(AssertionError.class, () -> fresh.audit.beforeAuxiliarySend(new RunRef(new TenantId("tenant"), "root"), "aux-attempt"));
    }

    @Test void hostBudgetRemainsTheTransactionalJdbcPortWhenLiveMonitorIsPresent() {
        var factory = new DefaultListableBeanFactory();
        var host = mock(JdbcExecutionBudgets.class);
        var monitor = mock(LivePromptCacheBudgets.class);
        factory.registerBeanDefinition("hostExecutionBudgets", new RootBeanDefinition(JdbcExecutionBudgets.class, () -> host));
        factory.registerBeanDefinition("liveTrajectoryBudgets", new RootBeanDefinition(LivePromptCacheBudgets.class, () -> monitor));
        TrajectoryLiveAcceptance.LiveConfiguration.liveTrajectoryHostBudget().postProcessBeanFactory(factory);

        assertSame(host, factory.getBean(ExecutionBudgetPort.class));
        assertSame(monitor, factory.getBean(LivePromptCacheBudgets.class));
    }

    @Test void businessProviderCannotReachOriginalAuditWhenGlobalBudgetGateRejects() {
        var fixture = new Fixture();
        var run = new RunRef(new TenantId("tenant"), "child");
        doThrow(new IllegalStateException("LIVE_CACHE_CONTROLLED_EXECUTION_STOPPED"))
                .when(fixture.businessGate).accept(run, "attempt-0");

        assertThrows(IllegalStateException.class, () -> fixture.start(fixture.call(0, "child")));

        verify(fixture.businessGate).accept(run, "attempt-0");
        verify(fixture.delegate, never()).start(any(), any());
        assertFalse(fixture.start(fixture.call(1, "root")));
    }

    @Test void auxiliaryGateRunsInsideOriginalGuardCallbackWithoutReplayingBudgetAuthority() throws Exception {
        var original = mock(ExternalInvocationGuard.class);
        var monitor = mock(LivePromptCacheBudgets.class);
        var guard = new TrajectoryLiveAcceptance.LiveAuxiliaryGuard(original, mock(ExternalInvocationLedger.class),
                mock(AuxiliaryModelBudgets.class), monitor);
        var run = new RunRef(new TenantId("tenant"), "root");
        var request = new ExternalInvocationRequest("invocation", "attempt", ExternalOperationType.MODEL_CHAT,
                "provider", "model", "key", new TraceContext("input", "root", "trace", "span", "event", null), java.util.Map.of());
        var demand = new AuxiliaryModelBudgets.Request(100, 1024, "fingerprint");
        doAnswer(call -> ((ExternalInvocationGuard.ExternalCall<?>) call.getArgument(3)).call())
                .when(original).invokeModel(any(), any(), any(), any());
        try (var scope = ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(mock(ExecutionBudgetPort.class), run))) {
            assertEquals("ok", guard.invokeModel("tenant", request, demand, () -> {
                verify(monitor).beforeProviderSend(run, "attempt", ExecutionBudgetPort.Category.AUXILIARY_MODEL);
                return "ok";
            }));
        }
        verify(original, times(1)).invokeModel(any(), any(), any(), any());
        verify(monitor, never()).markSent(any(), any());
        verify(monitor, never()).reserve(any());
    }

    @Test void sentDemandMonitorSharesTwentyFourSendBoundAcrossBusinessAndAuxiliary() throws Exception {
        var fixture = new DemandFixture();
        for (int index = 0; index < 24; index++) {
            var category = index % 2 == 0 ? ExecutionBudgetPort.Category.BUSINESS_MODEL : ExecutionBudgetPort.Category.AUXILIARY_MODEL;
            var demand = fixture.sent(index, category, 100, 10);
            fixture.monitor.beforeProviderSend(demand.run(), demand.attemptId(), category);
        }
        var extra = fixture.sent(24, ExecutionBudgetPort.Category.AUXILIARY_MODEL, 100, 10);
        assertThrows(IllegalStateException.class,
                () -> fixture.monitor.beforeProviderSend(extra.run(), extra.attemptId(), extra.category()));
        assertEquals(24, fixture.monitor.report().sentAttempts());
        verify(fixture.delegate, never()).reserve(any());
        verify(fixture.delegate, never()).markSent(any(), any());
        verify(fixture.delegate, never()).saveResult(any(), any(), any(), any());
        verify(fixture.delegate, never()).settle(any(), any());
    }

    @Test void sentDemandMonitorRequiresCommittedSentStateAndMatchingCategory() throws Exception {
        var fixture = new DemandFixture();
        var reserved = fixture.sent(0, ExecutionBudgetPort.Category.BUSINESS_MODEL, 100, 10);
        fixture.states.put(reserved.attemptId(), "RESERVED");
        assertEquals("LIVE_CACHE_PERSISTENT_SEND_REQUIRED", assertThrows(IllegalStateException.class,
                () -> fixture.monitor.beforeProviderSend(reserved.run(), reserved.attemptId(), reserved.category())).getMessage());
        var auxiliary = fixture.sent(1, ExecutionBudgetPort.Category.AUXILIARY_MODEL, 100, 10);
        assertEquals("LIVE_CACHE_SEND_CATEGORY_MISMATCH", assertThrows(IllegalStateException.class,
                () -> fixture.monitor.beforeProviderSend(auxiliary.run(), auxiliary.attemptId(), ExecutionBudgetPort.Category.BUSINESS_MODEL)).getMessage());
        assertEquals(0, fixture.monitor.report().sentAttempts());
    }

    @Test void sentDemandMonitorRetainsUnknownReservationsAtTheGlobalTokenLimit() throws Exception {
        var fixture = new DemandFixture();
        var first = fixture.sent(0, ExecutionBudgetPort.Category.BUSINESS_MODEL, 199_000, 1_000);
        var second = fixture.sent(1, ExecutionBudgetPort.Category.AUXILIARY_MODEL, 199_000, 1_000);
        fixture.monitor.beforeProviderSend(first.run(), first.attemptId(), first.category());
        fixture.monitor.beforeProviderSend(second.run(), second.attemptId(), second.category());
        var extra = fixture.sent(2, ExecutionBudgetPort.Category.BUSINESS_MODEL, 1, 1);
        assertThrows(IllegalStateException.class,
                () -> fixture.monitor.beforeProviderSend(extra.run(), extra.attemptId(), extra.category()));
        var report = fixture.monitor.report();
        assertEquals(400_000, report.chargedOrReservedTokens()); assertEquals(0, report.reportedTokens());
        assertEquals(2, report.unknownUsageAttempts()); assertEquals(2, report.sentAttempts());
    }

    @Test void sentDemandMonitorClosesAllFurtherSendsAfterUnknownOutcomeOrMissingUsage() throws Exception {
        for (var terminal : List.of("UNRESOLVED", "FAILED", "SUCCEEDED")) {
            var fixture = new DemandFixture();
            var first = fixture.sent(0, ExecutionBudgetPort.Category.AUXILIARY_MODEL, 100, 10);
            fixture.monitor.beforeProviderSend(first.run(), first.attemptId(), first.category());
            fixture.terminals.put(first.attemptId(), terminal);
            if ("SUCCEEDED".equals(terminal)) fixture.states.put(first.attemptId(), "RESULT_SAVED");
            var next = fixture.sent(1, ExecutionBudgetPort.Category.BUSINESS_MODEL, 100, 10);
            assertThrows(IllegalStateException.class,
                    () -> fixture.monitor.beforeProviderSend(next.run(), next.attemptId(), next.category()));
            assertEquals(1, fixture.monitor.report().sentAttempts());
        }
    }

    @Test void externalSuccessBeforeBudgetResultDoesNotTurnASentReservationIntoFinalMissingUsage() throws Exception {
        var fixture = new DemandFixture();
        var demand = fixture.sent(0, ExecutionBudgetPort.Category.AUXILIARY_MODEL, 100, 10);
        fixture.monitor.beforeProviderSend(demand.run(), demand.attemptId(), demand.category());
        fixture.terminals.put(demand.attemptId(), "SUCCEEDED");
        // 复现旧查询间隙：第一个预算快照仍SENT/空用量，查外调终态时预算提交完整结果。
        fixture.onExternalRead = () -> {
            fixture.states.put(demand.attemptId(), "RESULT_SAVED");
            fixture.usages.put(demand.attemptId(), ExecutionBudgetPort.Usage.reported(OptionalLong.of(90), OptionalLong.of(3)));
        };

        var pending = fixture.monitor.report();
        assertNull(pending.stopCode()); assertEquals(110, pending.chargedOrReservedTokens()); assertEquals(1, pending.unknownUsageAttempts());
        var completed = fixture.monitor.report();
        assertNull(completed.stopCode()); assertEquals(93, completed.chargedOrReservedTokens()); assertEquals(0, completed.unknownUsageAttempts());
    }

    @Test void completedSupplierUsageIsAuthoritativeBeforeBudgetPersistenceAndMissingUsageStillStops() throws Exception {
        var complete = new DemandFixture();
        var sent = complete.sent(0, ExecutionBudgetPort.Category.BUSINESS_MODEL, 100, 10);
        complete.monitor.beforeProviderSend(sent.run(), sent.attemptId(), sent.category());
        complete.terminals.put(sent.attemptId(), "SUCCEEDED");
        complete.monitor.providerUsage(sent.run(), sent.attemptId(),
                ExecutionBudgetPort.Usage.reported(OptionalLong.of(90), OptionalLong.of(3)));
        assertNull(complete.monitor.report().stopCode()); assertEquals(93, complete.monitor.report().reportedTokens());

        var missing = new DemandFixture();
        var unknown = missing.sent(0, ExecutionBudgetPort.Category.BUSINESS_MODEL, 100, 10);
        missing.monitor.beforeProviderSend(unknown.run(), unknown.attemptId(), unknown.category());
        missing.monitor.providerUsage(unknown.run(), unknown.attemptId(), ExecutionBudgetPort.Usage.unreported());
        assertEquals("LIVE_CACHE_USAGE_UNKNOWN", missing.monitor.report().stopCode());
        assertEquals(110, missing.monitor.report().chargedOrReservedTokens());
    }

    @Test void managementGetHonorsRetryAfterAndRetriesAtMostOnceWithoutRecordingQuery() throws Exception {
        for (int finalStatus : List.of(200, 429)) {
            var client = mock(HttpClient.class);
            var limited = managementResponse(429, "60");
            var completed = managementResponse(finalStatus, "60");
            when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(limited, completed);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1/api/v1/chat/trajectory?cursor=private-cursor"))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            var remaining = new java.util.concurrent.atomic.AtomicLong(120_000);
            var waits = new java.util.ArrayList<Long>();
            var evidence = new java.util.ArrayList<TrajectoryLiveAcceptance.ManagementHttpAttempt>();

            assertSame(completed, TrajectoryLiveAcceptance.sendManagementRead(client, request, remaining::get,
                    millis -> { waits.add(millis); remaining.addAndGet(-millis); }, evidence::add));

            assertEquals(List.of(60_000L), waits); verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
            assertTrue(evidence.getFirst().retryScheduled()); assertFalse(evidence.getLast().retryScheduled());
            assertEquals("/api/v1/chat/trajectory", TrajectoryLiveAcceptance.safeManagementPath(request.uri()));
        }
    }

    @Test void managementMutationsAndNon429ReadsAreNeverRetried() throws Exception {
        for (String method : List.of("POST", "PATCH", "GET")) {
            var client = mock(HttpClient.class);
            var response = managementResponse("GET".equals(method) ? 503 : 429, "60");
            when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1/api/v1/agents/update"))
                    .timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.noBody()).build();

            assertSame(response, TrajectoryLiveAcceptance.sendManagementRead(client, request, () -> 120_000,
                    millis -> { throw new AssertionError("unexpected_wait"); }, attempt -> assertFalse(attempt.retryScheduled())));

            verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    @Test void managementGetDoesNotWaitWithoutEnoughOriginalWindowOrValidRetryAfter() throws Exception {
        for (String retryAfter : List.of("60", "invalid", "")) {
            var client = mock(HttpClient.class);
            var response = managementResponse(429, retryAfter);
            when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1/api/v1/chat/trajectory"))
                    .timeout(Duration.ofSeconds(30)).GET().build();

            assertSame(response, TrajectoryLiveAcceptance.sendManagementRead(client, request, () -> 89_999,
                    millis -> { throw new AssertionError("unexpected_wait"); }, attempt -> assertFalse(attempt.retryScheduled())));

            verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    private static HttpResponse<String> managementResponse(int status, String retryAfter) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(retryAfter.isBlank() ? Map.of() : Map.of("Retry-After", List.of(retryAfter)),
                (name, value) -> true));
        return response;
    }

    @Test void institutionAndPlatformChildJvmReceiveDistinctFormalSnowflakeWorkerEnvironments() throws Exception {
        assertEquals("1", System.getenv("SNOWFLAKE_WORKER_ID"));
        var inherited = new LinkedHashMap<String, String>(); inherited.put("SNOWFLAKE_WORKER_ID", "0");
        assertThrows(AssertionError.class, () -> TrajectoryLiveAcceptance.configurePlatformSnowflake(inherited, "0"));
        var javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var classes = java.nio.file.Path.of(WorkerEnvironmentProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var builder = new ProcessBuilder(javaExecutable.toString(), "-cp", classes.toString(), WorkerEnvironmentProbe.class.getName()).redirectErrorStream(true);
        var assignment = TrajectoryLiveAcceptance.configurePlatformSnowflake(builder.environment(), System.getenv("SNOWFLAKE_WORKER_ID"));
        assertEquals("1", assignment.institutionWorkerId()); assertEquals("2", assignment.platformWorkerId());
        var child = builder.start();
        try {
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            var lines = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(value -> value.matches("SNOWFLAKE_WORKER_ID=[0-9]{1,2}")).toList();
            assertEquals(List.of("SNOWFLAKE_WORKER_ID=2"), lines);
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); }
            child.getInputStream().close(); child.getErrorStream().close(); child.getOutputStream().close();
        }
    }

    /** 纯离线子JVM只报告非秘密节点值，不初始化服务、数据库、ID生成器或模型。 */
    public static final class WorkerEnvironmentProbe {
        public static void main(String[] args) { System.out.println("SNOWFLAKE_WORKER_ID=" + System.getenv("SNOWFLAKE_WORKER_ID")); }
    }

    /** 只模拟JDBC读取回执；真实Demand/Resources及live门禁逻辑保持完整，不启数据库或网络。 */
    private static final class DemandFixture {
        final DataSource source = mock(DataSource.class);
        final ExecutionBudgetPort delegate = mock(ExecutionBudgetPort.class);
        final Map<String, ExecutionBudgetPort.Demand> demands = new LinkedHashMap<>();
        final Map<String, String> states = new LinkedHashMap<>(), terminals = new LinkedHashMap<>();
        final Map<String, ExecutionBudgetPort.Usage> usages = new LinkedHashMap<>();
        Runnable onExternalRead = () -> { };
        final LivePromptCacheBudgets monitor;
        DemandFixture() throws Exception {
            var connection = mock(Connection.class);
            when(source.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenAnswer(call -> statement(call.getArgument(0)));
            when(delegate.find(any())).thenAnswer(call -> {
                var run = (RunRef) call.getArgument(0);
                var capacity = new ExecutionBudgetPort.Resources(24, 24, 0, 0, 400_000, 400_000, 400_000);
                var policy = new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(
                        ExecutionBudgetPort.Scenario.STANDARD, capacity, capacity, capacity, 600, 600, "fixture"));
                return Optional.of(new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY, policy));
            });
            monitor = new LivePromptCacheBudgets(source, mock(PromptCacheRecordingHttpClient.class), 24, (run, id) -> { }, delegate);
            monitor.begin("tenant");
        }
        ExecutionBudgetPort.Demand sent(int index, ExecutionBudgetPort.Category category, long input, long output) {
            var demand = new ExecutionBudgetPort.Demand(new RunRef(new TenantId("tenant"), index % 2 == 0 ? "root" : "child"),
                    "attempt-" + index, "fingerprint-" + index, category, ExecutionBudgetPort.Resources.call(category, input, output), false);
            demands.put(demand.attemptId(), demand); states.put(demand.attemptId(), "SENT"); return demand;
        }
        private PreparedStatement statement(String sql) throws SQLException {
            var query = mock(PreparedStatement.class);
            Map<Integer, String> arguments = new LinkedHashMap<>();
            doAnswer(call -> { arguments.put(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(query).setString(anyInt(), anyString());
            when(query.executeQuery()).thenAnswer(call -> {
                if (sql.startsWith("SELECT fingerprint")) {
                    var demand = demands.get(arguments.get(3));
                    return rows(new Object[][] {{demand.fingerprint(), demand.category().name(), demand.closing(), states.get(demand.attemptId())}});
                }
                if (sql.startsWith("SELECT dimension,reserved")) {
                    var demand = demands.get(arguments.get(2));
                    return rows(java.util.Arrays.stream(ExecutionBudgetPort.Dimension.values())
                            .map(value -> new Object[] {value.name(), demand.resources().value(value)}).toArray(Object[][]::new));
                }
                if (sql.startsWith("SELECT attempt.state")) {
                    String attempt = arguments.get(3);
                    var usage = usages.getOrDefault(attempt, ExecutionBudgetPort.Usage.unreported());
                    return rows(new Object[][] {{states.get(attempt), usage.inputTokens().isPresent() ? usage.inputTokens().getAsLong() : null,
                            usage.outputTokens().isPresent() ? usage.outputTokens().getAsLong() : null}});
                }
                if (sql.startsWith("SELECT status")) {
                    onExternalRead.run();
                    if (terminals.containsKey(arguments.get(2))) return rows(new Object[][] {{terminals.get(arguments.get(2))}});
                }
                return rows(new Object[0][]);
            });
            return query;
        }
        private ResultSet rows(Object[][] values) throws SQLException {
            var result = mock(ResultSet.class);
            var cursor = new java.util.concurrent.atomic.AtomicInteger(-1);
            var wasNull = new java.util.concurrent.atomic.AtomicBoolean();
            when(result.next()).thenAnswer(call -> cursor.incrementAndGet() < values.length);
            when(result.getString(anyInt())).thenAnswer(call -> values[cursor.get()][(int) call.getArgument(0) - 1].toString());
            when(result.getLong(anyInt())).thenAnswer(call -> {
                Object value = values[cursor.get()][(int) call.getArgument(0) - 1]; wasNull.set(value == null);
                return value == null ? 0L : ((Number) value).longValue();
            });
            when(result.wasNull()).thenAnswer(call -> wasNull.get());
            when(result.getBoolean(anyInt())).thenAnswer(call -> (Boolean) values[cursor.get()][(int) call.getArgument(0) - 1]);
            return result;
        }
    }

    private static final class Fixture {
        final JdbcModelBindings bindings = mock(JdbcModelBindings.class);
        final JdbcModelBindings.Binding binding = mock(JdbcModelBindings.Binding.class);
        final PromptCacheRecordingHttpClient recording = mock(PromptCacheRecordingHttpClient.class);
        final ModelInvocationAudit delegate = mock(ModelInvocationAudit.class);
        final java.util.function.BiConsumer<RunRef, String> businessGate = mock(java.util.function.BiConsumer.class);
        final java.util.function.BiConsumer<ModelPort.ModelCall, ModelPort.ModelUsage> businessUsage = mock(java.util.function.BiConsumer.class);
        final ModelInvocationAudit.Provider provider = new ModelInvocationAudit.Provider("provider", "service", "model");
        final TrajectoryLiveAcceptance.LiveAudit audit = new TrajectoryLiveAcceptance.LiveAudit(bindings, recording, mock(DataSource.class), businessGate, businessUsage);
        Fixture() {
            var model = mock(PlatformModelConfig.class);
            when(model.id()).thenReturn("model"); when(model.revision()).thenReturn(1L);
            when(model.modelId()).thenReturn("model"); when(model.providerId()).thenReturn("provider"); when(model.serviceId()).thenReturn("service");
            when(bindings.find(any())).thenReturn(Optional.of(binding)); when(binding.sourceReference()).thenReturn("platform-model:model:1");
            when(delegate.start(any(), any())).thenReturn(true);
            audit.freeze(model, "tenant", Set.of("calculator", "coordinator"));
        }
        boolean start(ModelPort.ModelCall call) { return audit.start(delegate, call, provider); }
        ModelPort.ModelCall call(int index, String run) {
            var tenant = new TenantId("tenant");
            var input = new InputId("input-" + index);
            var agent = new AgentId("child".equals(run) ? "calculator" : "coordinator");
            var user = new EndUserId(7L);
            var owner = SessionOwner.user(new SessionRef(tenant, "session-" + run), agent, user);
            var identity = new RunIdentity(owner, agent, input, new RunRef(tenant, run),
                    new TraceEnvelope("trace-" + run, "span-" + index, input, Optional.empty()),
                    new ResultRef("identity-" + index), new RunRequester.EndUser(user), Optional.empty(),
                    new RunOriginRef(tenant, "input:" + input.value()));
            var verified = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, user), owner,
                    Set.of("runtime:invoke"), 1, new ResultRef("verified-" + index));
            var request = new ResultRef("request-" + index);
            // 离线纯值夹具只描述同一次派发，不代替真实Store登记或签发Provider发送权。
            var dispatch = new ProviderDispatch(identity.runRef(), new ActionRef(tenant, "action-" + index),
                    "invocation-" + index, "attempt-" + index, PortalCallKind.MODEL_GENERATE,
                    request, new ResultRef("capability-" + index), "fingerprint-" + index, 1);
            var context = ProviderCallContext.dispatched(new PortCallContext(identity, verified,
                    new InvocationControl(Instant.now().plusSeconds(30), () -> false)), dispatch);
            var target = new ModelPort.FrozenModelTarget(new ModelPort.ModelBindingRef(tenant, "model", 1), "model",
                    new ModelPort.ModelCapabilitySnapshot(Set.of(ModelPort.ResponseFormatKind.TEXT), false, false,
                            new ResultRef("capabilities")), Duration.ofSeconds(30), new ResultRef("resolution"));
            return new ModelPort.ModelCall(context, new ModelPort.RequestedModelStrategy("fixture", "1"), target, request,
                    List.of(new ModelPort.ModelMessage(input.value(), ModelPort.ModelRole.USER, "fixture")),
                    List.of(), ModelPort.ToolChoice.NONE,
                    new ModelPort.ResponseContract(new ModelPort.ResponseFormat.Text(), ModelPort.EnforcementLevel.STRICT, "supported"),
                    new ModelPort.GenerationOptions(OptionalInt.of(1024), OptionalDouble.empty()),
                    ModelPort.ReasoningPolicy.DISABLED, ExecutionBudgetPort.Category.BUSINESS_MODEL);
        }
    }
}

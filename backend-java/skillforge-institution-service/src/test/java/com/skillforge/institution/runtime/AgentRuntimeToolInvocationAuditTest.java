package com.skillforge.institution.runtime;

import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MeterScope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.adapter.mcp.McpToolPortal;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.json.CodecLimits;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 真实 loopback MCP 与宿主计量桥组合验证；账本 mock 不代替数据库/导出验收。 */
class AgentRuntimeToolInvocationAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExternalInvocationLedger ledger = mock(ExternalInvocationLedger.class);
    private final CostRecorder costs = mock(CostRecorder.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AtomicBoolean authorized = new AtomicBoolean(true);
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final ToolBinding binding = new ToolBinding(ToolBinding.ProviderType.MCP, "connector", "lookup");
    private HttpServer server;
    private ExecutorService workers;
    private AgentRuntimeToolInvocationAudit audit;
    private final AgentRuntimeProgramTools programs = mock(AgentRuntimeProgramTools.class);
    private final AgentRuntimeWorkflowNodeExecutions workflows=mock(AgentRuntimeWorkflowNodeExecutions.class);
    private RegisteredProviderFixture receipt;
    private FrozenToolTarget target;
    private volatile boolean waiting;
    private volatile boolean revokeAfterHandshake;
    private volatile int businessStatus = 200;
    private volatile String failureMode = "";
    private Clock clock = Clock.systemUTC();

    @BeforeEach void open() throws IOException {
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(ledger.startAuxiliary(eq("7"), any(), any())).thenReturn(true);
        when(ledger.finish(eq("7"), any(), any(), isNull(), any(), any())).thenReturn(true);
        when(programs.resourceOrigin(any(RunIdentity.class))).thenAnswer(call -> call.getArgument(0));
        when(workflows.resourceOrigin(any(RunIdentity.class))).thenAnswer(call -> call.getArgument(0));
        when(workflows.resourceOrigin(any(ExecutionStore.Run.class))).thenAnswer(call->((ExecutionStore.Run)call.getArgument(0)).identity());
        when(programs.resourceOrigin(any(ExecutionStore.Run.class))).thenAnswer(call->((ExecutionStore.Run)call.getArgument(0)).identity());
        audit = new AgentRuntimeToolInvocationAudit(ledger, costs, transactions, ProgramResourceOriginFixture.provider(programs), WorkflowResourceOriginFixture.provider(workflows));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        server.createContext("/mcp", this::respond); server.start();
        var tenant = new TenantId("7"); var input = new InputId("input-1");
        var owner = SessionOwner.user(new SessionRef(tenant, "session-1"), new AgentId("11"), new EndUserId(9));
        var run = new RunIdentity(owner, new AgentId("11"), input, new RunRef(tenant, "run-1"),
                new TraceEnvelope("trace-1", "span-1", new InputId("root-1"), Optional.empty()),
                new ResultRef("causation"), new RunRequester.EndUser(owner.requireUser()), Optional.empty(),
                new RunOriginRef(tenant, "input:" + input.value()));
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of("sessions.write"), 1, new ResultRef("direct-verified"));
        receipt = RegisteredProviderFixture.create(run, actor, ExecutionStore.Kind.TOOL, PortalCallKind.TOOL_INVOKE,
                "lookup", new TypedPayload("request.v1", "{}"));
        target = new FrozenToolTarget(new ToolHandle("tool:lookup"), new CapabilityRef(CapabilityType.TOOL, "lookup", "v1"),
                "deployment", 2, new TypedPayload("json-schema", "{}"), new TypedPayload("json-schema", "{}"),
                ToolEffect.READ_ONLY, true, Duration.ofSeconds(5), true, ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
    }

    @AfterEach void close() {
        if (server != null) server.stop(0);
        if (workers != null) workers.close();
        TenantContext.clear(); MeterScope.clear(); ExternalInvocationScope.clear();
    }

    @Test void actualDispatchUsesOriginalAttemptAndTrustedRunRestoringReusedWorkerScopes() {
        var seenStart = new AtomicReference<CostContext>();
        doAnswer(call -> { seenStart.set(MeterScope.current()); assertEquals("7", TenantContext.getTenantId()); return true; })
                .when(ledger).startAuxiliary(eq("7"), any(), any());
        doAnswer(call -> {
            assertEquals("direct-attempt", ExternalInvocationScope.current().attemptId());
            return null;
        }).when(costs).recordExternal(any(), any(), any(), any(), any(), any());
        TenantContext.setTenantId("other-worker");
        var parent = CostContext.builder().runId("other-run").endUserId(100L).scopeId("untrusted-parent").build();
        try (var scope = MeterScope.openIsolated(parent)) {
            assertInstanceOf(ToolOutcome.Succeeded.class, dispatch(receipt, "2026-07-28"));
            assertSame(parent, MeterScope.current()); assertEquals("other-worker", TenantContext.getTenantId());
        }
        var captured = org.mockito.ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        verify(ledger).startAuxiliary(eq("7"), captured.capture(), any());
        assertEquals("direct-action", captured.getValue().invocationId());
        assertEquals("direct-attempt", captured.getValue().attemptId());
        assertEquals(ExternalOperationType.TOOL_CALL, captured.getValue().operationType());
        assertEquals("direct-action", captured.getValue().idempotencyKey());
        assertEquals("run-1", seenStart.get().attributionRunId());
        assertEquals("run-1", seenStart.get().runId()); assertEquals("session-1", seenStart.get().sessionId());
        assertEquals("root-1", seenStart.get().rootInputId()); assertEquals("agent-runtime.tool", seenStart.get().callPoint());
        assertNull(seenStart.get().scopeId());
        verify(costs).recordExternal(eq("7"), eq(CostType.TOOL_CALL), eq("mcp"), eq("mcp:connector"), any(),
                eq(Map.of("runtime_run_id", "run-1", "tool_outcome", "succeeded", "unit", "call")));
        verify(ledger).finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.SUCCEEDED), isNull(), isNull(), any());
        assertEquals(1, requests.size()); assertNull(MeterScope.current()); assertNull(ExternalInvocationScope.current());
    }

    @Test void workflowToolKeepsActualRunAndBorrowsOnlyTheFrozenParentAttribution() {
        var actual=receipt.registered().run().identity();
        var parent=new RunIdentity(actual.owner(),actual.agentId(),actual.causationInputId(),new RunRef(actual.runRef().tenantId(),"workflow-parent"),
                actual.trace(),actual.causationIdentityRef(),actual.requester(),actual.sourceMessageRef(),actual.originRef());
        when(workflows.resourceOrigin(receipt.registered().run())).thenReturn(parent);
        var seen=new AtomicReference<CostContext>();
        var call=new ToolCall(receipt.context(),binding,target,new TypedPayload("application/json","{}"),new IdempotencyKey("direct-action"));
        audit.platform(receipt.registered(),receipt.prepared(),call,()->{seen.set(MeterScope.current());return new ToolOutcome.Failed(new PortFailure("fixture",RetryMode.NONE,new ResultRef("fixture-result")));});
        assertEquals(actual.runRef().runId(),seen.get().runId());assertEquals("workflow-parent",seen.get().attributionRunId());
        verify(workflows).resourceOrigin(receipt.registered().run());
    }

    @Test void handshakeThenRevocationProducesNoToolStartOrQuantity() {
        revokeAfterHandshake = true;
        assertInstanceOf(ToolOutcome.Failed.class, dispatch(receipt, "2025-11-25"));
        assertEquals(List.of("initialize", "notifications/initialized"), requests.stream().map(value -> value.path("method").asText()).toList());
        verifyNoInteractions(ledger, costs, transactions);
    }

    @Test void startTransactionCrossingDeadlinePreservesUnknownStartWithoutActualToolSendOrQuantity() {
        var current = new AtomicReference<>(Instant.now());
        clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        when(ledger.startAuxiliary(eq("7"), any(), any())).thenAnswer(call -> {
            // 在开始事务完成时推进受控时钟，不依赖 sleep 或调度时序。
            current.set(receipt.context().callContext().control().deadlineAt().plusSeconds(1));
            return true;
        });
        assertInstanceOf(ToolOutcome.Failed.class, dispatch(receipt, "2026-07-28"));
        verify(ledger).startAuxiliary(eq("7"), any(), any());
        verify(ledger).finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.FAILED), isNull(), anyString(), any());
        verifyNoInteractions(costs);
        assertTrue(requests.isEmpty(), "开始事实已提交也不能在原期限后发送业务HTTP");
    }

    @Test void revocationDuringStartTransactionPreventsActualToolSendAndQuantity() {
        when(ledger.startAuxiliary(eq("7"), any(), any())).thenAnswer(call -> {
            authorized.set(false);
            return true;
        });
        assertInstanceOf(ToolOutcome.Failed.class, dispatch(receipt, "2026-07-28"));
        verify(ledger).startAuxiliary(eq("7"), any(), any());
        verify(ledger).finish(eq("7"), eq("direct-attempt"), eq(ExternalInvocationStatus.FAILED), isNull(), anyString(), any());
        verifyNoInteractions(costs);
        assertTrue(requests.isEmpty(), "开始事务期间撤权后不得标记发送或发出业务HTTP");
    }

    @ParameterizedTest @ValueSource(ints = {400, 503})
    void sentFailedAndUnknownResponsesKeepOneActualCallAndOriginalUncertainty(int status) {
        businessStatus = status;
        ToolOutcome actual = dispatch(receipt, "2026-07-28");
        if (status == 400) assertInstanceOf(ToolOutcome.Failed.class, actual);
        else assertInstanceOf(ToolOutcome.OutcomeUnknown.class, actual);
        verify(ledger).startAuxiliary(eq("7"), any(), any());
        verify(costs).recordExternal(eq("7"), eq(CostType.TOOL_CALL), eq("mcp"), eq("mcp:connector"), any(),
                eq(Map.of("runtime_run_id", "run-1", "tool_outcome", status == 400 ? "failed" : "unknown", "unit", "call")));
        verify(ledger).finish(eq("7"), eq("direct-attempt"),
                eq(status == 400 ? ExternalInvocationStatus.FAILED : ExternalInvocationStatus.UNRESOLVED), isNull(), anyString(), any());
        assertEquals(1, requests.size(), "未知结果和失败响应均不能自动重放");
    }

    /** 真实 MCP 回执包含控制字符时，经 Core 和 Session 原样保存、读取，安全反馈不包含私有正文。 */
    @ParameterizedTest @ValueSource(strings = {"rpc-control", "tool-control"})
    void mcpFailureWithControlCharactersRoundTripsThroughSessionCodec(String mode) {
        failureMode = mode;
        var failed = assertInstanceOf(ToolOutcome.Failed.class, dispatch(receipt, "2026-07-28"));
        var fact = failed.comparisonFact().orElseThrow();
        assertTrue(fact.canonicalJson().contains("\\u000b\\u001f"));
        var limits = new CodecLimits(65536, 10000, 128);
        var payloads = new SessionRuntimePayloads();
        var portals = new CoreRuntime(limits, payloads).portalResults(payloads, payloads, payloads);
        var codec = new AgentExecutionCodec(limits);
        var saved = codec.encodeToolOutcome(portals, failed);
        assertEquals(failed, saved.outcome());
        assertEquals(failed, codec.decodeToolOutcome(saved.payload()));
        var request = new AgentExecutionCodec.ToolRequest(target, binding, new TypedPayload("application/json", "{}"),
                "call", "lookup", Optional.empty());
        assertFalse(codec.safeToolFeedback(request, failed).orElseThrow().canonicalJson().contains("private-control"));
        assertFalse(saved.toString().contains("private-control"));
        assertEquals(1, requests.size(), "结果编码和读取不能重新派发");
    }

    @Test void inputRequiredAndContinuationEachMeterTheirActualAttemptOnOriginalAction() {
        waiting = true;
        var required = assertInstanceOf(ToolOutcome.InputRequired.class, dispatch(receipt, "2026-07-28"));
        var resumedReceipt = RegisteredProviderFixture.resume(receipt, new TypedPayload("request.v1", "{\"resume\":true}"));
        var call = call(resumedReceipt);
        var resumed = new ToolContinuationCall(call.context(), call.binding(), call.target(), call.arguments(), call.idempotencyKey(),
                Map.of("roots", new ToolInputResponse(ToolInputMethod.ROOTS_LIST, new TypedPayload("application/json", "{\"roots\":[]}"))), required.requestState());
        waiting = false;
        var actual = audit.mcp(resumedReceipt.registered(), resumedReceipt.prepared(), call,
                before -> portal().forContinuation(resumed, frozen("2026-07-28"), authorized::get, before, value -> true, Optional.empty()).resume(resumed));
        assertInstanceOf(ToolOutcome.Succeeded.class, actual);
        var captured = org.mockito.ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        verify(ledger, times(2)).startAuxiliary(eq("7"), captured.capture(), any());
        assertEquals(List.of("direct-action", "direct-action"), captured.getAllValues().stream().map(ExternalInvocationRequest::invocationId).toList());
        assertEquals(List.of("direct-attempt", "direct-resume-attempt"), captured.getAllValues().stream().map(ExternalInvocationRequest::attemptId).toList());
        verify(costs, times(2)).recordExternal(eq("7"), eq(CostType.TOOL_CALL), any(), any(), any(), any());
        verify(costs).recordExternal(any(), any(), any(), any(), any(), eq(Map.of("runtime_run_id", "run-1", "tool_outcome", "input_required", "unit", "call")));
        assertEquals(2, requests.size());
        assertEquals("opaque-state", requests.getLast().path("params").path("requestState").asText());
    }

    @Test void duplicateAndUncommittedStartsNeverSendOrOverwriteExistingAttempt() {
        when(ledger.startAuxiliary(eq("7"), any(), any())).thenReturn(false);
        assertInstanceOf(ToolOutcome.Failed.class, dispatch(receipt, "2026-07-28"));
        when(ledger.startAuxiliary(eq("7"), any(), any())).thenThrow(new IllegalStateException("start_not_committed"));
        assertInstanceOf(ToolOutcome.Failed.class, dispatch(receipt, "2026-07-28"));
        assertTrue(requests.isEmpty()); verifyNoInteractions(costs, transactions);
        verify(ledger, never()).finish(any(), any(), any(), any(), any(), any());
    }

    @Test void terminalAuditFailurePreservesRealToolResultAndUnknownStartWithoutReplay() {
        doThrow(new IllegalStateException("usage_store_unavailable")).when(costs).recordExternal(any(), any(), any(), any(), any(), any());
        assertInstanceOf(ToolOutcome.Succeeded.class, dispatch(receipt, "2026-07-28"));
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
        verify(ledger).startAuxiliary(eq("7"), any(), any());
        verify(ledger, never()).finish(any(), any(), any(), any(), any(), any());
        assertEquals(1, requests.size());
    }

    @Test void terminalCasConflictRollsBackMeasurementAndPreservesOriginalToolResult() {
        when(ledger.finish(eq("7"), any(), any(), isNull(), any(), any())).thenReturn(false);
        assertInstanceOf(ToolOutcome.Succeeded.class, dispatch(receipt, "2026-07-28"));
        verify(costs).recordExternal(any(), eq(CostType.TOOL_CALL), any(), any(), any(), any());
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
        assertEquals(1, requests.size());
    }

    @Test void changedAttemptIsRejectedBeforeLedgerOrTransport() {
        var original = call(receipt); var dispatch = original.context().dispatch();
        var replaced = new ProviderDispatch(dispatch.run(), dispatch.action(), dispatch.invocationId(), "other-attempt",
                dispatch.callKind(), dispatch.requestRef(), dispatch.capabilityRef(), dispatch.fingerprint(), dispatch.fence());
        var changed = new ToolCall(ProviderCallContext.dispatched(original.context().callContext(), replaced), binding,
                target, original.arguments(), original.idempotencyKey());
        assertThrows(IllegalArgumentException.class, () -> audit.mcp(receipt.registered(), receipt.prepared(), changed,
                before -> { fail("替换Attempt不能进入transport"); return null; }));
        verifyNoInteractions(ledger, costs, transactions); assertTrue(requests.isEmpty());
    }

    @Test void platformOnlySuppliesTrustedMeterScopeWithoutDuplicateGuardAccounting() {
        var expected = new ToolOutcome.InputRequired(Map.of(), Optional.of("platform-state"));
        assertSame(expected, audit.platform(receipt.registered(), receipt.prepared(), call(receipt), () -> {
            assertEquals("run-1", MeterScope.current().runId()); assertEquals("7", TenantContext.getTenantId());
            assertEquals("run-1", MeterScope.current().attributionRunId());
            return expected;
        }));
        verifyNoInteractions(ledger, costs, transactions);
        assertNull(MeterScope.current()); assertNull(TenantContext.getTenantId());
    }

    @Test void programMeterRetainsActualChildAndUsesOnlyProvenResourceOrigin() {
        var actual=receipt.context().callContext().runIdentity();
        var origin=new RunIdentity(actual.owner(),actual.agentId(),actual.causationInputId(),new RunRef(actual.runRef().tenantId(),"parent"),
                actual.trace(),actual.causationIdentityRef(),actual.requester(),actual.sourceMessageRef(),actual.originRef());
        when(programs.resourceOrigin(receipt.registered().run())).thenReturn(origin);
        audit.platform(receipt.registered(),receipt.prepared(),call(receipt),()->{
            assertEquals("run-1",MeterScope.current().runId());
            assertEquals("parent",MeterScope.current().attributionRunId());
            return new ToolOutcome.Succeeded(new TypedPayload("application/json","{}"),new ResultRef("result"),List.of());
        });
        verify(programs).resourceOrigin(receipt.registered().run());
        when(programs.resourceOrigin(receipt.registered().run())).thenThrow(new IllegalStateException("program_child_source_rejected"));
        assertThrows(IllegalStateException.class,()->audit.platform(receipt.registered(),receipt.prepared(),call(receipt),()->{
            fail("来源失败不能进入工具");return null;
        }));
    }

    private ToolOutcome dispatch(RegisteredProviderFixture current, String protocol) {
        var call = call(current);
        return audit.mcp(current.registered(), current.prepared(), call,
                before -> portal().forDispatch(call, frozen(protocol), authorized::get, before, value -> true, Optional.empty()).invoke(call));
    }

    private ToolCall call(RegisteredProviderFixture current) {
        return new ToolCall(current.context(), binding, target, new TypedPayload("application/json", "{}"), new IdempotencyKey("direct-action"));
    }

    private McpToolPortal portal() {
        return new McpToolPortal((tenant, value) -> { throw new AssertionError("general_binding_read"); },
                encrypted -> { throw new AssertionError("unexpected_credential"); }, call -> false, call -> false,
                call -> true, call -> true, (call, value) -> true,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), clock, 65536);
    }

    private McpToolPortal.Binding frozen(String protocol) {
        return new McpToolPortal.Binding(target, binding, "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                Optional.empty(), binding.toolName(), protocol);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            var request = JSON.readTree(exchange.getRequestBody()); requests.add(request);
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                if (revokeAfterHandshake) authorized.set(false);
                exchange.sendResponseHeaders(204, -1); return;
            }
            if ("tools/call".equals(method) && businessStatus != 200) {
                exchange.sendResponseHeaders(businessStatus, -1); return;
            }
            var packet = JSON.createObjectNode().put("jsonrpc", "2.0"); packet.set("id", request.get("id"));
            if (failureMode.equals("rpc-control")) {
                packet.putObject("error").put("code", -32001).put("message", "private-control" + (char) 11 + (char) 31);
                byte[] body = JSON.writeValueAsBytes(packet);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); return;
            }
            var result = packet.putObject("result");
            if ("initialize".equals(method)) {
                result.put("protocolVersion", "2025-11-25"); result.putObject("capabilities").putObject("tools");
            } else if (waiting) {
                result.put("resultType", "input_required").put("requestState", "opaque-state"); result.putObject("inputRequests");
            } else {
                result.put("resultType", "complete").put("isError", failureMode.equals("tool-control")); result.putArray("content");
                if (failureMode.equals("tool-control")) result.putObject("structuredContent").put("message", "private-control" + (char) 11 + (char) 31);
                else result.putObject("structuredContent").put("answer", "actual-response");
            }
            byte[] body = JSON.writeValueAsBytes(packet);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        } finally { exchange.close(); }
    }
}

package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.capability.connector.PlatformInteractiveToolHandler;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolInteractionResult;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.ProviderCallContext;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolContinuationCall;
import com.skillforge.runtime.api.port.ToolPortal.ToolHandle;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputRequest;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AgentRuntimePlatformToolProviderTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final InputId INPUT = new InputId("input");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session");
    private static final SessionOwner OWNER = SessionOwner.user(SESSION, new AgentId("11"), new EndUserId(7));
    private static final ToolBinding BINDING = new ToolBinding(ToolBinding.ProviderType.PLATFORM,
            "lookup", "lookup");

    /** 新批次线程没有继承租户；真实Guard账本及异步Handler均按已核验的Run绑定。 */
    @Test
    void registeredToolBindsTrustedTenantForLedgerAndAsyncHandlerAndRestoresCaller() throws Exception {
        String previousTenant = TenantContext.getTenantId();
        try {
            for (String callerTenant : java.util.Arrays.asList(null, "outer-tenant")) {
                if (callerTenant == null) TenantContext.clear(); else TenantContext.setTenantId(callerTenant);
                var observed = new java.util.concurrent.CopyOnWriteArrayList<String>();
                var handler = new PlatformToolHandler() {
                    @Override public String toolName() { return "lookup"; }
                    @Override public Map<String,Object> execute(Map<String,Object> arguments, PlatformToolExecutionContext context) {
                        assertTrue(Thread.currentThread().isVirtual());
                        assertEquals(context.run().runRef().tenantId().value(), TenantContext.getTenantId());
                        observed.add("handler:" + TenantContext.getTenantId());
                        return Map.of("success", true);
                    }
                };
                var fixture = fixture(handler);
                when(fixture.ledger.startAuxiliary(anyString(), any(), any(Instant.class))).thenAnswer(call -> {
                    assertEquals("1", TenantContext.getTenantId());
                    assertEquals("1", call.getArgument(0)); observed.add("start:" + TenantContext.getTenantId());
                    return true;
                });
                when(fixture.ledger.finish(anyString(), anyString(), any(), any(), any(), any(Instant.class))).thenAnswer(call -> {
                    assertEquals("1", TenantContext.getTenantId()); observed.add("finish:" + TenantContext.getTenantId());
                    return true;
                });
                var direct = directFixture("lookup", "{}");
                assertInstanceOf(ToolOutcome.Succeeded.class,
                        fixture.provider.invokeRegistered(direct.receipt().registered(), direct.receipt().prepared(), direct.call()));
                assertEquals(List.of("start:1", "handler:1", "finish:1"), observed);
                assertEquals(callerTenant, TenantContext.getTenantId());
            }
        } finally {
            if (previousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(previousTenant);
        }
    }

    /** 原边界失败仍为UNKNOWN；失败不能泄漏或覆盖调用线程原有租户。 */
    @Test
    void registeredToolRestoresCallerTenantAfterLedgerOrHandlerFailure() throws Exception {
        String previousTenant = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId("outer-tenant");
            for (boolean ledgerFailure : List.of(false, true)) {
                var handlerCalls = new java.util.concurrent.atomic.AtomicInteger();
                var handler = new PlatformToolHandler() {
                    @Override public String toolName() { return "lookup"; }
                    @Override public Map<String,Object> execute(Map<String,Object> arguments, PlatformToolExecutionContext context) {
                        assertEquals("1", TenantContext.getTenantId()); handlerCalls.incrementAndGet();
                        throw new IllegalStateException("synthetic_handler_failure");
                    }
                };
                var fixture = fixture(handler);
                when(fixture.ledger.startAuxiliary(anyString(), any(), any(Instant.class))).thenAnswer(call -> {
                    assertEquals("1", TenantContext.getTenantId());
                    if (ledgerFailure) throw new IllegalStateException("synthetic_ledger_failure");
                    return true;
                });
                when(fixture.ledger.finish(anyString(), anyString(), any(), any(), any(), any(Instant.class))).thenAnswer(call -> {
                    assertEquals("1", TenantContext.getTenantId()); return true;
                });
                var direct = directFixture("lookup", "{}");
                var outcome = assertInstanceOf(ToolOutcome.OutcomeUnknown.class,
                        fixture.provider.invokeRegistered(direct.receipt().registered(), direct.receipt().prepared(), direct.call()));
                assertEquals("platform_tool_result_boundary_unknown", outcome.failure().reasonCode());
                assertEquals(ledgerFailure ? 0 : 1, handlerCalls.get());
                assertEquals("outer-tenant", TenantContext.getTenantId());
            }
        } finally {
            if (previousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(previousTenant);
        }
    }

    @Test void workflowContextRequiresItsRealSourceAndDoesNotBecomeProgramChild() throws Exception {
        var handler=org.mockito.Mockito.spy(new com.skillforge.core.capability.connector.TemplateRenderToolHandler());var fixture=fixture(handler);
        var direct=directFixture("template_render","{\"template\":\"节点{{quantity}}\",\"data\":{\"quantity\":7}}");var source=mock(AgentRuntimeWorkflowNodeExecutions.class);
        assertInstanceOf(ToolOutcome.Succeeded.class,fixture.provider.invokeWorkflowRegistered(direct.receipt().registered(),direct.receipt().prepared(),direct.call(),source));
        verify(source).requireCall(direct.receipt().registered(),direct.receipt().prepared());var context=org.mockito.ArgumentCaptor.forClass(PlatformToolExecutionContext.class);
        verify(handler).execute(any(),context.capture());assertTrue(context.getValue().workflowNode());assertFalse(context.getValue().programChild());
    }
    @Test void rejectedWorkflowSourceCannotReachHandlerOrExternalLedger() throws Exception {
        var handler=org.mockito.Mockito.spy(new com.skillforge.core.capability.connector.TemplateRenderToolHandler());var fixture=fixture(handler);
        var direct=directFixture("template_render","{\"template\":\"x\",\"data\":{}}");var source=mock(AgentRuntimeWorkflowNodeExecutions.class);
        org.mockito.Mockito.doThrow(new ExecutionStore.Rejection("workflow_node_source_changed")).when(source).requireCall(any(),any());
        assertThrows(ExecutionStore.Rejection.class,()->fixture.provider.invokeWorkflowRegistered(direct.receipt().registered(),direct.receipt().prepared(),direct.call(),source));
        verifyNoInteractions(handler,fixture.ledger);
    }
    @Test void programInteractiveHandlerIsRejectedBeforeAnyExternalCallOrFakeWait() throws Exception {
        var handler=new InteractiveMediaFixture(new PlatformToolInteractionResult.InputRequired(Map.of("confirm",new ToolInputRequest(ToolInputMethod.ELICITATION_CREATE,new TypedPayload("request.v1","{}"))),Optional.empty()),null);
        var fixture=fixture(handler);var direct=directFixture("lookup","{}");var source=mock(AgentRuntimeProgramTools.class);
        var outcome=assertInstanceOf(ToolOutcome.Failed.class,fixture.provider.invokeProgramRegistered(direct.receipt().registered(),direct.receipt().prepared(),direct.call(),source));
        assertEquals("program_tool_requires_direct_call",outcome.failure().reasonCode());verify(source).requireCall(direct.receipt().registered(),direct.receipt().prepared());verifyNoInteractions(fixture.ledger);
    }

    /** 实际模板 Handler 沿直接派发与原外调账本执行，不回读旧策略或 Codec。 */
    @Test
    void registeredPrimaryTemplateExecutesActualHandlerAndKeepsAttemptIdentityWithoutSecondAuthorization() throws Exception {
        var handler = org.mockito.Mockito.spy(new com.skillforge.core.capability.connector.TemplateRenderToolHandler());
        var fixture = fixture(handler);
        var direct = directFixture("template_render", "{\"template\":\"数量{{quantity}}\",\"data\":{\"quantity\":7}}");
        var call = direct.call();
        assertEquals(direct.receipt().registered().attempt().id(),call.context().dispatch().attemptId());
        var result = assertInstanceOf(ToolOutcome.Succeeded.class,
                fixture.provider.invokeRegistered(direct.receipt().registered(), direct.receipt().prepared(), call));
        var body = new ObjectMapper().readTree(result.observation().canonicalJson());
        assertEquals("数量7", body.path("structuredContent").path("rendered").asText());
        assertTrue(body.path("structuredContent").path("success").asBoolean());
        assertEquals("platform-result:direct-attempt", result.resultRef().value());
        var context = org.mockito.ArgumentCaptor.forClass(PlatformToolExecutionContext.class);
        verify(handler).execute(any(), context.capture());
        assertEquals(call.context().callContext().runIdentity(), context.getValue().run());
        assertEquals("direct-action", context.getValue().invocationId());
        assertEquals("direct-attempt", context.getValue().attemptId());
        assertFalse(context.getValue().programChild());
        assertFalse(context.getValue().workflowNode());
        var request = org.mockito.ArgumentCaptor.forClass(com.skillforge.core.billing.invocation.ExternalInvocationRequest.class);
        verify(fixture.ledger).startAuxiliary(eq("1"), request.capture(), any(Instant.class));
        assertEquals("direct-action", request.getValue().invocationId());
        assertEquals("direct-attempt", request.getValue().attemptId());

    }

    /** 错 actor 回执与模型伪造可信身份参数均在 Handler 和外调账本之前拒绝。 */
    @Test
    void registeredToolRejectsReceiptActorDriftAndForgedTrustedArgumentsBeforeEffects() throws Exception {
        var fixture = fixture(new com.skillforge.core.capability.connector.TemplateRenderToolHandler());
        var direct = directFixture("template_render", "{\"template\":\"数量{{quantity}}\",\"data\":{\"quantity\":7}}");
        var original = direct.call().context().callContext();
        var changed = new VerifiedIdentity(original.identity().principal(), OWNER, original.identity().scopes(), 1,
                new ResultRef("different-verification"));
        var wrongContext = ProviderCallContext.dispatched(new PortCallContext(original.runIdentity(), changed, original.control()),
                direct.call().context().dispatch());
        var wrong = new ToolCall(wrongContext, direct.call().binding(), direct.call().target(),
                direct.call().arguments(), direct.call().idempotencyKey());
        String originalTenant = TenantContext.getTenantId();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.provider.invokeRegistered(direct.receipt().registered(), direct.receipt().prepared(), wrong));
        assertEquals(originalTenant, TenantContext.getTenantId());
        var forged = directFixture("template_render", "{\"template\":\"x\",\"data\":{},\"tenant_id\":\"forged\"}");
        var failed = assertInstanceOf(ToolOutcome.Failed.class,
                fixture.provider.invokeRegistered(forged.receipt().registered(), forged.receipt().prepared(), forged.call()));
        assertEquals("tool_pre_dispatch_rejected", failed.failure().reasonCode());
        verifyNoInteractions(fixture.ledger);
    }

    /** 直接派发继续使用交互与媒体协议，等待不能编码为成功，错误正文保持类型。 */
    @Test
    void registeredToolPreservesInputRequiredAndMediaCompletedOrRejected() throws Exception {
        var waiting = new PlatformToolInteractionResult.InputRequired(Map.of("quantity", new ToolInputRequest(
                ToolInputMethod.ELICITATION_CREATE, new TypedPayload("request.v1", "{}"))), Optional.of("opaque"));
        for (PlatformToolInteractionResult output : List.of(waiting,
                new PlatformToolInteractionResult.Completed(Map.of("content", List.of(Map.of("type", "text", "text", "actual-output")), "success", true)),
                new PlatformToolInteractionResult.Rejected(Map.of("content", List.of(Map.of("type", "text", "text", "actual-output")), "success", false)))) {
            var fixture = fixture(new InteractiveMediaFixture(output, output));
            var direct = directFixture("lookup", "{}");
            var result = fixture.provider.invokeRegistered(direct.receipt().registered(), direct.receipt().prepared(), direct.call());
            if (output instanceof PlatformToolInteractionResult.InputRequired) {
                var required = assertInstanceOf(ToolOutcome.InputRequired.class, result);
                assertEquals(waiting.inputRequests(), required.inputRequests());
                assertEquals(waiting.requestState(), required.requestState());
            } else {
                var body = new ObjectMapper().readTree(assertInstanceOf(ToolOutcome.Succeeded.class, result).observation().canonicalJson());
                assertEquals("actual-output", body.path("content").get(0).path("text").asText());
                assertEquals(output instanceof PlatformToolInteractionResult.Rejected, body.path("isError").asBoolean());
                assertFalse(body.path("structuredContent").has("content"));
            }

            verify(fixture.ledger).startAuxiliary(eq("1"), any(), any(Instant.class));
        }
    }

    /** 新Attempt只恢复原交互Handler；原Action、响应、opaque state及真实外调账本身份精确保留。 */
    @Test
    void registeredContinuationUsesOriginalActionAndNewRequestWithoutLegacyPermit() throws Exception {
        var handler = org.mockito.Mockito.spy(new InteractiveMediaFixture(
                new PlatformToolInteractionResult.InputRequired(Map.of("browser-connection", new ToolInputRequest(
                        ToolInputMethod.ELICITATION_CREATE, new TypedPayload("request.v1", "{}"))), Optional.of("opaque-exact")),
                new PlatformToolInteractionResult.Completed(Map.of("content", List.of(Map.of("type", "text", "text", "resumed-real-result")), "success", true))));
        var fixture = fixture(handler);
        var original = directFixture("lookup", "{}");
        var receipt = RegisteredProviderFixture.resume(original.receipt(), new TypedPayload("application/json", "{\"resume\":true}"));
        var responses = Map.of("browser-connection", new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE,
                new TypedPayload("application/json", "{\"device_id\":\"chosen\"}")));
        var call = new ToolContinuationCall(receipt.context(), original.call().binding(), original.call().target(), original.call().arguments(),
                original.call().idempotencyKey(), responses, Optional.of("opaque-exact"));
        assertEquals(receipt.registered().attempt().id(),call.context().dispatch().attemptId());
        var result = assertInstanceOf(ToolOutcome.Succeeded.class,
                fixture.provider.resumeRegistered(receipt.registered(), receipt.prepared(), call));
        assertEquals("platform-result:direct-resume-attempt", result.resultRef().value());
        assertTrue(result.observation().canonicalJson().contains("resumed-real-result"));
        var context = org.mockito.ArgumentCaptor.forClass(PlatformToolExecutionContext.class);
        verify(handler).resume(any(), eq(responses), eq(Optional.of("opaque-exact")), context.capture());
        verify(handler, never()).invoke(any(), any());
        assertEquals("direct-action", context.getValue().invocationId());
        assertEquals("direct-resume-attempt", context.getValue().attemptId());
        assertFalse(context.getValue().programChild()); assertFalse(context.getValue().workflowNode());
        var request = org.mockito.ArgumentCaptor.forClass(com.skillforge.core.billing.invocation.ExternalInvocationRequest.class);
        verify(fixture.ledger).startAuxiliary(eq("1"), request.capture(), any(Instant.class));
        assertEquals("direct-resume-attempt", request.getValue().attemptId());

        var initialCall = new ToolCall(receipt.context(), call.binding(), call.target(), call.arguments(), call.idempotencyKey());
        assertThrows(IllegalArgumentException.class,
                () -> fixture.provider.invokeRegistered(receipt.registered(), receipt.prepared(), initialCall));
    }

    /** 实际run_code Handler服务RPC的回调留在调用它的同一平台线程，不能进入旧虚拟线程wrapper。 */
    @Test
    @SuppressWarnings("unchecked")
    void registeredProgramHandlerKeepsRpcOnTheCallerPlatformThread() throws Exception {
        var caller = Thread.currentThread(); assertFalse(caller.isVirtual());
        var execution = mock(com.skillforge.institution.execution.CodeExecutionToolHandler.class);
        ObjectProvider<com.skillforge.core.capability.connector.ProgramToolInvoker> invokers = mock(ObjectProvider.class);
        var rpc = new java.util.concurrent.atomic.AtomicInteger();
        com.skillforge.core.capability.connector.ProgramToolInvoker actualRpc = context -> (ordinal, handle, arguments) -> {
            assertEquals(caller, Thread.currentThread()); rpc.incrementAndGet(); return new TypedPayload("application/json", "{\"success\":true}");
        };
        when(invokers.getObject()).thenReturn(actualRpc);
        when(execution.executeProgram(any(), any(), any())).thenAnswer(call -> {
            assertEquals(caller, Thread.currentThread());
            var context = (PlatformToolExecutionContext) call.getArgument(1);
            ((com.skillforge.core.capability.connector.ProgramToolInvoker) call.getArgument(2)).bind(context).invoke(1, "tool:example", new TypedPayload("application/json", "{}"));
            return Map.of("success", true);
        });
        var fixture = fixture(new com.skillforge.institution.execution.ProgramCodeToolHandler(execution, invokers));
        var direct = directFixture("run_code", "{\"code\":\"await tools.call('tool:example', {});\"}");
        assertInstanceOf(ToolOutcome.Succeeded.class, fixture.provider.invokeRegistered(direct.receipt().registered(), direct.receipt().prepared(), direct.call()));
        assertEquals(1, rpc.get());
    }

    /** 按正式内建目录构造精确 target；自定义 lookup 用于交互协议夹具。 */
    private static DirectTool directFixture(String name, String arguments) throws Exception {
        var entry = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(value -> value.name().equals(name)).findFirst();
        var json = new ObjectMapper();
        var target = entry.isPresent() ? new FrozenToolTarget(new ToolHandle("tool:42"),
                new CapabilityRef(CapabilityType.TOOL, name, entry.get().publishedVersionKey()), "deployment", 3,
                new TypedPayload("json-schema", json.writeValueAsString(entry.get().parametersSchema())),
                new TypedPayload("json-schema", json.writeValueAsString(entry.get().outputSchema())), entry.get().effect(),
                entry.get().idempotent(), Duration.ofMillis(entry.get().timeoutMs()), entry.get().modelInvocable(),
                entry.get().confirmationPolicy(), entry.get().sensitiveFields(), Set.of("platform_handler:" + name), true)
                : new FrozenToolTarget(new ToolHandle("tool:42"), new CapabilityRef(CapabilityType.TOOL, "definition", "version"), "deployment", 3,
                new TypedPayload("json-schema", "{}"), new TypedPayload("json-schema", "{}"), ToolEffect.READ_ONLY, true,
                Duration.ofSeconds(5), true, ToolConfirmationPolicy.NEVER, Set.of(), Set.of("platform_handler:" + name), false);
        var run = new RunIdentity(OWNER, new AgentId((OWNER).target().id()), INPUT, new RunRef(TENANT, "run"), new TraceEnvelope("trace", "span", INPUT, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((OWNER).requireUser()), java.util.Optional.empty(), new RunOriginRef((OWNER).sessionRef().tenantId(), "input:" + (INPUT).value()));
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, OWNER.requireUser()), OWNER,
                Set.of("runtime:invoke"), 1, new ResultRef("direct-verified"));
        var payload = new TypedPayload("application/json", arguments);
        var receipt = RegisteredProviderFixture.create(run, actor, ExecutionStore.Kind.TOOL, PortalCallKind.TOOL_INVOKE,
                target.handle().value(), payload);
        var binding = new ToolBinding(ToolBinding.ProviderType.PLATFORM, name, name);
        return new DirectTool(receipt, new ToolCall(receipt.context(), binding, target, payload, new IdempotencyKey("direct-tool")));
    }

    /** @param receipt 结构合法的直接登记夹具 @param call 同一登记的实际 Tool 调用 */
    private record DirectTool(RegisteredProviderFixture receipt, ToolCall call) { }

    /** 真实交互和媒体协议夹具，不注入旧许可、策略或编排器。 */
    private record InteractiveMediaFixture(PlatformToolInteractionResult initial,PlatformToolInteractionResult continued)
            implements PlatformInteractiveToolHandler,com.skillforge.core.capability.connector.PlatformMediaToolHandler {
        @Override public String toolName(){return "lookup";}
        @Override public PlatformToolInteractionResult invoke(Map<String,Object> arguments,PlatformToolExecutionContext context){return initial;}
        @Override public PlatformToolInteractionResult resume(Map<String,Object> arguments,Map<String,ToolInputResponse> responses,Optional<String> state,PlatformToolExecutionContext context){return continued;}
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture(PlatformToolHandler handler) {
        ObjectProvider<PlatformToolHandler> handlers = mock(ObjectProvider.class);
        when(handlers.stream()).thenAnswer(ignored -> Stream.of(handler));
        var ledger = mock(ExternalInvocationLedger.class);
        when(ledger.startAuxiliary(anyString(), any(), any(Instant.class))).thenReturn(true);
        when(ledger.finish(anyString(), anyString(), any(), any(), any(), any(Instant.class))).thenReturn(true);
        return new Fixture(new AgentRuntimePlatformToolProvider(handlers,
                new ExternalInvocationGuard(ledger, mock(com.skillforge.core.billing.budget.AuxiliaryModelBudgets.class)), new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor())), ledger);
    }

    private record Fixture(AgentRuntimePlatformToolProvider provider, ExternalInvocationLedger ledger) { }
}

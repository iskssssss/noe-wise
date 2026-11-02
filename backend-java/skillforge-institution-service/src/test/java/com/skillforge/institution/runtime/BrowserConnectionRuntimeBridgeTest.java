package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 浏览器桥仅消费新Wait和精确响应；完整HTTP/PG组合由集成用例验证。 */
class BrowserConnectionRuntimeBridgeTest {
    private final AgentRunBindings bindings = mock(AgentRunBindings.class);
    private final ExecutionStore store = mock(ExecutionStore.class);
    private final AgentRuntimeExecutionGateway gateway = mock(AgentRuntimeExecutionGateway.class);
    private final AgentExecutionCodec codec = mock(AgentExecutionCodec.class);
    private final BrowserConnectionRuntimeBridge bridge = new BrowserConnectionRuntimeBridge(bindings, store, gateway, codec, new ObjectMapper());
    private final RunRef ref = new RunRef(new TenantId("1"), "run-1");
    private final AgentId actor = new AgentId("7");
    private final SessionOwner owner = SessionOwner.user(new SessionRef(ref.tenantId(), "session-1"), actor, new EndUserId(9));
    private final AgentRunBindings.Binding binding = mock(AgentRunBindings.Binding.class);
    private final ExecutionStore.Run run = mock(ExecutionStore.Run.class);
    private final ExecutionStore.WaitRef wait = new ExecutionStore.WaitRef(new ActionRef(ref.tenantId(), "action-1"), 2);
    private final TypedPayload request = new TypedPayload("skillforge.browser.connection-request.v1", "{\"connection_request_id\":\"request-1\"}");
    private final TypedPayload response = new TypedPayload("skillforge.browser.connection-response.v1", "{\"connection_request_id\":\"request-1\",\"device_id\":\"d\",\"browser_id\":\"b\",\"cancelled\":false}");

    /** 创建合法等待原事实，负向场景只改变目标条件。 */
    @BeforeEach void prepare() {
        when(bindings.find(ref)).thenReturn(Optional.of(binding));
        var scope = mock(AgentRunBindings.Scope.class);
        when(binding.scope()).thenReturn(scope); when(scope.origin()).thenReturn(owner);
        when(binding.owner()).thenReturn(owner); when(binding.actor()).thenReturn(actor);
        when(bindings.withContinuationRequester(eq(owner), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(store.load(ref)).thenReturn(Optional.of(run));
        when(run.identity()).thenReturn(new RunIdentity(owner, new AgentId((owner).target().id()), new InputId("original"), ref, new TraceEnvelope("trace-1", "span-1", new InputId("original"), Optional.empty()), new ResultRef("identity-1"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (new InputId("original")).value())));
        when(run.phase()).thenReturn(ExecutionStore.Phase.WAITING); when(run.revision()).thenReturn(3L);
        when(run.waiting()).thenReturn(Optional.of(wait));
        var waiting = new ExecutionStore.Waiting(wait, ExecutionStore.WaitKind.TOOL, ref, "attempt-1", Map.of("browser-connection", ToolInputMethod.ELICITATION_CREATE), Optional.empty(), Optional.empty(), Optional.empty(), java.util.OptionalLong.empty());
        when(store.waiting(ref, wait)).thenReturn(Optional.of(waiting));
        var attempt = mock(ExecutionStore.Attempt.class);
        when(store.attempt(ref, "attempt-1")).thenReturn(Optional.of(attempt));
        when(attempt.action()).thenReturn(wait.action()); when(attempt.resultKind()).thenReturn(Optional.of(ExecutionStore.ResultKind.INPUT_REQUIRED));
        var resultRef = new ResultRef("result-1"); when(attempt.result()).thenReturn(Optional.of(resultRef));
        var body = new TypedPayload("test.actual-result", "{}");
        when(store.material(ref.tenantId(), resultRef)).thenReturn(Optional.of(new ExecutionStore.Material(resultRef, body)));
        when(codec.decodeToolOutcome(body)).thenReturn(new ToolOutcome.InputRequired(
                Map.of("browser-connection", new ToolInputRequest(ToolInputMethod.ELICITATION_CREATE, request)), Optional.of("private-state")));
    }

    @Test void resumeUsesFreshIdentityAndOriginalTypedWait() {
        var pending = bridge.inspect("1", "run-1", "action-1", "request-1").pending();
        var input = new InputId("browser-connection:request-1"); var verified = mock(VerifiedIdentity.class);
        when(gateway.verify(run, input)).thenReturn(verified);
        bridge.resume("1", "run-1", "request-1", pending, response, Instant.EPOCH);
        verify(gateway).resume(run, verified, input, wait, 3L,
                Map.of("browser-connection", new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE, response)));
        assertEquals(wait, pending.waiting());
    }

    @Test void runningWithoutSavedWaitRemainsObservable() {
        when(run.phase()).thenReturn(ExecutionStore.Phase.READY);
        assertEquals(new BrowserConnectionRuntimeBridge.Snapshot(false, false, null), bridge.inspect("1", "run-1", "action-1", "request-1"));
        verifyNoInteractions(gateway, codec);
    }

    @Test void cancelledRunNeverPreparesNewResume() {
        when(run.cancelled()).thenReturn(true);
        assertTrue(bridge.inspect("1", "run-1", "action-1", "request-1").terminal());
        verifyNoInteractions(gateway, codec);
    }

    @Test void acceptedResponseSurvivesRestartWithoutRepeatingResume() {
        var input = new InputId("browser-connection:request-1");
        var accepted = new ExecutionStore.AcceptedResponse(input, new ResultRef("response-1"), "fingerprint", new ResultRef("actor-2"), 4);
        when(store.response(ref, input)).thenReturn(Optional.of(new ExecutionStore.Waiting(wait, ExecutionStore.WaitKind.TOOL, ref, "attempt-1",
                Map.of("browser-connection", ToolInputMethod.ELICITATION_CREATE), Optional.empty(), Optional.of(accepted), Optional.of("attempt-2"), java.util.OptionalLong.empty())));
        var saved = new TypedPayload(AgentExecutionCodec.TOOL_RESPONSES_SCHEMA, "{}");
        when(store.material(ref.tenantId(), accepted.response())).thenReturn(Optional.of(new ExecutionStore.Material(accepted.response(), saved)));
        when(codec.encodeToolResponses(Map.of("browser-connection", new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE, response)))).thenReturn(saved);
        when(run.phase()).thenReturn(ExecutionStore.Phase.FINISHED);
        assertTrue(bridge.inspect("1", "run-1", "action-1", "request-1").resumeAccepted());
        bridge.resume("1", "run-1", "request-1", new BrowserConnectionRuntimeBridge.ToolWait(ref, 3, wait, Map.of()), response, Instant.EPOCH);
        verifyNoInteractions(gateway);
    }

    @Test void revokedSourceDeniedBeforeAnyWaitOrReceiptRead() {
        doThrow(new SecurityException("revoked")).when(bindings).authorize(binding);
        assertThrows(SecurityException.class, () -> bridge.inspect("1", "run-1", "action-1", "request-1"));
        verifyNoInteractions(store, gateway, codec);
    }

    @Test void wrongRequestAndStaleGenerationCannotResume() {
        var pending = bridge.inspect("1", "run-1", "action-1", "request-1").pending();
        var stale = new BrowserConnectionRuntimeBridge.ToolWait(ref, 3, new ExecutionStore.WaitRef(wait.action(), 1), pending.requests());
        assertThrows(IllegalArgumentException.class, () -> bridge.resume("1", "run-1", "request-1", stale, response, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> bridge.resume("1", "run-1", "request-1", pending,
                new TypedPayload(response.schemaId(), "{\"connection_request_id\":\"other\"}"), Instant.EPOCH));
        verifyNoInteractions(gateway);
    }
}

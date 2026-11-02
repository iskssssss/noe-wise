package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionSteering;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentRuntimeExecutionSteeringTest {
    private static final CodecLimits LIMITS = new CodecLimits(1_000_000, 100_000, 128);
    private final ExecutionStore store = mock(ExecutionStore.class);
    private final AgentRuntimeExecutionWorker worker = mock(AgentRuntimeExecutionWorker.class);
    private final AgentRuntimeExecutionSteering steering = new AgentRuntimeExecutionSteering(store, worker, LIMITS);
    private final TenantId tenant = new TenantId("1");
    private final RunRef reference = new RunRef(tenant, "original-run");
    private final SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "original-session"), SessionTarget.agent(new AgentId("2")), new EndUserId(3));
    private final VerifiedIdentity actor = new VerifiedIdentity(new PrincipalRef.ServicePrincipal(tenant, "service", "chat", 1), owner, Set.of("session.execute"), 1, new ResultRef("actor"));
    private final RunIdentity identity = new RunIdentity(owner, new AgentId("2"), new InputId("original-input"), reference, new TraceEnvelope("trace", "span", new InputId("original-root"), Optional.empty()), actor.verificationRef(), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (new InputId("original-input")).value()));
    private final InputId input = new InputId("delegation-send:actual-parent-action");
    private final AgentExecutionSteering codec = new AgentExecutionSteering(LIMITS);
    private final ExecutionStore.Material content = new ExecutionStore.Material(new ResultRef("content"), codec.encode(input, "原补充内容"));

    @Test
    void offersTheStableInputToTheSameRunAndOnlyWakesAfterDurableAcceptance() {
        var run = run();
        when(store.offerInput(any())).thenAnswer(call -> {
            ExecutionStore.OfferInput offered = call.getArgument(0);
            return new ExecutionStore.InputOffer(run, new ExecutionStore.SupplementalInput(reference, input, offered.content().reference(),
                    offered.fingerprint(), actor.verificationRef(), 1, OptionalLong.empty()), true);
        });
        var result = steering.offer(run, actor, input, "原补充内容"); assertTrue(result.created());
        var capture = ArgumentCaptor.forClass(ExecutionStore.OfferInput.class); verify(store).offerInput(capture.capture());
        assertEquals(reference, capture.getValue().run()); assertEquals(input, capture.getValue().input()); assertSame(actor, capture.getValue().actor());
        assertEquals("原补充内容", codec.decode(capture.getValue().content().content()).text());
        assertEquals(new InputId("original-input"), run.identity().causationInputId()); assertEquals(new InputId("original-root"), run.identity().trace().rootInputId());
        verify(worker).wake(reference); verify(store, never()).resume(any()); verify(store, never()).admit(any());
    }

    @Test
    void localApplicationRemainsPendingUntilARealFrozenModelRequestContainsTheExactMessage() {
        var run = run(); inputs(true); when(store.actions(reference, 0, 100)).thenReturn(List.of());
        assertEquals(AgentRuntimeExecutionSteering.Status.PENDING, steering.snapshot(run).items().getFirst().status());
    }

    @Test
    void onlyTheSelectedAdoptedActualModelResultMarksAnIncludedInputConsumed() {
        var run = run(); inputs(true); var action = model(false, codec.message(input, content.content()));
        when(store.actions(reference, 0, 100)).thenReturn(List.of(action));
        assertEquals(AgentRuntimeExecutionSteering.Status.INCLUDED, steering.snapshot(run).items().getFirst().status());
        action = model(true, codec.message(input, content.content())); when(store.actions(reference, 0, 100)).thenReturn(List.of(action));
        assertEquals(AgentRuntimeExecutionSteering.Status.CONSUMED, steering.snapshot(run).items().getFirst().status());
    }

    @Test
    void aKnownMessageIdCannotConsumeDifferentText() {
        var run = run(); inputs(true);
        var action = model(false, new ModelMessage(AgentExecutionSteering.messageId(input), ModelRole.USER, "篡改内容"));
        when(store.actions(reference, 0, 100)).thenReturn(List.of(action));
        assertThrows(IllegalStateException.class, () -> steering.snapshot(run));
    }

    private ExecutionStore.Run run() {
        var run = mock(ExecutionStore.Run.class); when(run.identity()).thenReturn(identity); when(run.status()).thenReturn(RunStatus.RUNNING); return run;
    }

    private void inputs(boolean applied) {
        var offered = new ExecutionStore.SupplementalInput(reference, input, content.reference(), "fingerprint", actor.verificationRef(), 1,
                applied ? OptionalLong.of(10) : OptionalLong.empty());
        when(store.inputs(reference, 0, false, 100)).thenReturn(List.of(offered)); when(store.material(tenant, content.reference())).thenReturn(Optional.of(content));
    }

    private ExecutionStore.Action model(boolean adopted, ModelMessage message) {
        var target = new FrozenModelTarget(new ModelBindingRef(tenant, "binding", 1), "model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("capabilities")), Duration.ofSeconds(30), new ResultRef("resolution"));
        var request = new FrozenModelRequest(new RequestedModelStrategy("strategy", "1"), target, List.of(message),
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "text"),
                new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DISABLED);
        var material = new ExecutionStore.Material(new ResultRef("model-request"), new AgentExecutionCodec(LIMITS).encodeModelRequest(new AgentExecutionCodec.ModelCallRequest(request, List.of(), 1, Optional.empty())));
        when(store.material(tenant, material.reference())).thenReturn(Optional.of(material)); var action = new ActionRef(tenant, "actual-model-action");
        var result = new ExecutionStore.Action(action, reference, 1, ExecutionStore.Kind.MODEL, "model", new ResultRef("capability"), material.reference(), "fingerprint",
                adopted ? ExecutionStore.ActionStatus.SUCCEEDED : ExecutionStore.ActionStatus.DISPATCHED, "actual-attempt", adopted ? Optional.of("actual-attempt") : Optional.empty(), Optional.empty());
        if (adopted) when(store.attempt(reference, "actual-attempt")).thenReturn(Optional.of(new ExecutionStore.Attempt("actual-attempt", action, reference, 1, material.reference(),
                Optional.empty(), Optional.of(ExecutionStore.ResultKind.SUCCEEDED), Optional.of(new ResultRef("actual-result")), Optional.empty(), Optional.empty(), Optional.empty(), true)));
        return result;
    }
}

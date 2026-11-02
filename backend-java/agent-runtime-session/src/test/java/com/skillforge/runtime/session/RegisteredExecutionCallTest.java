package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.ProviderCallContext;
import com.skillforge.runtime.api.port.ProviderDispatch;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 真实登记后的纯值边界；没有 Store、Provider、预算或再次签发发送权。 */
class RegisteredExecutionCallTest {
    @Test void acceptsOriginalSingleAndExactSingleContinuation() {
        for (boolean continuation : new boolean[] {false, true}) {
            var fixture = fixture(Optional.empty(), continuation);
            assertEquals(fixture.context().dispatch(), require(fixture));
        }
    }

    @Test void acceptsEachBatchContinuationWithoutSingleRunWait() {
        var batch = new BatchRef(SessionFixtures.IDENTITY.runRef(), "model-original", 2);
        for (int slot = 1; slot <= batch.size(); slot++) {
            var fixture = fixture(Optional.of(new BatchSlot(batch, slot)), true);
            assertEquals(Optional.empty(), fixture.registered().run().waiting());
            assertEquals(fixture.context().dispatch(), require(fixture));
        }
    }

    @Test void rejectsAnotherBatchSourceAndAnotherSlotAction() {
        var batch = new BatchRef(SessionFixtures.IDENTITY.runRef(), "model-original", 2);
        var fixture = fixture(Optional.of(new BatchSlot(batch, 1)), true);
        var original = fixture.registered();
        var foreign = new BatchRef(batch.run(), "another-model-attempt", 2);
        var wrongBatchRun = run(original.action(), Optional.of(foreign), Optional.empty());
        rejected(new Fixture(new DispatchResult.Registered(wrongBatchRun, original.action(), original.attempt(), original.actor()),
                fixture.prepared(), fixture.context()));
        var sibling = fixture(Optional.of(new BatchSlot(batch, 2)), true);
        rejected(new Fixture(new DispatchResult.Registered(original.run(), original.action(), sibling.registered().attempt(), original.actor()),
                fixture.prepared(), fixture.context()));
    }

    @Test void singleContinuationRequiresOriginalWaitGeneration() {
        var fixture = fixture(Optional.empty(), true);
        var original = fixture.registered();
        var anotherWait = new WaitRef(original.action().reference(), 8);
        var wrongWaitRun = run(original.action(), Optional.empty(), Optional.of(anotherWait));
        rejected(new Fixture(new DispatchResult.Registered(wrongWaitRun, original.action(), original.attempt(), original.actor()),
                fixture.prepared(), fixture.context()));
    }

    @Test void adoptedReusedOriginalCannotBecomeFreshSendAuthority() {
        var fixture = fixture(Optional.empty(), false); var original = fixture.registered(); var action = original.action();
        var reused = new Action(action.reference(), action.run(), action.ordinal(), action.kind(), action.target(),
                action.capability(), action.request(), action.fingerprint(), ActionStatus.SUCCEEDED,
                action.currentAttempt(), Optional.of(action.currentAttempt()), action.batchSlot());
        var attempt = original.attempt();
        var adopted = new Attempt(attempt.id(), attempt.action(), attempt.run(), attempt.fence(), attempt.request(),
                attempt.continuation(), Optional.of(ResultKind.SUCCEEDED), Optional.of(new ResultRef("original-result")),
                Optional.empty(), Optional.of(new ResultRef("original-feedback")), Optional.empty(), true);
        rejected(new Fixture(new DispatchResult.Registered(original.run(), reused, adopted, original.actor()), fixture.prepared(), fixture.context()));
        var current = new BatchRef(action.run(), "current-model", 2);
        rejected(new Fixture(new DispatchResult.Registered(run(reused, Optional.of(current), Optional.empty()), reused, adopted, original.actor()),
                fixture.prepared(), fixture.context()));
    }

    @Test void refusesRequestFingerprintCallKindOrFenceDrift() {
        var fixture = fixture(Optional.empty(), false); var original = fixture.context().dispatch();
        for (var wrong : new ProviderDispatch[] {
                new ProviderDispatch(original.run(), original.action(), original.invocationId(), original.attemptId(),
                        original.callKind(), new ResultRef("different-request"), original.capabilityRef(), original.fingerprint(), original.fence()),
                new ProviderDispatch(original.run(), original.action(), original.invocationId(), original.attemptId(),
                        original.callKind(), original.requestRef(), original.capabilityRef(), "different-fingerprint", original.fence()),
                new ProviderDispatch(original.run(), original.action(), original.invocationId(), original.attemptId(),
                        PortalCallKind.MODEL_GENERATE, original.requestRef(), original.capabilityRef(), original.fingerprint(), original.fence()),
                new ProviderDispatch(original.run(), original.action(), original.invocationId(), original.attemptId(),
                        original.callKind(), original.requestRef(), original.capabilityRef(), original.fingerprint(), original.fence() + 1)}) {
            rejected(new Fixture(fixture.registered(), fixture.prepared(), ProviderCallContext.dispatched(fixture.context().callContext(), wrong)));
        }
    }

    private static ProviderDispatch require(Fixture fixture) {
        return RegisteredExecutionCall.require(fixture.registered(), fixture.prepared(), fixture.context(), Kind.TOOL);
    }
    private static void rejected(Fixture fixture) {
        assertThrows(IllegalArgumentException.class, () -> require(fixture));
    }

    private static Fixture fixture(Optional<BatchSlot> slot, boolean continuation) {
        String suffix = slot.map(value -> Integer.toString(value.slot())).orElse("single");
        var actionRef = new ActionRef(SessionFixtures.TENANT, "tool-" + suffix);
        var original = new ResultRef("original-request-" + suffix);
        var prepared = new Prepared(actionRef, 2, Kind.TOOL, "tool-target", new ResultRef("capability"),
                new Material(continuation ? new ResultRef("continuation-request-" + suffix) : original, SessionFixtures.CONTENT),
                continuation ? "continuation-fingerprint" : "original-fingerprint");
        var action = new Action(actionRef, SessionFixtures.IDENTITY.runRef(), prepared.ordinal(), Kind.TOOL, prepared.target(),
                prepared.capability(), original, "original-fingerprint", ActionStatus.DISPATCHED, "attempt-" + suffix,
                Optional.empty(), slot);
        var wait = continuation ? Optional.of(new WaitRef(actionRef, 7)) : Optional.<WaitRef>empty();
        var run = run(action, slot.map(BatchSlot::batch), slot.isPresent() ? Optional.empty() : wait);
        var attempt = new Attempt(action.currentAttempt(), actionRef, action.run(), run.fence(), prepared.request().reference(),
                wait, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
        var registered = new DispatchResult.Registered(run, action, attempt, SessionFixtures.ADMISSION);
        var dispatch = new ProviderDispatch(action.run(), actionRef, actionRef.actionId(), attempt.id(), PortalCallKind.TOOL_INVOKE,
                attempt.request(), action.capability(), prepared.fingerprint(), attempt.fence());
        var context = ProviderCallContext.dispatched(new PortCallContext(run.identity(), registered.actor(),
                new InvocationControl(SessionFixtures.NOW.plusSeconds(30), () -> false)), dispatch);
        return new Fixture(registered, prepared, context);
    }

    private static Run run(Action action, Optional<BatchRef> batch, Optional<WaitRef> waiting) {
        return new Run(SessionFixtures.IDENTITY, new ResultRef("configuration"), RunStatus.RUNNING, 3, 4, false,
                Phase.AWAITING_RESULT, new Progress(1, 0, 0, new ResultRef("context")),
                Optional.of(new LeaseFence(SessionFixtures.TENANT, "claim", "worker", 4, SessionFixtures.NOW.plusSeconds(60))),
                batch.isPresent() ? Optional.empty() : Optional.of(action.reference()), batch, waiting,
                SessionFixtures.ADMISSION.verificationRef(), Optional.empty(), SessionFixtures.NOW, SessionFixtures.NOW);
    }
    private record Fixture(DispatchResult.Registered registered, Prepared prepared, ProviderCallContext context) { }
}

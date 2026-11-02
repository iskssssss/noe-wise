package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.ProviderCallContext;
import com.skillforge.runtime.api.port.ProviderDispatch;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.Optional;

/** Provider 单测使用的结构合法登记回执，不代替真实 Store/IAM/数据库验收。 */
record RegisteredProviderFixture(ExecutionStore.DispatchResult.Registered registered,
                                 ExecutionStore.Prepared prepared, ProviderCallContext context) {
    /** 独立构造直接 Action/Attempt，不构造旧 permit、STARTED journal 或旧 checkpoint。 */
    static RegisteredProviderFixture create(RunIdentity identity, VerifiedIdentity actor, ExecutionStore.Kind kind,
                                             PortalCallKind callKind, String target, TypedPayload request) {
        var tenant = identity.runRef().tenantId();
        var action = new ActionRef(tenant, "direct-action");
        String attemptId = "direct-attempt";
        var reference = new ResultRef("direct-request");
        var capability = new ResultRef("direct-capability");
        var now = Instant.now();
        var lease = new LeaseFence(tenant, "direct-claim", "direct-worker", 3, now.plusSeconds(30));
        var run = new ExecutionStore.Run(identity, new ResultRef("direct-configuration"), RunStatus.RUNNING, 4, 3,
                false, ExecutionStore.Phase.AWAITING_RESULT, new ExecutionStore.Progress(0, 0, 0, new ResultRef("context")),
                Optional.of(lease), Optional.of(action), Optional.empty(), Optional.empty(), actor.verificationRef(), Optional.empty(), now, now);
        var prepared = new ExecutionStore.Prepared(action, 1, kind, target, capability,
                new ExecutionStore.Material(reference, request), "direct-fingerprint");
        var storedAction = new ExecutionStore.Action(action, identity.runRef(), 1, kind, target, capability, reference,
                prepared.fingerprint(), ExecutionStore.ActionStatus.DISPATCHED, attemptId, Optional.empty(), Optional.empty());
        var attempt = new ExecutionStore.Attempt(attemptId, action, identity.runRef(), 3, reference, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
        var dispatch = new ProviderDispatch(identity.runRef(), action, action.actionId(), attemptId, callKind,
                reference, capability, prepared.fingerprint(), 3);
        var context = ProviderCallContext.dispatched(new PortCallContext(identity, actor,
                new InvocationControl(now.plusSeconds(20), () -> false)), dispatch);
        return new RegisteredProviderFixture(new ExecutionStore.DispatchResult.Registered(run, storedAction, attempt, actor),
                prepared, context);
    }

    /** 同逻辑Action的新Attempt登记夹具；本轮请求与原Action请求必须保持不同引用。 */
    static RegisteredProviderFixture resume(RegisteredProviderFixture original, TypedPayload request) {
        var before = original.registered(); var run = before.run(); var action = before.action();
        var wait = new ExecutionStore.WaitRef(action.reference(), 1);
        var reference = new ResultRef("direct-resume-request");
        String attemptId = "direct-resume-attempt";
        var resumed = new ExecutionStore.Run(run.identity(), run.configuration(), run.status(), run.revision() + 1, run.fence(),
                false, run.phase(), run.progress(), run.lease(), run.currentAction(), run.currentBatch(), Optional.of(wait),
                before.actor().verificationRef(), run.nextDueAt(), run.createdAt(), run.updatedAt());
        var prepared = new ExecutionStore.Prepared(action.reference(), action.ordinal(), action.kind(), action.target(),
                action.capability(), new ExecutionStore.Material(reference, request), "direct-resume-fingerprint");
        var current = new ExecutionStore.Action(action.reference(), action.run(), action.ordinal(), action.kind(), action.target(),
                action.capability(), action.request(), action.fingerprint(), action.status(), attemptId, Optional.empty(), action.batchSlot());
        var attempt = new ExecutionStore.Attempt(attemptId, action.reference(), action.run(), run.fence(), reference, Optional.of(wait),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
        var dispatch = new ProviderDispatch(action.run(), action.reference(), action.reference().actionId(), attemptId,
                original.context().callKind(), reference, action.capability(), prepared.fingerprint(), run.fence());
        var context = ProviderCallContext.dispatched(original.context().callContext(), dispatch);
        return new RegisteredProviderFixture(new ExecutionStore.DispatchResult.Registered(resumed, current, attempt, before.actor()), prepared, context);
    }
}

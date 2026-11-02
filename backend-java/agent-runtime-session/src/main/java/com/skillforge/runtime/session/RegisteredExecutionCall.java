package com.skillforge.runtime.session;

import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.ProviderCallContext;
import com.skillforge.runtime.api.port.ProviderDispatch;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Objects;
import java.util.Optional;

/** 纯比对真实 Store 登记回执与实际调用；不查询、发送、计费或签发许可。 */
public final class RegisteredExecutionCall {
    private RegisteredExecutionCall() { }

    /**
     * 仅接受派发事务原样返回的 Registered，不能把 Existing 或已采纳的复用槽重新包装为发送权。
     * Store 拥有当前 IAM、挂载、预算和 manifest 校验；此处只阻止回执向另一个调用漂移。
     */
    public static ProviderDispatch require(ExecutionStore.DispatchResult.Registered registered,
            ExecutionStore.Prepared prepared, ProviderCallContext context, ExecutionStore.Kind kind) {
        Objects.requireNonNull(registered); Objects.requireNonNull(prepared); Objects.requireNonNull(context);
        Objects.requireNonNull(kind);
        var dispatch = context.dispatch();
        var run = Objects.requireNonNull(registered.run());
        var action = Objects.requireNonNull(registered.action());
        var attempt = Objects.requireNonNull(registered.attempt());
        boolean batch = action.batchSlot().isPresent();
        // 组成员只认原 Action 的不可变归属；不把复用 Action 的旧 batchSlot 改成当前组。
        boolean current = batch
                ? run.currentAction().isEmpty() && run.waiting().isEmpty()
                    && run.currentBatch().equals(Optional.of(action.batchSlot().orElseThrow().batch()))
                : run.currentBatch().isEmpty() && run.currentAction().equals(Optional.of(action.reference()));
        boolean continuation = attempt.continuation().isPresent();
        boolean currentWait = !continuation
                || (kind == ExecutionStore.Kind.TOOL || kind == ExecutionStore.Kind.WORKFLOW)
                    && attempt.continuation().orElseThrow().action().equals(action.reference())
                    && (batch || attempt.continuation().equals(run.waiting()));
        if (!run.identity().equals(context.callContext().runIdentity())
                || !registered.actor().equals(context.callContext().identity())
                || !registered.actor().verificationRef().equals(run.actorVerificationRef())
                || run.status() != RunStatus.RUNNING || run.cancelled()
                || run.phase() != ExecutionStore.Phase.AWAITING_RESULT || run.lease().isEmpty() || !current
                || !action.run().equals(run.identity().runRef()) || !action.reference().equals(prepared.action())
                || action.ordinal() != prepared.ordinal() || action.kind() != kind || prepared.kind() != kind
                || !action.target().equals(prepared.target()) || !action.capability().equals(prepared.capability())
                || !attempt.request().equals(prepared.request().reference())
                || !continuation && (!action.request().equals(prepared.request().reference())
                    || !action.fingerprint().equals(prepared.fingerprint()))
                || !currentWait || !continuation && run.waiting().isPresent()
                || action.status() != ExecutionStore.ActionStatus.DISPATCHED || action.selectedAttempt().isPresent()
                || !action.currentAttempt().equals(attempt.id()) || !attempt.action().equals(action.reference())
                || !attempt.run().equals(action.run()) || attempt.fence() != run.fence()
                || attempt.result().isPresent() || attempt.uncertainty().isPresent() || attempt.adopted()
                || !dispatch.run().equals(action.run()) || !dispatch.action().equals(action.reference())
                || !dispatch.invocationId().equals(action.reference().actionId()) || !dispatch.attemptId().equals(attempt.id())
                || !matches(kind, dispatch.callKind())
                || !dispatch.requestRef().equals(attempt.request()) || !dispatch.capabilityRef().equals(action.capability())
                || !dispatch.fingerprint().equals(prepared.fingerprint()) || dispatch.fence() != attempt.fence()) {
            throw new IllegalArgumentException("registered_execution_call_mismatch");
        }
        return dispatch;
    }

    private static boolean matches(ExecutionStore.Kind kind, PortalCallKind call) {
        return switch (kind) {
            case MODEL -> call == PortalCallKind.MODEL_GENERATE || call == PortalCallKind.MODEL_STREAM;
            case TOOL -> call == PortalCallKind.TOOL_INVOKE;
            case KNOWLEDGE -> call == PortalCallKind.KNOWLEDGE_EXECUTE;
            case WORKFLOW -> call == PortalCallKind.WORKFLOW_DISPATCH || call == PortalCallKind.WORKFLOW_RESUME
                    || call == PortalCallKind.WORKFLOW_RECOVER_RESULT || call == PortalCallKind.WORKFLOW_CANCEL;
            case DELEGATION -> false;
        };
    }
}

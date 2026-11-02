package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionMode;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.DelegationRef;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.CreateExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWait;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ProgressExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.RequestExecutionCancel;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ResumeExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.TransitionExecution;
import com.skillforge.runtime.api.port.LedgerEvent;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import java.time.Instant;
import java.util.Optional;

/** 从强类型执行事实生成与 JDBC 账本校验完全一致的命令及审计事件。 */
public final class ExecutionCommands {
    private ExecutionCommands() {}

    public static CreateExecution create(ExecutionRef ref, RunIdentity owner, String executionUnitId,
            String definitionVersion, ExecutionMode mode, Optional<ActionRef> action,
            Optional<DelegationRef> delegation, TypedPayload checkpoint, String fingerprint,
            EventId eventId, Instant at) {
        CreateExecution draft = new CreateExecution(ref, owner, executionUnitId, definitionVersion,
                mode, action, delegation, checkpoint, fingerprint, placeholder(owner, "execution.queued", eventId, at));
        ExecutionRecord output = new ExecutionRecord(ref, owner, executionUnitId, definitionVersion, mode,
                action, delegation, WorkflowExecutionState.QUEUED, 1, checkpoint, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), at, at);
        CreateExecution result = new CreateExecution(ref, owner, executionUnitId, definitionVersion, mode,
                action, delegation, checkpoint, fingerprint, ExecutionLedgerEventCodec.eventFor(draft, output, eventId, at));
        require(ExecutionLedgerEventCodec.validCreate(result, output));
        return result;
    }

    public static ProgressExecution progress(ExecutionRecord current, ExecutionLease lease,
            TypedPayload checkpoint, EventId eventId, Instant at) {
        requireLease(current, lease);
        if (current.state() != WorkflowExecutionState.RUNNING) throw new IllegalArgumentException("execution_not_running");
        ProgressExecution draft = new ProgressExecution(lease, current.executionRevision(), checkpoint,
                placeholder(current.owner(), "execution.step", eventId, at));
        ExecutionRecord output = output(current, current.state(), 1, checkpoint, current.waiting(),
                current.resultRef(), current.reasonCode(), current.cancelRequestId(), current.cancelRequestedAt());
        ProgressExecution result = new ProgressExecution(lease, current.executionRevision(), checkpoint,
                ExecutionLedgerEventCodec.eventFor(draft, output, eventId, at));
        require(ExecutionLedgerEventCodec.validProgress(result, output));
        return result;
    }

    public static TransitionExecution transition(ExecutionRecord current, ExecutionLease lease,
            WorkflowExecutionState target, TypedPayload checkpoint, Optional<ExecutionWait> waiting,
            Optional<ResultRef> resultRef, Optional<String> reasonCode, EventId eventId, Instant at) {
        requireLease(current, lease);
        String type = switch (target) {
            case RUNNING -> "execution.started";
            case WAITING_HITL -> "execution.hitl";
            case WAITING_EXTERNAL -> "execution.waiting";
            case COMPLETED -> "execution.completed";
            case FAILED -> "execution.failed";
            case LOOP_TERMINATED -> "execution.loop_terminated";
            case CANCELLED -> "execution.cancelled";
            default -> throw new IllegalArgumentException("invalid_execution_transition_target");
        };
        TransitionExecution draft = new TransitionExecution(lease, current.state(), current.executionRevision(),
                target, checkpoint, waiting, resultRef, reasonCode, placeholder(current.owner(), type, eventId, at));
        ExecutionRecord output = output(current, target, 1, checkpoint, waiting, resultRef, reasonCode,
                current.cancelRequestId(), current.cancelRequestedAt());
        TransitionExecution result = new TransitionExecution(lease, current.state(), current.executionRevision(),
                target, checkpoint, waiting, resultRef, reasonCode, ExecutionLedgerEventCodec.eventFor(draft, output, eventId, at));
        require(ExecutionLedgerEventCodec.validTransition(result, output));
        return result;
    }

    public static ResumeExecution resume(ExecutionRecord current, ExecutionLease lease,
            ExecutionWait expectedWait, TypedPayload checkpoint, EventId resumedId, EventId startedId, Instant at) {
        requireLease(current, lease);
        if (!current.state().isWaiting() || !current.waiting().equals(Optional.of(expectedWait))) {
            throw new IllegalArgumentException("execution_wait_mismatch");
        }
        ResumeExecution draft = new ResumeExecution(lease, current.executionRevision(), expectedWait.suspendRef(),
                expectedWait.actionKey(), checkpoint, placeholder(current.owner(), "execution.resumed", resumedId, at),
                placeholder(current.owner(), "execution.started", startedId, at), current.state());
        ExecutionRecord intermediate = output(current, WorkflowExecutionState.RESUMING, 1, checkpoint,
                Optional.empty(), Optional.empty(), Optional.empty(), current.cancelRequestId(), current.cancelRequestedAt());
        ExecutionRecord output = output(intermediate, WorkflowExecutionState.RUNNING, 1, checkpoint,
                Optional.empty(), Optional.empty(), Optional.empty(), current.cancelRequestId(), current.cancelRequestedAt());
        ResumeExecution result = new ResumeExecution(lease, current.executionRevision(), expectedWait.suspendRef(),
                expectedWait.actionKey(), checkpoint, ExecutionLedgerEventCodec.eventFor(draft, intermediate, resumedId, at),
                ExecutionLedgerEventCodec.eventFor(draft, output, startedId, at), current.state());
        require(ExecutionLedgerEventCodec.validResume(result, intermediate, output));
        return result;
    }

    public static RequestExecutionCancel cancel(ExecutionRecord current, VerifiedIdentity identity,
            InputId requestId, EventId eventId, Instant at) {
        if (!current.owner().owner().equals(identity.owner()) || terminal(current.state()) || current.cancelRequestId().isPresent()) {
            throw new IllegalArgumentException("execution_cancel_not_allowed");
        }
        RequestExecutionCancel draft = new RequestExecutionCancel(identity, current.executionRef(), current.executionRevision(),
                requestId, placeholder(current.owner(), "execution.cancel_requested", eventId, at));
        ExecutionRecord output = output(current, current.state(), 1, current.checkpoint(), current.waiting(),
                current.resultRef(), current.reasonCode(), Optional.of(requestId), Optional.of(at));
        RequestExecutionCancel result = new RequestExecutionCancel(identity, current.executionRef(), current.executionRevision(),
                requestId, ExecutionLedgerEventCodec.eventFor(draft, output, eventId, at));
        require(ExecutionLedgerEventCodec.validCancel(result, output));
        return result;
    }

    private static ExecutionRecord output(ExecutionRecord source, WorkflowExecutionState state, int revisions,
            TypedPayload checkpoint, Optional<ExecutionWait> waiting, Optional<ResultRef> result,
            Optional<String> reason, Optional<InputId> cancel, Optional<Instant> cancelAt) {
        return new ExecutionRecord(source.executionRef(), source.owner(), source.executionUnitId(), source.definitionVersion(),
                source.mode(), source.causativeActionRef(), source.delegationRef(), state,
                Math.addExact(source.executionRevision(), revisions), checkpoint, waiting, result, reason,
                terminal(state) ? Optional.empty() : source.lease(), cancel, cancelAt, source.createdAt(), source.updatedAt());
    }

    private static boolean terminal(WorkflowExecutionState state) {
        return switch (state) {
            case COMPLETED, FAILED, LOOP_TERMINATED, CANCELLED -> true;
            default -> false;
        };
    }

    private static void requireLease(ExecutionRecord current, ExecutionLease lease) {
        if (!current.executionRef().equals(lease.executionRef()) || current.lease().isEmpty()
                || !current.lease().get().claimId().equals(lease.fence().claimId())
                || !current.lease().get().workerId().equals(lease.fence().workerId())
                || current.lease().get().fencingToken() != lease.fence().fencingToken()) {
            throw new IllegalArgumentException("execution_lease_mismatch");
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("execution_command_encoding_failed");
    }

    /** 仅用于获取命令字段；该占位事件永不返回或提交给账本。 */
    private static LedgerEvent placeholder(RunIdentity owner, String type, EventId id, Instant at) {
        TypedPayload empty = new TypedPayload("internal.execution-command-placeholder", "{}");
        return new LedgerEvent(new SessionEventDraft(id, type, "1", "internal", owner.trace(), empty, at), type, "internal", empty);
    }
}

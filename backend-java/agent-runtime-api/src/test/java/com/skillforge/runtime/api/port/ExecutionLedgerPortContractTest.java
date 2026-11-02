package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.PrincipalRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionMode;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 冻结 ExecutionLedgerPort 的公开 ABI、构造期闭包与专属状态入口。 */
class ExecutionLedgerPortContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final TenantId OTHER_TENANT = new TenantId("tenant-2");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final EndUserId END_USER = new EndUserId(7);
    private static final SessionOwner SESSION_OWNER = SessionOwner.user(SESSION, AGENT, END_USER);
    private static final InputId INPUT = new InputId("input-1");
    private static final RunRef RUN = new RunRef(TENANT, "run-1");
    private static final ExecutionRef EXECUTION = new ExecutionRef(TENANT, "execution-1");
    private static final ResultRef IDENTITY_REF = new ResultRef("identity-1");
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    /** 冻结完整 13 方法闭包、泛型返回、record 字段顺序和 sealed permits。 */
    @Test
    void freezesExecutionLedgerPortAbi() throws ReflectiveOperationException {
        assertExactPortMethods(ExecutionLedgerPort.class, List.of(
                "acknowledgeStateEvent(PublicationAcknowledge):PublicationMutationResult",
                "acquireLease(ExecutionLeaseRequest):ExecutionLeaseResult",
                "claimStateEvents(PublicationClaimRequest):java.util.List<StateEventClaim>",
                "create(CreateExecution):ExecutionWriteResult",
                "find(ExecutionLookup):java.util.Optional<ExecutionRecord>",
                "progress(ProgressExecution):ExecutionWriteResult",
                "release(ExecutionLease):LeaseMutationStatus",
                "renew(ExecutionLease,java.time.Duration):LeaseMutationStatus",
                "requestCancel(RequestExecutionCancel):ExecutionCancelResult",
                "resume(ResumeExecution):ExecutionWriteResult",
                "retryStateEvent(PublicationRetry):PublicationMutationResult",
                "scanRecoverable(ExecutionRecoveryScan):java.util.List<ExecutionRecoveryCandidate>",
                "transition(TransitionExecution):ExecutionWriteResult"));
        assertGenericReturn(
                ExecutionLedgerPort.class.getMethod(
                        "find", ExecutionLedgerPort.ExecutionLookup.class),
                Optional.class,
                ExecutionLedgerPort.ExecutionRecord.class);
        assertGenericReturn(
                ExecutionLedgerPort.class.getMethod(
                        "scanRecoverable", ExecutionLedgerPort.ExecutionRecoveryScan.class),
                List.class,
                ExecutionLedgerPort.ExecutionRecoveryCandidate.class);
        assertGenericReturn(
                ExecutionLedgerPort.class.getMethod(
                        "claimStateEvents", PublicationClaimRequest.class),
                List.class,
                StateEventClaim.class);

        assertEquals(List.of(
                        "executionRef:ExecutionRef",
                        "owner:RunIdentity",
                        "executionUnitId:String",
                        "definitionVersion:String",
                        "mode:ExecutionMode",
                        "causativeActionRef:java.util.Optional<ActionRef>",
                        "delegationRef:java.util.Optional<DelegationRef>",
                        "state:WorkflowExecutionState",
                        "executionRevision:long",
                        "checkpoint:TypedPayload",
                        "waiting:java.util.Optional<ExecutionWait>",
                        "resultRef:java.util.Optional<ResultRef>",
                        "reasonCode:java.util.Optional<String>",
                        "lease:java.util.Optional<LeaseFence>",
                        "cancelRequestId:java.util.Optional<InputId>",
                        "cancelRequestedAt:java.util.Optional<java.time.Instant>",
                        "createdAt:java.time.Instant",
                        "updatedAt:java.time.Instant"),
                componentSignatures(ExecutionLedgerPort.ExecutionRecord.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef",
                        "owner:RunIdentity",
                        "executionUnitId:String",
                        "definitionVersion:String",
                        "mode:ExecutionMode",
                        "causativeActionRef:java.util.Optional<ActionRef>",
                        "delegationRef:java.util.Optional<DelegationRef>",
                        "initialCheckpoint:TypedPayload",
                        "requestFingerprint:String",
                        "queuedEvent:LedgerEvent"),
                componentSignatures(ExecutionLedgerPort.CreateExecution.class));
        assertEquals(List.of("suspendRef:String", "actionKey:String"),
                componentSignatures(ExecutionLedgerPort.ExecutionWait.class));
        assertEquals(List.of("identity:VerifiedIdentity", "executionRef:ExecutionRef"),
                componentSignatures(ExecutionLedgerPort.ExecutionLookup.class));
        assertEquals(List.of(
                        "identity:VerifiedIdentity",
                        "executionRef:ExecutionRef",
                        "workerId:String",
                        "leaseDuration:java.time.Duration",
                        "requestedAt:java.time.Instant"),
                componentSignatures(ExecutionLedgerPort.ExecutionLeaseRequest.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef",
                        "fence:LeaseFence",
                        "executionRevision:long"),
                componentSignatures(ExecutionLedgerPort.ExecutionLease.class));
        assertEquals(List.of(
                        "lease:ExecutionLease",
                        "expectedRevision:long",
                        "checkpoint:TypedPayload",
                        "stepEvent:LedgerEvent"),
                componentSignatures(ExecutionLedgerPort.ProgressExecution.class));
        assertEquals(List.of(
                        "lease:ExecutionLease",
                        "expectedState:WorkflowExecutionState",
                        "expectedRevision:long",
                        "targetState:WorkflowExecutionState",
                        "checkpoint:TypedPayload",
                        "waiting:java.util.Optional<ExecutionWait>",
                        "resultRef:java.util.Optional<ResultRef>",
                        "reasonCode:java.util.Optional<String>",
                        "event:LedgerEvent"),
                componentSignatures(ExecutionLedgerPort.TransitionExecution.class));
        assertEquals(List.of(
                        "lease:ExecutionLease",
                        "expectedRevision:long",
                        "expectedSuspendRef:String",
                        "expectedActionKey:String",
                        "checkpoint:TypedPayload",
                        "resumedEvent:LedgerEvent",
                        "startedEvent:LedgerEvent", "expectedState:WorkflowExecutionState"),
                componentSignatures(ExecutionLedgerPort.ResumeExecution.class));
        assertEquals(List.of(
                        "identity:VerifiedIdentity",
                        "executionRef:ExecutionRef",
                        "expectedRevision:long",
                        "cancelRequestId:InputId",
                        "cancelEvent:LedgerEvent"),
                componentSignatures(ExecutionLedgerPort.RequestExecutionCancel.class));
        assertEquals(List.of("tenantId:TenantId", "limit:int", "afterExecutionId:java.util.Optional<String>"),
                componentSignatures(ExecutionLedgerPort.ExecutionRecoveryScan.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef",
                        "owner:RunIdentity",
                        "state:WorkflowExecutionState",
                        "executionRevision:long",
                        "leaseUntil:java.util.Optional<java.time.Instant>",
                        "cancelRequestId:java.util.Optional<InputId>"),
                componentSignatures(ExecutionLedgerPort.ExecutionRecoveryCandidate.class));

        assertResultRecordAbi();
        assertEquals(Set.of(
                        ExecutionLedgerPort.ExecutionLeaseResult.Acquired.class,
                        ExecutionLedgerPort.ExecutionLeaseResult.Busy.class,
                        ExecutionLedgerPort.ExecutionLeaseResult.Rejected.class),
                Set.of(ExecutionLedgerPort.ExecutionLeaseResult.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                        ExecutionLedgerPort.ExecutionCancelResult.Applied.class,
                        ExecutionLedgerPort.ExecutionCancelResult.Replayed.class,
                        ExecutionLedgerPort.ExecutionCancelResult.Conflict.class,
                        ExecutionLedgerPort.ExecutionCancelResult.NotFound.class,
                        ExecutionLedgerPort.ExecutionCancelResult.Rejected.class),
                Set.of(ExecutionLedgerPort.ExecutionCancelResult.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                        ExecutionLedgerPort.ExecutionWriteResult.Applied.class,
                        ExecutionLedgerPort.ExecutionWriteResult.Replayed.class,
                        ExecutionLedgerPort.ExecutionWriteResult.Conflict.class,
                        ExecutionLedgerPort.ExecutionWriteResult.LeaseLost.class,
                        ExecutionLedgerPort.ExecutionWriteResult.Rejected.class),
                Set.of(ExecutionLedgerPort.ExecutionWriteResult.class.getPermittedSubclasses()));
    }

    /** 验证 ExecutionRecord 的状态字段、终态 lease、租户、取消字段与时间闭包。 */
    @Test
    void validatesExecutionRecordStateAndLeaseMatrix() {
        for (WorkflowExecutionState state : WorkflowExecutionState.values()) {
            Optional<ExecutionLedgerPort.ExecutionWait> waiting =
                    state.isWaiting()
                            ? Optional.of(waiting()) : Optional.empty();
            Optional<ResultRef> resultRef = needsResult(state)
                    ? Optional.of(new ResultRef("result-1")) : Optional.empty();
            Optional<String> reasonCode = needsReason(state)
                    ? Optional.of("diagnostic") : Optional.empty();
            Optional<LeaseFence> lease = isTerminal(state)
                    ? Optional.empty() : Optional.of(fence(TENANT));
            ExecutionLedgerPort.ExecutionRecord record = record(
                    owner(), state, waiting, resultRef, reasonCode, lease,
                    Optional.empty(), Optional.empty(), NOW, NOW);
            assertEquals(state, record.state());
        }

        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.WAITING_HITL, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.RUNNING, Optional.of(waiting()),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.COMPLETED, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.FAILED, Optional.empty(),
                Optional.of(new ResultRef("result-1")), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.CANCELLED, Optional.empty(),
                Optional.of(new ResultRef("result-1")), Optional.empty(),
                Optional.of(fence(TENANT)), Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.RUNNING, Optional.empty(),
                Optional.empty(), Optional.of(" "), Optional.empty(),
                Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.RUNNING, null,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLedgerPort.ExecutionRecord(
                EXECUTION, owner(), "workflow", "v1", ExecutionMode.FOREGROUND,
                Optional.empty(), Optional.empty(), WorkflowExecutionState.RUNNING, 0,
                checkpoint(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLedgerPort.ExecutionRecord(
                EXECUTION, owner(), " ", "v1", ExecutionMode.FOREGROUND,
                Optional.empty(), Optional.empty(), WorkflowExecutionState.RUNNING, 1,
                checkpoint(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLedgerPort.ExecutionRecord(
                EXECUTION, owner(), "workflow", "v1", ExecutionMode.FOREGROUND,
                Optional.empty(), Optional.empty(), WorkflowExecutionState.RUNNING, 1,
                null, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.RUNNING, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(fence(OTHER_TENANT)),
                Optional.empty(), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.RUNNING, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new InputId("cancel-1")), Optional.empty(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.RUNNING, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(NOW), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> record(
                owner(), WorkflowExecutionState.RUNNING, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), NOW, NOW.minusSeconds(1)));
    }

    /** 验证 Create 只接受同域身份、非空定义、匹配 trace 与 execution.queued 事件。 */
    @Test
    void validatesCreateExecutionIdentityAndQueuedEvent() {
        ExecutionLedgerPort.CreateExecution command = new ExecutionLedgerPort.CreateExecution(
                EXECUTION, owner(), "workflow", "definition-v1", ExecutionMode.BACKGROUND,
                Optional.of(new ActionRef(TENANT, "action-1")),
                Optional.of(new DelegationRef(TENANT, "delegation-1")),
                checkpoint(), "fingerprint-1", event("execution.queued", trace()));
        assertEquals(ExecutionMode.BACKGROUND, command.mode());
        assertEquals("execution.queued", command.queuedEvent().sessionEvent().eventType());

        assertThrows(IllegalArgumentException.class, () -> create(
                new ExecutionRef(OTHER_TENANT, "execution-other"), owner(),
                Optional.empty(), Optional.empty(), event("execution.queued", trace())));
        assertThrows(IllegalArgumentException.class, () -> create(
                EXECUTION, owner(), Optional.of(new ActionRef(OTHER_TENANT, "action-other")),
                Optional.empty(), event("execution.queued", trace())));
        assertThrows(IllegalArgumentException.class, () -> create(
                EXECUTION, owner(), Optional.empty(),
                Optional.of(new DelegationRef(OTHER_TENANT, "delegation-other")),
                event("execution.queued", trace())));
        assertThrows(IllegalArgumentException.class, () -> create(
                EXECUTION, owner(), Optional.empty(), Optional.empty(),
                event("execution.started", trace())));
        assertThrows(IllegalArgumentException.class, () -> create(
                EXECUTION, owner(), Optional.empty(), Optional.empty(),
                event("execution.queued", otherTrace())));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLedgerPort.CreateExecution(
                EXECUTION, owner(), "workflow", "definition-v1", ExecutionMode.FOREGROUND,
                null, Optional.empty(), checkpoint(), "fingerprint-1",
                event("execution.queued", trace())));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLedgerPort.CreateExecution(
                EXECUTION, owner(), "workflow", "definition-v1", ExecutionMode.FOREGROUND,
                Optional.empty(), Optional.empty(), null, "fingerprint-1",
                event("execution.queued", trace())));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLedgerPort.CreateExecution(
                EXECUTION, owner(), "workflow", "definition-v1", ExecutionMode.FOREGROUND,
                Optional.empty(), Optional.empty(), checkpoint(), " ",
                event("execution.queued", trace())));
    }

    /** 验证查询、租约请求、租约与 Acquired 结果的 tenant/ref/revision/fence 闭包。 */
    @Test
    void validatesLookupAndLeaseBoundary() {
        assertEquals(EXECUTION,
                new ExecutionLedgerPort.ExecutionLookup(identity(), EXECUTION).executionRef());
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionLookup(
                        identity(), new ExecutionRef(OTHER_TENANT, "execution-other")));

        ExecutionLedgerPort.ExecutionLeaseRequest request =
                new ExecutionLedgerPort.ExecutionLeaseRequest(
                        identity(), EXECUTION, "worker-1", Duration.ofSeconds(30), NOW);
        assertEquals("worker-1", request.workerId());
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionLeaseRequest(
                        identity(), EXECUTION, "worker-1", Duration.ZERO, NOW));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionLeaseRequest(
                        identity(), EXECUTION, " ", Duration.ofSeconds(30), NOW));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionLeaseRequest(
                        identity(), EXECUTION, "worker-1", Duration.ofSeconds(30), null));

        ExecutionLedgerPort.ExecutionLease lease = lease(3);
        ExecutionLedgerPort.ExecutionRecord leased = record(
                owner(), WorkflowExecutionState.RUNNING, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(lease.fence()),
                Optional.empty(), Optional.empty(), NOW, NOW, 3);
        assertEquals(lease,
                new ExecutionLedgerPort.ExecutionLeaseResult.Acquired(leased, lease).lease());
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionLease(
                        EXECUTION, fence(OTHER_TENANT), 3));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionLease(EXECUTION, fence(TENANT), 0));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionLeaseResult.Acquired(leased, lease(2)));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionLeaseResult.Acquired(
                        record(owner(), WorkflowExecutionState.RUNNING, Optional.empty(),
                                Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.empty(), NOW, NOW, 3),
                        lease));
    }

    /** 验证 progress 是唯一 RUNNING 自环入口，且不把 expected revision 绑死领取 revision。 */
    @Test
    void validatesProgressExecution() {
        ExecutionLedgerPort.ProgressExecution progress =
                new ExecutionLedgerPort.ProgressExecution(
                        lease(1), 7, checkpoint(), event("execution.step", trace()));
        assertEquals(7, progress.expectedRevision());
        assertEquals("execution.step", progress.stepEvent().sessionEvent().eventType());
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ProgressExecution(
                        lease(1), 0, checkpoint(), event("execution.step", trace())));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ProgressExecution(
                        lease(1), Long.MAX_VALUE, checkpoint(),
                        event("execution.step", trace())));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ProgressExecution(
                        lease(1), 7, null, event("execution.step", trace())));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ProgressExecution(
                        lease(1), 7, checkpoint(), event("execution.started", trace())));
    }

    /** 验证全部且仅有八条普通状态边，以及每个 target 的唯一公开事件类型。 */
    @Test
    void enforcesExactExecutionTransitionMatrix() {
        for (WorkflowExecutionState expected : WorkflowExecutionState.values()) {
            for (WorkflowExecutionState target : WorkflowExecutionState.values()) {
                if (isAllowed(expected, target)) {
                    ExecutionLedgerPort.TransitionExecution transition =
                            transition(expected, target, 7, eventType(target));
                    assertEquals(target, transition.targetState());
                    assertEquals(7, transition.expectedRevision());
                } else {
                    assertThrows(IllegalArgumentException.class,
                            () -> transition(expected, target, 7, "unregistered.transition"),
                            expected + " -> " + target);
                }
            }
        }

        assertThrows(IllegalArgumentException.class, () -> transition(
                WorkflowExecutionState.RUNNING,
                WorkflowExecutionState.COMPLETED,
                7,
                "execution.failed"));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.TransitionExecution(
                        lease(1), WorkflowExecutionState.RUNNING, 7,
                        WorkflowExecutionState.WAITING_HITL, checkpoint(), Optional.empty(),
                        Optional.empty(), Optional.empty(), event("execution.hitl", trace())));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.TransitionExecution(
                        lease(1), WorkflowExecutionState.RUNNING, 7,
                        WorkflowExecutionState.COMPLETED, checkpoint(), Optional.of(waiting()),
                        Optional.of(new ResultRef("result-1")), Optional.empty(),
                        event("execution.completed", trace())));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.TransitionExecution(
                        lease(1), WorkflowExecutionState.RUNNING, 0,
                        WorkflowExecutionState.COMPLETED, checkpoint(), Optional.empty(),
                        Optional.of(new ResultRef("result-1")), Optional.empty(),
                        event("execution.completed", trace())));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.TransitionExecution(
                        lease(1), WorkflowExecutionState.RUNNING, 7,
                        WorkflowExecutionState.COMPLETED, checkpoint(), Optional.empty(),
                        Optional.of(new ResultRef("result-1")), Optional.of(" "),
                        event("execution.completed", trace())));

        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.TransitionExecution(
                        lease(1), WorkflowExecutionState.RUNNING, Long.MAX_VALUE,
                        WorkflowExecutionState.COMPLETED, checkpoint(), Optional.empty(),
                        Optional.of(new ResultRef("result-1")), Optional.empty(),
                        event("execution.completed", trace())));

        ExecutionLedgerPort.TransitionExecution completed =
                new ExecutionLedgerPort.TransitionExecution(
                        lease(1), WorkflowExecutionState.RUNNING, 7,
                        WorkflowExecutionState.COMPLETED, checkpoint(), Optional.empty(),
                        Optional.of(new ResultRef("result-1")), Optional.empty(),
                        event("execution.completed", trace()));
        assertEquals("result-1", completed.resultRef().orElseThrow().value());
    }

    /** 验证原子 resume 的旧 wait 身份、+2 revision 安全边界和两类公开事件。 */
    @Test
    void validatesAtomicResumeCommand() {
        ExecutionLedgerPort.ResumeExecution resume = new ExecutionLedgerPort.ResumeExecution(
                lease(1), 7, "suspend-1", "action-key-1", checkpoint(),
                event(new EventId("resume-event-1"), "execution.resumed", trace()),
                event(new EventId("resume-event-2"), "execution.started", trace()), WorkflowExecutionState.WAITING_HITL);
        assertEquals(7, resume.expectedRevision());
        assertEquals("execution.resumed", resume.resumedEvent().sessionEvent().eventType());
        assertEquals("execution.started", resume.startedEvent().sessionEvent().eventType());

        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ResumeExecution(
                        lease(1), 0, "suspend-1", "action-key-1", checkpoint(),
                        event("execution.resumed", trace()),
                        event("execution.started", trace()), WorkflowExecutionState.WAITING_HITL));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ResumeExecution(
                        lease(1), Long.MAX_VALUE - 1, "suspend-1", "action-key-1", checkpoint(),
                        event("execution.resumed", trace()),
                        event("execution.started", trace()), WorkflowExecutionState.WAITING_HITL));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ResumeExecution(
                        lease(1), 7, " ", "action-key-1", checkpoint(),
                        event("execution.resumed", trace()),
                        event("execution.started", trace()), WorkflowExecutionState.WAITING_HITL));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ResumeExecution(
                        lease(1), 7, "suspend-1", "action-key-1", checkpoint(),
                        event("execution.started", trace()),
                        event("execution.started", trace()), WorkflowExecutionState.WAITING_HITL));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ResumeExecution(
                        lease(1), 7, "suspend-1", "action-key-1", checkpoint(),
                        event("execution.resumed", trace()),
                        event("execution.resumed", trace()), WorkflowExecutionState.WAITING_HITL));
        EventId sharedEventId = new EventId("resume-event-shared");
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ResumeExecution(
                        lease(1), 7, "suspend-1", "action-key-1", checkpoint(),
                        event(sharedEventId, "execution.resumed", trace()),
                        event(sharedEventId, "execution.started", trace()), WorkflowExecutionState.WAITING_HITL));
    }

    /** 验证取消意图是无 lease 的同租户 CAS，并绑定公开 cancel_requested 事件。 */
    @Test
    void validatesRegisteredCancelIntentEvent() {
        ExecutionLedgerPort.RequestExecutionCancel cancel =
                new ExecutionLedgerPort.RequestExecutionCancel(
                        identity(), EXECUTION, 7, new InputId("cancel-1"),
                        event("execution.cancel_requested", trace()));
        assertEquals(
                "execution.cancel_requested",
                cancel.cancelEvent().sessionEvent().eventType());
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.RequestExecutionCancel(
                        identity(), new ExecutionRef(OTHER_TENANT, "execution-other"), 7,
                        new InputId("cancel-1"),
                        event("execution.cancel_requested", trace())));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.RequestExecutionCancel(
                        identity(), EXECUTION, 0, new InputId("cancel-1"),
                        event("execution.cancel_requested", trace())));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.RequestExecutionCancel(
                        identity(), EXECUTION, Long.MAX_VALUE, new InputId("cancel-1"),
                        event("execution.cancel_requested", trace())));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.RequestExecutionCancel(
                        identity(), EXECUTION, 7, new InputId("cancel-1"),
                        event("execution.cancelled", trace())));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.RequestExecutionCancel(
                        identity(), EXECUTION, 7, new InputId("cancel-1"), null));
    }

    /** 验证三类结果联合都拒绝缺失事实，但不臆造 mutation 后的状态矩阵。 */
    @Test
    void validatesExecutionResultUnions() {
        ExecutionLedgerPort.ExecutionRecord current = record(
                owner(), WorkflowExecutionState.RUNNING, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), NOW, NOW);
        PortFailure failure = failure();

        assertEquals(current,
                new ExecutionLedgerPort.ExecutionLeaseResult.Busy(current).execution());
        assertEquals(failure,
                new ExecutionLedgerPort.ExecutionLeaseResult.Rejected(failure).failure());
        assertEquals(current,
                new ExecutionLedgerPort.ExecutionCancelResult.Applied(current).execution());
        assertEquals(current,
                new ExecutionLedgerPort.ExecutionCancelResult.Replayed(current).execution());
        assertEquals(current,
                new ExecutionLedgerPort.ExecutionCancelResult.Conflict(current).current());
        assertEquals(failure,
                new ExecutionLedgerPort.ExecutionCancelResult.NotFound(failure).failure());
        assertEquals(failure,
                new ExecutionLedgerPort.ExecutionCancelResult.Rejected(failure).failure());
        assertEquals(current,
                new ExecutionLedgerPort.ExecutionWriteResult.Applied(current).execution());
        assertEquals(current,
                new ExecutionLedgerPort.ExecutionWriteResult.Replayed(current).execution());
        assertEquals(current,
                new ExecutionLedgerPort.ExecutionWriteResult.Conflict(current).current());
        assertEquals(failure,
                new ExecutionLedgerPort.ExecutionWriteResult.LeaseLost(failure).failure());
        assertEquals(failure,
                new ExecutionLedgerPort.ExecutionWriteResult.Rejected(failure).failure());

        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionLeaseResult.Busy(null));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionLeaseResult.Rejected(null));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionCancelResult.Applied(null));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionCancelResult.NotFound(null));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionWriteResult.Conflict(null));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionWriteResult.LeaseLost(null));
    }

    /** 验证恢复扫描是 discovery，并拒绝终态、跨租户或无效 revision 候选。 */
    @Test
    void validatesRecoveryDiscoveryBoundary() {
        ExecutionLedgerPort.ExecutionRecoveryScan scan =
                new ExecutionLedgerPort.ExecutionRecoveryScan(TENANT, 20, Optional.empty());
        assertEquals(20, scan.limit());
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionRecoveryScan(TENANT, 0, Optional.empty()));

        for (WorkflowExecutionState state : List.of(
                WorkflowExecutionState.QUEUED,
                WorkflowExecutionState.RUNNING,
                WorkflowExecutionState.WAITING_HITL,
                WorkflowExecutionState.RESUMING)) {
            ExecutionLedgerPort.ExecutionRecoveryCandidate candidate =
                    new ExecutionLedgerPort.ExecutionRecoveryCandidate(
                            EXECUTION, owner(), state, 7, Optional.empty(), Optional.empty());
            assertEquals(state, candidate.state());
        }
        for (WorkflowExecutionState state : List.of(
                WorkflowExecutionState.COMPLETED,
                WorkflowExecutionState.FAILED,
                WorkflowExecutionState.LOOP_TERMINATED,
                WorkflowExecutionState.CANCELLED)) {
            assertThrows(IllegalArgumentException.class, () ->
                    new ExecutionLedgerPort.ExecutionRecoveryCandidate(
                            EXECUTION, owner(), state, 7,
                            Optional.empty(), Optional.empty()));
        }
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionRecoveryCandidate(
                        new ExecutionRef(OTHER_TENANT, "execution-other"), owner(),
                        WorkflowExecutionState.RUNNING, 7,
                        Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionRecoveryCandidate(
                        EXECUTION, owner(), WorkflowExecutionState.RUNNING, 0,
                        Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionLedgerPort.ExecutionRecoveryCandidate(
                        EXECUTION, owner(), WorkflowExecutionState.RUNNING, 7,
                        null, Optional.empty()));
    }

    /** 冻结所有 result leaf record 的字段名称与类型。 */
    private static void assertResultRecordAbi() {
        assertEquals(List.of("execution:ExecutionRecord", "lease:ExecutionLease"),
                componentSignatures(ExecutionLedgerPort.ExecutionLeaseResult.Acquired.class));
        assertEquals(List.of("execution:ExecutionRecord"),
                componentSignatures(ExecutionLedgerPort.ExecutionLeaseResult.Busy.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(ExecutionLedgerPort.ExecutionLeaseResult.Rejected.class));

        assertEquals(List.of("execution:ExecutionRecord"),
                componentSignatures(ExecutionLedgerPort.ExecutionCancelResult.Applied.class));
        assertEquals(List.of("execution:ExecutionRecord"),
                componentSignatures(ExecutionLedgerPort.ExecutionCancelResult.Replayed.class));
        assertEquals(List.of("current:ExecutionRecord"),
                componentSignatures(ExecutionLedgerPort.ExecutionCancelResult.Conflict.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(ExecutionLedgerPort.ExecutionCancelResult.NotFound.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(ExecutionLedgerPort.ExecutionCancelResult.Rejected.class));

        assertEquals(List.of("execution:ExecutionRecord"),
                componentSignatures(ExecutionLedgerPort.ExecutionWriteResult.Applied.class));
        assertEquals(List.of("execution:ExecutionRecord"),
                componentSignatures(ExecutionLedgerPort.ExecutionWriteResult.Replayed.class));
        assertEquals(List.of("current:ExecutionRecord"),
                componentSignatures(ExecutionLedgerPort.ExecutionWriteResult.Conflict.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(ExecutionLedgerPort.ExecutionWriteResult.LeaseLost.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(ExecutionLedgerPort.ExecutionWriteResult.Rejected.class));
    }

    /** 构造可用于全部正例的 Execution owner。 */
    private static RunIdentity owner() {
        return new RunIdentity(SESSION_OWNER, SESSION_OWNER.target().requireAgent(), INPUT, RUN, trace(), IDENTITY_REF, new com.skillforge.runtime.api.RunRequester.EndUser(SESSION_OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION_OWNER.sessionRef().tenantId(), INPUT.value()));
    }

    /** 构造本次入口已经验证的同域身份。 */
    private static VerifiedIdentity identity() {
        return new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, END_USER),
                SESSION_OWNER,
                Set.of("workflow:execute"),
                1,
                IDENTITY_REF);
    }

    /** 构造稳定 Workflow checkpoint。 */
    private static TypedPayload checkpoint() {
        return new TypedPayload("workflow.checkpoint.v1", "{\"node\":1}");
    }

    /** 构造当前 Session/Run 的 trace。 */
    private static TraceEnvelope trace() {
        return new TraceEnvelope("trace-1", "span-1", INPUT, Optional.empty());
    }

    /** 构造用于验证 create trace 闭包的不同 trace。 */
    private static TraceEnvelope otherTrace() {
        return new TraceEnvelope(
                "trace-other", "span-other", new InputId("input-other"), Optional.empty());
    }

    /** 构造已命名公开事件及其私有 ledger 载荷。 */
    private static LedgerEvent event(String eventType, TraceEnvelope trace) {
        return event(new EventId(eventType + "-event"), eventType, trace);
    }

    /** 构造带指定稳定 EventId 的公开事件。 */
    private static LedgerEvent event(
            EventId eventId, String eventType, TraceEnvelope trace) {
        return new LedgerEvent(
                new SessionEventDraft(
                        eventId,
                        eventType,
                        "v1",
                        "session-fingerprint-" + eventType,
                        trace,
                        new TypedPayload("session.event.v1", "{}"),
                        NOW),
                "ledger." + eventType,
                "ledger-fingerprint-" + eventType,
                new TypedPayload("ledger.event.v1", "{}"));
    }

    /** 构造同 tenant 的 fencing lease。 */
    private static LeaseFence fence(TenantId tenantId) {
        return new LeaseFence(
                tenantId, "claim-1", "worker-1", 1, NOW.plusSeconds(30));
    }

    /** 构造指定领取 revision 的 Execution 写凭据。 */
    private static ExecutionLedgerPort.ExecutionLease lease(long revision) {
        return new ExecutionLedgerPort.ExecutionLease(EXECUTION, fence(TENANT), revision);
    }

    /** 构造 WAITING_HITL 的稳定恢复身份。 */
    private static ExecutionLedgerPort.ExecutionWait waiting() {
        return new ExecutionLedgerPort.ExecutionWait("suspend-1", "action-key-1");
    }

    /** 构造带稳定审计引用的 Port 失败。 */
    private static PortFailure failure() {
        return new PortFailure(
                "storage_failure", RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                new ResultRef("failure-1"));
    }

    /** 构造标准 ExecutionRecord。 */
    private static ExecutionLedgerPort.ExecutionRecord record(
            RunIdentity owner,
            WorkflowExecutionState state,
            Optional<ExecutionLedgerPort.ExecutionWait> waiting,
            Optional<ResultRef> resultRef,
            Optional<String> reasonCode,
            Optional<LeaseFence> lease,
            Optional<InputId> cancelRequestId,
            Optional<Instant> cancelRequestedAt,
            Instant createdAt,
            Instant updatedAt) {
        return record(
                owner, state, waiting, resultRef, reasonCode, lease,
                cancelRequestId, cancelRequestedAt, createdAt, updatedAt, 7);
    }

    /** 构造带指定 revision 的 ExecutionRecord。 */
    private static ExecutionLedgerPort.ExecutionRecord record(
            RunIdentity owner,
            WorkflowExecutionState state,
            Optional<ExecutionLedgerPort.ExecutionWait> waiting,
            Optional<ResultRef> resultRef,
            Optional<String> reasonCode,
            Optional<LeaseFence> lease,
            Optional<InputId> cancelRequestId,
            Optional<Instant> cancelRequestedAt,
            Instant createdAt,
            Instant updatedAt,
            long revision) {
        return new ExecutionLedgerPort.ExecutionRecord(
                EXECUTION, owner, "workflow", "definition-v1", ExecutionMode.FOREGROUND,
                Optional.empty(), Optional.empty(), state, revision, checkpoint(), waiting,
                resultRef, reasonCode, lease, cancelRequestId, cancelRequestedAt,
                createdAt, updatedAt);
    }

    /** 构造 CreateExecution 以集中表达身份/event 负例。 */
    private static ExecutionLedgerPort.CreateExecution create(
            ExecutionRef executionRef,
            RunIdentity owner,
            Optional<ActionRef> actionRef,
            Optional<DelegationRef> delegationRef,
            LedgerEvent event) {
        return new ExecutionLedgerPort.CreateExecution(
                executionRef, owner, "workflow", "definition-v1", ExecutionMode.FOREGROUND,
                actionRef, delegationRef, checkpoint(), "fingerprint-1", event);
    }

    /** 构造普通 transition，waiting 仅随 WAITING_HITL 目标出现。 */
    private static ExecutionLedgerPort.TransitionExecution transition(
            WorkflowExecutionState expected,
            WorkflowExecutionState target,
            long revision,
            String eventType) {
        return new ExecutionLedgerPort.TransitionExecution(
                lease(1), expected, revision, target, checkpoint(),
                target.isWaiting()
                        ? Optional.of(waiting()) : Optional.empty(),
                needsResult(target) ? Optional.of(new ResultRef("result-1")) : Optional.empty(),
                needsReason(target) ? Optional.of("diagnostic") : Optional.empty(),
                event(eventType, trace()));
    }

    private static boolean needsResult(WorkflowExecutionState state) {
        return switch (state) {
            case WAITING_HITL, WAITING_EXTERNAL, COMPLETED, FAILED, LOOP_TERMINATED, CANCELLED -> true;
            case QUEUED, RUNNING, RESUMING -> false;
        };
    }

    private static boolean needsReason(WorkflowExecutionState state) {
        return state == WorkflowExecutionState.FAILED
                || state == WorkflowExecutionState.LOOP_TERMINATED;
    }

    private static boolean isTerminal(WorkflowExecutionState state) {
        return state == WorkflowExecutionState.COMPLETED
                || state == WorkflowExecutionState.FAILED
                || state == WorkflowExecutionState.LOOP_TERMINATED
                || state == WorkflowExecutionState.CANCELLED;
    }

    /** 镜像 §6.2 与 §7.6.4 冻结的八条普通状态边。 */
    private static boolean isAllowed(
            WorkflowExecutionState expected, WorkflowExecutionState target) {
        return switch (expected) {
            case QUEUED -> Set.of(
                    WorkflowExecutionState.RUNNING,
                    WorkflowExecutionState.CANCELLED).contains(target);
            case RUNNING -> Set.of(
                    WorkflowExecutionState.WAITING_HITL,
                    WorkflowExecutionState.WAITING_EXTERNAL,
                    WorkflowExecutionState.COMPLETED,
                    WorkflowExecutionState.FAILED,
                    WorkflowExecutionState.LOOP_TERMINATED,
                    WorkflowExecutionState.CANCELLED).contains(target);
            case WAITING_HITL, WAITING_EXTERNAL -> target == WorkflowExecutionState.CANCELLED;
            case RESUMING, COMPLETED, FAILED, LOOP_TERMINATED, CANCELLED -> false;
        };
    }

    /** 返回普通 transition 目标对应的唯一公开 Session eventType。 */
    private static String eventType(WorkflowExecutionState target) {
        return switch (target) {
            case RUNNING -> "execution.started";
            case WAITING_HITL -> "execution.hitl";
            case WAITING_EXTERNAL -> "execution.waiting";
            case COMPLETED -> "execution.completed";
            case FAILED -> "execution.failed";
            case LOOP_TERMINATED -> "execution.loop_terminated";
            case CANCELLED -> "execution.cancelled";
            case QUEUED, RESUMING -> throw new IllegalArgumentException(
                    "state has no ordinary transition event");
        };
    }

    /** 精确冻结非泛型纯接口及其全部 inherited public abstract 方法。 */
    private static void assertExactPortMethods(Class<?> type, List<String> expectedSignatures) {
        assertTrue(type.isInterface(), type.getName());
        assertFalse(type.isAnnotation(), type.getName());
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
        assertEquals(null, type.getEnclosingClass(), type.getName());
        assertFalse(type.isSealed(), type.getName());
        assertEquals(0, type.getTypeParameters().length, type.getName());
        assertEquals(0, type.getInterfaces().length, type.getName());
        assertEquals(0, type.getGenericInterfaces().length, type.getName());
        List<Method> methods = Arrays.stream(type.getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .toList();
        for (Method method : methods) {
            assertTrue(Modifier.isPublic(method.getModifiers()), method.toString());
            assertTrue(Modifier.isAbstract(method.getModifiers()), method.toString());
            assertFalse(method.isDefault(), method.toString());
            assertFalse(Modifier.isStatic(method.getModifiers()), method.toString());
            assertEquals(0, method.getTypeParameters().length, method.toString());
            assertEquals(0, method.getGenericExceptionTypes().length, method.toString());
        }
        assertEquals(expectedSignatures, methods.stream()
                .map(ExecutionLedgerPortContractTest::methodSignature)
                .sorted()
                .toList());
    }

    /** 生成保留参数与返回泛型的稳定方法签名。 */
    private static String methodSignature(Method method) {
        String parameters = Arrays.stream(method.getGenericParameterTypes())
                .map(ExecutionLedgerPortContractTest::stableTypeName)
                .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + "):"
                + stableTypeName(method.getGenericReturnType());
    }

    /** 验证方法返回的 raw type 与唯一泛型参数。 */
    private static void assertGenericReturn(
            Method method, Class<?> rawType, Class<?> argument) {
        assertEquals(rawType, method.getReturnType());
        ParameterizedType genericType = (ParameterizedType) method.getGenericReturnType();
        assertEquals(List.of(argument), Arrays.asList(genericType.getActualTypeArguments()));
    }

    /** 返回 record component 的名称与完整泛型类型。 */
    private static List<String> componentSignatures(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + stableTypeName(component))
                .toList();
    }

    /** 压缩 record component 中稳定 SDK 与 java.lang 前缀。 */
    private static String stableTypeName(RecordComponent component) {
        return stableTypeName(component.getGenericType());
    }

    /** 压缩任意反射类型中的稳定 SDK 与 java.lang 前缀。 */
    private static String stableTypeName(Type type) {
        return type.getTypeName()
                .replace("com.skillforge.runtime.api.port.ExecutionLedgerPort$", "")
                .replace("com.skillforge.runtime.api.port.", "")
                .replace("com.skillforge.runtime.api.", "")
                .replace("java.lang.", "")
                .replace('$', '.');
    }
}

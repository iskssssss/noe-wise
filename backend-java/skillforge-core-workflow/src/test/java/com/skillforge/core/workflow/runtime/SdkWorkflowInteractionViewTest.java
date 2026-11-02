package com.skillforge.core.workflow.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SdkWorkflowInteractionViewTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "session"),
            new AgentId("agent"), new EndUserId(7));
    private static final RunIdentity RUN = new RunIdentity(OWNER, OWNER.target().requireAgent(), new InputId("input"), new RunRef(TENANT, "run"), new TraceEnvelope("trace", "span", new InputId("input"), Optional.empty()), new ResultRef("verified"), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), new InputId("input").value()));
    private static final VerifiedIdentity IDENTITY = new VerifiedIdentity(
            new PrincipalRef.SystemPrincipal(TENANT, "host"), OWNER, Set.of("read"), 1, new ResultRef("current"));
    private static final ExecutionRef EXECUTION = new ExecutionRef(TENANT, "execution");

    @Test void exposesOnlyWaitingCardAndDropsChangedSnapshot() {
        var first = waiting(4, RUN);
        var reads = new AtomicInteger();
        var view = new SdkWorkflowInteractionView(query -> true, lookup -> {
            assertEquals(IDENTITY.owner(), lookup.identity().owner());
            return Optional.of(reads.incrementAndGet() == 1 ? first : waiting(5, RUN));
        });

        assertTrue(view.find(new WorkflowQuery(IDENTITY, EXECUTION)).isEmpty());

        var stable = new SdkWorkflowInteractionView(query -> true, lookup -> Optional.of(first));
        var pending = stable.find(new WorkflowQuery(IDENTITY, EXECUTION)).orElseThrow();
        assertEquals("application/json", pending.card().schemaId());
        assertEquals("{\"actions\":[\"approve\"],\"title\":\"确认\"}", pending.card().canonicalJson());
        assertFalse(pending.card().canonicalJson().contains("private"));
        assertEquals("resume-route", pending.actionKey());
    }

    @Test void authorizesBeforeReadAndRejectsReturnedOwnerMismatch() {
        var calls = new ArrayList<String>();
        var denied = new SdkWorkflowInteractionView(query -> { calls.add("authorize"); return false; },
                lookup -> { calls.add("read"); return Optional.of(waiting(4, RUN)); });
        assertTrue(denied.find(new WorkflowQuery(IDENTITY, EXECUTION)).isEmpty());
        assertEquals(List.of("authorize"), calls);

        var otherOwner = SessionOwner.user(new SessionRef(TENANT, "other"), OWNER.target().requireAgent(), OWNER.requireUser());
        var otherRun = new RunIdentity(otherOwner, otherOwner.target().requireAgent(), RUN.causationInputId(), RUN.runRef(), RUN.trace(), RUN.causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(otherOwner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(otherOwner.sessionRef().tenantId(), RUN.causationInputId().value()));
        var mismatched = new SdkWorkflowInteractionView(query -> true, lookup -> Optional.of(waiting(4, otherRun)));
        assertTrue(mismatched.find(new WorkflowQuery(IDENTITY, EXECUTION)).isEmpty());
    }

    private static ExecutionRecord waiting(long revision, RunIdentity owner) {
        var engine = new Checkpoint(Boundary.WAITING, "human", null,
                Map.of("private", "state"), Map.of(), List.of(),
                Map.of("title", "确认", "actions", List.of("approve")), 30, Optional.empty(), Optional.of(com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason.HUMAN_INPUT), com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Cursor.initial());
        var state = new WorkflowCheckpointCodec.State(
                new FrozenWorkflowTarget(new WorkflowHandle("workflow:review"), "instance", 1,
                        new CapabilityRef(CapabilityType.WORKFLOW, "definition", "version"),
                        java.time.Duration.ofSeconds(30), 1L),
                new CapabilitySnapshotRef(owner.owner().sessionRef(), owner.causationInputId(), "snapshot", 1),
                new TypedPayload("application/json", "{\"private\":\"parameters\"}"), List.of(),
                Optional.of(engine), Map.of(), Optional.empty(), Optional.empty());
        var now = Instant.parse("2026-09-13T00:00:00Z");
        return new ExecutionRecord(EXECUTION, owner, "unit", "1", ExecutionMode.FOREGROUND,
                Optional.empty(), Optional.empty(), WorkflowExecutionState.WAITING_HITL, revision,
                WorkflowCheckpointCodec.encode(state), Optional.of(new ExecutionWait("suspend", "resume-route")),
                Optional.of(new ResultRef("suspend")), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), now, now);
    }

}

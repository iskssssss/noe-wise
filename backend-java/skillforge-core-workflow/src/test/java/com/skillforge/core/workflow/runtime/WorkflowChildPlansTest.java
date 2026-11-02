package com.skillforge.core.workflow.runtime;

import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.*;
import com.skillforge.core.workflow.execution.WorkflowModels.*;
import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.execution.node.NodeCallIdentity;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings.Binding;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class WorkflowChildPlansTest {
    private static final TenantId TENANT = new TenantId("tenant");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session");
    private static final RunIdentity OWNER = new RunIdentity(SessionOwner.user(SESSION, new AgentId("agent"), new EndUserId(7)), SessionOwner.user(SESSION, new AgentId("agent"), new EndUserId(7)).target().requireAgent(), new InputId("input"), new RunRef(TENANT, "run"), new TraceEnvelope("trace", "span", new InputId("input"), Optional.empty()), new ResultRef("verified"), new com.skillforge.runtime.api.RunRequester.EndUser(SessionOwner.user(SESSION, new AgentId("agent"), new EndUserId(7)).requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SessionOwner.user(SESSION, new AgentId("agent"), new EndUserId(7)).sessionRef().tenantId(), new InputId("input").value()));
    private static final ExecutionRef PARENT_EXECUTION = new ExecutionRef(TENANT, "parent-execution");
    private static final FrozenWorkflowTarget PARENT_TARGET = target("parent", "parent-instance", 7, 11);

    @Test
    void parallelCreatesIndependentStableChildrenFromDeepCopiedParentState() {
        WorkflowNode parallel = node("parallel", "parallel", Map.of(), List.of("left", "right"), null);
        Workflow workflow = new Workflow("parent", "parallel", List.of(parallel,
                node("left", "assign", Map.of(), List.of(), null),
                node("right", "assign", Map.of(), List.of(), null)), List.of());
        Map<String, Object> state = Map.of("customer", "Ada", "nested", Map.of("flag", "parent"));
        Checkpoint checkpoint = checkpoint(parallel, state, Map.of("code", "E-1"),
                List.of(Map.of("content", "parent-message")), null);
        ExecutionRecord parent = parent(source(checkpoint));
        NodeExecutionContext context = context(workflow, parallel, state, Map.of("code", "E-1"),
                List.of(Map.of("content", "parent-message")), null, Map.of());
        WorkflowRuntimeBindings slots = new WorkflowRuntimeBindings(List.of(), List.of(), List.of(), List.of());
        Function<FrozenWorkflowTarget, Binding> noBindingLookup = ignored -> fail("parallel must not resolve a subgraph binding");

        var prepared = WorkflowChildPlans.prepare(parent, context, slots, noBindingLookup, new WorkflowParser());
        var rebuilt = WorkflowChildPlans.prepare(parent, context, slots, noBindingLookup, new WorkflowParser());

        assertEquals(prepared, rebuilt);
        assertEquals(new WorkflowNodeCallIdentity(OWNER, PARENT_EXECUTION, "parallel", 2, 1), prepared.parent());
        assertEquals(List.of("left", "right"), prepared.children().stream().map(child -> child.branchId()).toList());
        assertEquals(2, prepared.children().stream().map(child -> child.command().executionRef()).distinct().count());
        assertTrue(prepared.children().stream().allMatch(child -> child.command().owner().equals(OWNER)
                && !child.command().executionRef().equals(PARENT_EXECUTION)));

        var left = childState(prepared.children().getFirst());
        var right = childState(prepared.children().getLast());
        assertEquals("left", left.engine().orElseThrow().nodeId());
        assertEquals("right", right.engine().orElseThrow().nodeId());
        assertEquals(state, left.engine().orElseThrow().state());
        assertEquals(state, right.engine().orElseThrow().state());
        assertNotSame(left.engine().orElseThrow().state(), right.engine().orElseThrow().state());
        assertNotSame(left.engine().orElseThrow().state().get("nested"),
                right.engine().orElseThrow().state().get("nested"));
        assertEquals(List.of(), left.engine().orElseThrow().messages());
        assertEquals(List.of(), right.engine().orElseThrow().messages());
    }

    @Test
    void subgraphUsesExactFrozenTargetAndOnlyExplicitParametersAndIncomingTransferContext() {
        FrozenWorkflowTarget childTarget = target("child", "child-instance", 3, 5);
        WorkflowNode subgraph = node("subgraph", "subgraph",
                Map.of("explicit", "${state.customer}", "tier", "${config.tier}"), List.of(), "child-slot");
        WorkflowEdge incoming = new WorkflowEdge("previous", "subgraph", null, 0,
                Map.of("from_edge", "${extracted.code}"));
        Workflow workflow = new Workflow("parent", "subgraph", List.of(subgraph), List.of(incoming));
        Map<String, Object> state = Map.of("customer", "Ada", "private_state", "do-not-copy");
        Map<String, Object> extracted = Map.of("code", "E-1", "private_extracted", "do-not-copy");
        Checkpoint checkpoint = checkpoint(subgraph, state, extracted,
                List.of(Map.of("content", "parent-message")), incoming);
        ExecutionRecord parent = parent(source(checkpoint));
        NodeExecutionContext context = context(workflow, subgraph, state, extracted,
                List.of(Map.of("content", "parent-message")), incoming, Map.of("tier", "gold"));
        WorkflowRuntimeBindings slots = new WorkflowRuntimeBindings(List.of(), List.of(),
                List.of(new WorkflowRuntimeBindings.WorkflowSlot("child-slot", childTarget)), List.of());
        WorkflowParser parser = mock(WorkflowParser.class);
        when(parser.parse(eq("child-definition"), any())).thenReturn(new Workflow("child", "entry",
                List.of(node("entry", "assign", Map.of(), List.of(), null)), List.of()));
        Binding exact = binding(childTarget);
        List<FrozenWorkflowTarget> lookedUp = new ArrayList<>();

        var prepared = WorkflowChildPlans.prepare(parent, context, slots, target -> {
            lookedUp.add(target);
            return exact;
        }, parser);

        assertEquals(List.of(childTarget), lookedUp);
        var child = childState(prepared.children().getFirst());
        assertEquals(childTarget, child.target());
        assertEquals(Map.of("explicit", "Ada", "tier", "gold", "from_edge", "E-1"),
                WorkflowCheckpointCodec.object(child.parameters()));
        assertEquals(Map.of("ctx_explicit", "Ada", "ctx_tier", "gold", "ctx_from_edge", "E-1"),
                child.engine().orElseThrow().state());
        assertEquals(List.of(), child.engine().orElseThrow().messages());
        assertEquals(List.of(), child.evidenceRefs());

        Binding drifted = binding(target("other", "other-instance", 4, 6));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowChildPlans.prepare(parent, context, slots, ignored -> drifted, parser));
    }

    private static WorkflowCheckpointCodec.State childState(
            com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowChildExecutions.Child child) {
        return WorkflowCheckpointCodec.decode(child.command().initialCheckpoint());
    }

    private static WorkflowCheckpointCodec.State source(Checkpoint checkpoint) {
        return new WorkflowCheckpointCodec.State(PARENT_TARGET,
                new CapabilitySnapshotRef(SESSION, new InputId("input"), "snapshot", 1),
                new TypedPayload("parameters.v1", "{\"parent\":\"parameters\"}"),
                List.of(new EvidenceRef(SESSION, "parent-bundle", List.of("parent-evidence"), 1,
                        new EventId("parent-evidence-event"))),
                Optional.of(checkpoint), Map.of(), Optional.empty(), Optional.empty());
    }

    private static ExecutionRecord parent(WorkflowCheckpointCodec.State state) {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        return new ExecutionRecord(PARENT_EXECUTION, OWNER, "parent-instance", "7", ExecutionMode.FOREGROUND,
                Optional.empty(), Optional.empty(), WorkflowExecutionState.RUNNING, 3,
                WorkflowCheckpointCodec.encode(state), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), now, now);
    }

    private static Checkpoint checkpoint(WorkflowNode node, Map<String, Object> state,
            Map<String, Object> extracted, List<Map<String, Object>> messages, WorkflowEdge incoming) {
        return new Checkpoint(Boundary.PROGRESS, node.id(), node.id(), state, extracted, messages,
                null, null, Optional.empty(), Optional.empty(),
                new Cursor(Map.of(node.id(), 2), node.id(), 3, incoming));
    }

    private static NodeExecutionContext context(Workflow workflow, WorkflowNode node, Map<String, Object> state,
            Map<String, Object> extracted, List<Map<String, Object>> messages, WorkflowEdge incoming,
            Map<String, Object> config) {
        return new NodeExecutionContext(workflow, node, config, state, extracted, "input", messages,
                "parent-instance", TENANT.value(), List.of(), PARENT_EXECUTION.executionId(), null,
                SESSION.sessionId(), Map.of(), incoming, new NodeCallIdentity(PARENT_EXECUTION.executionId(), node.id(), 2, 1));
    }

    private static Binding binding(FrozenWorkflowTarget target) {
        return new Binding(target, "child-definition", "{}", "{}", Optional.empty(), "{}", "{}", "{}");
    }

    private static FrozenWorkflowTarget target(String handle, String instanceId, long instanceRevision,
            long installationRevision) {
        return new FrozenWorkflowTarget(new WorkflowHandle("workflow:" + handle), instanceId, instanceRevision,
                new CapabilityRef(CapabilityType.WORKFLOW, "capability-" + handle, "version-" + handle),
                Duration.ofSeconds(30), installationRevision);
    }

    private static WorkflowNode node(String id, String type, Map<String, Object> args,
            List<String> branches, String workflow) {
        return new WorkflowNode(id, type, id, null, null, args, "state", null, null, branches, workflow,
                List.of(), null, List.of(), null, null, null, null, ExternalNodeSpec.empty());
    }
}

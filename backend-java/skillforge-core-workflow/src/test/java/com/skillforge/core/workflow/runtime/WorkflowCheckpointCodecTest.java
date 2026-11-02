package com.skillforge.core.workflow.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.workflow.execution.ControlledWorkflowExecution;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Boundary;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Checkpoint;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.LoopLimit;
import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowModels.Workflow;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowEdge;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowDispatch;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowCheckpointCodecTest {
    private static final BigInteger BIG = new BigInteger("1234567890123456789012345678901234567890");

    @Test
    void initialRetainsCompleteFrozenDispatchValues() {
        var expected = initial();
        WorkflowDispatch dispatch = mock(WorkflowDispatch.class);
        when(dispatch.target()).thenReturn(expected.target());
        when(dispatch.snapshot()).thenReturn(expected.snapshot());
        when(dispatch.parameters()).thenReturn(expected.parameters());
        when(dispatch.evidenceRefs()).thenReturn(expected.evidenceRefs());
        var state = WorkflowCheckpointCodec.initial(dispatch);
        assertEquals(expected, state);
        assertEquals(state, WorkflowCheckpointCodec.decode(WorkflowCheckpointCodec.encode(state)));
    }

    @Test
    void realEngineWaitingResponseResumeAndCompletedRoundTrip() {
        var engine = new StateMachineEngine();
        Workflow workflow = new Workflow("human", "human", List.of(node("human", "human_input"), node("done", "output")),
                List.of(new WorkflowEdge("human", "done", null, 0, null)));
        List<Checkpoint> boundaries = new ArrayList<>();
        engine.runControlled(request(workflow, null), hooks(boundaries));
        var waiting = roundTrip(initial().checkpoint(boundaries.getLast()));
        assertEquals(Boundary.WAITING, waiting.engine().orElseThrow().boundary());
        assertEquals("human", waiting.engine().orElseThrow().nodeId());
        assertEquals(BIG, waiting.engine().orElseThrow().state().get("large"));
        var withResponse = roundTrip(waiting.response(Map.of("action", "confirm", "emoji", "你好🚀", "large", BIG)));
        assertEquals("你好🚀", withResponse.response().get("emoji"));
        assertEquals(BIG, withResponse.response().get("large"));
        var c = withResponse.engine().orElseThrow();
        engine.runControlled(request(workflow, new ControlledWorkflowExecution.Resume(c.nodeId(), false,
                c.state(), c.extracted(), c.messages(), c.cursor())), hooks(boundaries));
        var completed = roundTrip(withResponse.checkpoint(boundaries.getLast()));
        assertEquals(Boundary.COMPLETED, completed.engine().orElseThrow().boundary());
        assertEquals("完成🚀", completed.engine().orElseThrow().messages().getLast().get("content"));
    }

    @Test
    void bothRealEngineLoopLimitsRoundTrip() {
        for (String type : List.of("start", "assign")) {
            var engine = new StateMachineEngine();
            Workflow workflow = new Workflow("loop", "loop", List.of(node("loop", type)),
                    List.of(new WorkflowEdge("loop", "loop", null, 0, null)));
            List<Checkpoint> boundaries = new ArrayList<>();
            engine.runControlled(request(workflow, null), hooks(boundaries));
            var restored = roundTrip(initial().checkpoint(boundaries.getLast())).engine().orElseThrow();
            assertEquals(Boundary.LOOP_TERMINATED, restored.boundary());
            assertEquals(type.equals("start") ? LoopLimit.MAX_STEPS : LoopLimit.NODE_REVISIT,
                    restored.loopLimit().orElseThrow());
            assertEquals(type.equals("start") ? com.skillforge.runtime.api.LoopTerminationReason.WORKFLOW_MAX_STEPS_REACHED
                            : com.skillforge.runtime.api.LoopTerminationReason.WORKFLOW_NODE_REVISIT,
                    WorkflowExecutionWorker.loopTerminationReason(restored.loopLimit().orElseThrow()));
        }
    }

    @Test
    void toolInputWaitRoundTripRetainsEveryRecoveryIdentity() {
        var wait = toolWait("tenant", "tool", 2, 3);
        var restored = roundTrip(initial()
                .checkpoint(waiting("tool", 2,
                        com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason.TOOL_INPUT))
                .toolWait(wait));

        assertEquals(Optional.of(wait), restored.toolWait());
        var actual = restored.toolWait().orElseThrow();
        assertEquals(new ExecutionRef(new TenantId("tenant"), "execution-tool"),
                actual.executionRef());
        assertEquals("tool", actual.nodeId());
        assertEquals(2, actual.visit());
        assertEquals(3, actual.call());
        assertEquals(new ActionRef(new TenantId("tenant"), "action-tool"),
                actual.actionRef());
        assertEquals(new RunRef(new TenantId("tenant"), "node-run"), actual.nodeRun());
        assertEquals(7, actual.generation());
        assertEquals(new ResultRef("fact-tool"), actual.sourceResult());
    }

    @Test
    void toolInputWaitRejectsCrossTenantBindings() {
        var tenant = new TenantId("tenant");
        var other = new TenantId("other-tenant");

        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowCheckpointCodec.ToolWait(
                        new ExecutionRef(tenant, "execution-tool"), "tool", 2, 3,
                        new RunRef(tenant,"node-run"),new ActionRef(other, "action-tool"), 7,new ResultRef("fact-tool")));

        var foreignWait = toolWait("other-tenant", "tool", 2, 3);
        var state = initial().checkpoint(waiting(
                "tool", 2,
                com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason.TOOL_INPUT));
        assertThrows(IllegalArgumentException.class,
                () -> state.toolWait(foreignWait));
    }

    @Test
    void toolInputWaitRejectsNodeVisitAndNonToolCheckpointDrift() {
        var toolCheckpoint = initial().checkpoint(waiting(
                "tool", 2,
                com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason.TOOL_INPUT));

        assertThrows(IllegalArgumentException.class,
                () -> toolCheckpoint.toolWait(
                        toolWait("tenant", "other-node", 2, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> toolCheckpoint.toolWait(
                        toolWait("tenant", "tool", 3, 3)));

        var humanCheckpoint = initial().checkpoint(waiting(
                "tool", 2,
                com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason.HUMAN_INPUT));
        assertThrows(IllegalArgumentException.class,
                () -> humanCheckpoint.toolWait(
                        toolWait("tenant", "tool", 2, 3)));
    }

    @Test
    void asyncWaitRoundTripsAndRejectsEngineDrift() {
        var wait = new WorkflowCheckpointCodec.AsyncWait("async", 2, 3,
                Optional.of(Instant.parse("2026-09-27T02:00:00Z")));
        var state = initial().checkpoint(waiting("async", 2,
                com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason.CALLBACK))
                .asyncWait(wait).response(Map.of("signal", "ready"));
        var restored = roundTrip(state);

        assertEquals(Optional.of(wait), restored.asyncWait());
        assertEquals(Map.of("signal", "ready"), restored.response());
        assertEquals(Optional.of(wait), restored.checkpoint(new Checkpoint(Boundary.PROGRESS, "async", "next",
                Map.of(), Map.of(), List.of(), null, null, Optional.empty(), Optional.empty(),
                new ControlledWorkflowExecution.Cursor(Map.of("async", 2), "async", 3, null))).asyncWait());
        assertThrows(IllegalArgumentException.class, () -> initial().checkpoint(waiting("async", 2,
                com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason.CALLBACK))
                .asyncWait(new WorkflowCheckpointCodec.AsyncWait("other", 2, 3, Optional.empty())));
        assertThrows(IllegalArgumentException.class, () -> initial().checkpoint(waiting("async", 2,
                com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason.HUMAN_INPUT)).asyncWait(wait));
    }

    @Test
    void completedOutputCannotExportPrivateToolWaitMetadata() {
        var wait = toolWait("tenant", "tool", 2, 3);
        Checkpoint completed = new Checkpoint(
                Boundary.COMPLETED, "done", null, Map.of(), Map.of(),
                List.of(Map.of("content", "done")), null, null,
                Optional.empty(), Optional.empty(),
                ControlledWorkflowExecution.Cursor.initial());

        TypedPayload output = WorkflowCheckpointCodec.output(
                completed,
                Set.of("toolWait", "nodeRun", "actionRef", "generation"));

        assertEquals("{}", output.canonicalJson());
        for (String privateValue : List.of(
                wait.executionRef().executionId(), wait.nodeId(),
                wait.actionRef().actionId(),wait.nodeRun().runId(),wait.sourceResult().value())) {
            assertFalse(output.canonicalJson().contains(privateValue));
        }
    }

    @Test
    void rejectsUnknownDuplicateNoncanonicalAndWrongSchemaInputs() {
        String json = WorkflowCheckpointCodec.encode(initial()).canonicalJson();
        for (String invalid : List.of(" " + json, json + " {}", json.replaceFirst("\\{", "{\"unknown\":1,"),
                json.replace("\"engine\":null", "\"engine\":null,\"engine\":null"),
                json.replace("\"instanceId\":", "\"unknownTarget\":true,\"instanceId\":"),
                json.replace("\"generation\":9", "\"generation\":9,\"unknownSnapshot\":true"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> WorkflowCheckpointCodec.decode(new TypedPayload(WorkflowCheckpointCodec.SCHEMA, invalid)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowCheckpointCodec.decode(new TypedPayload("other.schema", json)));
        assertThrows(IllegalArgumentException.class, () -> WorkflowCheckpointCodec.object("{\"a\":1,\"a\":2}"));
    }

    @Test
    void rejectsOversizedAndExcessivelyNestedPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowCheckpointCodec.object("{\"value\":\"" + "中".repeat(400_000) + "\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowCheckpointCodec.encode(initial().response(Map.of("large", "x".repeat(1_048_576)))));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowCheckpointCodec.object("{\"a\":".repeat(66) + "0" + "}".repeat(66)));
    }

    @Test
    void completedOutputExcludesPrivateStateAndRetainsEmojiAndLargeNumbers() {
        Checkpoint completed = new Checkpoint(Boundary.COMPLETED, "done", null,
                Map.of("secret", "private-state-secret", "__workflow_action_visits", Map.of("tool", 3)),
                Map.of("secret", "private-extracted-secret"), List.of(Map.of("content", "完成🚀", "large", BIG)),
                Map.of("secret", "private-card-secret"), null, Optional.empty(), Optional.empty(), ControlledWorkflowExecution.Cursor.initial());
        TypedPayload output = WorkflowCheckpointCodec.output(completed, Set.of("messages"));
        assertEquals("{}", WorkflowCheckpointCodec.output(completed, Set.of()).canonicalJson());
        assertEquals(Map.of("secret", "private-extracted-secret"),
                WorkflowCheckpointCodec.object(WorkflowCheckpointCodec.output(completed, Set.of("secret"))));
        assertEquals("{}", WorkflowCheckpointCodec.output(completed, Set.of("absent")).canonicalJson());
        assertEquals(WorkflowCheckpointCodec.OUTPUT_SCHEMA, output.schemaId());
        assertFalse(output.canonicalJson().contains("private-"));
        assertFalse(output.canonicalJson().contains("__workflow_action_visits"));
        assertEquals(Map.of("messages", List.of(Map.of("content", "完成🚀", "large", BIG))), WorkflowCheckpointCodec.object(output));
        assertThrows(IllegalArgumentException.class, () -> WorkflowCheckpointCodec.output(new Checkpoint(
                Boundary.WAITING, "human", "human", Map.of(), Map.of(), List.of(), null, null, Optional.empty(), Optional.of(com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason.HUMAN_INPUT), ControlledWorkflowExecution.Cursor.initial()), Set.of("messages")));
    }

    private static WorkflowCheckpointCodec.State roundTrip(WorkflowCheckpointCodec.State state) {
        var encoded = WorkflowCheckpointCodec.encode(state);
        var decoded = WorkflowCheckpointCodec.decode(encoded);
        assertEquals(encoded, WorkflowCheckpointCodec.encode(decoded));
        return decoded;
    }

    private static Checkpoint waiting(
            String nodeId, int visit,
            com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason reason) {
        return new Checkpoint(
                Boundary.WAITING, nodeId, nodeId,
                Map.of("private", "state"), Map.of(), List.of(), null, null,
                Optional.empty(), Optional.of(reason),
                new ControlledWorkflowExecution.Cursor(
                        Map.of(nodeId, visit), nodeId, visit + 1, null));
    }

    private static WorkflowCheckpointCodec.ToolWait toolWait(
            String tenantId, String nodeId, int visit, int call) {
        var tenant = new TenantId(tenantId);
        return new WorkflowCheckpointCodec.ToolWait(
                new ExecutionRef(tenant, "execution-tool"), nodeId, visit, call,
                new RunRef(tenant,"node-run"),new ActionRef(tenant, "action-tool"), 7,new ResultRef("fact-tool"));
    }

    private static WorkflowCheckpointCodec.State initial() {
        SessionRef session = new SessionRef(new TenantId("tenant"), "session");
        return new WorkflowCheckpointCodec.State(new FrozenWorkflowTarget(new WorkflowHandle("workflow:mounted"),
                "instance", 12, new CapabilityRef(CapabilityType.WORKFLOW, "capability", "version"), Duration.ofMillis(12345), 1L),
                new CapabilitySnapshotRef(session, new InputId("input"), "snapshot", 9),
                new TypedPayload("parameters.v1", "{\"large\":" + BIG + ",\"text\":\"你好🚀\"}"),
                List.of(new EvidenceRef(session, "bundle", List.of("item-one", "item-two"), 3, new EventId("source"))),
                Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
    }

    private static ControlledWorkflowExecution.Request request(Workflow workflow, ControlledWorkflowExecution.Resume resume) {
        return new ControlledWorkflowExecution.Request(workflow, Map.of(), "input", "session", Map.of("action", "confirm"),
                resume, "instance", "tenant", List.of(), "execution", Map.of("end_user_id", 100L),
                new StateMachineEngine.InitialState(Map.of("large", BIG), Map.of()));
    }

    private static ControlledWorkflowExecution.Hooks hooks(List<Checkpoint> boundaries) {
        return new ControlledWorkflowExecution.Hooks() {
            public void beforeNode(String nodeId) {}
            public void checkpoint(Checkpoint checkpoint) { boundaries.add(checkpoint); }
        };
    }

    private static WorkflowNode node(String id, String type) {
        return new WorkflowNode(id, type, id, null, null, Map.of("ready", true), "state", "完成🚀",
                null, null, null, List.of(), null, null, null, null, null, null, com.skillforge.core.workflow.execution.WorkflowModels.ExternalNodeSpec.empty());
    }
}

package com.skillforge.core.workflow.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.*;
import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowModels.Workflow;
import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.execution.node.NodeCallIdentity;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.NodeOutcome;
import com.skillforge.core.workflow.execution.node.WorkflowNodeFailure;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import com.skillforge.runtime.session.spi.SessionWorkflowWakeups;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkflowAsyncTasksTest {
    private static final Instant NOW = Instant.parse("2026-11-28T08:00:00Z");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void messageEndsTheOriginalWaitWithSafeOutputAfterFailedCallbackOrTimeout(boolean timedOut) {
        var fixture = new Fixture("message", "state.receipt");
        fixture.suspend();
        var originalWait = fixture.state.asyncWait().orElseThrow();
        fixture.fail(timedOut);
        var result = fixture.run();
        assertEquals("completed", result.get("status"));
        assertEquals(List.of("async", "async"), fixture.visited);
        assertEquals(List.of(new NodeCallIdentity("execution", "async", 1, 1),
                new NodeCallIdentity("execution", "async", 1, 1)), fixture.calls);
        assertEquals(1, originalWait.visit());
        assertEquals(1, originalWait.call());
        assertEquals(Optional.of(NOW.plusSeconds(30)), originalWait.deadlineAt());
        assertEquals(Map.of("node_id", "async", "reason", timedOut ? "workflow_async_timed_out" : "provider_private_failure"),
                ((Map<?, ?>) result.get("state")).get("last_error"));
        assertFalse(((Map<?, ?>) result.get("state")).containsKey("receipt"));
        assertEquals(List.of(Map.of("role", "assistant", "content", "节点执行失败，请检查输入或查看执行记录。", "node_id", "async")),
                result.get("messages"));
        assertEquals(Boundary.COMPLETED, fixture.state.engine().orElseThrow().boundary());
        assertEquals(Map.of("async", 1), fixture.state.engine().orElseThrow().cursor().visits());
        assertEquals(1, fixture.state.engine().orElseThrow().cursor().steps());
        assertTrue(fixture.state.asyncWait().isEmpty());
        assertTrue(WorkflowAsyncTasks.timeout(fixture.state, NOW.plusSeconds(60)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultFailAndGotoKeepTheirFailureSemantics(boolean timedOut) {
        for (String policy : new String[] {null, "fail", "goto:recover"}) {
            var fixture = new Fixture(policy, "state.receipt");
            fixture.suspend();
            fixture.fail(timedOut);
            String reason = timedOut ? "workflow_async_timed_out" : "provider_private_failure";
            if ("goto:recover".equals(policy)) {
                var result = fixture.run();
                assertEquals("completed", result.get("status"));
                assertEquals(List.of("async", "async", "recover", "end"), fixture.visited);
                assertEquals(List.of(Map.of("role", "assistant", "content", "已进入恢复分支")), result.get("messages"));
                assertEquals(Boundary.COMPLETED, fixture.state.engine().orElseThrow().boundary());
            } else {
                assertEquals(reason, assertThrows(WorkflowNodeFailure.class, fixture::run).reason());
                assertEquals(List.of("async", "async"), fixture.visited);
                assertTrue(fixture.node.messages().isEmpty());
                assertTrue(fixture.checkpoints.stream().noneMatch(value -> value.boundary() == Boundary.COMPLETED));
            }
            assertEquals(Map.of("node_id", "async", "reason", reason), fixture.node.state().get("last_error"));
            assertFalse(fixture.node.state().containsKey("receipt"));
            assertEquals(fixture.calls.getFirst(), fixture.calls.getLast());
            assertEquals(2, fixture.calls.size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"state", "extracted", "state.receipt", "extracted.receipt"})
    void successfulCallbackPreservesItsResultAndContinuesTheNormalSuccessor(String target) {
        var fixture = new Fixture("message", target);
        fixture.suspend();
        var receipt = Map.of("count", BigInteger.ZERO, "enabled", false, "nested", Map.of("value", "已确认"));
        fixture.state = fixture.state.response(Map.of("status", "completed", "result", receipt));
        var result = fixture.run();
        assertEquals("completed", result.get("status"));
        assertEquals(List.of("async", "async", "normal", "end"), fixture.visited);
        assertEquals(receipt, ((Map<?, ?>) result.get(target.startsWith("extracted") ? "extracted" : "state"))
                .get(target.contains(".") ? "receipt" : "async"));
        assertFalse(((Map<?, ?>) result.get("state")).containsKey("last_error"));
        assertEquals(List.of(Map.of("role", "assistant", "content", "正常完成")), result.get("messages"));
        assertEquals(fixture.calls.getFirst(), fixture.calls.getLast());
        assertEquals(2, fixture.calls.size());
        assertTrue(fixture.state.asyncWait().isEmpty());
    }

    @Test void resumedWaitingKeepsDeadlineAndRejectsDifferentNodeVisitOrCall() {
        var fixture = new Fixture("message", "state.receipt");
        fixture.suspend();
        var original = fixture.state.asyncWait().orElseThrow();
        fixture.now = NOW.plusSeconds(10);
        assertEquals("waiting_external", fixture.run().get("status"));
        assertEquals(Optional.of(original), fixture.state.asyncWait());
        assertEquals(fixture.calls.getFirst(), fixture.calls.getLast());
        assertEquals(1, fixture.state.engine().orElseThrow().cursor().steps());
        assertTrue(WorkflowAsyncTasks.timeout(fixture.state, original.deadlineAt().orElseThrow().minusNanos(1)).isEmpty());
        var callback = new TypedPayload(SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA,
                "{\"status\":\"failed\",\"reason_code\":\"provider_private_failure\"}");
        assertDoesNotThrow(() -> WorkflowAsyncTasks.requireCallback(fixture.state, callback, NOW.plusSeconds(29)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowAsyncTasks.requireCallback(fixture.state, callback, original.deadlineAt().orElseThrow()));
        fixture.state = fixture.state.response(WorkflowCheckpointCodec.object(callback));
        var originalContext = fixture.node;
        for (var changed : List.of(new NodeCallIdentity("execution", "other", 1, 1),
                new NodeCallIdentity("execution", "async", 2, 1), new NodeCallIdentity("execution", "async", 1, 2))) {
            var definition = changed.nodeId().equals("async") ? originalContext.node() : fixture.workflow.nodes().get(1);
            var node = new NodeExecutionContext(fixture.workflow, definition, Map.of(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), "", new ArrayList<>(), "instance", "7", List.of(), "execution", null,
                    "session", Map.of(), null, changed);
            assertEquals("workflow_async_response_rejected",
                    assertThrows(IllegalArgumentException.class, () -> WorkflowAsyncTasks.execute(node, fixture.state)).getMessage());
            assertTrue(node.state().isEmpty());
            assertTrue(node.messages().isEmpty());
        }
        assertEquals(Optional.of(original), fixture.state.asyncWait());
    }

    private static final class Fixture implements Hooks {
        private final Workflow workflow;
        private final List<String> visited = new ArrayList<>();
        private final List<NodeCallIdentity> calls = new ArrayList<>();
        private final List<Checkpoint> checkpoints = new ArrayList<>();
        private WorkflowCheckpointCodec.State state;
        private NodeExecutionContext node;
        private Instant now = NOW;

        Fixture(String policy, String resultTo) {
            var async = new LinkedHashMap<String, Object>(Map.of("id", "async", "type", "async_task",
                    "result_to", resultTo, "timeout_seconds", 30));
            if (policy != null) async.put("on_error", policy);
            workflow = new WorkflowParser().parse("async-error", Map.of("entry", "async", "nodes", List.of(async,
                    Map.of("id", "normal", "type", "output", "message", "正常完成"),
                    Map.of("id", "recover", "type", "output", "message", "已进入恢复分支"),
                    Map.of("id", "end", "type", "end")), "edges", List.of(
                    Map.of("from", "async", "to", "normal"), Map.of("from", "normal", "to", "end"),
                    Map.of("from", "recover", "to", "end"))));
            var session = new SessionRef(new TenantId("7"), "session");
            state = new WorkflowCheckpointCodec.State(new FrozenWorkflowTarget(new WorkflowHandle("workflow:mounted"),
                    "instance", 12, new CapabilityRef(CapabilityType.WORKFLOW, "capability", "version"), Duration.ofMinutes(1), 1L),
                    new CapabilitySnapshotRef(session, new InputId("input"), "snapshot", 9), new TypedPayload("input.v1", "{}"),
                    List.of(), Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
        }

        void suspend() {
            assertEquals("waiting_external", run().get("status"));
            assertEquals(Boundary.WAITING, state.engine().orElseThrow().boundary());
            assertEquals(Optional.of(NodeOutcome.WaitReason.CALLBACK), state.engine().orElseThrow().waitReason());
        }

        void fail(boolean timedOut) {
            var response = timedOut ? WorkflowAsyncTasks.timeout(state, NOW.plusSeconds(30)).orElseThrow()
                    : new TypedPayload(SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA,
                            "{\"status\":\"failed\",\"reason_code\":\"provider_private_failure\"}");
            if (!timedOut) WorkflowAsyncTasks.requireCallback(state, response, NOW.plusSeconds(1));
            state = state.response(WorkflowCheckpointCodec.object(response));
        }

        Map<String, Object> run() {
            Resume resume = state.engine().map(value -> new Resume(value.nodeId(), true, value.state(),
                    value.extracted(), value.messages(), value.cursor())).orElse(null);
            return new StateMachineEngine().runControlled(new Request(workflow, Map.of(), "", "session", null, resume,
                    "instance", "7", List.of(), "execution", Map.of("end_user_id", 100L), null), this);
        }

        public void beforeNode(String id) { visited.add(id); }

        public NodeOutcome executeExternalNode(NodeExecutionContext value) {
            node = value;
            calls.add(value.callIdentity());
            return WorkflowAsyncTasks.execute(value, state);
        }

        public void checkpoint(Checkpoint value) {
            var previous = state.asyncWait();
            state = state.checkpoint(value);
            if (value.boundary() == Boundary.WAITING)
                state = state.asyncWait(previous.orElseGet(() -> WorkflowAsyncTasks.waiting(node, now)));
            state = WorkflowCheckpointCodec.decode(WorkflowCheckpointCodec.encode(state));
            checkpoints.add(value);
        }
    }
}

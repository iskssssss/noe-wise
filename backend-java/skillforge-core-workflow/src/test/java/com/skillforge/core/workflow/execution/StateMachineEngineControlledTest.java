package com.skillforge.core.workflow.execution;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.core.workflow.execution.WorkflowModels.*;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateMachineEngineControlledTest {
    private final StateMachineEngine engine = new StateMachineEngine();

    @Test
    void realPureNodesSuspendAndResumeWithoutLegacyStores() {
        Workflow workflow = graph(node("assign", "assign"), node("human", "human_input"), node("done", "output"));
        List<Checkpoint> checkpoints = new ArrayList<>();
        Hooks hooks = hooks(checkpoints);
        assertEquals("waiting_hitl", engine.runControlled(request(workflow, null), hooks).get("status"));
        Checkpoint suspended = checkpoints.getLast();
        assertEquals(Boundary.WAITING, suspended.boundary());
        assertEquals(true, suspended.state().get("ready"));
        Resume resume = new Resume(suspended.nodeId(), false, suspended.state(), suspended.extracted(), suspended.messages(), suspended.cursor());
        assertEquals("completed", engine.runControlled(request(workflow, resume), hooks).get("status"));
        assertEquals(Boundary.COMPLETED, checkpoints.getLast().boundary());
        assertEquals("done", checkpoints.getLast().messages().getLast().get("content"));
    }

    @Test
    void humanResponseReachesNextNodeAndSurvivesProgressRecovery() {
        for (String target : List.of("state.requirements", "extracted.requirements", "state", "extracted")) {
            WorkflowNode human = new WorkflowNode("collect", "human_input", "补充需求", null, null,
                    Map.of(), target, null, null, null, null, List.of(), null, null, null, null, null, null,
                    com.skillforge.core.workflow.execution.WorkflowModels.ExternalNodeSpec.empty());
            Workflow workflow = graph(human, node("next", "tool"));
            List<Checkpoint> checkpoints = new ArrayList<>();
            assertEquals("waiting_hitl", engine.runControlled(request(workflow, null), hooks(checkpoints)).get("status"));
            Checkpoint pending = checkpoints.getLast();
            Map<String, Object> response = Map.of("action", "confirm", "response", Map.of("deployment", "本地", "users", 80));
            Resume resume = new Resume(pending.nodeId(), false, pending.state(), pending.extracted(), pending.messages(), pending.cursor());
            Hooks crashAfterHuman = new Hooks() {
                public void beforeNode(String id) {}
                public void checkpoint(Checkpoint value) {
                    checkpoints.add(value);
                    if (value.boundary() == Boundary.PROGRESS) throw new IllegalStateException("restart after response checkpoint");
                }
            };
            Request input = new Request(workflow, Map.of(), "", "session", response, resume,
                    "instance", "7", List.of(), "execution", Map.of("end_user_id", 100L), null);
            assertThrows(IllegalStateException.class, () -> engine.runControlled(input, crashAfterHuman));
            Checkpoint saved = checkpoints.getLast();
            String key = target.contains(".") ? "requirements" : "collect";
            assertEquals(response, (target.startsWith("extracted") ? saved.extracted() : saved.state()).get(key));
            assertEquals("confirm", saved.extracted().get("action"));
            assertFalse(pending.state().containsKey("requirements"));
            assertFalse(pending.extracted().containsKey("requirements"));
            Hooks recovered = new Hooks() {
                public void beforeNode(String id) { assertEquals("next", id); }
                public void checkpoint(Checkpoint value) {}
                public com.skillforge.core.workflow.execution.node.NodeOutcome executeExternalNode(
                        com.skillforge.core.workflow.execution.node.NodeExecutionContext context) {
                    assertEquals(response, (target.startsWith("extracted") ? context.extracted() : context.state()).get(key));
                    return com.skillforge.core.workflow.execution.node.NodeOutcome.continueFlow();
                }
            };
            Resume next = new Resume(saved.nextNodeId(), true, saved.state(), saved.extracted(), saved.messages(), saved.cursor());
            assertEquals("completed", engine.runControlled(request(workflow, next), recovered).get("status"));
        }
    }

    @Test
    void conditionRoutesBothEdgesWithoutExternalExecutor() {
        for (boolean needsMore : List.of(true, false)) {
            Workflow workflow = new Workflow("branching", "branch",
                    List.of(node("branch", "condition"), node("ask", "human_input"), node("done", "end")),
                    List.of(new WorkflowEdge("branch", "ask", "${state.needs_more} == true", 0, null),
                            new WorkflowEdge("branch", "done", "${state.needs_more} == false", 0, null)));
            Request request = new Request(workflow, Map.of(), "", "session", null, null,
                    "instance", "7", List.of(), "execution", Map.of("end_user_id", 100L),
                    new StateMachineEngine.InitialState(Map.of("needs_more", needsMore), Map.of()));
            List<Checkpoint> checkpoints = new ArrayList<>();
            assertEquals(needsMore ? "waiting_hitl" : "completed",
                    engine.runControlled(request, hooks(checkpoints)).get("status"));
            assertEquals(needsMore ? "ask" : "done", checkpoints.getFirst().nextNodeId());
        }
    }

    @Test
    void numericConditionsKeepAdjacentLargeIntegersDistinctAndCompareDecimalValues() {
        record Case(Number value, String condition, boolean matches) {}
        for (var test : List.of(
                new Case(9007199254740992L, "${state.value} == 9007199254740993", false),
                new Case(9007199254740992L, "${state.value} != 9007199254740993", true),
                new Case(9007199254740993L, "${state.value} > 9007199254740992", true),
                new Case(9007199254740992L, "${state.value} < 9007199254740993", true),
                new Case(new java.math.BigInteger("9223372036854775808"), "${state.value} > 9223372036854775807", true),
                new Case(new java.math.BigDecimal("1.25"), "${state.value} > 1.2", true),
                new Case(new java.math.BigDecimal("1.00"), "${state.value} == 1", true),
                new Case(1.5d, "${state.value} <= 1.5", true))) {
            var workflow = new Workflow("numeric", "branch",
                    List.of(node("branch", "condition"), node("matched", "end"), node("fallback", "end")),
                    List.of(new WorkflowEdge("branch", "matched", test.condition(), 1, null),
                            new WorkflowEdge("branch", "fallback", null, 0, null)));
            var request = new Request(workflow, Map.of(), "", "session", null, null,
                    "instance", "7", List.of(), "execution", Map.of("end_user_id", 100L),
                    new StateMachineEngine.InitialState(Map.of("value", test.value()), Map.of()));
            List<Checkpoint> checkpoints = new ArrayList<>();
            assertEquals("completed", engine.runControlled(request, hooks(checkpoints)).get("status"));
            assertEquals(test.matches() ? "matched" : "fallback", checkpoints.getFirst().nextNodeId(), test.condition());
        }
    }

    @Test
    void beforeFailureAndCheckpointFailurePreventNextEffect() {
        Workflow workflow = graph(node("assign", "assign"), node("tool", "tool"));
        Hooks before = new Hooks() {
            public void beforeNode(String nodeId) { throw new IllegalStateException("lease lost"); }
            public void checkpoint(Checkpoint checkpoint) { fail("must not checkpoint"); }
        };
        assertThrows(IllegalStateException.class, () -> engine.runControlled(request(workflow, null), before));
        List<String> visited = new ArrayList<>();
        Hooks after = new Hooks() {
            public void beforeNode(String nodeId) { visited.add(nodeId); }
            public void checkpoint(Checkpoint checkpoint) { throw new IllegalStateException("lease lost"); }
        };
        assertThrows(IllegalStateException.class, () -> engine.runControlled(request(workflow, null), after));
        assertEquals(List.of("assign"), visited);
    }

    @Test
    void everyExternalNodeFailsClosedWithoutExplicitHook() {
        for (String type : List.of("tool", "llm", "agentic", "knowledge_query", "async_task", "script", "parallel", "subgraph")) {
            assertThrows(IllegalStateException.class, () -> engine.runControlled(
                    request(graph(node("external", type)), null), hooks(new ArrayList<>())));
        }
    }

    @Test
    void explicitExternalHookIsTheOnlyExecutorAndTerminalFailurePropagates() {
        List<String> calls = new ArrayList<>();
        Hooks controlled = new Hooks() {
            public void beforeNode(String nodeId) { calls.add("before:" + nodeId); }
            public com.skillforge.core.workflow.execution.node.NodeOutcome executeExternalNode(
                    com.skillforge.core.workflow.execution.node.NodeExecutionContext context) {
                calls.add("external:" + context.node().id());
                assertNull(context.runId());
                return com.skillforge.core.workflow.execution.node.NodeOutcome.continueFlow();
            }
            public void checkpoint(Checkpoint checkpoint) {
                if (checkpoint.boundary() == Boundary.PROGRESS) return;
                assertEquals(Boundary.COMPLETED, checkpoint.boundary());
                assertEquals("tool", checkpoint.nodeId());
                throw new IllegalStateException("terminal fenced");
            }
        };
        assertThrows(IllegalStateException.class,
                () -> engine.runControlled(request(graph(node("tool", "tool")), null), controlled));
        assertEquals(List.of("before:tool", "external:tool"), calls);
    }

    @Test
    void preparedExternalVisitSurvivesCrashAndIsPersistedBeforeCalling() {
        Workflow workflow = graph(node("tool", "tool"));
        List<Checkpoint> checkpoints = new ArrayList<>();
        List<com.skillforge.core.workflow.execution.node.NodeCallIdentity> identities = new ArrayList<>();
        Hooks crash = new Hooks() {
            public void beforeNode(String node) {}
            public void checkpoint(Checkpoint value) { checkpoints.add(value); }
            public com.skillforge.core.workflow.execution.node.NodeOutcome executeExternalNode(
                    com.skillforge.core.workflow.execution.node.NodeExecutionContext context) {
                assertEquals(context.node().id(), checkpoints.getLast().cursor().activeNode());
                identities.add(context.callIdentity());
                throw new IllegalStateException("process stopped after preparing call");
            }
        };
        assertThrows(IllegalStateException.class, () -> engine.runControlled(request(workflow, null), crash));
        var saved = checkpoints.getLast();
        var resume = new Resume(saved.nextNodeId(), true, saved.state(), saved.extracted(), saved.messages(), saved.cursor());
        assertThrows(IllegalStateException.class, () -> engine.runControlled(request(workflow, resume), crash));
        assertEquals(identities.getFirst(), identities.getLast());
        assertEquals(1, checkpoints.getLast().cursor().steps());
        assertFalse(checkpoints.getLast().state().containsKey("visits"));
    }

    @Test
    void subgraphIncomingEdgeSurvivesPreparedCallRecoveryAndRejectsDefinitionDrift() {
        WorkflowNode start = node("start", "start");
        WorkflowNode subgraph = node("child", "subgraph");
        Map<String, Object> transferContext = Map.of("customer", "state.customer", "priority", "config.priority");
        WorkflowEdge edge = new WorkflowEdge("start", "child", null, 0, transferContext);
        Workflow workflow = new Workflow("controlled", "start", List.of(start, subgraph), List.of(edge));
        List<Checkpoint> checkpoints = new ArrayList<>();
        List<WorkflowEdge> incomingEdges = new ArrayList<>();
        List<com.skillforge.core.workflow.execution.node.NodeCallIdentity> identities = new ArrayList<>();
        Hooks crashBeforeExternalCall = new Hooks() {
            public void beforeNode(String nodeId) {}
            public void checkpoint(Checkpoint checkpoint) { checkpoints.add(checkpoint); }
            public com.skillforge.core.workflow.execution.node.NodeOutcome executeExternalNode(
                    com.skillforge.core.workflow.execution.node.NodeExecutionContext context) {
                assertEquals("child", context.node().id());
                assertEquals(edge, context.incomingEdge());
                assertEquals(transferContext, context.incomingEdge().transferContext());
                incomingEdges.add(context.incomingEdge());
                identities.add(context.callIdentity());
                throw new IllegalStateException("process stopped after preparing subgraph call");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> engine.runControlled(request(workflow, null), crashBeforeExternalCall));
        Checkpoint routed = checkpoints.getFirst();
        assertEquals(Boundary.PROGRESS, routed.boundary());
        assertEquals("child", routed.nextNodeId());
        assertEquals(edge, routed.cursor().incomingEdge());
        assertEquals(transferContext, routed.cursor().incomingEdge().transferContext());
        Checkpoint saved = checkpoints.getLast();
        assertEquals(Boundary.PROGRESS, saved.boundary());
        assertEquals("child", saved.cursor().activeNode());
        assertEquals(edge, saved.cursor().incomingEdge());
        assertEquals(transferContext, saved.cursor().incomingEdge().transferContext());

        Resume resume = new Resume(saved.nextNodeId(), true, saved.state(), saved.extracted(), saved.messages(), saved.cursor());
        assertThrows(IllegalStateException.class,
                () -> engine.runControlled(request(workflow, resume), crashBeforeExternalCall));
        assertEquals(List.of(edge, edge), incomingEdges);
        assertEquals(identities.getFirst(), identities.getLast());
        assertEquals(1, identities.getFirst().visit());

        WorkflowEdge driftedEdge = new WorkflowEdge("start", "child", null, 0,
                Map.of("customer", "state.account", "priority", "config.priority"));
        Cursor driftedCursor = new Cursor(saved.cursor().visits(), saved.cursor().activeNode(),
                saved.cursor().steps(), driftedEdge);
        Resume driftedResume = new Resume(saved.nextNodeId(), true, saved.state(), saved.extracted(),
                saved.messages(), driftedCursor);
        assertThrows(IllegalArgumentException.class,
                () -> engine.runControlled(request(workflow, driftedResume), crashBeforeExternalCall));
    }

    @Test
    void externalCheckpointFailurePreventsProviderDispatch() {
        Hooks fenced = new Hooks() {
            public void beforeNode(String node) {}
            public void checkpoint(Checkpoint value) { throw new IllegalStateException("lost fence"); }
            public com.skillforge.core.workflow.execution.node.NodeOutcome executeExternalNode(
                    com.skillforge.core.workflow.execution.node.NodeExecutionContext context) {
                fail("provider must not be called without committed preparation boundary");
                return null;
            }
        };
        assertThrows(IllegalStateException.class,
                () -> engine.runControlled(request(graph(node("tool", "tool")), null), fenced));
    }

    @Test
    void structuralLoopHasExplicitTerminalBoundary() {
        Workflow workflow = new Workflow("loop", "start", List.of(node("start", "start")),
                List.of(new WorkflowEdge("start", "start", null, 0, null)));
        List<Checkpoint> checkpoints = new ArrayList<>();
        assertEquals("loop_terminated", engine.runControlled(request(workflow, null), hooks(checkpoints)).get("status"));
        assertEquals(Boundary.LOOP_TERMINATED, checkpoints.getLast().boundary());
        assertEquals(LoopLimit.MAX_STEPS, checkpoints.getLast().loopLimit().orElseThrow());
    }

    @Test
    void pureNodeLoopReportsNodeRevisitLimit() {
        Workflow workflow = new Workflow("loop", "assign", List.of(node("assign", "assign")),
                List.of(new WorkflowEdge("assign", "assign", null, 0, null)));
        List<Checkpoint> checkpoints = new ArrayList<>();
        assertEquals("loop_terminated", engine.runControlled(request(workflow, null), hooks(checkpoints)).get("status"));
        assertEquals(LoopLimit.NODE_REVISIT, checkpoints.getLast().loopLimit().orElseThrow());
        assertTrue(checkpoints.stream().filter(value -> value.boundary() != Boundary.LOOP_TERMINATED)
                .allMatch(value -> value.loopLimit().isEmpty()));
    }

    private static Hooks hooks(List<Checkpoint> checkpoints) {
        return new Hooks() {
            public void beforeNode(String nodeId) {}
            public void checkpoint(Checkpoint checkpoint) { checkpoints.add(checkpoint); }
        };
    }

    private static Request request(Workflow workflow, Resume resume) {
        return new Request(workflow, Map.of(), "input", "session", Map.of("action", "confirm"), resume,
                "instance", "7", List.of(), "execution", Map.of("end_user_id", 100L), null);
    }

    private static WorkflowNode node(String id, String type) {
        return new WorkflowNode(id, type, id, null, null, Map.of("ready", true), "state", "done",
                null, null, null, List.of(), null, null, null, null, null, null, com.skillforge.core.workflow.execution.WorkflowModels.ExternalNodeSpec.empty());
    }

    private static Workflow graph(WorkflowNode... nodes) {
        List<WorkflowEdge> edges = new ArrayList<>();
        for (int i = 1; i < nodes.length; i++) edges.add(new WorkflowEdge(nodes[i - 1].id(), nodes[i].id(), null, 0, null));
        return new Workflow("controlled", nodes[0].id(), List.of(nodes), edges);
    }
}

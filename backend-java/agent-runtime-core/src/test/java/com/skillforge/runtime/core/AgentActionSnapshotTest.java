package com.skillforge.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.DelegationCommand;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** actionsTaken 的结构契约；构造合法不证明 journal、事件或当前执行资格真实存在。 */
class AgentActionSnapshotTest {
    /** Agent 动作必须绑定同输入能力快照；四类外部动作还必须绑定原请求 journal。 */
    @Test
    void requiresAgentSnapshotAndExternalJournalForAllActionKinds() {
        var context = ContextSnapshotCodecTest.fixture();
        var operation = context.operation();
        var capability = context.capabilitySnapshot();
        var external = List.<AgentAction>of(new AgentAction.ToolCall("tool", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")),
                new AgentAction.Dispatch("workflow"), new AgentAction.KnowledgeQuery("knowledge", "question"),
                delegate());
        for (var action : external) {
            var snapshot = new AgentActionSnapshot(operation, FrozenContextSnapshot.ContextScope.AGENT_INPUT,
                    capability, action, Optional.of(new ResultRef("journal")), new AgentActionSnapshot.Pending());
            assertEquals(action, snapshot.action());
            assertThrows(IllegalArgumentException.class, () -> new AgentActionSnapshot(operation,
                    FrozenContextSnapshot.ContextScope.AGENT_INPUT, capability, action, Optional.empty(),
                    new AgentActionSnapshot.Pending()));
        }

        var local = List.<AgentAction>of(new AgentAction.Respond("answer", List.of()),
                new AgentAction.ActivateSkill("skill"), new AgentAction.AskUser("question"));
        for (var action : local) {
            var snapshot = new AgentActionSnapshot(operation, FrozenContextSnapshot.ContextScope.AGENT_INPUT,
                    capability, action, Optional.empty(), new AgentActionSnapshot.Pending());
            assertEquals(action, snapshot.action());
            assertThrows(IllegalArgumentException.class, () -> new AgentActionSnapshot(operation,
                    FrozenContextSnapshot.ContextScope.AGENT_INPUT, capability, action,
                    Optional.of(new ResultRef("journal")), new AgentActionSnapshot.Pending()));
        }
        assertThrows(IllegalArgumentException.class, () -> new AgentActionSnapshot(operation,
                FrozenContextSnapshot.ContextScope.AGENT_INPUT, Optional.empty(), local.getFirst(), Optional.empty(),
                new AgentActionSnapshot.Pending()));
    }

    /** 受限 Workflow 只记录局部 Respond/AskUser，且不能补造 Agent capability snapshot 或外调 journal。 */
    @Test
    void restrictsWorkflowSnapshotsWithoutInventingAgentSources() {
        var operation = ContextSnapshotCodecTest.fixture().operation();
        for (var action : List.<AgentAction>of(new AgentAction.Respond("answer", List.of()),
                new AgentAction.AskUser("question"))) {
            var snapshot = new AgentActionSnapshot(operation, FrozenContextSnapshot.ContextScope.RESTRICTED_WORKFLOW,
                    Optional.empty(), action, Optional.empty(), new AgentActionSnapshot.Pending());
            assertTrue(snapshot.capabilitySnapshot().isEmpty());
            assertTrue(snapshot.requestJournalRef().isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> new AgentActionSnapshot(operation,
                FrozenContextSnapshot.ContextScope.RESTRICTED_WORKFLOW,
                ContextSnapshotCodecTest.fixture().capabilitySnapshot(), new AgentAction.AskUser("question"),
                Optional.empty(), new AgentActionSnapshot.Pending()));
        assertThrows(IllegalArgumentException.class, () -> new AgentActionSnapshot(operation,
                FrozenContextSnapshot.ContextScope.RESTRICTED_WORKFLOW, Optional.empty(),
                new AgentAction.ToolCall("tool", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")), Optional.of(new ResultRef("journal")),
                new AgentActionSnapshot.Pending()));
    }

    /** 快照归属必须匹配完整输入 scope，不能把另一个 input 的能力清单拼到当前 operation。 */
    @Test
    void rejectsCapabilityScopeDrift() {
        var context = ContextSnapshotCodecTest.fixture();
        var original = context.capabilitySnapshot().orElseThrow();
        var drifted = new CapabilitySnapshotRef(original.sessionRef(),
                new InputId("other-input"), original.snapshotId(), original.generation());
        assertThrows(IllegalArgumentException.class, () -> new AgentActionSnapshot(context.operation(),
                FrozenContextSnapshot.ContextScope.AGENT_INPUT, Optional.of(drifted),
                new AgentAction.AskUser("question"), Optional.empty(), new AgentActionSnapshot.Pending()));
    }

    /** 封闭 outcome 的确定性分类保持精确，日志不展开安全引用。 */
    @Test
    void preservesOutcomeKindsAndRedactsReferences() {
        var unresolved = List.<AgentActionSnapshot.Outcome>of(new AgentActionSnapshot.Pending(),
                new AgentActionSnapshot.Accepted(Optional.of(new ResultRef("pending"))),
                new AgentActionSnapshot.Suspended("suspend", new ResultRef("waiting"),
                        com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL),
                new AgentActionSnapshot.OutcomeUnknown(new ResultRef("unknown")));
        var resolved = List.<AgentActionSnapshot.Outcome>of(
                new AgentActionSnapshot.Completed(new ResultRef("complete"), new EventId("event-complete")),
                new AgentActionSnapshot.Failed(new ResultRef("failed"), Optional.empty()),
                new AgentActionSnapshot.Rejected(new ResultRef("rejected"), new EventId("event-rejected")),
                new AgentActionSnapshot.Cancelled(new ResultRef("cancelled"), new EventId("event-cancelled")));
        unresolved.forEach(outcome -> assertFalse(outcome.resolved()));
        resolved.forEach(outcome -> assertTrue(outcome.resolved()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentActionSnapshot.Suspended(" ", new ResultRef("waiting"),
                        com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL));
        resolved.forEach(outcome -> assertFalse(outcome.toString().contains("event-")));
    }

    /** 只有 Knowledge 的确定失败可以没有 Observation 事件；其他动作缺事件属于损坏历史。 */
    @Test
    void permitsMissingFailureEventOnlyForKnowledge() {
        var context = ContextSnapshotCodecTest.fixture();
        var failed = new AgentActionSnapshot.Failed(new ResultRef("failed"), Optional.empty());
        var knowledge = new AgentActionSnapshot(context.operation(), FrozenContextSnapshot.ContextScope.AGENT_INPUT,
                context.capabilitySnapshot(), new AgentAction.KnowledgeQuery("knowledge", "question"),
                Optional.of(new ResultRef("journal")), failed);
        assertEquals(failed, knowledge.outcome());
        for (var action : List.<AgentAction>of(new AgentAction.ToolCall("tool", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")),
                new AgentAction.Dispatch("workflow"),
                delegate())) {
            assertThrows(IllegalArgumentException.class, () -> new AgentActionSnapshot(context.operation(),
                    FrozenContextSnapshot.ContextScope.AGENT_INPUT, context.capabilitySnapshot(), action,
                    Optional.of(new ResultRef("journal")), failed));
        }
    }

    private static AgentAction.Delegate delegate() {
        return new AgentAction.Delegate(DelegationCommand.start("agent",
                new DelegationCommand.Task("task", "", List.of(), "summary")));
    }
}

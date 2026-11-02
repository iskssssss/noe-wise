package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.DelegationCommand;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 新执行入口验证能力语义、整批预检及原未决身份，不装配 Provider 或持久化替身。 */
class AgentExecutionLoopTest {
    private static final CodecLimits LIMITS = new CodecLimits(100_000, 10_000, 64);
    private static final LoopBudget.Enforced BUDGET = new LoopBudget.Enforced(10, 10, 10,
            10_000, 10_000, 20_000, Duration.ofMinutes(5));
    private final AgentExecutionLoop loop = new AgentExecutionLoop(LIMITS);

    /** 工具、Knowledge、Workflow、委派和本地等待均保留原动作，计算无需阶段 journal。 */
    @Test
    void routesRealCapabilityAndLocalDecisionsWithoutJournalInput() {
        var context = context(1, List.of("tool:one", "workflow:one", "knowledge:one", "skill:one"));
        var delegate = new AgentAction.Delegate(DelegationCommand.start("agent:one",
                new DelegationCommand.Task("目标", "", List.of(), "结果")));
        var actions = List.<AgentAction>of(tool("tool:one"), new AgentAction.Dispatch("workflow:one"),
                new AgentAction.KnowledgeQuery("knowledge:one", "精确查询"), delegate,
                new AgentAction.Respond("完整回答", List.of()), new AgentAction.AskUser("补充哪一项？"),
                new AgentAction.ActivateSkill("skill:one"), new AgentAction.ReadSkillResource("skill:one", "guide.md"));
        for (var action : actions) {
            var decision = loop.decode(new AgentDecisionDecoder(LIMITS).encode(action));
            var delegation = new AgentDecisionRouter.DelegationSelection.Configured(context.operation(), List.of("agent:one"));
            var input = new AgentExecutionLoop.DecisionInput(decision, context, BUDGET, usage(1, 0),
                    Duration.ZERO, List.of(), delegation, ToolCallRouting.none(context));
            var routed = assertInstanceOf(AgentExecutionLoop.Single.class, loop.next(input));
            assertEquals(action, assertInstanceOf(AgentDecisionRouter.Selected.class, routed.route()).action());
            assertEquals(usage(1, 0), input.usage());
        }
        var missing = assertInstanceOf(AgentExecutionLoop.Single.class, loop.next(input(
                new AgentDecisionDecoder.Decoded(delegate), context, BUDGET, usage(1, 0), List.of())));
        assertInstanceOf(AgentDecisionRouter.MissingDelegation.class, missing.route());
    }

    /** 已付费模型回执越线时保留回答和提问；同一用量阻止新的工具派发。 */
    @Test
    void keepsPaidLocalCompletionWhileRejectingFurtherExternalWork() {
        var context = context(1, List.of("tool:one"));
        var usage = new LoopUsage(1, 0, 0,
                new LoopUsage.TokenTally(BUDGET.maxInputTokens(), true), new LoopUsage.TokenTally(0, true));
        for (var action : List.<AgentAction>of(new AgentAction.Respond("回答", List.of()), new AgentAction.AskUser("问题"))) {
            var selected = assertInstanceOf(AgentExecutionLoop.Single.class, loop.next(input(
                    new AgentDecisionDecoder.Decoded(action), context, BUDGET, usage, List.of())));
            assertInstanceOf(AgentDecisionRouter.Selected.class, selected.route());
        }
        var blocked = assertInstanceOf(AgentExecutionLoop.Single.class, loop.next(input(
                new AgentDecisionDecoder.Decoded(tool("tool:one")), context, BUDGET, usage, List.of())));
        assertEquals(new AgentDecisionRouter.BudgetDenied(BudgetGuard.Limit.INPUT_TOKEN_BUDGET_EXCEEDED), blocked.route());
    }

    /** 后续槽位被预算或可见性拒绝时，不返回前面已经选中的部分动作。 */
    @Test
    void rejectsWholeBatchWhenLaterSlotCannotRun() {
        var context = context(1, List.of("tool:one", "tool:two"));
        var budget = new LoopBudget.Enforced(10, 1, 10, 10_000, 10_000, 20_000, Duration.ofMinutes(5));
        var decision = new AgentDecisionDecoder.DecodedBatch(List.of(tool("tool:one"), tool("tool:two")));
        var input = input(decision, context, budget, usage(1, 0), List.of());
        var blocked = assertInstanceOf(AgentExecutionLoop.Single.class, loop.next(input));
        assertEquals(new AgentDecisionRouter.BudgetDenied(BudgetGuard.Limit.TOOL_CALL_BUDGET_EXCEEDED), blocked.route());
        assertEquals(0, input.usage().toolCalls());

        var invisible = assertInstanceOf(AgentExecutionLoop.Single.class, loop.next(input(
                new AgentDecisionDecoder.DecodedBatch(List.of(tool("tool:one"), tool("tool:missing"))),
                context, BUDGET, usage(1, 0), List.of())));
        assertEquals(new AgentDecisionRouter.Rejected(AgentDecisionRouter.Rejection.CAPABILITY_NOT_VISIBLE), invisible.route());
        var repeated = assertInstanceOf(AgentExecutionLoop.Single.class, loop.next(input(
                new AgentDecisionDecoder.DecodedBatch(List.of(tool("tool:one"), tool("tool:one"))),
                context, BUDGET, usage(1, 0), List.of())));
        assertEquals(new AgentDecisionRouter.Rejected(AgentDecisionRouter.Rejection.INVALID_HISTORY), repeated.route());
    }

    /** 结果已知只复用原结果，UNKNOWN 沿原身份收敛；两者都不占用新派发次数。 */
    @Test
    void retainsOrderedKnownAndUnknownIdentitiesAtToolBudgetLimit() {
        var context = context(3, List.of("tool:one", "tool:two"));
        var completed = history(context, 1, tool("tool:one"), new AgentActionSnapshot.Completed(
                new ResultRef("safe-result"), new EventId("safe-event")));
        var unknown = history(context, 2, tool("tool:two"), new AgentActionSnapshot.OutcomeUnknown(new ResultRef("unknown-result")));
        var budget = new LoopBudget.Enforced(10, 2, 10, 10_000, 10_000, 20_000, Duration.ofMinutes(5));
        var decision = new AgentDecisionDecoder.DecodedBatch(List.of(tool("tool:two"), tool("tool:one")));
        var result = assertInstanceOf(AgentExecutionLoop.Batch.class, loop.next(input(
                decision, context, budget, usage(3, 2), List.of(completed, unknown))));
        assertSame(unknown, assertInstanceOf(AgentDecisionRouter.Converge.class, result.routes().getFirst()).original());
        assertSame(completed, assertInstanceOf(AgentDecisionRouter.Reuse.class, result.routes().getLast()).original());
        assertThrows(UnsupportedOperationException.class, () -> result.routes().clear());
    }

    /** 同轮部分结果和完整未决批次沿原槽位恢复，不能将相邻槽位误判为损坏历史。 */
    @Test
    void resumesCurrentBatchByOriginalOrdinalWithoutDispatchingCompletedSlotsAgain() {
        var context = context(1, List.of("tool:one", "tool:two"));
        var one = tool("tool:one"); var two = tool("tool:two");
        var decision = new AgentDecisionDecoder.DecodedBatch(List.of(one, two));
        var completed = new AgentActionSnapshot(context.operation(), context.contextScope(), context.capabilitySnapshot(),
                one, Optional.of(new ResultRef("request-slot-one")),
                new AgentActionSnapshot.Completed(new ResultRef("safe-one"), new EventId("event-one")), 1);
        var pending = new AgentActionSnapshot(context.operation(), context.contextScope(), context.capabilitySnapshot(),
                two, Optional.of(new ResultRef("request-slot-two")), new AgentActionSnapshot.Pending(), 2);
        var partial = assertInstanceOf(AgentExecutionLoop.Batch.class, loop.next(input(
                decision, context, BUDGET, usage(1, 1), List.of(completed))));
        assertSame(completed, assertInstanceOf(AgentDecisionRouter.Reuse.class, partial.routes().getFirst()).original());
        assertEquals(two, assertInstanceOf(AgentDecisionRouter.Selected.class, partial.routes().getLast()).action());
        var full = assertInstanceOf(AgentExecutionLoop.Batch.class, loop.next(input(
                decision, context, BUDGET, usage(1, 2), List.of(completed, pending))));
        assertSame(completed, assertInstanceOf(AgentDecisionRouter.Reuse.class, full.routes().getFirst()).original());
        assertSame(pending, assertInstanceOf(AgentDecisionRouter.Converge.class, full.routes().getLast()).original());

        var wrongSlot = new AgentActionSnapshot(context.operation(), context.contextScope(), context.capabilitySnapshot(),
                two, Optional.of(new ResultRef("wrong-slot-request")), new AgentActionSnapshot.Pending(), 1);
        var rejected = assertInstanceOf(AgentExecutionLoop.Single.class, loop.next(input(
                decision, context, BUDGET, usage(1, 1), List.of(wrongSlot))));
        assertEquals(new AgentDecisionRouter.Rejected(AgentDecisionRouter.Rejection.INVALID_HISTORY), rejected.route());
    }

    /** 当前快照的解析失败显式返回反馈，不伪造回答或新的工具动作。 */
    @Test
    void returnsTypedDecodeFailureForCurrentModelResult() {
        var context = context(1, List.of());
        var failure = loop.decode(new TypedPayload("unsupported", "{}"));
        var result = assertInstanceOf(AgentExecutionLoop.Single.class, loop.next(input(
                failure, context, BUDGET, usage(1, 0), List.of())));
        assertEquals(new AgentDecisionRouter.DecodeFeedback(AgentDecisionDecoder.Reason.UNSUPPORTED_SCHEMA), result.route());
    }

    /** 仅替换本轮纯计算字段，沿用合法模型材料；不构造来源放行实现。 */
    private static FrozenContextSnapshot context(int iteration, List<String> handles) {
        var source = ContextSnapshotCodecTest.fixture();
        var operation = new ModelPreparationIdentity(source.operation().runIdentity(), source.operation().loopId(), iteration);
        return new FrozenContextSnapshot(operation, source.contextScope(), source.executionRef(), source.capabilitySnapshot(),
                source.persona(), source.relationship(), source.blocks(), source.evidenceRefs(), handles,
                source.modelRequest(), source.budget(), source.viewVersion(), source.createdAt());
    }

    /** 传入模型结果采纳后的计数，Core 不再额外递增一次模型调用。 */
    private static LoopUsage usage(int iterations, int tools) {
        return new LoopUsage(iterations, tools, 0, new LoopUsage.TokenTally(0, true), new LoopUsage.TokenTally(0, true));
    }

    /** 规范工具参数只表示模型意图，不证明真实工具已执行。 */
    private static AgentAction.ToolCall tool(String handle) {
        return new AgentAction.ToolCall(handle, new TypedPayload("application/json", "{}"));
    }

    /** 历史身份从当前 Run 纯视图派生，保留原请求引用供重复治理比较。 */
    private static AgentActionSnapshot history(FrozenContextSnapshot context, int iteration,
            AgentAction.ToolCall action, AgentActionSnapshot.Outcome outcome) {
        var operation = new ModelPreparationIdentity(context.operation().runIdentity(), context.operation().loopId(), iteration);
        return new AgentActionSnapshot(operation, context.contextScope(), context.capabilitySnapshot(), action,
                Optional.of(new ResultRef("request-" + iteration)), outcome);
    }

    /** 没有委派目标的纯计算用例显式声明缺省能力，不从工具句柄推断。 */
    private static AgentExecutionLoop.DecisionInput input(AgentDecisionDecoder.Result decision,
            FrozenContextSnapshot context, LoopBudget budget, LoopUsage usage, List<AgentActionSnapshot> history) {
        return new AgentExecutionLoop.DecisionInput(decision, context, budget, usage, Duration.ZERO, history,
                new AgentDecisionRouter.DelegationSelection.Absent(), ToolCallRouting.none(context));
    }
}

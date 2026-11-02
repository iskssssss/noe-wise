package com.skillforge.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.core.BudgetGuard.Limit;
import com.skillforge.runtime.core.BudgetGuard.Operation;
import com.skillforge.runtime.core.LoopUsage.TokenTally;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 预算边界和输入不变量测试；所有数值是显式测试夹具，不是生产默认策略。 */
class BudgetGuardTest {
    @ParameterizedTest
    @EnumSource(Operation.class)
    void observeOnlyRetainsMeasuredUsageWithoutCumulativeRejection(Operation operation) {
        var used = usage(1_000, 2_000, 3_000, 4_000_000, 5_000_000);
        assertEquals(Optional.empty(), BudgetGuard.evaluate(new LoopBudget.ObserveOnly(), used, operation, Duration.ofDays(10)));
        assertThrows(IllegalArgumentException.class, () -> BudgetGuard.evaluate(new LoopBudget.ObserveOnly(), used,
                operation, Duration.ofNanos(-1)));
    }

    @Test void loopPolicyComesFromTheFrozenRegistrationMode() {
        var capacity = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Resources(7, 6, 5, 4, 100, 200, 300);
        var limits = new com.skillforge.runtime.api.port.ExecutionBudgetPort.Limits(
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.STANDARD, capacity, capacity, capacity, 60, 30, "v1");
        assertEquals(new LoopBudget.Enforced(7, 5, 4, 100, 200, 300, Duration.ofSeconds(30)),
                LoopBudget.from(new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(limits)));
        assertEquals(new LoopBudget.ObserveOnly(), LoopBudget.from(new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.ObserveOnly(
                com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario.STANDARD, "v2")));
    }

    /** 提供分量总和能单独触达 total 上限的有限预算。 */
    private static LoopBudget budget() {
        return new LoopBudget.Enforced(2, 3, 4, 10, 20, 25, Duration.ofNanos(100));
    }

    /** 只创建完整报告的用量，缺报场景在独立测试显式标明。 */
    private static LoopUsage usage(int decisions, int tools, int knowledge, long input, long output) {
        return new LoopUsage(decisions, tools, knowledge, new TokenTally(input, true), new TokenTally(output, true));
    }

    /** 零业务额度是明确禁用，既不伪造一次额度，也不禁止处理原结果或无关工具。 */
    @Test
    void zeroBusinessAllowanceRejectsOnlyNewBusinessModelCalls() {
        var budget = new LoopBudget.Enforced(0, 1, 1, 10, 10, 20, Duration.ofSeconds(1));
        assertEquals(Optional.of(Limit.BUSINESS_MODEL_CALL_BUDGET_EXCEEDED),
                BudgetGuard.evaluate(budget, LoopUsage.empty(), Operation.BUSINESS_MODEL_CALL, Duration.ZERO));
        assertEquals(Optional.empty(), BudgetGuard.evaluate(budget, LoopUsage.empty(), Operation.CONTINUATION, Duration.ZERO));
        assertEquals(Optional.empty(), BudgetGuard.evaluate(budget, LoopUsage.empty(), Operation.TOOL, Duration.ZERO));
    }

    /** 每种动作均受 wall-time 和 token 限制；时间不降精度，也不主动读取系统时钟。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void commonLimitsApplyToEveryOperation(Operation operation) {
        assertEquals(Optional.empty(), BudgetGuard.evaluate(budget(), LoopUsage.empty(), operation, Duration.ofNanos(99)));
        assertEquals(Optional.of(Limit.WALL_TIME_BUDGET_EXCEEDED),
                BudgetGuard.evaluate(budget(), usage(2, 3, 4, 10, 20), operation, Duration.ofNanos(100)));
        assertEquals(Optional.of(Limit.INPUT_TOKEN_BUDGET_EXCEEDED),
                BudgetGuard.evaluate(budget(), usage(2, 3, 4, 10, 20), operation, Duration.ZERO));
        assertEquals(Optional.of(Limit.OUTPUT_TOKEN_BUDGET_EXCEEDED),
                BudgetGuard.evaluate(budget(), usage(2, 3, 4, 9, 20), operation, Duration.ZERO));
        assertEquals(Optional.of(Limit.TOTAL_TOKEN_BUDGET_EXCEEDED),
                BudgetGuard.evaluate(budget(), usage(2, 3, 4, 9, 16), operation, Duration.ZERO));
    }

    /** 计数只能拒绝相关动作；处理当前结果不重复消耗模型或能力次数。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void countersAreScopedToTheRequestedOperation(Operation operation) {
        Optional<Limit> expected = switch (operation) {
            case BUSINESS_MODEL_CALL -> Optional.of(Limit.BUSINESS_MODEL_CALL_BUDGET_EXCEEDED);
            case TOOL, WORKFLOW, DELEGATE -> Optional.of(Limit.TOOL_CALL_BUDGET_EXCEEDED);
            case KNOWLEDGE -> Optional.of(Limit.KNOWLEDGE_CALL_BUDGET_EXCEEDED);
            case CONTINUATION -> Optional.empty();
        };
        assertEquals(expected, BudgetGuard.evaluate(budget(), usage(2, 3, 4, 1, 1), operation, Duration.ZERO));
        assertEquals(Optional.empty(), BudgetGuard.evaluate(budget(), usage(1, 2, 3, 1, 1), operation, Duration.ZERO));
        assertEquals(Optional.empty(), BudgetGuard.evaluate(budget(),
                usage(operation == Operation.BUSINESS_MODEL_CALL ? 0 : 2,
                        operation == Operation.TOOL || operation == Operation.WORKFLOW || operation == Operation.DELEGATE ? 0 : 3,
                        operation == Operation.KNOWLEDGE ? 0 : 4, 0, 0), operation, Duration.ZERO));
    }

    /** 输入输出四种缺报组合独立保留完整性，未知分量不会伪装成确切零。 */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void preservesIndependentMissingUsageAndMonotonicCompleteness(int mask) {
        LoopUsage initial = LoopUsage.empty();
        LoopUsage first = initial.recordModelUsage(new ModelUsage(
                (mask & 1) == 0 ? OptionalLong.empty() : OptionalLong.of(2),
                (mask & 2) == 0 ? OptionalLong.empty() : OptionalLong.of(3)));
        assertTrue(initial.totalTokens().complete());
        assertEquals(0, initial.totalTokens().reportedTokens());
        assertEquals((mask & 1) != 0, first.inputTokens().complete());
        assertEquals((mask & 2) != 0, first.outputTokens().complete());
        LoopUsage next = first.recordModelUsage(new ModelUsage(OptionalLong.of(5), OptionalLong.of(7)));
        assertEquals(first.inputTokens().complete(), next.inputTokens().complete());
        assertEquals(first.outputTokens().complete(), next.outputTokens().complete());
        assertEquals(first.totalTokens().reportedTokens() + 12, next.totalTokens().reportedTokens());
        assertEquals(mask == 3, next.totalTokens().complete());
    }

    /** 未知下界仍触发资源拒绝；低下界放行不能抹掉未知标识或改变计数。 */
    @Test
    void incompleteUsageStillEnforcesKnownLowerBounds() {
        LoopUsage unknown = LoopUsage.empty().recordModelUsage(new ModelUsage(OptionalLong.empty(), OptionalLong.empty()));
        assertFalse(unknown.totalTokens().complete());
        assertTrue(BudgetGuard.evaluate(budget(), unknown, Operation.BUSINESS_MODEL_CALL, Duration.ZERO).isEmpty());
        LoopUsage reported = unknown.recordModelUsage(new ModelUsage(OptionalLong.of(10), OptionalLong.empty()));
        assertEquals(Optional.of(Limit.INPUT_TOKEN_BUDGET_EXCEEDED),
                BudgetGuard.evaluate(budget(), reported, Operation.TOOL, Duration.ZERO));
        assertFalse(reported.inputTokens().complete());
        assertEquals(0, reported.businessModelCalls());
        assertEquals(0, reported.toolCalls());
    }

    /** 每次记账返回新值；多次纯检查不能自动消费次数或修改冻结限制。 */
    @Test
    void countingAndChecksHaveNoImplicitSideEffects() {
        LoopUsage initial = LoopUsage.empty();
        LoopUsage next = initial.nextBusinessModelCall().nextToolCall().nextKnowledgeCall();
        assertEquals(usage(1, 1, 1, 0, 0), next);
        for (int index = 0; index < 5; index++) {
            // 反复询问并不等于取得调用许可或实际开始一次动作。
            assertTrue(BudgetGuard.evaluate(budget(), next, Operation.TOOL, Duration.ZERO).isEmpty());
        }
        assertEquals(LoopUsage.empty(), initial);
        assertEquals(1, next.toolCalls());
    }

    /** 计数、分量与总量溢出均在产生新状态前拒绝，原状态保持不变。 */
    @Test
    void rejectsAllArithmeticOverflow() {
        assertThrows(ArithmeticException.class, () -> usage(Integer.MAX_VALUE, 0, 0, 0, 0).nextBusinessModelCall());
        assertThrows(ArithmeticException.class, () -> usage(0, Integer.MAX_VALUE, 0, 0, 0).nextToolCall());
        assertThrows(ArithmeticException.class, () -> usage(0, 0, Integer.MAX_VALUE, 0, 0).nextKnowledgeCall());
        LoopUsage maximum = usage(0, 0, 0, Long.MAX_VALUE, 0);
        assertThrows(ArithmeticException.class,
                () -> maximum.recordModelUsage(new ModelUsage(OptionalLong.of(1), OptionalLong.empty())));
        assertThrows(ArithmeticException.class,
                () -> maximum.recordModelUsage(new ModelUsage(OptionalLong.empty(), OptionalLong.of(1))));
        assertThrows(ArithmeticException.class, () -> usage(0, 0, 0, Long.MAX_VALUE, 1));
        assertEquals(Long.MAX_VALUE, maximum.totalTokens().reportedTokens());
    }

    /** 调用次数允许设为零以禁用；其他预算须为正，耗时和报告必须有效。 */
    @Test
    void rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> new LoopBudget.Enforced(-1, 1, 1, 1, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new LoopBudget.Enforced(1, -1, 1, 1, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new LoopBudget.Enforced(1, 1, -1, 1, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new LoopBudget.Enforced(1, 1, 1, 0, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new LoopBudget.Enforced(1, 1, 1, 1, 0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new LoopBudget.Enforced(1, 1, 1, 1, 1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new LoopBudget.Enforced(1, 1, 1, 1, 1, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new LoopBudget.Enforced(1, 1, 1, 1, 1, 1, Duration.ofNanos(-1)));
        assertThrows(NullPointerException.class, () -> new LoopBudget.Enforced(1, 1, 1, 1, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> usage(-1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> usage(0, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> usage(0, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenTally(-1, false));
        assertThrows(NullPointerException.class, () -> new LoopUsage(0, 0, 0, null, new TokenTally(0, true)));
        assertThrows(NullPointerException.class, () -> new LoopUsage(0, 0, 0, new TokenTally(0, true), null));
        assertThrows(NullPointerException.class, () -> LoopUsage.empty().recordModelUsage(null));
        assertThrows(NullPointerException.class, () -> BudgetGuard.evaluate(null, LoopUsage.empty(), Operation.TOOL, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> BudgetGuard.evaluate(budget(), null, Operation.TOOL, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> BudgetGuard.evaluate(budget(), LoopUsage.empty(), null, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> BudgetGuard.evaluate(budget(), LoopUsage.empty(), Operation.TOOL, null));
        assertThrows(IllegalArgumentException.class,
                () -> BudgetGuard.evaluate(budget(), LoopUsage.empty(), Operation.TOOL, Duration.ofNanos(-1)));
    }
}

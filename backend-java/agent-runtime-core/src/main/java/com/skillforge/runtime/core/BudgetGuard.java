package com.skillforge.runtime.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** 纯预算判定：不读时钟、不消费次数、不写账本、不替代 Policy 或外部调用许可。 */
public final class BudgetGuard {

    /** 无状态工具，不允许宿主创建包含隐式策略或配置的实例。 */
    private BudgetGuard() {
    }

    /** 下一步所需的资源类别；CONTINUATION 只处理当前结果，不重复计一次决策。 */
    public enum Operation { BUSINESS_MODEL_CALL, CONTINUATION, TOOL, KNOWLEDGE, WORKFLOW, DELEGATE }

    /** 与主事实源一致的稳定预算拒绝原因；不表示整个 Loop 的全部终止原因。 */
    public enum Limit {
        WALL_TIME_BUDGET_EXCEEDED,
        INPUT_TOKEN_BUDGET_EXCEEDED,
        OUTPUT_TOKEN_BUDGET_EXCEEDED,
        TOTAL_TOKEN_BUDGET_EXCEEDED,
        BUSINESS_MODEL_CALL_BUDGET_EXCEEDED,
        TOOL_CALL_BUDGET_EXCEEDED,
        KNOWLEDGE_CALL_BUDGET_EXCEEDED
    }

    /** 空结果仅表示预算未拒绝，不能作为已授予身份、策略或 Provider 调用权的证明。 */
    public static Optional<Limit> evaluate(LoopBudget budget, LoopUsage usage, Operation operation, Duration elapsed) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
        if (budget instanceof LoopBudget.ObserveOnly) return Optional.empty();
        var limits = (LoopBudget.Enforced) budget;

        // 先检查所有动作共用的限制，确定性优先级避免同一事实产生不同终止原因。
        if (elapsed.compareTo(limits.maxWallTime()) >= 0) {
            return Optional.of(Limit.WALL_TIME_BUDGET_EXCEEDED);
        }
        if (usage.inputTokens().reportedTokens() >= limits.maxInputTokens()) {
            return Optional.of(Limit.INPUT_TOKEN_BUDGET_EXCEEDED);
        }
        if (usage.outputTokens().reportedTokens() >= limits.maxOutputTokens()) {
            return Optional.of(Limit.OUTPUT_TOKEN_BUDGET_EXCEEDED);
        }
        if (usage.totalTokens().reportedTokens() >= limits.maxTotalTokens()) {
            return Optional.of(Limit.TOTAL_TOKEN_BUDGET_EXCEEDED);
        }

        // 缺报下界未达到限制时仍检查动作次数，不声称未知 token 的硬上限已被证明。
        return switch (operation) {
            case BUSINESS_MODEL_CALL -> usage.businessModelCalls() >= limits.maxBusinessModelCalls()
                    ? Optional.of(Limit.BUSINESS_MODEL_CALL_BUDGET_EXCEEDED) : Optional.empty();
            case TOOL, WORKFLOW, DELEGATE -> usage.toolCalls() >= limits.maxToolCalls()
                    ? Optional.of(Limit.TOOL_CALL_BUDGET_EXCEEDED) : Optional.empty();
            case KNOWLEDGE -> usage.knowledgeCalls() >= limits.maxKnowledgeCalls()
                    ? Optional.of(Limit.KNOWLEDGE_CALL_BUDGET_EXCEEDED) : Optional.empty();
            case CONTINUATION -> Optional.empty();
        };
    }
}

package com.skillforge.runtime.core;

import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import java.time.Duration;
import java.util.Objects;

/** input 受理时冻结的累计预算模式；不解析宿主配置，也不设定单次供应商输出限制。 */
public sealed interface LoopBudget {
    /** 调用额度允许零，表示禁止派发；Token 和时长须为正，Duration 保留纳秒精度。 */
    record Enforced(int maxBusinessModelCalls, int maxToolCalls, int maxKnowledgeCalls,
                    long maxInputTokens, long maxOutputTokens, long maxTotalTokens,
                    Duration maxWallTime) implements LoopBudget {
        public Enforced {
            if (maxBusinessModelCalls < 0 || maxToolCalls < 0 || maxKnowledgeCalls < 0
                    || maxInputTokens <= 0 || maxOutputTokens <= 0 || maxTotalTokens <= 0)
                throw new IllegalArgumentException("budget limits must be positive");
            Objects.requireNonNull(maxWallTime, "maxWallTime");
            if (maxWallTime.isZero() || maxWallTime.isNegative())
                throw new IllegalArgumentException("maxWallTime must be positive");
        }
    }
    /** 保留实际消耗、活动时间与取消语义，不施加累计资源或累计活动时间上限。 */
    record ObserveOnly() implements LoopBudget { }
    /** 从同一 Run 的冻结账本策略生成循环策略，避免宿主另造一套累计限制。 */
    static LoopBudget from(ExecutionBudgetPort.Policy policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy instanceof ExecutionBudgetPort.Policy.ObserveOnly) return new ObserveOnly();
        var limits = policy.requireEnforced();
        var run = limits.run();
        return new Enforced(Math.toIntExact(run.businessModels()), Math.toIntExact(run.tools()),
                Math.toIntExact(run.knowledge()), run.inputTokens(), run.outputTokens(), run.totalTokens(),
                Duration.ofSeconds(limits.runActiveSeconds()));
    }
}

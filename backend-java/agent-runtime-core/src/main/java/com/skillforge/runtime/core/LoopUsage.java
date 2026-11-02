package com.skillforge.runtime.core;

import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.util.Objects;
import java.util.OptionalLong;

/** 不可变的动作计数和 token 已报告下界；缺报完整性不会被后续报告覆盖。 */
public record LoopUsage(
        int businessModelCalls,
        int toolCalls,
        int knowledgeCalls,
        TokenTally inputTokens,
        TokenTally outputTokens) {

    /** 拒绝负计数、空分量及无法精确表示的总 token，不截断或饱和数值。 */
    public LoopUsage {
        if (businessModelCalls < 0 || toolCalls < 0 || knowledgeCalls < 0) {
            throw new IllegalArgumentException("usage counts must not be negative");
        }
        Objects.requireNonNull(inputTokens, "inputTokens");
        Objects.requireNonNull(outputTokens, "outputTokens");
        Math.addExact(inputTokens.reportedTokens(), outputTokens.reportedTokens());
    }

    /** 尚未发生模型或能力调用的已知零，不表示一次未知调用使用了零 token。 */
    public static LoopUsage empty() {
        return new LoopUsage(0, 0, 0, new TokenTally(0, true), new TokenTally(0, true));
    }

    /** 在业务模型结果采纳时推进次数；解码失败也消耗业务调用预算。 */
    public LoopUsage nextBusinessModelCall() {
        return new LoopUsage(Math.incrementExact(businessModelCalls), toolCalls, knowledgeCalls, inputTokens, outputTokens);
    }

    /** Tool、Workflow 与 Delegate 共用能力调用计数；本方法不授权任何外部调用。 */
    public LoopUsage nextToolCall() {
        return new LoopUsage(businessModelCalls, Math.incrementExact(toolCalls), knowledgeCalls, inputTokens, outputTokens);
    }

    /** Knowledge 独立计数，不能因重建上下文或恢复重置。 */
    public LoopUsage nextKnowledgeCall() {
        return new LoopUsage(businessModelCalls, toolCalls, Math.incrementExact(knowledgeCalls), inputTokens, outputTokens);
    }

    /** 只累计一次调用的最终报告；调用去重和流式 delta 合并由上层协调负责。 */
    public LoopUsage recordModelUsage(ModelUsage usage) {
        Objects.requireNonNull(usage, "usage");
        return new LoopUsage(businessModelCalls, toolCalls, knowledgeCalls,
                inputTokens.add(usage.inputTokens()), outputTokens.add(usage.outputTokens()));
    }

    /** 返回总量下界；只有两个分量都完整时才可声称是确切总量。 */
    public TokenTally totalTokens() {
        return new TokenTally(Math.addExact(inputTokens.reportedTokens(), outputTokens.reportedTokens()),
                inputTokens.complete() && outputTokens.complete());
    }

    /** 保存某个 token 分量的已报告下界与所有历史报告是否完整。 */
    public record TokenTally(long reportedTokens, boolean complete) {

        /** 即使不完整，已知下界也必须是可精确表示的非负整数。 */
        public TokenTally {
            if (reportedTokens < 0) {
                throw new IllegalArgumentException("reportedTokens must not be negative");
            }
        }

        /** 缺报仅降低完整性，不删除历史下界；后续报告永远不能恢复已丢失的完整性。 */
        public TokenTally add(OptionalLong reported) {
            Objects.requireNonNull(reported, "reported");
            if (reported.isEmpty()) {
                return new TokenTally(reportedTokens, false);
            }
            if (reported.getAsLong() < 0) {
                throw new IllegalArgumentException("reported token increment must not be negative");
            }
            return new TokenTally(Math.addExact(reportedTokens, reported.getAsLong()), complete);
        }
    }
}

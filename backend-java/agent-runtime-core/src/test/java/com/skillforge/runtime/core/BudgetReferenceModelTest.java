package com.skillforge.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 独立历史序列模型从完整报告历史重算预期值，不调用生产 TokenTally 计算期望。 */
class BudgetReferenceModelTest {

    /** 穷举五次调用的全部缺报组合，覆盖缺失后的多次补报、交错缺报与累计不变量。 */
    @Test
    void exhaustivelyMatchesReportHistoryFor1024Sequences() {
        List<ModelUsage> alphabet = List.of(
                new ModelUsage(OptionalLong.empty(), OptionalLong.empty()),
                new ModelUsage(OptionalLong.of(2), OptionalLong.empty()),
                new ModelUsage(OptionalLong.empty(), OptionalLong.of(3)),
                new ModelUsage(OptionalLong.of(5), OptionalLong.of(7)));
        int leaves = enumerate(LoopUsage.empty(), new ArrayList<>(), alphabet, 5);
        assertEquals(1024, leaves);
    }

    /** 递归状态由不可变生产值承载；预期由事件历史重新求和，避免复刻同一增量算法。 */
    private int enumerate(LoopUsage state, List<ModelUsage> history, List<ModelUsage> alphabet, int remaining) {
        BigInteger input = history.stream().filter(report -> report.inputTokens().isPresent())
                .map(report -> BigInteger.valueOf(report.inputTokens().getAsLong())).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger output = history.stream().filter(report -> report.outputTokens().isPresent())
                .map(report -> BigInteger.valueOf(report.outputTokens().getAsLong())).reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(input.longValueExact(), state.inputTokens().reportedTokens());
        assertEquals(output.longValueExact(), state.outputTokens().reportedTokens());
        assertEquals(input.add(output).longValueExact(), state.totalTokens().reportedTokens());
        assertEquals(history.stream().allMatch(report -> report.inputTokens().isPresent()), state.inputTokens().complete());
        assertEquals(history.stream().allMatch(report -> report.outputTokens().isPresent()), state.outputTokens().complete());
        assertEquals(history.stream().allMatch(report -> report.inputTokens().isPresent() && report.outputTokens().isPresent()),
                state.totalTokens().complete());
        assertEquals(history.size(), state.businessModelCalls());
        if (remaining == 0) {
            return 1;
        }

        int leaves = 0;
        for (ModelUsage report : alphabet) {
            // 每个分支代表一次新模型调用的最终报告，历史分支退出时恢复，不模拟持久账本去重。
            history.add(report);
            leaves += enumerate(state.nextBusinessModelCall().recordModelUsage(report), history, alphabet, remaining - 1);
            history.removeLast();
        }
        return leaves;
    }
}

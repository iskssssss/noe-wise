package com.skillforge.runtime.session;

import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.Dimension;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.Resources;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 将真实账本快照转换为模型可读观察，不签发调用许可，也不修改预算。 */
final class AgentExecutionBudgetContext {
    private static final String MESSAGE = "execution-budget:";

    private AgentExecutionBudgetContext() { }

    /** 采纳和恢复只复用原实际请求的观察文本，不重新读取当前账本。 */
    static List<ModelMessage> restore(List<ModelMessage> original, List<ModelMessage> frozenRequestMessages, long ordinal) {
        var result = new ArrayList<>(original.stream()
                .filter(message -> !message.messageId().startsWith(MESSAGE)).toList());
        var frozen = frozenRequestMessages.stream().filter(message -> message.messageId().startsWith(MESSAGE)).toList();
        if (frozen.size() > 1 || frozen.stream().anyMatch(message -> message.role() != ModelRole.USER
                || !message.messageId().equals(MESSAGE + ordinal)))
            throw new IllegalArgumentException("execution_budget_context_invalid");
        result.addAll(frozen);
        return List.copyOf(result);
    }

    static List<ModelMessage> messages(List<ModelMessage> original,
            Optional<ExecutionBudgetPort.Snapshot> snapshot, long ordinal) {
        var result = new ArrayList<>(original.stream()
                .filter(message -> !message.messageId().startsWith(MESSAGE)).toList());
        if (snapshot.isEmpty()) return List.copyOf(result);
        var value = snapshot.orElseThrow();
        var text = new StringBuilder("执行预算观察：本次请求预留前的账本快照，替代此前的预算观察。\n")
                .append("本次输入/输出还会消耗额度；收尾保护会随实际请求重新计量；可用量不代表调用许可，当前允许动作以后续执行要求为准。\n");
        if (value.registration().policy() instanceof Policy.Enforced enforced) {
            text.append("已扣除已消耗与全部预留，结果未知（UNKNOWN）的预留仍占用额度。\n");
            var limits = enforced.limits();
            var root = limits.root().remainingAfter(value.consumed()).remainingAfter(value.reserved());
            var run = limits.run().remainingAfter(value.runConsumed()).remainingAfter(value.runReserved());
            long[] remaining = new long[Dimension.values().length];
            for (var dimension : Dimension.values())
                remaining[dimension.ordinal()] = Math.min(root.value(dimension), run.value(dimension));
            var balance = Resources.from(remaining);
            var business = balance.remainingAfter(value.closing().resources());
            text.append("采样时阶段：").append(value.phase() == ExecutionBudgetPort.Phase.BUSINESS ? "业务" : "收尾")
                    .append("。以下余额取共享总预算与当前执行预算中较紧者。\n");
            text.append("账本余额：");
            appendResources(text, balance);
            text.append("当前账本收尾保护：");
            appendResources(text, value.closing().resources());
            text.append("扣除当前收尾保护后可用于业务的额度：");
            appendResources(text, business);
            long activeMillis = Math.min(Math.max(0, limits.rootActiveSeconds() * 1000 - value.activeMillis()),
                    Math.max(0, limits.runActiveSeconds() * 1000 - value.runActiveMillis()));
            long protectedMillis = value.closing().activeSeconds() * 1000;
            text.append("活跃剩余时间：").append(activeMillis).append(" 毫秒；当前收尾时间保护：")
                    .append(protectedMillis).append(" 毫秒；扣除保护后业务可用时间：")
                    .append(Math.max(0, activeMillis - protectedMillis)).append(" 毫秒。\n");
            if (value.phase() == ExecutionBudgetPort.Phase.CLOSING)
                text.append("已进入收尾阶段，业务可用量仅为算术观察，不表示可以继续业务调用。\n");
        } else {
            text.append("当前策略仅计量，未设置数值限额；业务模型、工具、知识调用、Token 和活跃时间的剩余额度未定义。\n");
        }
        result.add(new ModelMessage(MESSAGE + ordinal, ModelRole.USER, text.toString()));
        return List.copyOf(result);
    }

    private static void appendResources(StringBuilder text, Resources resources) {
        text.append("业务模型 ").append(resources.businessModels()).append(" 次，工具 ")
                .append(resources.tools()).append(" 次，知识 ").append(resources.knowledge())
                .append(" 次，总 Token ").append(resources.totalTokens()).append("。\n");
    }
}

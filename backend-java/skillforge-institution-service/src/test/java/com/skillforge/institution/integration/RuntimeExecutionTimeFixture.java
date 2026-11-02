package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.session.AgentExecutionTime;
import com.skillforge.runtime.session.AgentExecutionOutputMessageFixture;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** HTTP 验收只移除已通过正式契约验证的末条时间样本，不过滤其他 SYSTEM、USER 或业务历史。 */
final class RuntimeExecutionTimeFixture {
    private static final Instant SAMPLED_AT = Instant.parse("2026-10-22T01:02:03.123456789Z");

    private RuntimeExecutionTimeFixture() { }

    /** 实际 OpenAI USER wire 仅含 role/content；这里只验证正文时间契约，准备序号身份由 Runtime 冻结请求验证。 */
    static JsonNode businessMessages(JsonNode request, long expectedOrdinal) throws IOException {
        var messages = request.path("messages");
        if (!messages.isArray() || messages.isEmpty()) throw invalid();
        var footer = messages.get(messages.size() - 1);
        if (!footer.isObject() || footer.size() != 2 || !"user".equals(footer.path("role").asText())
                || !footer.path("content").isTextual()) throw invalid();
        try {
            AgentExecutionTime.frozen(List.of(new ModelMessage("time-context:" + expectedOrdinal,
                    ModelRole.USER, footer.path("content").textValue())), expectedOrdinal);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        var business = ((ArrayNode) messages).deepCopy();
        business.remove(business.size() - 1);
        return business;
    }

    private static final String BUDGET_PREFIX = "执行预算观察：本次请求预留前的账本快照，替代此前的预算观察。\n"
            + "本次输入/输出还会消耗额度；收尾保护会随实际请求重新计量；可用量不代表调用许可，当前允许动作以后续执行要求为准。\n";
    static final String HUMAN_RECEIPT = "用户已回答，回答原文见下一条用户消息；按其真实内容继续，不推定额外授权。";

    /** 只识别 Runtime 预算观察的固定文本及策略，不跳过任意 USER 或 SYSTEM 消息。 */
    static boolean isBudget(JsonNode message) {
        if (!message.isObject() || message.size() != 2 || !"user".equals(message.path("role").asText())
                || !message.path("content").isTextual()) return false;
        String content = message.path("content").textValue();
        if (!content.startsWith(BUDGET_PREFIX)) return false;
        String details = content.substring(BUDGET_PREFIX.length());
        return details.equals("当前策略仅计量，未设置数值限额；业务模型、工具、知识调用、Token 和活跃时间的剩余额度未定义。\n")
                || details.matches("已扣除已消耗与全部预留，结果未知（UNKNOWN）的预留仍占用额度。\n"
                        + "采样时阶段：(业务|收尾)。以下余额取共享总预算与当前执行预算中较紧者。\n"
                        + "账本余额：业务模型 [0-9]+ 次，工具 [0-9]+ 次，知识 [0-9]+ 次，总 Token [0-9]+。\n"
                        + "当前账本收尾保护：业务模型 [0-9]+ 次，工具 [0-9]+ 次，知识 [0-9]+ 次，总 Token [0-9]+。\n"
                        + "扣除当前收尾保护后可用于业务的额度：业务模型 [0-9]+ 次，工具 [0-9]+ 次，知识 [0-9]+ 次，总 Token [0-9]+。\n"
                        + "活跃剩余时间：[0-9]+ 毫秒；当前收尾时间保护：[0-9]+ 毫秒；扣除保护后业务可用时间：[0-9]+ 毫秒。\n"
                        + "(已进入收尾阶段，业务可用量仅为算术观察，不表示可以继续业务调用。\n)?");
    }

    static Map<String, Object> budget() {
        return Map.of("role", "user", "content", BUDGET_PREFIX
                + "已扣除已消耗与全部预留，结果未知（UNKNOWN）的预留仍占用额度。\n"
                + "采样时阶段：业务。以下余额取共享总预算与当前执行预算中较紧者。\n"
                + "账本余额：业务模型 10 次，工具 10 次，知识 10 次，总 Token 1000。\n"
                + "当前账本收尾保护：业务模型 1 次，工具 0 次，知识 0 次，总 Token 100。\n"
                + "扣除当前收尾保护后可用于业务的额度：业务模型 9 次，工具 10 次，知识 10 次，总 Token 900。\n"
                + "活跃剩余时间：60000 毫秒；当前收尾时间保护：1000 毫秒；扣除保护后业务可用时间：59000 毫秒。\n");
    }

    /** 单测使用正式入口生成保留纳秒精度的 UTC 样本，不手拼正文或更改原业务消息列表。 */
    static Map<String, Object> footer(long ordinal) {
        var message = AgentExecutionTime.append(List.of(), new AgentExecutionTime.Sample(SAMPLED_AT), ordinal).getLast();
        return Map.of("role", "user", "content", message.textContent());
    }

    /** 复用正式成果规则构造首条，与末条时间样本共同保持真实 Agent 请求的消息顺序。 */
    static Map<String, Object> outputPrefix(long ordinal) { return AgentExecutionOutputMessageFixture.wire(ordinal); }

    /** 固定拒绝原因不回显实际模型消息。 */
    private static IOException invalid() { return new IOException("execution_time_context_missing_or_invalid"); }
}

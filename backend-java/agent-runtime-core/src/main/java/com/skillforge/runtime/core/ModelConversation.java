package com.skillforge.runtime.core;

import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 把真实模型回合和已提交的安全结果接入下一轮原生模型历史的纯计算。
 * 直接使用现有消息及多模态引用，不读取 journal，也不把消息结构校验当作来源或权限证明。
 */
public final class ModelConversation {
    /** 纯函数集合不维护另一份会话状态。 */
    private ModelConversation() { }

    /**
     * 追加完整 assistant/tool 回合，按原模型调用顺序排列已提交结果。
     *
     * <p>Session 只在结果提交成功后调用；并行结果可以乱序到达，但全部完成后才构造下一轮输入。
     * 原正文、reasoning、调用参数和图片引用保持原值，不能用占位结果补齐未决或 UNKNOWN。</p>
     *
     * @param history 已加载且包含完整调用配对的历史
     * @param assistant 原模型回合及其真实规范调用身份
     * @param results 已保存安全结果的 TOOL 消息；无工具的正文回合传空列表
     * @return 防御复制后的完整模型历史
     * @throws IllegalArgumentException 调用与结果缺失、重复、错配或消息身份冲突
     */
    public static List<ModelMessage> append(List<ModelMessage> history, ModelMessage assistant,
            List<ModelMessage> results) {
        var previous = List.copyOf(history);
        Objects.requireNonNull(assistant, "assistant");
        var feedback = List.copyOf(results);
        if (assistant.role() != ModelRole.ASSISTANT
                || assistant.toolCalls().size() != feedback.size()
                || feedback.stream().anyMatch(result -> result.role() != ModelRole.TOOL)) {
            throw invalid();
        }
        var appended = new ArrayList<ModelMessage>(previous);
        appended.add(assistant);
        for (var call : assistant.toolCalls()) {
            var matching = feedback.stream().filter(result ->
                    result.toolCallId().equals(java.util.Optional.of(call.callId()))).toList();
            if (matching.size() != 1) throw invalid();
            appended.add(matching.getFirst());
        }
        var messageIds = new HashSet<String>();
        if (appended.stream().anyMatch(message -> !messageIds.add(message.messageId()))) throw invalid();
        ModelPort.validateConversation(appended);
        return List.copyOf(appended);
    }

    /** 固定错误分类不输出私有消息或结果正文。 */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("model_conversation_exchange_mismatch");
    }
}

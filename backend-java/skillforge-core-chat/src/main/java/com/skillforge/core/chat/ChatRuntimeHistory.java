package com.skillforge.core.chat;

import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.session.SessionView;

/** 宿主把 Runtime 已提交的会话事实投影为产品历史；不从模型原始结果重建回答。 */
public interface ChatRuntimeHistory {
    ChatHistoryResponse read(SessionView session);
    /** 读取本会话指定消息的当前公开快照，缺失或不可见返回空。 */
    java.util.Optional<com.skillforge.core.chat.message.ChatMessageRecord> message(SessionView session, String messageId);
    /** 仅恢复当前消息页对应的计划，避免全会话历史上限截断旧轮次。 */
    default java.util.List<java.util.Map<String,Object>> workPlans(SessionView session,java.util.Set<String> inputIds) {
        return java.util.List.of();
    }
}

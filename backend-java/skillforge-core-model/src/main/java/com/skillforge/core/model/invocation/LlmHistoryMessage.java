package com.skillforge.core.model.invocation;

import java.util.Objects;

/** 辅助模型调用接受的已筛选文本历史，不携带聊天存储身份或持久化状态。 */
public record LlmHistoryMessage(Role role, String content) {
    public LlmHistoryMessage {
        Objects.requireNonNull(role, "role");
    }

    /** 允许进入辅助模型历史的对话角色。 */
    public enum Role {
        USER, ASSISTANT
    }
}

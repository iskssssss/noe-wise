package com.skillforge.runtime.api;

/** 指向已经提交的 Session 消息；作者与序号由消息拥有者保存。 */
public record SourceMessageRef(SessionRef sessionRef, String messageId) {
    public SourceMessageRef {
        sessionRef = ApiContracts.requireNonNull(sessionRef, "sessionRef");
        messageId = ApiContracts.requireText(messageId, "messageId");
    }
}

package com.skillforge.core.chat.inbound;

/** 进入会话运行时的统一输入类型。 */
public enum InboundKind {
    MESSAGE,
    ACTION,
    CALLBACK,
    RECOVERY,
    COMMAND,
    SCHEDULED
}

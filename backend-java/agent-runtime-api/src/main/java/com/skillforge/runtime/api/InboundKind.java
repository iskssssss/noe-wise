package com.skillforge.runtime.api;

/** 定义 Unified Inbound 唯一允许的六类规范输入。 */
public enum InboundKind {
    /** 用户或渠道提交的普通消息。 */
    MESSAGE,
    /** 用户或系统针对现有交互提交的动作。 */
    ACTION,
    /** 外部系统回调形成的规范输入。 */
    CALLBACK,
    /** 持久恢复事实形成的规范输入。 */
    RECOVERY,
    /** 中断、取消等控制命令形成的规范输入。 */
    COMMAND,
    /** 持久调度器到期后形成的规范输入。 */
    SCHEDULED
}

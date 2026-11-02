package com.skillforge.core.workflow.execution;

/** 工作流执行所需的历史正文，不携带聊天存储身份或展示状态。 */
public record WorkflowHistoryMessage(String role, String content) {}

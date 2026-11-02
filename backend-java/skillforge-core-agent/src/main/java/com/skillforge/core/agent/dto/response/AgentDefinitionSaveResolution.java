package com.skillforge.core.agent.dto.response;

/** 原保存键的持久事实；UNKNOWN 不推断请求未发生。 */
public record AgentDefinitionSaveResolution(String state, AdminAgentTemplateItemResponse receipt) { }

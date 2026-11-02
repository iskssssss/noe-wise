package com.skillforge.core.agent;

/** 跨领域使用的 Agent 身份与可用状态投影，不暴露持久化实体。 */
public record AgentView(Long id, Long tenantId, String name, String status, String iconUrl, String agentKey) { }

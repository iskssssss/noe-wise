package com.skillforge.core.agent.dto.response;

/** 原子安装完成后的实例与真实能力装配，供机构直接进入对话。 */
public record AgentTemplateInstallationResponse(AgentItemResponse agent, CapabilityMountSetResponse mounts) { }

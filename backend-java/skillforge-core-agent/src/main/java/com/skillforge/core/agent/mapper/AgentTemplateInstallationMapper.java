package com.skillforge.core.agent.mapper;

import org.apache.ibatis.annotations.Param;

/** 安装请求账本；与 Agent 和挂载在同一事务写入，不存请求正文或凭据。 */
public interface AgentTemplateInstallationMapper {
    Long lockRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
    Receipt find(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
    int insert(@Param("tenantId") Long tenantId, @Param("requestId") String requestId,
            @Param("fingerprint") String fingerprint, @Param("templateId") Long templateId,
            @Param("agentId") Long agentId);
    record Receipt(String fingerprint, Long agentId) { }
}

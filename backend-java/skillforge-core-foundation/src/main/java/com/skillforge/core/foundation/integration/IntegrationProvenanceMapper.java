package com.skillforge.core.foundation.integration;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Session/Run 来源事实只经本领域 Mapper 读写；调用方事务是唯一写入所有者。 */
@Mapper
public interface IntegrationProvenanceMapper {
    IntegrationProvenanceService.Source session(@Param("tenantId") String tenantId,@Param("sessionId") String sessionId);
    IntegrationProvenanceService.Source run(@Param("tenantId") String tenantId,@Param("runId") String runId);
    IntegrationProvenanceService.Source controlOperation(@Param("tenantId") String tenantId,@Param("operationId") String operationId);
    int insert(@Param("tenantId") String tenantId,@Param("runId") String runId,
               @Param("parentRunId") String parentRunId,@Param("source") IntegrationProvenanceService.Source source);
}

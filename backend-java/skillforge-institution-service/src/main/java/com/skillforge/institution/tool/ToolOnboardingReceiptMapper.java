package com.skillforge.institution.tool;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 接入回执与接入写入由同一事务提交。 */
@Mapper
public interface ToolOnboardingReceiptMapper {
    int claim(@Param("tenant") Long tenant, @Param("requestId") String requestId,
            @Param("actor") String actor, @Param("fingerprint") String fingerprint, @Param("owner") String owner,
            @Param("connectorId") String connectorId, @Param("toolNames") String toolNames);
    int complete(@Param("tenant") Long tenant, @Param("requestId") String requestId,
            @Param("owner") String owner, @Param("status") String status, @Param("response") String response);
    Row find(@Param("tenant") Long tenant, @Param("requestId") String requestId);
    record Row(String actorRef, String fingerprint, String status, String connectorId, String toolNamesJson, String responseJson) {}
}

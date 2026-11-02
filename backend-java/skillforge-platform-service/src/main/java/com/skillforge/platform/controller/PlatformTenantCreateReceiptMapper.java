package com.skillforge.platform.controller;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlatformTenantCreateReceiptMapper {
    int claim(@Param("requestId") String requestId, @Param("actor") String actor);
    int complete(@Param("requestId") String requestId, @Param("status") String status,
            @Param("tenantId") Long tenantId, @Param("tenantName") String tenantName,
            @Param("errorMessage") String errorMessage);
    Row find(@Param("requestId") String requestId);
    record Row(String actorRef, String status, Long tenantId, String tenantName, String errorMessage) { }
}

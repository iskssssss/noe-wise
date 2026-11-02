package com.skillforge.platform.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.foundation.dto.audit.AuditLogQuery;
import com.skillforge.core.foundation.dto.audit.AuditLogListResponse;
import com.skillforge.core.foundation.application.AuditLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台审计协议入口；筛选、分页与租户范围由审计服务统一处理。 */
@RestController
@RequestMapping("/api/v1/platform/audit")
public class PlatformAuditController {
    private final AuditLogService auditLogService;
    public PlatformAuditController(AuditLogService auditLogService) { this.auditLogService = auditLogService; }

    @RequirePermission("foundation.audit.read")
    @GetMapping("/logs")
    public ApiResponse<AuditLogListResponse> listLogs(AuditLogQuery query) { return ApiResponses.wrap(auditLogService.query(query)); }
}

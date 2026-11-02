package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;


import com.skillforge.institution.common.TenantIds;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.foundation.constant.AuditConstants;
import com.skillforge.core.foundation.dto.audit.AuditLogListResponse;
import com.skillforge.core.foundation.dto.audit.AuditLogResponse;
import com.skillforge.core.foundation.persistence.AuditLogDO;
import com.skillforge.core.foundation.application.AuditLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志接口控制器。
 *
 * <p>提供当前租户审计日志的分页查询与动作过滤，
 * 对外提供当前 audit API 契约。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    /** 审计日志 MyBatis-Plus 服务。 */
    private final AuditLogService auditLogService;

    /**
     * 构造审计控制器。
     *
     * @param auditLogService 审计日志服务
     */
    public AuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 分页列出审计日志。
     *
     * @param action 动作过滤（可空）
     * @param limit  返回条数上限
     * @return 日志分页响应
     */
    @RequirePermission("foundation.audit.read")
    @GetMapping("/logs")
    public ApiResponse<AuditLogListResponse> listLogs(
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "" + AuditConstants.DEFAULT_LIMIT) int limit) {
        int safeLimit = Math.max(1, Math.min(ApiConstants.MAX_PAGE_SIZE, limit));
        LambdaQueryWrapper<AuditLogDO> wrapper = new LambdaQueryWrapper<AuditLogDO>()
                .eq(AuditLogDO::getTenantId, Long.parseLong(TenantIds.requireTenantId()))
                .orderByDesc(AuditLogDO::getCreatedAt);
        if (action != null && !action.isBlank()) {
            wrapper.eq(AuditLogDO::getAction, action);
        }
        Page<AuditLogDO> page = auditLogService.page(
                new Page<>(ApiConstants.DEFAULT_PAGE, safeLimit), wrapper);
        return ApiResponses.wrap(new AuditLogListResponse(
                page.getTotal(),
                page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::logItem).toList()));
    }

    /**
     * 构建日志响应项。
     *
     * @param log 审计日志
     * @return 日志响应 DTO
     */
    private AuditLogResponse logItem(AuditLogDO log) {
        return new AuditLogResponse(
                log.getId(),
                log.getAction(),
                log.getActorId(),
                log.getResourceType(),
                log.getResourceId(),
                log.getMessage(),
                log.getTraceId(),
                log.getPayload(),
                log.getCreatedAt());
    }


    }

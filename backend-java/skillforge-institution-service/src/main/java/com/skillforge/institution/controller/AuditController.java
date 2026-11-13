package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;


import com.skillforge.institution.common.TenantIds;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.foundation.constant.AuditConstants;
import com.skillforge.core.foundation.dto.audit.AuditLogListResponse;
import com.skillforge.core.foundation.dto.audit.AuditLogResponse;
import com.skillforge.core.foundation.persistence.AuditLogDO;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.application.AuditLogService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final TenantMemberMapper tenantMembers;

    /**
     * 构造审计控制器。
     *
     * @param auditLogService 审计日志服务
     */
    public AuditController(AuditLogService auditLogService, TenantMemberMapper tenantMembers) {
        this.auditLogService = auditLogService;
        this.tenantMembers = tenantMembers;
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

    /** 当前机构审计中实际出现的操作类型与操作者，供筛选和名称回显。 */
    @RequirePermission("foundation.audit.read")
    @GetMapping("/options")
    public ApiResponse<AuditFilterOptions> options() {
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        List<AuditFilterOption> actions = distinct(tenant, "action").stream()
                .map(action -> new AuditFilterOption(action, auditActionLabel(action))).toList();
        Map<Long, String> names = new LinkedHashMap<>();
        tenantMembers.selectList(new LambdaQueryWrapper<TenantMemberDO>()
                        .eq(TenantMemberDO::getTenantId, tenant))
                .forEach(member -> names.put(member.getId(), member.getUsername()));
        List<AuditFilterOption> actors = distinct(tenant, "actor_id").stream().map(id -> {
            String label = names.get(Long.parseLong(id));
            return new AuditFilterOption(id, label == null ? "平台或历史操作者" : label);
        }).toList();
        return ApiResponses.wrap(new AuditFilterOptions(
                actions,
                actors,
                distinct(tenant, "category").stream()
                        .map(value -> new AuditFilterOption(value, auditCategoryLabel(value))).toList(),
                distinct(tenant, "outcome").stream()
                        .map(value -> new AuditFilterOption(value, auditOutcomeLabel(value))).toList(),
                distinct(tenant, "resource_type").stream()
                        .map(value -> new AuditFilterOption(value, auditResourceLabel(value))).toList()));
    }

    private List<String> distinct(long tenant, String column) {
        return auditLogService.listObjs(new QueryWrapper<AuditLogDO>()
                        .select("DISTINCT " + column)
                        .eq("tenant_id", tenant)
                        .isNotNull(column)
                        .ne(column, "")
                        .orderByAsc(column))
                .stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }

    private String auditActionLabel(String action) {
        return switch (action) {
            case "auth.login" -> "登录";
            case "auth.refresh" -> "刷新令牌";
            case "auth.password_change" -> "修改密码";
            case "platform.session.messages.read" -> "读取会话正文";
            case "trajectory.content.read" -> "读取轨迹正文";
            case "sensitive.audit.read" -> "读取安全审计";
            case "admin.tenant.provision" -> "开通机构";
            case "admin.tenant.update" -> "更新机构";
            case "admin.tenant.suspend" -> "停用机构";
            case "admin.tenant.activate" -> "启用机构";
            case "admin.tenant_member.create" -> "新增成员";
            case "admin.tenant_member.update" -> "更新成员";
            case "admin.tenant_member.reset_password" -> "重置成员密码";
            default -> action;
        };
    }

    private String auditCategoryLabel(String category) {
        return switch (category) {
            case "authentication" -> "认证";
            case "authorization" -> "授权";
            case "sensitive" -> "敏感访问";
            case "export" -> "导出";
            case "operation" -> "业务操作";
            default -> category;
        };
    }

    private String auditOutcomeLabel(String outcome) {
        return switch (outcome) {
            case "success" -> "成功";
            case "failure" -> "失败";
            case "denied" -> "拒绝";
            default -> outcome;
        };
    }

    private String auditResourceLabel(String resource) {
        return switch (resource) {
            case "security_session" -> "安全会话";
            case "session" -> "会话";
            case "tenant" -> "机构";
            case "tenant_member" -> "机构成员";
            case "platform_member" -> "平台成员";
            case "agent" -> "智能体";
            case "agent_definition" -> "公共定义";
            case "agent_definition_grant" -> "定义授权";
            case "knowledge_base" -> "知识库";
            case "knowledge_document" -> "知识文档";
            case "browser_authorization" -> "浏览器授权";
            case "audit_query" -> "审计查询";
            case "account" -> "账号";
            case "member" -> "成员";
            default -> resource;
        };
    }

    public record AuditFilterOption(String value, String label) { }

    public record AuditFilterOptions(List<AuditFilterOption> actions, List<AuditFilterOption> actors,
            List<AuditFilterOption> categories, List<AuditFilterOption> outcomes,
            List<AuditFilterOption> resourceTypes) { }

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

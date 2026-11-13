package com.skillforge.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.foundation.dto.audit.AuditLogQuery;
import com.skillforge.core.foundation.dto.audit.AuditLogListResponse;
import com.skillforge.core.foundation.application.AuditLogService;
import com.skillforge.core.foundation.persistence.AuditLogDO;
import com.skillforge.core.foundation.persistence.PlatformMemberDO;
import com.skillforge.core.foundation.persistence.PlatformMemberMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台审计协议入口；筛选、分页与租户范围由审计服务统一处理。 */
@RestController
@RequestMapping("/api/v1/platform/audit")
public class PlatformAuditController {
    private final AuditLogService auditLogService;
    private final PlatformMemberMapper platformMembers;
    private final TenantMemberMapper tenantMembers;

    public PlatformAuditController(AuditLogService auditLogService, PlatformMemberMapper platformMembers,
            TenantMemberMapper tenantMembers) {
        this.auditLogService = auditLogService;
        this.platformMembers = platformMembers;
        this.tenantMembers = tenantMembers;
    }

    @RequirePermission("foundation.audit.read")
    @GetMapping("/logs")
    public ApiResponse<AuditLogListResponse> listLogs(AuditLogQuery query) { return ApiResponses.wrap(auditLogService.query(query)); }

    /** 平台审计中实际出现的操作、操作者与资源类型，供筛选和名称回显。 */
    @RequirePermission("foundation.audit.read")
    @GetMapping("/options")
    public ApiResponse<AuditFilterOptions> options() {
        Map<Long, String> names = new LinkedHashMap<>();
        platformMembers.selectList(new LambdaQueryWrapper<PlatformMemberDO>())
                .forEach(member -> names.put(member.getId(), member.getUsername()));
        tenantMembers.selectList(new LambdaQueryWrapper<TenantMemberDO>())
                .forEach(member -> names.putIfAbsent(member.getId(), member.getUsername()));
        List<AuditFilterOption> actors = auditLogService.listObjs(new QueryWrapper<AuditLogDO>()
                        .select("DISTINCT actor_id")
                        .isNotNull("actor_id")
                        .orderByAsc("actor_id"))
                .stream().filter(java.util.Objects::nonNull).map(value -> {
                    String id = value.toString();
                    String label = names.get(Long.parseLong(id));
                    return new AuditFilterOption(id, label == null ? "操作者 #" + id : label);
                }).toList();
        return ApiResponses.wrap(new AuditFilterOptions(
                auditLogService.listObjs(new QueryWrapper<AuditLogDO>()
                                .select("DISTINCT action").isNotNull("action").ne("action", "").orderByAsc("action"))
                        .stream().filter(java.util.Objects::nonNull).map(Object::toString).toList(),
                actors,
                auditLogService.listObjs(new QueryWrapper<AuditLogDO>()
                                .select("DISTINCT resource_type").isNotNull("resource_type")
                                .ne("resource_type", "").orderByAsc("resource_type"))
                        .stream().filter(java.util.Objects::nonNull).map(Object::toString).toList()));
    }

    public record AuditFilterOption(String value, String label) { }

    public record AuditFilterOptions(List<String> actions, List<AuditFilterOption> actors,
            List<String> resourceTypes) { }
}

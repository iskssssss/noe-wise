package com.skillforge.core.foundation.application;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.foundation.constant.AuditConstants;
import com.skillforge.core.foundation.dto.audit.AuditLogQuery;
import com.skillforge.core.foundation.dto.audit.AuditLogListResponse;
import com.skillforge.core.foundation.dto.audit.AuditLogResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.persistence.AuditLogDO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 审计查询入口；显式约束当前租户，并保留数据库 RLS 作为隔离边界。 */
@Service
public class AuditLogService extends ServiceImpl<AuditLogMapper, AuditLogDO> {
    public AuditLogService(AuditLogMapper mapper) { this.baseMapper = mapper; }

    public AuditLogListResponse query(AuditLogQuery query) {
        Long requestedTenant = parseTenantId(query.tenantId());
        Long boundTenant = parseTenantId(TenantContext.getTenantId());
        if (boundTenant != null && requestedTenant != null && !boundTenant.equals(requestedTenant)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权查询其他机构的审计日志");
        }
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "截止时间必须晚于起始时间");
        }
        int limit = query.limit() == null ? AuditConstants.DEFAULT_LIMIT
                : Math.max(1, Math.min(ApiConstants.MAX_PAGE_SIZE, query.limit()));
        int number = query.page() == null ? 1 : Math.max(1, query.page());
        Page<AuditLogDO> result = baseMapper.selectLogs(new Page<>(number, limit), query,
                boundTenant != null ? boundTenant : requestedTenant);
        return new AuditLogListResponse(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords().stream().map(log ->
                new AuditLogResponse(log.getId(), log.getAction(), log.getActorId(),
                        log.getResourceType(), log.getResourceId(), log.getMessage(), log.getTraceId(),
                        log.getPayload(), log.getCreatedAt())).toList());
    }

    private static Long parseTenantId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long id = Long.parseLong(value.trim());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "tenant_id 格式无效");
        }
    }
}

package com.skillforge.core.foundation.application;
import com.skillforge.core.shared.config.SnowflakeIds;

import com.skillforge.common.context.TenantContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.core.foundation.operations.AuditPayload;
import java.time.Instant;
import java.util.Set;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.persistence.AuditLogDO;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计日志服务。
 *
 * <p>提供统一的审计记录入口：业务模块调用 {@link #record} 写入操作日志，
 * 所有记录仅追加、不修改，写入前必须确保 PII 已脱敏。
 */
@Service
public class AuditService {

    /** 审计日志表映射器。 */
    private final AuditLogMapper auditLogMapper;

    /**
     * 构造审计服务。
     *
     * @param auditLogMapper 审计日志表映射器
     */
    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录一条审计日志。
     *
     * @param tenantId     租户 ID；为 null 时回退到当前线程租户上下文
     * @param actorId      操作者 ID
     * @param action       操作类型，如 auth.login
     * @param resourceType 被操作资源类型
     * @param resourceId   被操作资源 ID
     * @param message      可读的操作描述
     * @param payload      附加数据 JSON，须已脱敏
     */
    public void record(
            String tenantId,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String message,
            Map<String, Object> payload) {
        recordSecurity(tenantId, actorId, action, resourceType, resourceId, "success", message, payload);
    }

    /**
     * 记录安全事实；认证失败应在业务事务外调用，权限变更应与其业务事务共同提交。
     * 无租户上下文表示平台或未归因认证失败，平台查询才能读取；秘密字段递归脱敏。
     * 调用方只能提供已验证的租户身份，不允许以请求中的任意 tenant_id 绑定数据库上下文。
     */
    public void recordSecurity(String tenantId, String actorId, String action, String resourceType,
            String resourceId, String outcome, String message, Map<String, Object> payload) {
        persist(SnowflakeIds.nextId(), tenantId, actorId, action, resourceType, resourceId, outcome, message, payload);
    }

    /** 写入已原子领取的授权读取批次事实；其类型化过滤、授权版本和有界时间位于精确子记录。 */
    public void recordReadBatch(long auditId, String tenantId, String actorId, String queryDigest) {
        if (auditId <= 0 || actorId == null || queryDigest == null || !queryDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("audit_read_batch_invalid");
        persist(auditId, tenantId, actorId, "sensitive.audit.read", "audit_query", queryDigest,
                "success", "检索安全审计（同授权范围的一分钟读取批次）", Map.of());
    }

    /** 与调用者事务共同追加权威安全事实，写入失败不得返回已成功受理。 */
    private void persist(long auditId, String tenantId, String actorId, String action, String resourceType,
            String resourceId, String outcome, String message, Map<String, Object> payload) {
        String effectiveTenant = tenantId != null ? tenantId : TenantContext.getTenantId();
        if (action == null || action.isBlank() || action.length() > 64
                || !Set.of("success", "failure", "denied").contains(outcome))
            throw new IllegalArgumentException("audit_event_invalid");
        // 构造并插入审计记录
        AuditLogDO log = new AuditLogDO();
        log.setId(auditId);
        log.setTenantId(effectiveTenant == null ? null : Long.parseLong(effectiveTenant));
        log.setActorId(actorId);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setMessage(message);
        log.setTraceId(RequestContext.getTraceId());
        log.setPayload(AuditPayload.sanitize(payload));
        log.setCategory(category(action));
        log.setOutcome(outcome);
        log.setCreatedAt(Instant.now());
        // 登录前未必已有线程上下文；仅在写入已验证租户事实时临时绑定并完整恢复。
        String previous = TenantContext.getTenantId();
        if (previous != null && effectiveTenant != null && !previous.equals(effectiveTenant))
            throw new IllegalArgumentException("audit_tenant_mismatch");
        try {
            if (effectiveTenant != null) TenantContext.setTenantId(effectiveTenant);
            if (auditLogMapper.insert(log) != 1) throw new IllegalStateException("audit_write_failed");
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    /** 将稳定动作编码归入安全检索分类，避免调用方自行决定展示分类。 */
    private static String category(String action) {
        if (action.startsWith("auth.") || action.startsWith("security.")) return "authentication";
        if (action.startsWith("permission.") || action.startsWith("access.")) return "authorization";
        if (action.startsWith("sensitive.")) return "sensitive";
        if (action.startsWith("export.") || action.contains(".export")) return "export";
        return "operation";
    }

    /**
     * 登录或MFA拒绝事实独立提交，外层认证事务回滚不会删除拒绝证据。
     * 必须经注入的Spring bean外部调用；成功事实仍使用recordSecurity共同提交业务事务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeniedSecurity(String tenantId, String actorId, String action, String resourceType,
            String resourceId, String outcome, String message, Map<String, Object> payload) {
        if (!Set.of("failure", "denied").contains(outcome)) throw new IllegalArgumentException("audit_denied_outcome_invalid");
        recordSecurity(tenantId, actorId, action, resourceType, resourceId, outcome, message, payload);
    }
}

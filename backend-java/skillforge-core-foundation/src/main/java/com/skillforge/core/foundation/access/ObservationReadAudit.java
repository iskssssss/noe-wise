package com.skillforge.core.foundation.access;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.shared.exception.ApiException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 观测正文读取的独立审计事务；不让读取审计改变外层平台快照的租户绑定。 */
@Service
public class ObservationReadAudit {
    private static final Set<String> ACTIONS = Set.of("platform.session.messages.read",
            "platform.session.trace.content.read", "trajectory.content.read", "chat.citation.read", "platform.session.model-attempt.detail.read");
    private final FoundationAccess foundation;
    private final AuditService audit;

    public ObservationReadAudit(FoundationAccess foundation, AuditService audit) {
        this.foundation = foundation; this.audit = audit;
    }

    /** 只接收安全审计元数据；失败仍阻止正文返回，不改变业务执行与计量事实。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String tenantId, String actorId, String action, String resourceType, String resourceId,
                       String message, Map<String, Object> payload) {
        var subject = foundation.currentSubject();
        if (!ACTIONS.contains(action) || !subject.memberId().equals(actorId)
                || tenantId == null || !tenantId.matches("[1-9][0-9]{0,18}")
                || !subject.platform() && !tenantId.equals(subject.tenantId())) throw denied();
        foundation.require(subject, "platform.session.model-attempt.detail.read".equals(action)
                ? "foundation.sessions.diagnostic_read" : "foundation.sessions.read");
        if (!subject.platform()) {
            if (!"session".equals(resourceType)) throw denied();
            foundation.requireSession(subject, resourceId, "read");
        }
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            audit.record(tenantId, actorId, action, resourceType, resourceId, message, payload);
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    private static ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN, "观测读取审计作用域无效"); }
}

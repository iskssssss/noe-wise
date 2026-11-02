package com.skillforge.core.foundation.access;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 平台观测在原事务中读取明确机构；只给领域白名单SELECT提供RLS绑定，不签发资源读取权。 */
@Service
public class PlatformObservationAccess {
    private static final ThreadLocal<Authorization> ACTIVE = new ThreadLocal<>();
    private static final int TARGET_BATCH_LIMIT = 100;
    private final FoundationAccess foundation;
    private final PlatformObservationTargetMapper mapper;

    public PlatformObservationAccess(FoundationAccess foundation, PlatformObservationTargetMapper mapper) {
        this.foundation = foundation; this.mapper = mapper;
    }

    /** 当前平台账号、动作与显式目标的短期证明；可构造的值本身不授予读取权。 */
    public record Authorization(String actorId, Set<Long> tenantIds) {
        public Authorization { tenantIds = Set.copyOf(tenantIds); }
    }

    public <T> T read(long tenantId, Supplier<T> operation) {
        if (tenantId <= 0) throw denied();
        var subject = foundation.currentSubject();
        if (!subject.platform()) {
            if (!Long.toString(tenantId).equals(subject.tenantId())
                    || !subject.tenantId().equals(TenantContext.getTenantId())) throw denied();
            return operation.get();
        }
        if (TenantContext.getTenantId() != null || ACTIVE.get() != null) throw denied();
        foundation.require(subject, "foundation.sessions.read");
        var authorization = new Authorization(subject.memberId(), Set.of(tenantId));
        ACTIVE.set(authorization);
        try { return TenantRlsInterceptor.withPlatformObservationTarget(tenantId, operation); }
        finally { ACTIVE.remove(); }
    }

    /** 先枚举平台当前可定位的机构，再逐机构核产品Session与实际Runtime事实。 */
    public List<Long> targets(Long requestedTenantId, long afterTenantId, int limit) {
        var subject = foundation.currentSubject();
        if (!subject.platform() || TenantContext.getTenantId() != null || ACTIVE.get() != null) throw denied();
        foundation.require(subject, "foundation.sessions.read");
        if (requestedTenantId != null && requestedTenantId <= 0 || afterTenantId < 0
                || limit < 1 || limit > TARGET_BATCH_LIMIT) throw new ApiException(HttpStatus.BAD_REQUEST, "机构读取批次无效");
        return mapper.targets(requestedTenantId, afterTenantId, limit);
    }

    /** 拦截器只接受本Bean在实时授权后建立的窗口，不读取请求提供的角色或scope。 */
    public static Authorization currentAuthorization() { return ACTIVE.get(); }
    private static ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN, "平台观测读取作用域无效"); }
}

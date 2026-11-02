package com.skillforge.core.foundation.institution.usage;

import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.shared.security.TokenHash;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 当前总部主体、独立动作与精确关系许可的交集；不创建跨请求授权缓存。 */
@Service
public class HeadquartersUsageAccess {
    private static final ThreadLocal<Authorization> ACTIVE = new ThreadLocal<>();
    private final FoundationAccess foundation;
    private final InstitutionIntegrationService institutions;
    private final HeadquartersUsageMapper mapper;

    public HeadquartersUsageAccess(FoundationAccess foundation, InstitutionIntegrationService institutions,
            HeadquartersUsageMapper mapper) {
        this.foundation = foundation; this.institutions = institutions; this.mapper = mapper;
    }

    /** 本次读取的角色/凭据与 grant/relation 快照；可构造的值本身不授予读取权限。 */
    public record Authorization(String actorTenantId, String actorRef, String authorizationVersion,
            String action, Set<Long> tenantIds) {
        public Authorization { tenantIds = Set.copyOf(tenantIds); }
    }

    public Authorization authorize(String action) {
        if (!HeadquartersUsageActions.ALL.contains(action)) throw denied("不支持的总部用量动作");
        if (ManagementContext.get() != null) throw denied("平台管理身份不能进入总部用量域");
        String actorTenant, actorRef, version;
        var principal = ServicePrincipalContext.get();
        if (principal != null) {
            if (!principal.tenantId().equals(TenantContext.getTenantId())) throw denied("机构凭据与当前总部不一致");
            institutions.requirePrincipal(principal, action);
            actorTenant = principal.tenantId(); actorRef = "service_principal:" + principal.principalId();
            // HTTP 认证的当前 scope 与服务端 requirePrincipal 实时复核共同约束本次读取。
            version = principal.credentialVersion() + ":" + principal.scopes().stream().sorted().collect(Collectors.joining(","));
        } else {
            var subject = foundation.currentSubject();
            if (subject.platform() || !subject.tenantId().equals(TenantContext.getTenantId()))
                throw denied("总部用量必须使用可信当前机构主体");
            var snapshot = foundation.snapshot(subject);
            if (!snapshot.permissions().contains(action)) throw denied("没有当前总部用量动作");
            actorTenant = subject.tenantId(); actorRef = "tenant_member:" + actorTenant + ":" + subject.memberId();
            version = Long.toString(snapshot.authorizationVersion());
        }
        var targets = mapper.currentTargets(Long.parseLong(actorTenant), action);
        String targetVersion = targets.stream().sorted(java.util.Comparator.comparingLong(HeadquartersUsageMapper.TargetGrant::tenantId)
                        .thenComparingLong(HeadquartersUsageMapper.TargetGrant::grantId))
                .map(target -> target.tenantId() + ":" + target.grantId() + ":" + target.grantRevision()
                        + ":" + target.relationRef() + ":" + target.relationRevision())
                .collect(Collectors.joining(","));
        return new Authorization(actorTenant, actorRef, TokenHash.sha256Hex(action + "\n" + version + "\n" + targetVersion),
                action, targets.stream().map(HeadquartersUsageMapper.TargetGrant::tenantId).collect(Collectors.toUnmodifiableSet()));
    }

    /**
     * 在调用方原 Spring 事务内执行当前总部授权的受限读取。
     * 每次进入窗口前重新核验当前主体、动作、角色/凭据及 grant/relation 版本；
     * 公开快照被修改或授权已变化时，在执行 operation 前拒绝。
     * 不允许嵌套窗口，成功或失败退出均移除本次线程授权。
     */
    public <T> T read(Authorization authorization, Supplier<T> operation) {
        if (ACTIVE.get() != null) throw new IllegalStateException("headquarters_usage_nested_scope_forbidden");
        if (authorization == null || !authorize(authorization.action()).equals(authorization))
            throw denied("总部用量读取授权已变化");
        ACTIVE.set(authorization);
        try { return TenantRlsInterceptor.withHeadquartersUsageTargets(authorization.actorTenantId(),
                authorization.action(), authorization.tenantIds(), operation); }
        finally { ACTIVE.remove(); }
    }

    /** 只读窗口核验服务端刚建立的当前授权，不接收外部 DTO 身份。 */
    public static Authorization currentAuthorization() { return ACTIVE.get(); }
    private ApiException denied(String message) { return new ApiException(HttpStatus.FORBIDDEN, message); }
}

package com.skillforge.institution.service;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.config.ExternalAuthPaths;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 机构成员使用实时资源权限；独立凭据继续受宿主已认证的外部范围约束。 */
@Service
public final class InstitutionResourceAccess {
    /** 仅覆盖已核验非成员请求者的新运行槽位解析，不形成管理或历史读取权限。 */
    private static final ThreadLocal<String> EXTERNAL_EXECUTION_TENANT = new ThreadLocal<>();
    private final FoundationAccess access;
    private final com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests;
    public InstitutionResourceAccess(FoundationAccess access,com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests) { this.access = access;this.tests=tests; }

    public void requireOrdinaryCapability(long tenant,String id) {tests.requireOrdinary(tenant,"capability",id);}
    public void require(String kind, String id, String action) {
        if(!"execute".equals(action))tests.requireOrdinary(Long.parseLong(TenantContext.getTenantId()),kind,id);
        if (externalExecutionAllows(kind, action)) return;
        if (!external()) access.requireResource(kind, id, action);
    }

    public boolean allowed(String kind, String id, String action) {
        if (externalExecutionAllows(kind, action)) return true;
        return external() || access.canAccessResource(access.currentSubject(), kind, id, action);
    }

    public FoundationAccess.ResourceFilter filter(String kind, String action) {
        return external() ? new FoundationAccess.ResourceFilter(true, java.util.List.of()) : access.resourceFilter(kind, action);
    }

    /**
     * 已持久绑定或入口认证的渠道/服务请求者，经当前会话及目标授权核验后才能调用。
     * 范围只用于同租户的 Workflow/Knowledge execute 槽位解析；不绑定虚构成员身份。
     */
    public static <T> T withExternalExecution(String tenantId, Supplier<T> operation) {
        Objects.requireNonNull(operation);
        if (tenantId == null || !tenantId.equals(TenantContext.getTenantId()))
            throw new SecurityException("external_execution_tenant_mismatch");
        String previous = EXTERNAL_EXECUTION_TENANT.get();
        if (previous != null && !previous.equals(tenantId))
            throw new SecurityException("external_execution_tenant_mismatch");
        try {
            EXTERNAL_EXECUTION_TENANT.set(tenantId);
            return operation.get();
        } finally {
            if (previous == null) EXTERNAL_EXECUTION_TENANT.remove();
            else EXTERNAL_EXECUTION_TENANT.set(previous);
        }
    }

    private static boolean externalExecutionAllows(String kind, String action) {
        String tenant = EXTERNAL_EXECUTION_TENANT.get();
        if (tenant == null) return false;
        if (!tenant.equals(TenantContext.getTenantId()))
            throw new SecurityException("external_execution_tenant_mismatch");
        return "execute".equals(action) && ("workflow".equals(kind) || "knowledge".equals(kind));
    }

    /** 来源属性仅由在先执行的 ExternalAuthInterceptor 写入，不读取客户端自报身份。 */
    public boolean external() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return false;
        Object source = attributes.getRequest().getAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE);
        return ExternalAuthPaths.SOURCE_OPENAPI.equals(source)
                || ExternalAuthPaths.SOURCE_SERVICE_PRINCIPAL.equals(source);
    }
}

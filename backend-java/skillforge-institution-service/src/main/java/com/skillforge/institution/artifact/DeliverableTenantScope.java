package com.skillforge.institution.artifact;

import com.skillforge.common.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 成果请求与受信任后台 Run 的租户作用域；嵌套调用和异常退出均恢复原线程身份。 */
final class DeliverableTenantScope implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DeliverableTenantScope.class);
    private final String previous;
    private final long started = System.nanoTime();

    private DeliverableTenantScope(String tenant) {
        if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("deliverable_tenant_required");
        previous = TenantContext.getTenantId();
        if (previous != null && !previous.equals(tenant)) {
            log.debug("成果租户作用域拒绝 reason=tenant_mismatch");
            throw new SecurityException("database_tenant_scope_mismatch");
        }
        TenantContext.setTenantId(tenant);
        log.debug("成果租户作用域进入 tenant={}", tenant);
    }

    /** 输入必须来自已授权请求、票据或真实 Run 身份，不接收未经授权的任意租户选择。 */
    static DeliverableTenantScope open(String tenant) { return new DeliverableTenantScope(tenant); }

    /** 恢复调用者上下文；不改变事务连接生命周期，连接绑定由 MyBatis 拦截器管理。 */
    @Override
    public void close() {
        if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        log.debug("成果租户作用域退出 durationMicros={}", (System.nanoTime() - started) / 1000);
    }
}

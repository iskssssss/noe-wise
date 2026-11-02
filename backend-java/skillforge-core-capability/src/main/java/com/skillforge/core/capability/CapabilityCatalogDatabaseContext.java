package com.skillforge.core.capability;

import com.skillforge.common.context.TenantContext;
import org.springframework.http.HttpStatus;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Capability Catalog 的数据库安全上下文绑定器。
 *
 * <p>V86 的 RLS policy 依赖 PostgreSQL 事务级 {@code app.tenant_id} 与
 * {@code app.platform_admin}。每一个 Catalog 操作都显式绑定上下文；平台上下文
 * 拒绝任何已有租户上下文，避免平台 JWT 被请求头污染。
 */
@Component
public final class CapabilityCatalogDatabaseContext {
    private static final Logger LOG = LoggerFactory.getLogger(CapabilityCatalogDatabaseContext.class);
    private static final AtomicLong CALLS = new AtomicLong();


    private final CapabilityCatalogContextMapper mapper;

    /** 装配当前领域的 Mapper 依赖；不在构造阶段获取连接或开启事务。 */
    public CapabilityCatalogDatabaseContext(CapabilityCatalogContextMapper mapper) {
        LOG.debug("持久化组件装配进入 component={}", "CapabilityCatalogDatabaseContext");
        this.mapper = mapper;
        LOG.debug("持久化组件装配退出 component={}", "CapabilityCatalogDatabaseContext");
    }

    /** 绑定租户事务上下文，并确认调用方没有越权替换租户。 */
    public void bindTenant(Long tenantId) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "bindTenant");
        try {
            if (tenantId == null || tenantId <= 0) {
                throw CapabilityCatalogErrors.exception(
                        HttpStatus.UNAUTHORIZED, CapabilityCatalogErrors.TENANT_REQUIRED,
                        "缺少有效租户上下文");
            }
            String current = TenantContext.getTenantId();
            if (current == null || !Long.toString(tenantId).equals(current.trim())) {
                throw CapabilityCatalogErrors.exception(
                        HttpStatus.FORBIDDEN, CapabilityCatalogErrors.SCOPE_FORBIDDEN,
                        "租户上下文与资源归属不匹配");
            }
            setConfig("app.tenant_id", Long.toString(tenantId));
            setConfig("app.platform_admin", "false");
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "bindTenant", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "bindTenant", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 绑定平台事务上下文，只允许公共资源写入。 */
    public void bindPlatform() {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "bindPlatform");
        try {
            String current = TenantContext.getTenantId();
            if (current != null && !current.isBlank()) {
                throw CapabilityCatalogErrors.exception(
                        HttpStatus.FORBIDDEN, CapabilityCatalogErrors.SCOPE_FORBIDDEN,
                        "平台操作不得携带租户上下文");
            }
            setConfig("app.tenant_id", "");
            setConfig("app.platform_admin", "true");
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "bindPlatform", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "bindPlatform", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 设置当前事务的数据库上下文；调用方必须在事务中完成授权与数据操作。 */
    private void setConfig(String key, String value) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "setConfig");
        try {
            mapper.bind(key, value);
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "setConfig", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "setConfig", started == 0 ? 0 : System.nanoTime() - started);
        }
    }
}

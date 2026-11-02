package com.skillforge.common.context;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 租户上下文（线程本地变量）。
 *
 * <p>用于在整个请求处理链中传递当前租户 ID。MyBatis 的 RLS 拦截器与各业务服务
 * 会读取该上下文，在执行 SQL 前通过 {@code set_config('app.tenant_id', ...)}
 * 绑定 PostgreSQL 行级安全策略，确保多租户数据隔离。
 */
public final class TenantContext {

    /** 当前线程绑定的租户 ID。 */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 工具类不允许实例化。 */
    private TenantContext() {
    }

    /**
     * 设置当前线程的租户 ID。
     *
     * @param tenantId 租户 Long 字符串
     */
    public static void setTenantId(String tenantId) {
        CURRENT.set(tenantId);
    }

    /**
     * 获取当前线程的租户 ID。
     *
     * @return 租户 Long 字符串；未设置时返回 null
     */
    public static String getTenantId() {
        return CURRENT.get();
    }

    /**
     * 在当前线程按已验证的租户身份执行宿主操作，成功或异常后均恢复外层上下文。
     *
     * <p>本方法只管理线程作用域，不授予权限；调用方必须从可信调用身份取得租户，
     * 不得直接使用未验证的请求字段。异步回调须在实际执行线程进入此作用域。
     *
     * @param tenantId 已验证的租户 ID
     * @param work 当前线程内执行的操作，不能将作用域隐式传给新线程
     * @return 操作的原始结果
     */
    public static <T> T callWithTenant(String tenantId, Supplier<T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(work, "work");
        String previous = getTenantId();
        setTenantId(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) clear();
            else setTenantId(previous);
        }
    }

    /**
     * 使用已验证的租户身份执行无返回值操作，并在退出时恢复当前线程的原上下文。
     *
     * @param tenantId 已验证的租户 ID，不承担授权校验
     * @param work 当前线程内的宿主操作
     */
    public static void runWithTenant(String tenantId, Runnable work) {
        Objects.requireNonNull(work, "work");
        callWithTenant(tenantId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * 清理当前线程的租户上下文，避免线程池复用导致串号。
     */
    public static void clear() {
        CURRENT.remove();
    }
}

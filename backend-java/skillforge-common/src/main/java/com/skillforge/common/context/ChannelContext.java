package com.skillforge.common.context;

/**
 * 渠道上下文（线程本地变量）。
 *
 * <p>承载外部调用（C 端渠道 / OpenAPI 集成方）经认证后解析出的渠道 ID。
 * 与 {@link TenantContext} 同生命周期：由外部认证拦截器注入，宿主请求过滤器在
 * 当前 Servlet 派发返回时统一清理（包括 SSE 异步交接）；SSE 业务异步线程
 * 经显式带出/恢复传递（与 tenantId / traceId 同款），禁止跨线程直读。
 */
public final class ChannelContext {

    /** 当前线程绑定的渠道 ID。 */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 工具类不允许实例化。 */
    private ChannelContext() {
    }

    /**
     * 设置当前线程的渠道 ID。
     *
     * @param channelId 渠道 Long 字符串
     */
    public static void setChannelId(String channelId) {
        CURRENT.set(channelId);
    }

    /**
     * 获取当前线程的渠道 ID。
     *
     * @return 渠道 Long 字符串；未设置时返回 null
     */
    public static String getChannelId() {
        return CURRENT.get();
    }

    /**
     * 清理当前线程的渠道上下文，避免线程池复用导致串号。
     */
    public static void clear() {
        CURRENT.remove();
    }
}

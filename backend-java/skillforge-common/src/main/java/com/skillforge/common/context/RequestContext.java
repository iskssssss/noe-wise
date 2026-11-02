package com.skillforge.common.context;

/**
 * 请求上下文（线程本地变量）。
 *
 * <p>承载一次请求的追踪 ID、用户 ID 与模块名；MDC 同步由业务服务侧
 * 的观测组件负责，common 模块不依赖具体日志框架。
 */
public final class RequestContext {

    /** 当前线程追踪 ID。 */
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    /** 当前线程用户 ID。 */
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    /** 当前线程模块名。 */
    private static final ThreadLocal<String> MODULE = new ThreadLocal<>();

    private RequestContext() {
    }

    /**
     * 设置追踪 ID。
     *
     * @param traceId 追踪 ID
     */
    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    /**
     * 获取追踪 ID。
     *
     * @return 追踪 ID；未设置时返回 null
     */
    public static String getTraceId() {
        return TRACE_ID.get();
    }

    /**
     * 设置用户 ID。
     *
     * @param userId 用户 ID
     */
    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    /**
     * 获取用户 ID。
     *
     * @return 用户 ID；未设置时返回 null
     */
    public static String getUserId() {
        return USER_ID.get();
    }

    /**
     * 设置模块名。
     *
     * @param module 模块名
     */
    public static void setModule(String module) {
        MODULE.set(module);
    }

    /**
     * 获取模块名。
     *
     * @return 模块名；未设置时返回 null
     */
    public static String getModule() {
        return MODULE.get();
    }

    /**
     * 清理当前线程上下文，避免线程池复用串号。
     */
    public static void clear() {
        TRACE_ID.remove();
        USER_ID.remove();
        MODULE.remove();
    }
}

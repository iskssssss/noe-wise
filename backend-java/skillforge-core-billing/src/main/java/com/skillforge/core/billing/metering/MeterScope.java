package com.skillforge.core.billing.metering;

/**
 * 计量上下文作用域（线程本地变量，与 {@code TenantContext} 同构）。
 *
 * <p>调用点经 try-with-resources 打开作用域提供关联维度；Provider 内自动计量时
 * 经 {@link #current()} 读取。嵌套 open 时继承父作用域的链路字段（requestId 等）
 * 并冻结父作用域最近事件 ID 为 {@code parentEventId}（Langfuse 父子树语义：
 * 同作用域内连续调用为兄弟，内层作用域调用为子）。连接测试等无作用域调用
 * 不计费（{@code current()} 返回 null）。
 *
 * <p>异步线程不自动传递 ThreadLocal：异步任务须在方法内自行 open 作用域
 * （上下文从方法签名参数获取）。
 */
public final class MeterScope implements AutoCloseable {

    /** 当前线程绑定的计量作用域。 */
    private static final ThreadLocal<MeterScope> CURRENT = new ThreadLocal<>();

    /** 本作用域关联维度上下文。 */
    private final CostContext context;

    /** 父作用域最近计费事件 ID（open 时冻结；无父或父未计费为 null）。 */
    private final Long parentEventId;

    /** 本作用域最近计费事件 ID（Provider 计量后回写，供嵌套子作用域作父引用）。 */
    private Long lastEventId;

    /** 父作用域（close 时恢复；无父为 null）。 */
    private final MeterScope parent;

    private MeterScope(CostContext context, Long parentEventId, MeterScope parent) {
        this.context = context;
        this.parentEventId = parentEventId;
        this.parent = parent;
    }

    /**
     * 打开计量作用域：与父作用域合并链路字段（子非空优先），并冻结父事件为父引用。
     *
     * @param context 本作用域关联维度上下文（可为空 Builder 构建的空上下文）
     * @return 作用域（须 try-with-resources 关闭）
     */
    public static MeterScope open(CostContext context) {
        MeterScope parentScope = CURRENT.get();
        CostContext merged = CostContext.merge(parentScope == null ? null : parentScope.context, context);
        Long parentEventId = parentScope == null ? null : parentScope.lastEventId;
        MeterScope scope = new MeterScope(merged, parentEventId, parentScope);
        CURRENT.set(scope);
        return scope;
    }

    /**
     * 从已持久冻结的任务来源打开独立作用域，不合并线程残留身份；关闭仍恢复调用方作用域。
     * 用于异步任务恢复，避免无来源字段被后台线程中的其他任务填充。
     */
    public static MeterScope openIsolated(CostContext context) {
        MeterScope scope = new MeterScope(java.util.Objects.requireNonNull(context, "context"), null, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /**
     * 读取当前作用域上下文（自动计量数据源）。
     *
     * @return 当前上下文；无作用域（连接测试等）返回 null
     */
    public static CostContext current() {
        MeterScope scope = CURRENT.get();
        return scope == null ? null : scope.context;
    }

    /**
     * 当前作用域冻结的父事件 ID（嵌套子事件的 parent_event_id）。
     *
     * @return 父事件 ID；无作用域或父未计费返回 null
     */
    public static Long parentEventId() {
        MeterScope scope = CURRENT.get();
        return scope == null ? null : scope.parentEventId;
    }

    /**
     * 回写本作用域最近计费事件 ID（Provider 计量落库后调用）。
     *
     * @param eventId 事件 ID
     */
    public static void setLastEventId(Long eventId) {
        MeterScope scope = CURRENT.get();
        if (scope != null) {
            scope.lastEventId = eventId;
        }
    }

    /**
     * 关闭作用域，恢复父作用域。
     */
    @Override
    public void close() {
        MeterScope scope = CURRENT.get();
        if (scope != this) {
            return;
        }
        CURRENT.set(parent);
    }

    /**
     * 清理当前线程的计量作用域（异常路径兜底：调用方线程 finally 中调用，
     * 避免线程池复用导致 scope 串号）。
     */
    public static void clear() {
        CURRENT.remove();
    }
}

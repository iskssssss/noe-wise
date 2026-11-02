package com.skillforge.core.billing.invocation;

/**
 * 当前线程正在执行的受控外部调用尝试。
 *
 * <p>仅由 {@link ExternalInvocationGuard} 打开，供 UsageEvent 在执行期关联 invocationId/attemptId；
 * 它不是业务因果上下文，也不能替代 TraceContext 跨线程传播。</p>
 */
public final class ExternalInvocationScope implements AutoCloseable {

    private static final ThreadLocal<ExternalInvocationScope> CURRENT = new ThreadLocal<>();

    private final ExternalInvocationRequest request;
    private final ExternalInvocationScope parent;

    private ExternalInvocationScope(ExternalInvocationRequest request, ExternalInvocationScope parent) {
        this.request = request;
        this.parent = parent;
    }

    public static ExternalInvocationScope open(ExternalInvocationRequest request) {
        ExternalInvocationScope scope = new ExternalInvocationScope(request, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static ExternalInvocationRequest current() {
        ExternalInvocationScope scope = CURRENT.get();
        return scope == null ? null : scope.request;
    }

    @Override
    public void close() {
        if (CURRENT.get() == this) {
            CURRENT.set(parent);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}

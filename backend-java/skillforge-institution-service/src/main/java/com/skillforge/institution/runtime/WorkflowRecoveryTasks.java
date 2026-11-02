package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.starter.AgentRuntimeTenantBinder;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 恢复扫描只登记立即可执行的工作；未取得本地槽位的 Execution 不提前领取数据库租约。 */
final class WorkflowRecoveryTasks implements AutoCloseable {
    private final int limit;
    private final AgentRuntimeTenantBinder binder;
    private final Map<ExecutionRef, Thread> active = new HashMap<>();
    private volatile boolean closed;
    private long sequence;

    WorkflowRecoveryTasks(int limit, AgentRuntimeTenantBinder binder) {
        if (limit <= 0) throw new IllegalArgumentException("workflow_recovery_concurrency_invalid");
        this.limit = limit;
        this.binder = Objects.requireNonNull(binder);
    }

    synchronized boolean available() { return !closed && active.size() < limit; }
    boolean stopped() { return closed; }

    synchronized boolean submit(ExecutionRef execution, Runnable work) {
        Objects.requireNonNull(execution); Objects.requireNonNull(work);
        if (!available() || active.containsKey(execution)) return false;
        var thread = Thread.ofPlatform().daemon().name("workflow-recovery-" + sequence++).unstarted(() -> {
            try { binder.bind(execution.tenantId(), () -> { if (!closed) work.run(); }); }
            finally { synchronized (this) { active.remove(execution); } }
        });
        active.put(execution, thread);
        try { thread.start(); }
        catch (RuntimeException | Error failure) { active.remove(execution); throw failure; }
        return true;
    }

    /** 先禁止新恢复并撤销所有本地发送许可，再中断、等待正在执行的工作退出。 */
    @Override public void close() {
        List<Thread> threads;
        synchronized (this) { closed = true; threads = List.copyOf(active.values()); }
        threads.forEach(Thread::interrupt);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        for (var thread : threads) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try { thread.join(Duration.ofNanos(remaining)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        synchronized (this) {
            if (!active.isEmpty()) throw new IllegalStateException("workflow_recovery_not_quiescent");
        }
    }
}

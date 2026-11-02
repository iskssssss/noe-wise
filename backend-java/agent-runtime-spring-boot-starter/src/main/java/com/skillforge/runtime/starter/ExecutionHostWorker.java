package com.skillforge.runtime.starter;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** 所有宿主共享二十平台槽和同一个持久due发现器；没有第二工作队列或额外Host线程池。 */
public final class ExecutionHostWorker implements ApplicationRunner, AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExecutionHostWorker.class);
    private static final Duration FALLBACK_INTERVAL = Duration.ofSeconds(5);
    private static final int SCAN_LIMIT = 100, ACTIVE_LIMIT = 20;
    private final ExecutionHostRegistry registry;
    private final ExecutionTenantDiscovery discovery;
    private final ExecutionHostScheduler scheduler;
    private final AgentRuntimeTenantBinder tenantBinder;
    private final String worker;
    private final Duration lease;
    private final boolean autoStartup;
    private final Set<RunRef> active = ConcurrentHashMap.newKeySet();
    /** 只保存复用父物理槽的当前子身份，不保存Runnable，不形成另一个工作队列。 */
    private final Set<RunRef> inlineActive = ConcurrentHashMap.newKeySet();
    private final Object dispatching = new Object();
    private final ThreadLocal<OccupiedSlot> occupied = new ThreadLocal<>();
    private final ThreadPoolExecutor slots = new ThreadPoolExecutor(0, ACTIVE_LIMIT, 60, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().name("runtime-execution-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean(), pumpRequested = new AtomicBoolean();
    private final AtomicBoolean recoveryRequested = new AtomicBoolean(true);
    private final Object scheduling = new Object();
    private ScheduledFuture<?> fallback, nearest;
    private volatile List<String> discoveredTenants = List.of();
    private String tenantCursor = "";

    public ExecutionHostWorker(ExecutionHostRegistry registry, ExecutionTenantDiscovery discovery, ExecutionHostScheduler scheduler,
            AgentRuntimeTenantBinder tenantBinder, String worker, Duration lease, boolean autoStartup) {
        this.registry = Objects.requireNonNull(registry); this.discovery = Objects.requireNonNull(discovery);
        this.scheduler = Objects.requireNonNull(scheduler); this.worker = Objects.requireNonNull(worker);
        this.lease = Objects.requireNonNull(lease); this.autoStartup = autoStartup;
        this.tenantBinder = Objects.requireNonNull(tenantBinder);
    }
    @Override public void run(ApplicationArguments args) {
        if (!autoStartup || !started.compareAndSet(false, true)) return;
        requestPump(); scheduleFallback();
    }
    public boolean isRunning() { return started.get() && !closed.get(); }
    public boolean isClosed() { return closed.get(); }
    public boolean wake(RunRef run) {
        Objects.requireNonNull(run);
        synchronized (dispatching) { if (closed.get() || inlineActive.contains(run) || !active.add(run)) return false; }
        try { slots.execute(() -> driveAndRefill(run)); return true; }
        catch (RejectedExecutionException full) { active.remove(run); return false; }
    }
    /** 已持父物理槽的程序RPC复用本线程；原来源和独立child准入须已提交，不创建池或新队列。 */
    public ExecutionCoordinator.Outcome driveProgramChild(RunIdentity parentIdentity, ExecutionStore.ProgramChildSource source, RunRef child) {
        var slot = occupied.get(); var parentRef = parentIdentity.runRef();
        if (slot == null || slot.inline() || !slot.run().equals(parentRef) || !active.contains(parentRef)
                || Thread.currentThread().isVirtual() || !parentRef.tenantId().equals(child.tenantId()) || parentRef.equals(child))
            throw new IllegalArgumentException("execution_inline_parent_slot_required");
        return withTenant(parentRef.tenantId().value(), () -> {
            var parent = registry.store().load(parentRef).orElseThrow();
            var action = registry.store().action(source.action()).orElseThrow();
            var attempt = registry.store().attempt(parentRef, source.attemptId()).orElseThrow();
            if (!parent.identity().equals(parentIdentity) || parent.cancelled() || parent.status() != com.skillforge.runtime.api.RunStatus.RUNNING
                    || parent.phase() != ExecutionStore.Phase.AWAITING_RESULT
                    || parent.lease().isEmpty() || !Instant.now().isBefore(parent.lease().orElseThrow().leaseUntil())
                    || !parent.currentAction().equals(Optional.of(source.action()))
                    || action.kind() != ExecutionStore.Kind.TOOL || !action.run().equals(parentRef)
                    || !action.currentAttempt().equals(source.attemptId()) || !attempt.action().equals(source.action())
                    || attempt.fence() != parent.fence() || attempt.result().isPresent() || attempt.adopted())
                throw new IllegalArgumentException("execution_inline_original_parent_required");
            var savedChild = registry.store().load(child).orElseThrow(); var childHost = registry.host(savedChild);
            childHost.requireProgramChild(parent, source, savedChild);
            synchronized (dispatching) {
                if (closed.get()) throw new IllegalStateException("execution_worker_closed");
                if (active.contains(child) || !inlineActive.add(child))
                    return new ExecutionCoordinator.Outcome(ExecutionCoordinator.Stop.NOT_CLAIMED, Optional.of(savedChild), "execution_inline_child_already_active");
            }
            try { return invokeHost(child, true); } finally { inlineActive.remove(child); }
        });
    }
    /** 必须在Store取消提交后调用；先更新Host纯内存信号，再驱动原持久依赖清理。 */
    public void cancelled(RunRef run) {
        withTenant(run.tenantId().value(), () -> { cancelBound(run); return null; });
    }
    private void cancelBound(RunRef run) {
        var persisted = registry.store().load(Objects.requireNonNull(run)).orElseThrow();
        var host = registry.host(persisted); host.cancelled(run);
        try { host.committed(persisted); }
        catch (RuntimeException failure) { AgentRuntimeFailureDiagnostic.warn(LOG, "已提交取消的依赖清理暂缓", "execution-cancel", run.runId(), "execution_cancel_cleanup_failed", failure); }
    }

    private void requestPump() {
        if (closed.get() || !pumpRequested.compareAndSet(false, true)) return;
        try {
            scheduler.scheduleControl(() -> AgentRuntimeJdbcObservation.worker("execution-pump", () -> {
                pumpRequested.set(false);
                if (closed.get()) return null;
                try { pump(); }
                catch (RuntimeException failure) { AgentRuntimeFailureDiagnostic.warn(LOG, "执行到期补位失败", "execution-pump", worker, "execution_pump_failed", failure); }
                return null;
            }), Duration.ZERO);
        } catch (IllegalStateException rejected) {
            pumpRequested.set(false);
            if (!closed.get()) throw rejected;
        }
    }

    /** 每次最多消费一百条到期地址；无槽位时不查询，释放槽位会立即再触发本方法。 */
    private void pump() {
        if (active.size() >= ACTIVE_LIMIT) return;
        var owners = discovery.page(tenantCursor, SCAN_LIMIT);
        if (owners.size() > SCAN_LIMIT) throw new IllegalStateException("execution_tenant_page_unbounded");
        if (owners.isEmpty() && !tenantCursor.isEmpty()) {
            tenantCursor = ""; owners = discovery.page(tenantCursor, SCAN_LIMIT);
            if (owners.size() > SCAN_LIMIT) throw new IllegalStateException("execution_tenant_page_unbounded");
        }
        discoveredTenants = List.copyOf(owners);
        if (owners.isEmpty()) { scheduleNearest(Optional.empty()); return; }
        int remaining = SCAN_LIMIT;
        boolean recovering = recoveryRequested.getAndSet(false);
        int recoveryLimit = Math.max(1, SCAN_LIMIT / owners.size());
        Optional<Instant> earliest = Optional.empty();
        int inspected = 0;
        for (var owner : owners) {
            if (remaining <= 0 || inspected++ >= SCAN_LIMIT || closed.get()) break;
            var tenant = new TenantId(owner);
            if (recovering) withTenant(tenant.value(), () -> { registry.recover(tenant, recoveryLimit); return null; });
            int available = ACTIVE_LIMIT - active.size();
            if (available <= 0) break;
            int queryLimit = Math.min(remaining, available);
            var ready = withTenant(tenant.value(), () -> registry.store().due(tenant, queryLimit));
            remaining -= ready.size();
            for (var run : ready) {
                if (!run.tenantId().equals(tenant)) throw new IllegalStateException("execution_due_tenant_mismatch");
                wake(run);
                if (active.size() >= ACTIVE_LIMIT) break;
            }
            tenantCursor = owner;
            var due = withTenant(tenant.value(), () -> registry.store().nextDueAt(tenant));
            if (due.isPresent() && (earliest.isEmpty() || due.orElseThrow().isBefore(earliest.orElseThrow()))) earliest = due;
        }
        scheduleNearest(earliest);
    }

    /** 只为未来due安排一个定时器；已经到期但未取得槽位的任务由槽位释放或兜底接续。 */
    private void scheduleNearest(Optional<Instant> due) {
        synchronized (scheduling) {
            if (nearest != null) { nearest.cancel(false); nearest = null; }
            if (closed.get() || active.size() >= ACTIVE_LIMIT || due.isEmpty()) return;
            Duration delay = Duration.between(Instant.now(), due.orElseThrow());
            if (delay.isZero() || delay.isNegative()) return;
            nearest = scheduler.scheduleControl(this::requestPump, delay);
        }
    }

    /** 丢失通知的恢复兜底只有一个五秒定时器，不以高频跨租户轮询替代直接唤醒。 */
    private void scheduleFallback() {
        synchronized (scheduling) {
            if (closed.get()) return;
            fallback = scheduler.scheduleControl(() -> { recoveryRequested.set(true); requestPump(); scheduleFallback(); }, FALLBACK_INTERVAL);
        }
    }

    /** 完成一个Run后直接在原平台线程槽位领取持久到期工作，无需等待另一次Executor提交。 */
    private void driveAndRefill(RunRef first) {
        RunRef next = first;
        while (next != null && !closed.get()) {
            // 技术失败可能发生在claim之前，原Run仍然due；不可立即再次读回并重试同一副作用边界。
            if (!drive(next)) return;
            next = AgentRuntimeJdbcObservation.worker("execution-refill", () -> nextReady(first.tenantId()));
        }
        requestPump();
    }

    /** 只临时读取最多一百条地址，成功占有本进程active标记后直接执行；没有第二工作队列。 */
    private RunRef nextReady(TenantId preferred) {
        if (closed.get() || active.size() >= ACTIVE_LIMIT) return null;
        var owners = new ArrayList<String>(); owners.add(preferred.value());
        discoveredTenants.stream().filter(value -> !value.equals(preferred.value())).limit(SCAN_LIMIT - 1).forEach(owners::add);
        int remaining = SCAN_LIMIT;
        for (var owner : owners) {
            var tenant = new TenantId(owner); int queryLimit = remaining;
            final List<RunRef> ready;
            try { ready = withTenant(owner, () -> registry.store().due(tenant, queryLimit)); }
            catch (RuntimeException failure) {
                AgentRuntimeFailureDiagnostic.warn(LOG, "槽位释放后的到期补位失败", "execution-refill", owner, "execution_refill_failed", failure);
                return null;
            }
            remaining -= ready.size();
            for (var candidate : ready) {
                if (!candidate.tenantId().equals(tenant)) throw new IllegalStateException("execution_due_tenant_mismatch");
                synchronized (dispatching) { if (!inlineActive.contains(candidate) && active.add(candidate)) return candidate; }
            }
            if (remaining <= 0) return null;
        }
        return null;
    }

    /** 单次驱动的技术异常停止本轮补位，由既有持久恢复兜底接续，不紧循环重试。 */
    private boolean drive(RunRef run) {
        return AgentRuntimeJdbcObservation.worker("execution-drive", () ->
                withTenant(run.tenantId().value(), () -> driveBound(run)));
    }
    private boolean driveBound(RunRef run) {
        try {
            var outcome = invokeHost(run, false);
            if (outcome.stop() != ExecutionCoordinator.Stop.FINISHED)
                LOG.info("执行主链停止 stop={} reason={}", outcome.stop(), outcome.reason());
            return true;
        } catch (RuntimeException failure) {
            AgentRuntimeFailureDiagnostic.warn(LOG, "执行主链未完成，保留原派发事实", "execution-drive", run.runId(), "execution_drive_failed", failure); return false;
        } finally { active.remove(run); }
    }
    private ExecutionCoordinator.Outcome invokeHost(RunRef run, boolean inline) {
        var previous = occupied.get();
        if (!inline && previous != null) throw new IllegalStateException("execution_slot_recursion_rejected");
        var host = registry.host(run); boolean opened = false;
        try {
            host.begin(run); opened = true; occupied.set(new OccupiedSlot(run, inline));
            var outcome = host.drive(new ExecutionStore.Claim(run, worker, "execution-drive:" + UUID.randomUUID(), lease));
            if (outcome.run().isPresent()) host.committed(outcome.run().orElseThrow()); return outcome;
        } finally {
            try { if (opened) host.end(run); }
            finally { if (previous == null) occupied.remove(); else occupied.set(previous); }
        }
    }
    private record OccupiedSlot(RunRef run, boolean inline) { }
    private <T> T withTenant(String tenant, java.util.function.Supplier<T> work) {
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        tenantBinder.bind(new TenantId(tenant), () -> result.set(work.get())); return result.get();
    }
    /** 只关闭唯一Run槽位；共享控制timer由独立Bean在所有Host退出后统一关闭。 */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (scheduling) { if (fallback != null) fallback.cancel(false); if (nearest != null) nearest.cancel(false); }
        for (var run : active) {
            try { registry.host(run).cancelled(run); } catch (RuntimeException failure) { LOG.warn("停止活动控制暂缓 failureType={}", failure.getClass().getSimpleName()); }
        }
        for (var run : inlineActive) {
            try { registry.host(run).cancelled(run); }
            catch (RuntimeException failure) { LOG.warn("停止程序子控制暂缓 failureType={}", failure.getClass().getSimpleName()); }
        }
        slots.shutdownNow();
        try { if (!slots.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("execution_worker_shutdown_incomplete"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("execution_worker_shutdown_interrupted", interrupted); }
    }
}

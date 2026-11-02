package com.skillforge.institution.runtime;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 一个进程唯一的执行活动控制器；同一调度线程负责租约续期与有界补位定时。
 * 每个领取区间只有一个Control，取消和失租getter只读内存；续租中的活动确认复用Store业务事务。
 */
public final class AgentRuntimeExecutionActivity implements ExecutionCoordinator.Controls, AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentRuntimeExecutionActivity.class);
    private final ExecutionStore store;
    private final AgentRuntimeExecutionSources sources;
    private final AgentRuntimeWorkflowNodeExecutions nodes;
    private final Duration leaseDuration;
    private final ScheduledExecutorService timer;
    private final ScheduledFuture<?> heartbeat;
    private final boolean ownsTimer;
    private final java.util.function.Supplier<AgentRuntimeProgramTools> programs;
    /** 只有已领取且尚未释放的活动Run，规模受Worker的二十个槽位约束。 */
    private final Map<RunRef, Active> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycle = new Object();
    /** 只供显式计时观察，不参与写权或调度；没有订阅时回调不保存任何样本。 */
    private volatile BiConsumer<RunRef, Long> claimObserver = (run, nanos) -> { };

    /** 独立测试可拥有timer；生产借Starter共享控制线程，续租周期不超过十秒或租期三分之一。 */
    public AgentRuntimeExecutionActivity(ExecutionStore store, AgentRuntimeExecutionSources sources,
            AgentRuntimeWorkflowNodeExecutions nodes, Duration leaseDuration, ScheduledExecutorService timer) {
        this(store, sources, nodes, leaseDuration, timer, true);
    }

    /** 生产使用Starter的共享控制timer；单测可显式拥有独立timer并等待它退出。 */
    public AgentRuntimeExecutionActivity(ExecutionStore store, AgentRuntimeExecutionSources sources,
            AgentRuntimeWorkflowNodeExecutions nodes, Duration leaseDuration, ScheduledExecutorService timer, boolean ownsTimer) {
        this(store, sources, nodes, leaseDuration, timer, ownsTimer, () -> { throw new IllegalStateException("program_control_not_configured"); });
    }
    public AgentRuntimeExecutionActivity(ExecutionStore store, AgentRuntimeExecutionSources sources,
            AgentRuntimeWorkflowNodeExecutions nodes, Duration leaseDuration, ScheduledExecutorService timer, boolean ownsTimer,
            java.util.function.Supplier<AgentRuntimeProgramTools> programs) {
        this.store = Objects.requireNonNull(store); this.sources = Objects.requireNonNull(sources);
        this.nodes = Objects.requireNonNull(nodes);
        this.leaseDuration = Objects.requireNonNull(leaseDuration); this.timer = Objects.requireNonNull(timer);
        this.ownsTimer = ownsTimer;
        this.programs = Objects.requireNonNull(programs);
        if (leaseDuration.isNegative() || leaseDuration.isZero()) throw new IllegalArgumentException("execution_duration_invalid");
        long period = Math.max(1, Math.min(Duration.ofSeconds(10).toNanos(), leaseDuration.toNanos() / 3));
        heartbeat = timer.scheduleWithFixedDelay(this::confirm, period, period, TimeUnit.NANOSECONDS);
    }

    /** claim后建立控制，不能把已终止或无租约Run包装成活跃执行；来源frame必须已由Worker建立。 */
    @Override public ExecutionCoordinator.Control open(ExecutionStore.Run run) {
        Objects.requireNonNull(run);
        if (closed.get()) throw new IllegalStateException("execution_activity_closed");
        claimObserver.accept(run.identity().runRef(), System.nanoTime());
        var control = new Active(run.identity().runRef(), run.lease().orElseThrow());
        // 活动基线必须早于第一次计时确认；否则慢配置读取期间的心跳会被elapsed重复计数。
        try {
            if (AgentRuntimeProgramTools.owns(run)) {
                programs.get().begin(control.run); control.programFrame = true; programs.get().bindControl(control.run, control);
            } else if (AgentRuntimeWorkflowNodeExecutions.owns(run)) {
                nodes.begin(control.run); control.nodeFrame = true; nodes.bindControl(control.run, control);
            } else sources.bindControl(control.run, control);
            synchronized (lifecycle) {
                if (closed.get()) throw new IllegalStateException("execution_activity_closed");
                if (active.putIfAbsent(control.run, control) != null) throw new IllegalStateException("execution_control_already_active");
            }
        } catch (RuntimeException failed) { control.close(); throw failed; }
        return control;
    }

    /** 唯一Worker注入领取观察；回调只消费原始单调时点，不允许添加存储或外调。 */
    void observeClaims(BiConsumer<RunRef, Long> observer) { claimObserver = Objects.requireNonNull(observer); }

    /** 已完成持久取消的入口立即触发本进程信号；不替代Store的当前IAM与Run事务。 */
    public void cancelled(RunRef run) {
        var control = active.get(Objects.requireNonNull(run));
        if (control != null) control.cancelled = true;
    }

    /** 同一个调度器只安排控制/补扫回调，实际Run始终进入Worker的有界平台线程槽位。 */
    ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        if (closed.get()) throw new IllegalStateException("execution_activity_closed");
        return timer.schedule(task, Math.max(0, delay.toNanos()), TimeUnit.NANOSECONDS);
    }

    /** 心跳只触及已领取活动；关闭等待当前续租完成，之后不存在使用已释放frame的续租。 */
    private void confirm() {
        for (var control : active.values()) {
            synchronized (control) {
                if (control.released || control.cancelled || control.ownershipLost) continue;
                try {
                    var renewed = TenantContext.callWithTenant(control.run.tenantId().value(),
                            () -> store.renew(control.run, control.lease, leaseDuration));
                    if (!renewed.identity().runRef().equals(control.run)) throw new IllegalStateException("execution_renew_owner_mismatch");
                    if (renewed.cancelled() || renewed.status() == RunStatus.CANCELLED) control.cancelled = true;
                    else if (renewed.lease().isEmpty() || !sameLease(control.lease, renewed.lease().orElseThrow())) control.ownershipLost = true;
                    else control.lease = renewed.lease().orElseThrow();
                } catch (RuntimeException failure) {
                    // 无法确认当前写权即单调停止新派发；实际在途结果仍由Coordinator保存真实事实。
                    control.ownershipLost = true;
                    LOG.warn("执行活动续租未确认，停止新派发 tenant={} run={} failureType={}", control.run.tenantId().value(),
                            control.run.runId(), failure.getClass().getSimpleName(), failure);
                }
            }
        }
    }

    /** 租约期限允许续长，claim/worker/fence任一变化都不属于原控制区间。 */
    private static boolean sameLease(LeaseFence before, LeaseFence after) {
        return before.tenantId().equals(after.tenantId()) && before.claimId().equals(after.claimId())
                && before.workerId().equals(after.workerId()) && before.fencingToken() == after.fencingToken();
    }

    /** 停止新定时任务、等待活动续租退出，再等待所属调度线程静止。 */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        heartbeat.cancel(false);
        synchronized (lifecycle) { active.values().forEach(Active::close); }
        if (!ownsTimer) return;
        timer.shutdownNow();
        try {
            if (!timer.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("execution_activity_shutdown_incomplete");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("execution_activity_shutdown_interrupted", interrupted);
        }
    }

    /** 单次claim的线程安全停止观察，只有控制器可更新租约；close与续租互斥。 */
    private final class Active implements ExecutionCoordinator.Control {
        private final RunRef run;
        private LeaseFence lease;
        /** 取消仅从false变true，Provider可无阻塞观察。 */
        private volatile boolean cancelled;
        /** 续租失败或同一租约不再存在时单调变true。 */
        private volatile boolean ownershipLost;
        /** 释放与正在执行的续租通过实例锁互斥。 */
        private boolean released;
        private boolean nodeFrame;
        private boolean programFrame;

        /** 保留真实claim身份，不根据RunId推导另一租约。 */
        private Active(RunRef run, LeaseFence lease) { this.run = run; this.lease = lease; }
        @Override public boolean cancelled() { return cancelled; }
        @Override public boolean ownershipLost() { return ownershipLost; }
        @Override public synchronized void close() {
            if (released) return;
            released = true; active.remove(run, this);
            if (nodeFrame) nodes.end(run);
            if (programFrame) programs.get().end(run);
        }
    }
}

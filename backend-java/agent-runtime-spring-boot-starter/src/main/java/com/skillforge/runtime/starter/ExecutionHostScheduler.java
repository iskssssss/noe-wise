package com.skillforge.runtime.starter;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 所有执行宿主共用的一个控制线程，只承载续租/有界补扫通知，不派发Run业务。 */
public final class ExecutionHostScheduler implements AutoCloseable {
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("runtime-execution-control-", 0).factory());
    public ExecutionHostScheduler() { timer.setRemoveOnCancelPolicy(true); }
    public ScheduledFuture<?> scheduleControl(Runnable task, Duration delay) {
        return timer.schedule(task, Math.max(0, delay.toNanos()), TimeUnit.NANOSECONDS);
    }
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration interval) {
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("execution_control_interval_invalid");
        return timer.scheduleWithFixedDelay(task, interval.toNanos(), interval.toNanos(), TimeUnit.NANOSECONDS);
    }
    /** Institution活动控制适配仍复用原续租实现；该Executor仅由本Bean关闭。 */
    public java.util.concurrent.ScheduledExecutorService controls() { return timer; }
    @Override public void close() {
        timer.shutdownNow();
        try { if (!timer.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("execution_control_shutdown_incomplete"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("execution_control_shutdown_interrupted", interrupted); }
    }
}

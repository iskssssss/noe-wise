package com.skillforge.core.shared.resilience;

import java.time.Clock;
import java.util.Objects;

/**
 * 熔断器。
 *
 * <p>状态机：CLOSED（正常）→ OPEN（失败达阈值）→ HALF_OPEN（超时后探测）→ CLOSED。
 * 线程安全，供高频外部依赖调用使用。
 */
public class CircuitBreaker {

    /** 熔断器状态。 */
    public enum State {
        /** 关闭：允许全部请求。 */
        CLOSED,
        /** 打开：快速失败。 */
        OPEN,
        /** 半开：允许探测请求。 */
        HALF_OPEN
    }

    /** 熔断器名称。 */
    private final String name;

    /** 失败阈值。 */
    private final int failureThreshold;

    /** 打开后复位时间（毫秒）。 */
    private final long resetTimeoutMs;

    private final Clock clock;

    /** 当前状态。 */
    private State state = State.CLOSED;

    /** 连续失败次数。 */
    private int failureCount = 0;

    /** 打开时刻（毫秒时间戳）。 */
    private long openedAt = 0;

    private long generation;

    /**
     * 构造熔断器。
     *
     * @param name             名称
     * @param failureThreshold 失败阈值
     * @param resetTimeoutMs   复位时间
     */
    public CircuitBreaker(String name, int failureThreshold, long resetTimeoutMs) {
        this(name, failureThreshold, resetTimeoutMs, Clock.systemUTC());
    }

    /** 使用明确时钟计算探测窗口，调用方可统一外部调用的时间来源。 */
    public CircuitBreaker(String name, int failureThreshold, long resetTimeoutMs, Clock clock) {
        this.name = name;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.resetTimeoutMs = Math.max(1, resetTimeoutMs);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 获取名称。
     *
     * @return 名称
     */
    public String getName() {
        return name;
    }

    /**
     * 尝试获取执行许可。
     *
     * @return 是否允许执行
     */
    public synchronized boolean tryAcquire() {
        return acquire() != null;
    }

    /** 许可绑定状态代，旧调用不能结算后来取得的半开探测。 */
    synchronized Permit acquire() {
        if (state == State.OPEN) {
            if (clock.millis() - openedAt >= resetTimeoutMs) {
                state = State.HALF_OPEN;
                generation++;
                return new Permit(generation);
            }
            return null;
        }
        return state == State.CLOSED ? new Permit(generation) : null;
    }

    /**
     * 记录一次成功，关闭熔断并清零失败计数。
     */
    public synchronized void recordSuccess() {
        if (state != State.CLOSED) generation++;
        state = State.CLOSED;
        failureCount = 0;
    }

    synchronized void recordSuccess(Permit permit) {
        if (permit.generation() == generation) recordSuccess();
    }

    /**
     * 记录一次失败，达到阈值后打开熔断。
     */
    public synchronized void recordFailure() {
        failureCount++;
        if (state == State.HALF_OPEN || failureCount >= failureThreshold) {
            state = State.OPEN;
            generation++;
            openedAt = clock.millis();
        }
    }

    synchronized void recordFailure(Permit permit) {
        if (permit.generation() == generation) recordFailure();
    }

    /** 探测未产生业务结果时重新打开，避免异常退出永久占住半开资格。 */
    synchronized void releaseProbe(Permit permit) {
        if (permit.generation() == generation && state == State.HALF_OPEN) {
            state = State.OPEN;
            generation++;
            openedAt = clock.millis();
        }
    }

    record Permit(long generation) { }

    /**
     * 获取当前状态（测试用）。
     *
     * @return 状态
     */
    public synchronized State getState() {
        return state;
    }
}

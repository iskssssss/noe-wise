package com.skillforge.runtime.desktop;

import java.time.Duration;

/** 单机调度与单次调用容量；任务累计资源只计量，不施加平台累计预算。 */
public record DesktopPolicy(int maxActiveTasks, int maxQueuedTasks, int outputTokens, long inputTokens,
        Duration modelTimeout, Duration leaseDuration) {
    public DesktopPolicy {
        if (maxActiveTasks <= 0 || maxQueuedTasks <= 0 || outputTokens <= 0 || inputTokens <= 0
                || modelTimeout.isNegative() || modelTimeout.isZero()
                || leaseDuration.compareTo(modelTimeout) <= 0)
            throw new IllegalArgumentException("desktop_policy_invalid");
    }

    public DesktopPolicy withOutputTokens(int value) {
        return new DesktopPolicy(maxActiveTasks, maxQueuedTasks, value, inputTokens, modelTimeout, leaseDuration);
    }

    public static DesktopPolicy defaults() {
        return new DesktopPolicy(2, 100, 16384, 65536, Duration.ofSeconds(120), Duration.ofMinutes(5));
    }
}

package com.skillforge.core.shared.resilience;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 外部调用弹性组件。
 *
 * <p>统一提供“超时（调用方自行保证）→ 带退避的重试 → 熔断”三件套；
 * 每个命名依赖拥有独立熔断器，失败按指数退避重试若干次后计入熔断。
 */
@Component
public class Resilience {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long RESET_TIMEOUT_MILLIS = 10_000;

    /** 熔断器集合：名称 → 熔断器。 */
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    /** 每次逻辑调用冻结技术重试参数。 */
    private final RetryPolicyProvider policies;

    public Resilience(RetryPolicyProvider policies) {
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    /**
     * 带重试与熔断地执行外部调用。
     *
     * @param name   依赖名称
     * @param action 调用动作
     * @param <T>    返回类型
     * @return 调用结果
     */
    public <T> T call(String name, Callable<T> action) {
        RetryPolicyProvider.RetryPolicy policy = policies.currentPolicy();
        return execute(name, action, policy.maxAttempts(), policy.backoffBaseMilliseconds());
    }

    /**
     * 单次执行 + 熔断，不做自动重试。
     *
     * <p>用于非幂等的外部调用（如 MCP tools/call 会产生副作用），
     * 重试可能导致副作用重复执行，仅保留熔断保护。
     *
     * @param name   依赖名称
     * @param action 调用动作
     * @param <T>    返回类型
     * @return 调用结果
     */
    public <T> T callOnce(String name, Callable<T> action) {
        return execute(name, action, 1, 0);
    }

    /** 一次逻辑调用持有一次探测资格；重试耗尽才累计一次失败。 */
    private <T> T execute(String name, Callable<T> action, int maxAttempts, long backoffBaseMs) {
        if (Thread.currentThread().isInterrupted()) {
            throw new ResilienceException(name, new InterruptedException("外部调用已取消"));
        }
        CircuitBreaker breaker = breakers.computeIfAbsent(name,
                key -> new CircuitBreaker(key, FAILURE_THRESHOLD, RESET_TIMEOUT_MILLIS));
        var permit = breaker.acquire();
        if (permit == null) {
            throw new CircuitOpenException(name);
        }
        boolean settled = false;
        try {
            Exception last = null;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("外部调用已取消");
                }
                try {
                    T result = action.call();
                    breaker.recordSuccess(permit);
                    settled = true;
                    return result;
                } catch (CircuitOpenException | InterruptedException terminal) {
                    throw terminal;
                } catch (Exception failure) {
                    last = failure;
                }
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(Math.multiplyExact(backoffBaseMs, 1L << attempt));
                }
            }
            breaker.recordFailure(permit);
            settled = true;
            throw new ResilienceException(name, last);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            breaker.recordFailure(permit);
            settled = true;
            throw new ResilienceException(name, cancelled);
        } finally {
            if (!settled) {
                breaker.releaseProbe(permit);
            }
        }
    }
}

package com.skillforge.core.shared.resilience;

/**
 * 熔断打开异常。
 *
 * <p>当外部依赖连续失败达到阈值后，熔断器打开并抛出该异常，
 * 调用方应快速失败或走降级路径，避免继续阻塞请求。
 */
public class CircuitOpenException extends RuntimeException {

    /**
     * 构造熔断异常。
     *
     * @param name 熔断器名称
     */
    public CircuitOpenException(String name) {
        super("熔断已打开: " + name);
    }
}

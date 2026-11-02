package com.skillforge.core.shared.resilience;

/**
 * 外部调用重试耗尽异常。
 *
 * <p>表示指定依赖在多次重试后仍然失败，由调用方决定降级策略。
 */
public class ResilienceException extends RuntimeException {

    /**
     * 构造异常。
     *
     * @param name 依赖名称
     * @param cause 最后一次失败原因
     */
    public ResilienceException(String name, Throwable cause) {
        super("外部调用重试耗尽: " + name, cause);
    }
}

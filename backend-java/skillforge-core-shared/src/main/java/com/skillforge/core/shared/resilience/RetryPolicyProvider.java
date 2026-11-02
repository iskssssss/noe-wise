package com.skillforge.core.shared.resilience;

/** 外部调用技术重试参数的来源；一次逻辑调用读取一次不可变策略。 */
@FunctionalInterface
public interface RetryPolicyProvider {
    RetryPolicy currentPolicy();

    /** 尝试次数包含首次调用；退避基数单位为毫秒。 */
    record RetryPolicy(int maxAttempts, long backoffBaseMilliseconds) {
        public RetryPolicy {
            if (maxAttempts <= 0 || backoffBaseMilliseconds < 0) {
                throw new IllegalArgumentException("重试次数必须为正数且退避基数不能为负数");
            }
        }
    }
}

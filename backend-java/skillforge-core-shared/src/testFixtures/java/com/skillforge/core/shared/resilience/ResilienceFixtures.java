package com.skillforge.core.shared.resilience;

/** 单元测试显式固定技术重试策略，不依赖宿主运营配置或数据库。 */
public final class ResilienceFixtures {
    private static final RetryPolicyProvider.RetryPolicy POLICY = new RetryPolicyProvider.RetryPolicy(3, 500);
    private ResilienceFixtures() { }

    public static Resilience withDefaultSettings() {
        return new Resilience(() -> POLICY);
    }
}

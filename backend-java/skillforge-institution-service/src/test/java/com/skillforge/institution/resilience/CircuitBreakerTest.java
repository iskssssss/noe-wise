package com.skillforge.institution.resilience;
import com.skillforge.core.shared.resilience.Resilience;
import com.skillforge.core.shared.resilience.ResilienceException;
import com.skillforge.core.shared.resilience.CircuitOpenException;
import com.skillforge.core.shared.resilience.CircuitBreaker;

import com.skillforge.core.shared.resilience.ResilienceFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 弹性组件单元测试。
 *
 * <p>覆盖带退避的重试恢复、失败达阈值后的熔断快速失败，以及半开探测恢复。
 */
class CircuitBreakerTest {

    /**
     * 首次失败、第二次成功时重试应恢复。
     */
    @Test
    void retrySucceedsOnSecondAttempt() throws Exception {
        Resilience resilience = ResilienceFixtures.withDefaultSettings();
        AtomicInteger calls = new AtomicInteger();
        String result = resilience.call("retry", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(2, calls.get());
    }

    /**
     * 连续失败达到阈值后，后续调用应快速失败（熔断打开）。
     */
    @Test
    void resilienceOpensAfterThreshold() {
        Resilience resilience = ResilienceFixtures.withDefaultSettings();
        for (int i = 0; i < 3; i++) {
            assertThrows(ResilienceException.class, () -> resilience.call("fail",
                    () -> {
                        throw new IllegalStateException("boom");
                    }));
        }
        assertThrows(CircuitOpenException.class, () -> resilience.call("fail",
                () -> "should-not-run"));
    }

    /**
     * 半开状态探测成功后应恢复关闭。
     */
    @Test
    void halfOpenRecoversOnSuccess() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("recover", 1, 50);
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        Thread.sleep(80);
        assertTrue(breaker.tryAcquire());
        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }
}

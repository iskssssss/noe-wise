package com.skillforge.core.shared.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 中断必须阻止后续外调，不能只设置标记后继续重试。 */
class ResilienceCancellationTest {
    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void interruptedActionStopsAfterOneAttemptAndPreservesInterrupt() {
        var attempts = new AtomicInteger();
        var resilience = resilience();
        var failure = assertThrows(ResilienceException.class, () -> resilience.call("cancelled", () -> {
            attempts.incrementAndGet();
            throw new InterruptedException("cancelled");
        }));
        assertEquals(1, attempts.get());
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(failure.getCause() instanceof InterruptedException);
    }

    @Test
    void interruptedBackoffDoesNotStartAnotherAttempt() {
        var attempts = new AtomicInteger();
        assertThrows(ResilienceException.class, () -> resilience().call("backoff", () -> {
            attempts.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("unavailable");
        }));
        assertEquals(1, attempts.get());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void preInterruptedCallDoesNotInvokeExternalAction() {
        var attempts = new AtomicInteger();
        Thread.currentThread().interrupt();
        assertThrows(ResilienceException.class,
                () -> resilience().callOnce("cancelled", attempts::incrementAndGet));
        assertEquals(0, attempts.get());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void halfOpenAllowsOnlyOneOutstandingProbe() {
        var clock = new TestClock();
        var breaker = new CircuitBreaker("probe", 1, 10, clock);
        breaker.recordFailure();
        assertFalse(breaker.tryAcquire());
        clock.advance(10);
        assertTrue(breaker.tryAcquire());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());
        assertFalse(breaker.tryAcquire());
        breaker.recordSuccess();
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void concurrentRecoveryRequestsShareOnlyOneHalfOpenPermit() throws Exception {
        var clock = new TestClock();
        var breaker = new CircuitBreaker("concurrent-probe", 1, 10, clock);
        breaker.recordFailure();
        clock.advance(10);
        var start = new java.util.concurrent.CyclicBarrier(12);
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = java.util.stream.IntStream.range(0, 12).mapToObj(index -> workers.submit(() -> {
                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return breaker.acquire();
            })).toList();
            var winners = new java.util.ArrayList<CircuitBreaker.Permit>();
            for (var request : requests) {
                var permit = request.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (permit != null) winners.add(permit);
            }
            assertEquals(1, winners.size());
            assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());
            breaker.recordSuccess(winners.getFirst());
            assertTrue(breaker.tryAcquire());
        }
    }

    @Test
    void failedOrAbandonedProbeReopensUntilNextWindow() {
        var clock = new TestClock();
        var breaker = new CircuitBreaker("probe", 1, 10, clock);
        breaker.recordFailure();
        clock.advance(10);
        assertTrue(breaker.tryAcquire());
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.tryAcquire());
        clock.advance(10);
        var permit = breaker.acquire();
        org.junit.jupiter.api.Assertions.assertNotNull(permit);
        breaker.releaseProbe(permit);
        assertFalse(breaker.tryAcquire());
        clock.advance(10);
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void lateClosedCallsCannotSettleOrReleaseTheNewHalfOpenProbe() {
        var clock = new TestClock();
        var breaker = new CircuitBreaker("probe", 1, 10, clock);
        var oldSuccess = breaker.acquire();
        var oldAbandoned = breaker.acquire();
        var failing = breaker.acquire();
        breaker.recordFailure(failing);
        clock.advance(10);
        var probe = breaker.acquire();
        org.junit.jupiter.api.Assertions.assertNotNull(probe);
        breaker.recordSuccess(oldSuccess);
        breaker.releaseProbe(oldAbandoned);
        breaker.recordFailure(failing);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());
        assertFalse(breaker.tryAcquire());
        breaker.recordSuccess(probe);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void exhaustedRetriesCountOnceAndSuccessfulRetryResetsFailures() {
        var resilience = resilience();
        var attempts = new AtomicInteger();
        for (int call = 0; call < 2; call++) {
            assertThrows(ResilienceException.class, () -> resilience.call("dependency", () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("unavailable");
            }));
        }
        assertEquals(6, attempts.get());
        var recovery = new AtomicInteger();
        assertEquals("ok", resilience.call("dependency", () -> {
            if (recovery.incrementAndGet() == 1) throw new IllegalStateException("transient");
            return "ok";
        }));
        for (int call = 0; call < 3; call++) {
            assertThrows(ResilienceException.class, () -> resilience.callOnce("dependency", () -> {
                throw new IllegalStateException("unavailable");
            }));
        }
        assertThrows(CircuitOpenException.class, () -> resilience.callOnce("dependency", () -> "unexpected"));
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-03T00:00:00Z");
        void advance(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @Test
    void retriesUseOneFrozenPolicyEvenWhenConfigurationChangesMidCall() {
        var current = new java.util.concurrent.atomic.AtomicReference<>(new RetryPolicyProvider.RetryPolicy(2, 0));
        var reads = new AtomicInteger();
        var resilience = new Resilience(() -> {
            reads.incrementAndGet();
            return current.get();
        });
        var attempts = new AtomicInteger();
        assertThrows(ResilienceException.class, () -> resilience.call("frozen", () -> {
            attempts.incrementAndGet();
            current.set(new RetryPolicyProvider.RetryPolicy(4, 0));
            throw new IllegalStateException("temporary");
        }));
        assertEquals(2, attempts.get());
        assertEquals(1, reads.get());
        assertThrows(ResilienceException.class, () -> resilience.call("next", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("temporary");
        }));
        assertEquals(6, attempts.get());
        assertEquals(2, reads.get());
    }

    private Resilience resilience() {
        return new Resilience(() -> new RetryPolicyProvider.RetryPolicy(3, 1));
    }
}

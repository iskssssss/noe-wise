package com.skillforge.runtime.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.RetryMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DesktopStreamTest {
    @Test void bodyPreviewNeverConvertsUnknownToCompletionAndSubscriptionIsReleased() {
        var cancelled = new AtomicBoolean(); var previews = new ArrayList<String>();
        var failure = new PortFailure("MODEL_OUTCOME_UNKNOWN", RetryMode.NONE, new ResultRef("receipt:original"));
        var result = DesktopCapabilities.stream(subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long count) {
                    subscriber.onNext(new ModelPort.ModelStreamEvent.ContentDelta(1, "partial"));
                    subscriber.onNext(new ModelPort.ModelStreamEvent.ReasoningDelta(2, "private"));
                    subscriber.onNext(new ModelPort.ModelStreamEvent.ToolCallDelta(3, 0, "call", "tool", "{}"));
                    subscriber.onNext(new ModelPort.ModelStreamEvent.OutcomeUnknown(failure));
                    subscriber.onComplete();
                }
                public void cancel() { cancelled.set(true); }
            });
        }, Duration.ofSeconds(1), () -> false, previews::add);
        assertEquals(List.of("partial"), previews);
        assertEquals(new ModelPort.ModelResult.OutcomeUnknown(failure), result);
        assertTrue(cancelled.get());
    }

    @Test void cancellationReleasesSilentProviderWithoutWaitingForModelTimeout() throws Exception {
        var requested = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new AtomicBoolean(); var released = new AtomicBoolean();
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var result = workers.submit(() -> DesktopCapabilities.stream(subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    public void request(long count) {
                        subscriber.onNext(new ModelPort.ModelStreamEvent.ContentDelta(1, "partial"));
                        requested.countDown();
                        // 上游取消后可以静默；宿主必须自行结束等待。
                    }
                    public void cancel() { released.set(true); }
                });
            }, Duration.ofMinutes(5), cancelled::get, delta -> { }));
            assertTrue(requested.await(1, java.util.concurrent.TimeUnit.SECONDS));
            cancelled.set(true);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("desktop_stream_cancelled", failure.getCause().getMessage());
            assertTrue(released.get());
        }
    }

    @Test void partialBodyWithoutTerminalRemainsUnresolved() {
        var cancelled = new AtomicBoolean(); var previews = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> DesktopCapabilities.stream(subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long count) {
                    subscriber.onNext(new ModelPort.ModelStreamEvent.ContentDelta(1, "partial"));
                    subscriber.onComplete();
                }
                public void cancel() { cancelled.set(true); }
            });
        }, Duration.ofSeconds(1), () -> false, previews::add));
        assertEquals(List.of("partial"), previews);
        assertTrue(cancelled.get());
    }
}

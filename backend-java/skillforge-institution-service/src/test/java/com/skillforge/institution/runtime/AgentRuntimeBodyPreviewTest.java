package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRuntimeBodyPreviewTest {
    @Test void cumulativeSafeSnapshotsHaveUniqueReplayIdentitiesAndNewAttemptsReplaceRatherThanAppend() {
        var fixture = new Fixture();
        var first = fixture.begin("attempt-1", 1);
        first.accept("第一轮".repeat(100));
        fixture.advance(Duration.ofMillis(100));
        first.accept("继续".repeat(100));
        assertEquals(2, fixture.outputs.size());
        var one = fixture.data(0); var two = fixture.data(1);
        assertTrue(two.get("content").toString().startsWith(one.get("content").toString()));
        assertEquals(1L, ((Number) one.get("preview_sequence")).longValue());
        assertEquals(2L, ((Number) two.get("preview_sequence")).longValue());
        assertNotEquals(fixture.ids.get(0), fixture.ids.get(1));
        var next = fixture.begin("attempt-2", 2);
        next.accept("第二轮".repeat(100));
        assertEquals(one.get("preview_message_id"), fixture.data(2).get("preview_message_id"));
        assertEquals("attempt-2", fixture.data(2).get("attempt_id"));
        assertFalse(fixture.data(2).get("content").toString().contains("第一轮"));
        assertEquals(false, one.get("authoritative"));
        assertEquals("root-input", one.get("root_input_id"));
        assertNull(TenantContext.getTenantId());
    }

    @Test void splitCredentialsAreHeldBackAndRejectionClearsEarlierPreviewWithoutPublishingSecrets() {
        var fixture = new Fixture(); var preview = fixture.begin("attempt-1", 1);
        preview.accept("可以展示的说明".repeat(100));
        preview.accept(" api_");
        preview.accept("key=\"real-secret-value");
        fixture.advance(Duration.ofMillis(100));
        preview.accept("\" ".repeat(100));
        assertEquals("rejected", fixture.data(fixture.outputs.size() - 1).get("state"));
        assertEquals("", fixture.data(fixture.outputs.size() - 1).get("content"));
        assertFalse(fixture.outputs.toString().contains("real-secret-value"));
        int count = fixture.outputs.size(); preview.accept("结束");
        assertEquals(count, fixture.outputs.size());
    }

    @Test void failuresUnknownAndCancellationCloseOnlyThePreviewAndNeverPromoteItToAResult() {
        for (String state : List.of("failed", "unknown", "cancelled")) {
            var fixture = new Fixture(); var preview = fixture.begin("attempt-1", 1);
            preview.accept("部分正文".repeat(100)); preview.unresolved(state);
            var terminal = fixture.data(fixture.outputs.size() - 1);
            assertEquals(state, terminal.get("state")); assertEquals("", terminal.get("content"));
            assertEquals(false, terminal.get("authoritative"));
            assertTrue(fixture.outputs.stream().allMatch(value -> value.get("event").equals("message.preview")));
        }
    }

    @Test void newAttemptClearsTheOldCandidateEvenWhileItsFirstTextIsInsideTheSafetyHoldback() {
        var fixture = new Fixture(); fixture.begin("attempt-1", 1).accept("上一轮正文".repeat(100));
        fixture.begin("attempt-2", 2).accept("短");
        assertEquals("attempt-2", fixture.data(1).get("attempt_id"));
        assertEquals("", fixture.data(1).get("content"));
        assertEquals("streaming", fixture.data(1).get("state"));
    }

    @Test void creatingAnObserverDoesNotReadPublicPolicyAndCaptureFailureStaysInsideTheContentCallback() {
        var guard = mock(PublicOutputGuard.class);
        var mailbox = mock(SessionMailboxService.class);
        var previews = new AgentRuntimeBodyPreview(mailbox, guard, new ObjectMapper(), 2000, Duration.ofMillis(100), System::nanoTime);
        var fixture = new Fixture();
        var registered = mock(ExecutionStore.DispatchResult.Registered.class, RETURNS_DEEP_STUBS);
        when(registered.run().identity()).thenReturn(fixture.identity); when(registered.attempt().id()).thenReturn("attempt-1");
        var prepared = mock(ExecutionStore.Prepared.class); when(prepared.ordinal()).thenReturn(1L);
        var observer = previews.begin(registered, prepared);
        verifyNoInteractions(guard, mailbox);
        when(guard.capture(fixture.identity)).thenThrow(new IllegalStateException("policy unavailable"));
        assertThrows(IllegalStateException.class, () -> observer.accept("正文"));
        observer.flush();
        verify(guard).capture(fixture.identity);
        verifyNoInteractions(mailbox);
    }

    @Test void previewBudgetDoesNotExposeAnUncheckedTail() {
        var fixture = new Fixture(); var preview = fixture.begin("attempt-1", 1);
        preview.accept("x".repeat(2001));
        assertEquals("unavailable", fixture.data(0).get("state"));
        assertEquals("", fixture.data(0).get("content"));
    }

    @Test void frequentSmallDeltasCoalescePublicationCountAndStoredUtf8BytesWithoutChangingTheLastSafePrefix() {
        var perDelta = new Fixture(Duration.ofNanos(1));
        var combined = new Fixture(Duration.ofMillis(100));
        var before = perDelta.begin("attempt-1", 1); var after = combined.begin("attempt-1", 1);
        for (int index = 0; index < 200; index++) {
            perDelta.advance(Duration.ofMillis(1)); combined.advance(Duration.ofMillis(1));
            before.accept("正文，"); after.accept("正文，");
        }
        before.flush(); after.flush();
        assertTrue(combined.outputs.size() <= 4);
        assertTrue(perDelta.outputs.size() > combined.outputs.size() * 10);
        long beforeBytes = perDelta.contentBytes(); long afterBytes = combined.contentBytes();
        System.out.println("body_preview_coalescing chunks=200 chunk_interval_ms=1 baseline_interval_ns=1 combined_interval_ms=100"
                + " baseline_writes=" + perDelta.outputs.size() + " combined_writes=" + combined.outputs.size()
                + " baseline_content_utf8_bytes=" + beforeBytes + " combined_content_utf8_bytes=" + afterBytes);
        assertTrue(afterBytes < beforeBytes / 10, "before=" + beforeBytes + " after=" + afterBytes);
        assertEquals(perDelta.data(perDelta.outputs.size() - 1).get("content"), combined.data(combined.outputs.size() - 1).get("content"));
        assertTrue(combined.outputs.stream().allMatch(output -> ((Map<?, ?>) output.get("data")).get("authoritative").equals(false)));
    }

    @Test void intervalBoundaryAndSuccessfulFlushPublishOnlySafeAccumulatedPrefixes() {
        var f = new Fixture(); var preview = f.begin("attempt-1", 1);
        preview.accept("首段，".repeat(100));
        preview.accept("合并，".repeat(20));
        f.advance(Duration.ofMillis(99)); preview.accept("未到期，");
        assertEquals(1, f.outputs.size());
        f.advance(Duration.ofMillis(1)); preview.accept("恰好到期，");
        assertEquals(2, f.outputs.size());
        preview.accept("待flush，".repeat(20)); preview.flush();
        assertEquals(3, f.outputs.size());
        assertEquals("streaming", f.data(2).get("state"));
        preview.flush(); assertEquals(3, f.outputs.size());
        assertFalse(f.data(2).get("content").toString().endsWith("待flush，".repeat(20)));
    }

    @Test void secretAcrossCoalescedChunksIsNeverPublishedAndLastFlushClearsThePreview() {
        var f = new Fixture(); var preview = f.begin("attempt-1", 1);
        preview.accept("可公开说明，".repeat(100));
        preview.accept(" api_"); preview.accept("key=\"real-secret-value"); preview.accept("\" ".repeat(100));
        assertEquals(1, f.outputs.size());
        preview.flush();
        assertEquals("rejected", f.data(1).get("state"));
        assertEquals("", f.data(1).get("content"));
        assertFalse(f.outputs.toString().contains("real-secret-value"));
    }

    @Test void flushStorageFailureStopsOnlyThePreviewAndCannotEscapeIntoTheAlreadyObtainedModelResult() {
        var f = new Fixture(); var preview = f.begin("attempt-1", 1);
        preview.accept("首段，".repeat(100)); preview.accept("末段，".repeat(100));
        doThrow(new IllegalStateException("outbox unavailable")).when(f.mailbox)
                .appendOutput(anyString(), anyString(), anyString(), anyString(), anyMap(), any(TraceContext.class), anyString());
        assertDoesNotThrow(preview::flush);
        clearInvocations(f.mailbox); f.advance(Duration.ofMillis(100));
        preview.accept("不再发布"); preview.flush();
        verifyNoInteractions(f.mailbox);
    }

    private static final class Fixture {
        private final SessionMailboxService mailbox = mock(SessionMailboxService.class);
        private final List<Map<String, Object>> outputs = new ArrayList<>();
        private final List<String> ids = new ArrayList<>();
        private final TenantId tenant = new TenantId("7");
        private final SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "session-1"), new AgentId("31"), new EndUserId(51));
        private final RunIdentity identity = new RunIdentity(owner, new AgentId("31"), new InputId("input-1"), new RunRef(tenant, "run-1"),
                new TraceEnvelope("trace-1", "span-1", new InputId("root-input"), Optional.empty()), new ResultRef("verified-1"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, "input:input-1"));
        private final AtomicLong ticks = new AtomicLong();
        private final AgentRuntimeBodyPreview previews;

        private Fixture() { this(Duration.ofMillis(100)); }
        private Fixture(Duration interval) {
            previews = new AgentRuntimeBodyPreview(mailbox, new PublicOutputGuard("private-artifacts"), new ObjectMapper(), 2000, interval, ticks::get);
            when(mailbox.appendOutput(eq("7"), eq("session-1"), anyString(), eq("message.preview"), anyMap(), any(TraceContext.class), eq("run-1")))
                    .thenAnswer(call -> {
                        assertEquals("7", TenantContext.getTenantId());
                        ids.add(call.getArgument(2)); outputs.add(call.getArgument(4)); return null;
                    });
        }
        private AgentRuntimeBodyPreview.Accumulator begin(String attempt, long ordinal) {
            var registered = mock(ExecutionStore.DispatchResult.Registered.class, RETURNS_DEEP_STUBS);
            when(registered.run().identity()).thenReturn(identity); when(registered.attempt().id()).thenReturn(attempt);
            var prepared = mock(ExecutionStore.Prepared.class); when(prepared.ordinal()).thenReturn(ordinal);
            return previews.begin(registered, prepared);
        }
        private void advance(Duration elapsed) { ticks.addAndGet(elapsed.toNanos()); }
        private long contentBytes() { return outputs.stream().mapToLong(output -> ((Map<?, ?>) output.get("data")).get("content").toString().getBytes(StandardCharsets.UTF_8).length).sum(); }
        @SuppressWarnings("unchecked") private Map<String, Object> data(int index) { return (Map<String, Object>) outputs.get(index).get("data"); }
    }
}

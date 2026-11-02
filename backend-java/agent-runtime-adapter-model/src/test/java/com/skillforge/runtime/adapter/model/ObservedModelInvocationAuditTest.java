package com.skillforge.runtime.adapter.model;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ObservedModelInvocationAuditTest {
    private static final ModelInvocationAudit.Provider PROVIDER = new ModelInvocationAudit.Provider("provider", "service", "model");
    private static final ModelUsage USAGE = new ModelUsage(OptionalLong.of(7), OptionalLong.empty());

    @Test void delegatesAllCallbacksAndObservesOnlyReportedUsage() {
        var delegate = new RecordingAudit();
        var audit = new ObservedModelInvocationAudit(delegate);
        assertTrue(audit.observed().isEmpty());
        assertFalse(audit.start(null, PROVIDER));
        audit.succeeded(null, PROVIDER, USAGE);
        assertTrue(audit.observed().isEmpty());
        audit.usageReported(null, PROVIDER, 2, USAGE);
        assertEquals(new ObservedModelInvocationAudit.ObservedUsage(null, PROVIDER, 2, USAGE), audit.observed().orElseThrow());
        audit.failed(null, PROVIDER, "failed");
        audit.unresolved(null, PROVIDER, "unknown");
        assertEquals(List.of("start", "succeeded", "usage:2", "failed:failed", "unresolved:unknown"), delegate.events);
    }

    @Test void failedDelegationDoesNotReplaceLastSuccessfulSnapshot() {
        var delegate = new RecordingAudit();
        var audit = new ObservedModelInvocationAudit(delegate);
        audit.usageReported(null, PROVIDER, 1, USAGE);
        delegate.failUsage = true;
        assertThrows(IllegalStateException.class, () -> audit.usageReported(null, PROVIDER, 2, USAGE));
        assertEquals(1, audit.observed().orElseThrow().revision());
    }

    @Test void separateAttemptsStayIsolatedAndAreReadableAcrossThreads() throws Exception {
        var first = new ObservedModelInvocationAudit(new RecordingAudit());
        var second = new ObservedModelInvocationAudit(new RecordingAudit());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> first.usageReported(null, PROVIDER, 1, USAGE));
            var two = workers.submit(() -> second.usageReported(null, PROVIDER, 2, USAGE));
            one.get();
            two.get();
        }
        assertEquals(1, first.observed().orElseThrow().revision());
        assertEquals(2, second.observed().orElseThrow().revision());
        assertTrue(first.observed().orElseThrow().usage().outputTokens().isEmpty());
    }

    private static final class RecordingAudit implements ModelInvocationAudit {
        private final List<String> events = new ArrayList<>();
        private boolean failUsage;
        @Override public boolean start(ModelCall call, Provider provider) { events.add("start"); return false; }
        @Override public void succeeded(ModelCall call, Provider provider, ModelUsage usage) { events.add("succeeded"); }
        @Override public void usageReported(ModelCall call, Provider provider, long revision, ModelUsage usage) {
            if (failUsage) throw new IllegalStateException("audit failed");
            events.add("usage:" + revision);
        }
        @Override public void failed(ModelCall call, Provider provider, String reasonCode) { events.add("failed:" + reasonCode); }
        @Override public void unresolved(ModelCall call, Provider provider, String reasonCode) { events.add("unresolved:" + reasonCode); }
    }
}

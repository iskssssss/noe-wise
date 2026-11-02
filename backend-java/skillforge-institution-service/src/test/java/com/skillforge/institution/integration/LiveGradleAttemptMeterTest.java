package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.skillforge.institution.integration.LiveGradleAttemptMeter.Bounds;
import com.skillforge.institution.integration.LiveGradleAttemptMeter.State;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No container/provider: exercises the actual audit decorator and exact frozen JDBC Demand lookup. */
class LiveGradleAttemptMeterTest {
    @TempDir Path root;
    final List<Map<String, Object>> journal = new ArrayList<>();
    final AtomicLong elapsed = new AtomicLong();
    final ModelInvocationAudit.Provider provider = new ModelInvocationAudit.Provider("fixture-provider", "fixture-service", "fixture-model");
    private Map<String, String> environment() {
        return new LinkedHashMap<>(Map.of("LIVE_GRADLE_SUITE", "conversation", "LIVE_GRADLE_RUN_ID", "full-project-test-20261012",
            "LIVE_GRADLE_ROUND", "1", "LIVE_GRADLE_RESERVATION_ID", "1b0cc243-311d-47a6-a49c-cbf8d0cd8f30",
            "LIVE_GRADLE_BATCH_ID", "offline-control", "LIVE_GRADLE_EVIDENCE_DIR", root.toString(),
            "LIVE_GRADLE_MAX_ATTEMPTS", "2", "LIVE_GRADLE_MAX_TOKENS", "20000", "LIVE_GRADLE_MAX_SECONDS", "600"));
    }
    private State state() { return new State(environment(), Clock.systemUTC(), elapsed::get,
        row -> journal.add(new LinkedHashMap<>(row))); }
    private ModelCall call(String attempt) {
        ModelCall call = mock(ModelCall.class, RETURNS_DEEP_STUBS);
        when(call.context().dispatch().attemptId()).thenReturn(attempt);
        when(call.context().callContext().runIdentity().runRef().runId()).thenReturn("owned-run");
        when(call.context().callContext().runIdentity().runRef().tenantId().value()).thenReturn("1");
        when(call.budgetCategory()).thenReturn(ExecutionBudgetPort.Category.BUSINESS_MODEL);
        when(call.generation()).thenReturn(new GenerationOptions(OptionalInt.of(128), OptionalDouble.empty()));
        return call;
    }
    private ModelUsage usage(long input, long output) { return new ModelUsage(OptionalLong.of(input), OptionalLong.of(output)); }
    private DataSource source(String run, String category, String status, Object[][] amounts) throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = mock(Connection.class);
        PreparedStatement scope = mock(PreparedStatement.class), query = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
            invocation.<String>getArgument(0).contains("runtime_budget_attempts") ? query : scope);
        // 每个 attempt 查询拥有新的 JDBC 游标；不能复用上次已关闭且耗尽的结果集。
        when(query.executeQuery()).thenAnswer(queryInvocation -> {
            ResultSet rows = mock(ResultSet.class); AtomicInteger index = new AtomicInteger(-1);
            when(rows.next()).thenAnswer(invocation -> index.incrementAndGet() < amounts.length);
            when(rows.getString(1)).thenReturn(run); when(rows.getString(2)).thenReturn(category);
            when(rows.getString(3)).thenReturn(status);
            when(rows.getString(4)).thenAnswer(invocation -> amounts[index.get()][0]);
            when(rows.getLong(5)).thenAnswer(invocation -> ((Number) amounts[index.get()][1]).longValue());
            when(rows.wasNull()).thenReturn(false);
            return rows;
        });
        return source;
    }
    private DataSource source() throws Exception {
        return source("owned-run", "BUSINESS_MODEL", "SENT", new Object[][] {
            {"INPUT_TOKENS", 10000L}, {"OUTPUT_TOKENS", 128L}, {"TOTAL_TOKENS", 10128L}});
    }

    @Test void exactDemandAndDurableSendPrecedeDelegatePermissionAndNoTotalDoubleCount() throws Exception {
        State state = state(); ModelInvocationAudit delegate = mock(ModelInvocationAudit.class);
        when(delegate.start(any(), any())).thenAnswer(invocation -> {
            assertEquals("reserved", journal.getLast().get("event")); return true;
        });
        var meter = LiveGradleAttemptMeter.wrapForTest(delegate, source(), state);
        ModelCall first = call("first"); assertTrue(meter.start(first, provider));
        assertEquals("sent", journal.getLast().get("event"));
        assertEquals(10000L, journal.getFirst().get("reserved_input_tokens"));
        assertEquals(128L, journal.getFirst().get("reserved_output_tokens"));
        meter.succeeded(first, provider, usage(11, 1));
        assertTrue(meter.start(call("second"), provider));
        verify(delegate, times(2)).start(any(), any());
    }

    @Test void persistenceFailureOrExpiredGateNeverCallsDelegate() throws Exception {
        State state = new State(environment(), Clock.systemUTC(), elapsed::get, row -> {
            if ("reserved".equals(row.get("event"))) throw new IllegalStateException("injected_fsync_failure");
        });
        ModelInvocationAudit delegate = mock(ModelInvocationAudit.class);
        var meter = LiveGradleAttemptMeter.wrapForTest(delegate, source(), state);
        assertThrows(IllegalStateException.class, () -> meter.start(call("journal-fail"), provider));
        verifyNoInteractions(delegate); assertTrue(state.stopped());
        State expired = state(); elapsed.set(Duration.ofSeconds(600).toNanos());
        assertFalse(LiveGradleAttemptMeter.wrapForTest(delegate, source(), expired).start(call("expired"), provider));
        verifyNoInteractions(delegate);
    }

    @Test void missingForeignUnsentOrWrongCategoryDemandCannotReachDelegate() throws Exception {
        for (DataSource source : List.of(
            source("foreign-run", "BUSINESS_MODEL", "SENT", new Object[][] {{"INPUT_TOKENS", 1L}}),
            source("owned-run", "TOOL", "SENT", new Object[][] {{"INPUT_TOKENS", 1L}}),
            source("owned-run", "BUSINESS_MODEL", "RESERVED", new Object[][] {{"INPUT_TOKENS", 1L}}),
            source("owned-run", "BUSINESS_MODEL", "SENT", new Object[0][]))) {
            ModelInvocationAudit delegate = mock(ModelInvocationAudit.class);
            var meter = LiveGradleAttemptMeter.wrapForTest(delegate, source, state());
            assertThrows(IllegalStateException.class, () -> meter.start(call("missing-demand"), provider));
            verifyNoInteractions(delegate);
        }
    }

    @Test void delegateRefusalIsNotSentAndRepeatedAttemptNeverReadsSentDemandOrDelegatesAgain() throws Exception {
        ModelInvocationAudit delegate = mock(ModelInvocationAudit.class);
        when(delegate.start(any(), any())).thenReturn(false);
        var meter = LiveGradleAttemptMeter.wrapForTest(delegate, source(), state());
        ModelCall call = call("refused"); assertFalse(meter.start(call, provider));
        assertEquals(List.of("reserved", "not_sent"), journal.stream().map(row -> row.get("event")).toList());
        assertFalse(meter.start(call, provider)); verify(delegate, times(1)).start(any(), any());
    }

    @Test void unknownFailureAndPartialUsageStopNextBusinessOrAuxiliaryAttempt() throws Exception {
        for (boolean unresolved : List.of(false, true)) {
            State state = state(); ModelInvocationAudit delegate = mock(ModelInvocationAudit.class);
            when(delegate.start(any(), any())).thenReturn(true);
            var meter = LiveGradleAttemptMeter.wrapForTest(delegate, source(), state);
            ModelCall first = call("failed-" + unresolved); assertTrue(meter.start(first, provider));
            meter.usageReported(first, provider, 1, new ModelUsage(OptionalLong.of(7), OptionalLong.empty()));
            if (unresolved) meter.unresolved(first, provider, "fixture_unknown");
            else meter.failed(first, provider, "fixture_failed");
            assertTrue(state.stopped()); assertFalse(meter.start(call("next-" + unresolved), provider));
            verify(delegate, times(1)).start(any(), any());
            assertNull(state.providerRecords().getLast().get("completion_tokens"));
        }
    }

    @Test void partialCompletionActualExcessAndCountCapPreserveActualEvidenceAndStop() throws Exception {
        State state = state(); ModelInvocationAudit delegate = mock(ModelInvocationAudit.class);
        when(delegate.start(any(), any())).thenReturn(true);
        var meter = LiveGradleAttemptMeter.wrapForTest(delegate, source(), state);
        ModelCall first = call("excess"); assertTrue(meter.start(first, provider));
        meter.succeeded(first, provider, usage(10001, 129));
        assertTrue(state.stopped()); assertFalse(meter.start(call("after-excess"), provider));
        assertEquals("10001", state.providerRecords().getLast().get("prompt_tokens"));

        State count = state();
        assertTrue(count.reserve("one", "owned-run", "p", "m", new Bounds(1, 1))); count.sent("one"); count.complete("one", "succeeded", usage(1, 1));
        assertTrue(count.reserve("two", "child-run", "p", "m", new Bounds(1, 1))); count.sent("two"); count.complete("two", "succeeded", usage(1, 1));
        assertFalse(count.reserve("three", "auxiliary-run", "p", "m", new Bounds(1, 1)));
        assertEquals(2, count.providerAttempts().size());
    }

    @Test void cumulativeRevisionsAndFailureSnapshotKeepSourceIdentityWithoutSubsetDoubleCount() throws Exception {
        State state = state();
        assertTrue(state.reserve("owned", "child-run", "p", "m", new Bounds(1000, 128))); state.sent("owned");
        state.usage("owned", 1, usage(100, 2));
        state.complete("owned", "succeeded", new ModelUsage(OptionalLong.of(120), OptionalLong.of(3),
            OptionalLong.of(100), OptionalLong.of(10), OptionalLong.of(2)));
        state.outcome("FAIL"); state.outcome("PASS");
        state.snapshot("owned_testcontainer_jdbc", false, state.providerAttempts(), state.providerRecords());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(root.resolve("attempt-evidence.json").toFile());
        assertEquals(123, json.path("charged_or_reserved_tokens").asLong());
        assertEquals("FAIL", json.path("acceptance_outcome").asText()); assertFalse(json.path("collection_complete").asBoolean());
        assertEquals("full-project-test-20261012", json.path("run_id").asText());
        assertEquals(2, json.path("provider_records").size());
    }
}

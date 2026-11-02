package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.RunView;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelToolCall;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import com.skillforge.runtime.session.spi.SessionContextSummary;
import com.skillforge.runtime.session.spi.SessionPreparations;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class ContextSummaryServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SystemAgentExecution execution = mock(SystemAgentExecution.class);
    private final ContextSummaryService service = new ContextSummaryService(execution, JSON);

    @Test
    void originalBusinessRootAndSourceTraceReplaceDirtyThreadContextWithoutChangingBudgetBinding() {
        var request = request(() -> false, Duration.ofSeconds(1));
        var dirty = CostContext.builder().runId("foreign-run").sessionId("foreign-session").agentId(99L)
                .requestId("foreign-input").rootInputId("foreign-root").channelId("foreign-channel").endUserId(99L)
                .traceContext(new TraceContext("foreign-input", "foreign-root", "foreign-trace", "foreign-span", "foreign-event", null)).build();
        var binding = new ExecutionBudgetScope.Binding(mock(ExecutionBudgetPort.class), request.key().identity().runRef());
        var completed = new RunView("summary-run", "summary", 7L, false, "COMPLETED",
                "{\"content\":\"摘要\",\"coveredMessages\":4}", null, 10L, 3L, 1, Instant.now());
        when(execution.executePurposeControlled(any(), any(), any(), any(), any())).thenAnswer(call -> {
            var actual = MeterScope.current();
            assertEquals("run", actual.runId()); assertEquals("session", actual.sessionId()); assertEquals(42L, actual.agentId());
            assertEquals("input", actual.requestId()); assertEquals("root", actual.rootInputId()); assertEquals(1L, actual.endUserId());
            assertEquals("input", actual.traceContext().inputId()); assertEquals("root", actual.traceContext().rootInputId());
            assertEquals("trace", actual.traceContext().traceId()); assertEquals("span", actual.traceContext().spanId());
            assertTrue(actual.traceContext().eventId().startsWith("execution-accepted:"));
            assertEquals("origin-cause", actual.traceContext().causedByEventId());
            assertNull(actual.channelId()); assertSame(binding, ExecutionBudgetScope.current().orElseThrow());
            return new SystemAgentExecution.PurposeResult(completed, new ResultRef("configuration"), new ResultRef("invocation"), 4);
        });
        try (var unrelated = MeterScope.openIsolated(dirty); var budget = ExecutionBudgetScope.open(binding)) {
            assertEquals(SessionContextSummary.Status.AVAILABLE, service.summarize(request).status());
            assertSame(dirty, MeterScope.current()); assertSame(binding, ExecutionBudgetScope.current().orElseThrow());
        }
        assertNull(MeterScope.current()); assertTrue(ExecutionBudgetScope.current().isEmpty());
    }

    @Test
    void invokesFrozenContextSummaryPurposeWithRequestLimitsAndCompleteGroupMarkers() throws Exception {
        var request = request(() -> false, Duration.ofNanos(500_000));
        var configuration = new ResultRef("system-agent-config:summary:7");
        var invocation = new ResultRef("system-agent-invocation:run-7");
        var completed = new RunView("run-7", "summary", 7L, false, "COMPLETED",
                "{\"content\":\"压缩后的历史\",\"coveredMessages\":4}", null, 11L, 5L, 12, Instant.now());
        when(execution.executePurposeControlled(eq("context-summary"), eq("tenant"),
                eq("context-summary:run:loop:2:digest"), any(), any()))
                .thenReturn(new SystemAgentExecution.PurposeResult(completed, configuration, invocation, 4));

        var outcome = service.summarize(request);
        assertEquals(SessionContextSummary.Status.AVAILABLE, outcome.status());
        assertEquals("SUMMARY_AVAILABLE", outcome.reason());
        var result = outcome.result();

        assertTrue(result.isPresent());
        assertEquals(configuration, result.orElseThrow().configurationRef());
        assertEquals(invocation, result.orElseThrow().invocationRef());
        assertEquals("digest", result.orElseThrow().sourceDigest());
        assertEquals(4, result.orElseThrow().coveredMessages());
        assertEquals("压缩后的历史", result.orElseThrow().content());

        var payload = ArgumentCaptor.forClass(String.class);
        var limits = ArgumentCaptor.forClass(SystemAgentExecution.ExecutionLimits.class);
        verify(execution).executePurposeControlled(eq("context-summary"), eq("tenant"),
                eq("context-summary:run:loop:2:digest"), payload.capture(), limits.capture());
        var input = JSON.readTree(payload.getValue());
        assertEquals("digest", input.path("sourceDigest").asText());
        assertEquals(1, input.path("currentContext").size());
        assertEquals("input", input.path("currentContext").get(0).path("id").asText());
        assertEquals(request.currentContext().get(0).accountingText(), input.path("currentContext").get(0).path("text").asText());
        assertEquals(4, input.path("messages").size(), "当前目标不属于历史覆盖范围");
        assertEquals(321, input.path("maxOutputTokens").asInt());
        assertEquals(1, input.path("remainingMillis").asLong());
        assertEquals("session-preparation:run:loop:2", input.path("root").asText());
        assertEquals(4, input.path("messages").size());
        assertTrue(input.path("messages").get(0).path("closed").asBoolean());
        assertFalse(input.path("messages").get(1).path("closed").asBoolean());
        assertFalse(input.path("messages").get(2).path("closed").asBoolean());
        assertTrue(input.path("messages").get(3).path("closed").asBoolean());
        var calls = input.path("messages").get(1).path("toolCalls");
        assertEquals(2, calls.size());
        assertEquals("call-1", calls.get(0).path("callId").asText());
        assertEquals("call-2", calls.get(1).path("callId").asText());
        assertEquals(321, limits.getValue().maxOutputTokens());
        assertEquals(Duration.ofNanos(500_000), limits.getValue().remaining());
        assertEquals(new ResultRef("session-preparation:run:loop:2"), limits.getValue().root());
    }

    @Test
    void cancellationBeforeAcceptanceDoesNotCreateASystemAgentRun() {
        var outcome = service.summarize(request(() -> true, Duration.ofSeconds(1)));
        assertEquals(SessionContextSummary.Status.UNAVAILABLE, outcome.status());
        assertEquals("SUMMARY_CANCELLED_BEFORE_ACCEPTANCE", outcome.reason());
        assertTrue(outcome.invocationRef().isEmpty());
        verifyNoInteractions(execution);
    }

    @Test
    void rejectsIncompleteOrOutOfRangeStoredResults() {
        var invalid = new RunView("run-7", "summary", 7L, false, "COMPLETED",
                "{\"content\":\"摘要\",\"coveredMessages\":5}", null, null, null, 12, Instant.now());
        when(execution.executePurposeControlled(any(), any(), any(), any(), any()))
                .thenReturn(new SystemAgentExecution.PurposeResult(invalid, new ResultRef("configuration"),
                        new ResultRef("invocation"), 4));

        var outcome = service.summarize(request(() -> false, Duration.ofSeconds(1)));
        assertEquals(SessionContextSummary.Status.REJECTED, outcome.status());
        assertEquals("SUMMARY_COVERAGE_INVALID", outcome.reason());
        assertEquals(new ResultRef("configuration"), outcome.configurationRef().orElseThrow());
        assertEquals(new ResultRef("invocation"), outcome.invocationRef().orElseThrow());
        assertTrue(outcome.result().isEmpty());
    }

    @Test
    void pendingInvocationIsUnavailableAndDoesNotClaimGenerationFailed() {
        var pending = new RunView("run-7", "summary", 7L, false, "RUNNING", null,
                null, null, null, 12, Instant.now());
        when(execution.executePurposeControlled(any(), any(), any(), any(), any()))
                .thenReturn(new SystemAgentExecution.PurposeResult(pending, new ResultRef("configuration"),
                        new ResultRef("invocation"), 0));
        var outcome = service.summarize(request(() -> false, Duration.ofSeconds(1)));
        assertEquals(SessionContextSummary.Status.UNAVAILABLE, outcome.status());
        assertEquals("SUMMARY_RESULT_PENDING", outcome.reason());
        assertEquals(new ResultRef("invocation"), outcome.invocationRef().orElseThrow());
    }

    @Test
    void acceptanceRejectionIsUnavailableAndDoesNotExposeExceptionText() {
        when(execution.executePurposeControlled(any(), any(), any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "secret\nprovider text"));
        var outcome = service.summarize(request(() -> false, Duration.ofSeconds(1)));
        assertEquals(SessionContextSummary.Status.UNAVAILABLE, outcome.status());
        assertEquals("SUMMARY_ACCEPTANCE_REJECTED", outcome.reason());
        assertTrue(outcome.configurationRef().isEmpty());
        assertTrue(outcome.invocationRef().isEmpty());
        verify(execution).executePurposeControlled(any(), any(), any(), any(), any());
        assertFalse(outcome.toString().contains("secret"));
    }

    @Test
    void unknownInvocationRemainsUnavailableWithOriginalReferencesWhenRemainingChanges() {
        var unknown = new RunView("run-7", "summary", 7L, false, "FAILED", null,
                "system_agent_outcome_unknown", null, null, 12, Instant.now());
        when(execution.executePurposeControlled(any(), any(), any(), any(), any()))
                .thenReturn(new SystemAgentExecution.PurposeResult(unknown, new ResultRef("configuration"),
                        new ResultRef("invocation"), 0));

        for (var remaining : List.of(Duration.ofSeconds(5), Duration.ofMillis(500))) {
            var outcome = service.summarize(request(() -> false, remaining));
            assertEquals(SessionContextSummary.Status.UNAVAILABLE, outcome.status());
            assertEquals("SUMMARY_RESULT_UNKNOWN", outcome.reason());
            assertEquals(new ResultRef("configuration"), outcome.configurationRef().orElseThrow());
            assertEquals(new ResultRef("invocation"), outcome.invocationRef().orElseThrow());
            assertTrue(outcome.result().isEmpty());
        }
        verify(execution, org.mockito.Mockito.times(2)).executePurposeControlled(eq("context-summary"),
                eq("tenant"), eq("context-summary:run:loop:2:digest"), any(), any());
    }

    @Test
    void generationFailureKeepsFrozenReferencesAndSafeReason() {
        var failed = new RunView("run-7", "summary", 7L, false, "FAILED", null,
                "system_agent_output_truncated", null, null, 12, Instant.now());
        when(execution.executePurposeControlled(any(), any(), any(), any(), any()))
                .thenReturn(new SystemAgentExecution.PurposeResult(failed, new ResultRef("configuration"),
                        new ResultRef("invocation"), 0));
        var outcome = service.summarize(request(() -> false, Duration.ofSeconds(1)));
        assertEquals(SessionContextSummary.Status.FAILED, outcome.status());
        assertEquals("SUMMARY_OUTPUT_TRUNCATED", outcome.reason());
        assertEquals(new ResultRef("configuration"), outcome.configurationRef().orElseThrow());
        assertEquals(new ResultRef("invocation"), outcome.invocationRef().orElseThrow());
        assertTrue(outcome.result().isEmpty());
    }

    @Test
    void invalidOutputIsRejectedAndCancellationKeepsTheSameInvocation() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var completed = new RunView("run-7", "summary", 7L, false, "COMPLETED",
                "{\"content\":\"摘要\",\"coveredMessages\":\"4\"}", null, null, null, 12, Instant.now());
        var accepted = new SystemAgentExecution.PurposeResult(completed, new ResultRef("configuration"),
                new ResultRef("invocation"), 4);
        when(execution.executePurposeControlled(any(), any(), any(), any(), any())).thenReturn(accepted);
        var rejected = service.summarize(request(cancelled::get, Duration.ofSeconds(1)));
        assertEquals(SessionContextSummary.Status.REJECTED, rejected.status());
        assertEquals("SUMMARY_OUTPUT_INVALID", rejected.reason());
        assertEquals(new ResultRef("invocation"), rejected.invocationRef().orElseThrow());
        when(execution.executePurposeControlled(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            cancelled.set(true);
            return accepted;
        });
        var interrupted = service.summarize(request(cancelled::get, Duration.ofSeconds(1)));
        assertEquals(SessionContextSummary.Status.FAILED, interrupted.status());
        assertEquals("SUMMARY_CANCELLED", interrupted.reason());
        assertEquals(new ResultRef("invocation"), interrupted.invocationRef().orElseThrow());
    }

    @Test
    void passesPreviousSummaryAndNewCorrectionTogetherWithoutPruningFacts() throws Exception {
        var original = request(() -> false, Duration.ofSeconds(1));
        var history = List.of(
                new ModelMessage("context-summary", ModelRole.USER,
                        "历史摘要（压缩）：目标是整理5项；source-event-2 下架2.6万余条，source-event-1详情未读取。"),
                new ModelMessage("history-correction", ModelRole.USER, "置顶不计入5项，补充每项精确值和来源。"));
        var request = new SessionContextSummary.Request(original.key(), original.actor(), original.sourceDigest(),
                history, original.currentContext(), original.maxOutputTokens(), original.remaining(), original.cancelled());
        var completed = new RunView("run-7", "summary", 7L, false, "COMPLETED",
                "{\"content\":\"连续合并后的摘要\",\"coveredMessages\":2}", null, null, null, 12, Instant.now());
        when(execution.executePurposeControlled(any(), any(), any(), any(), any()))
                .thenReturn(new SystemAgentExecution.PurposeResult(completed, new ResultRef("configuration"),
                        new ResultRef("invocation"), 2));
        assertEquals(SessionContextSummary.Status.AVAILABLE, service.summarize(request).status());
        var payload = ArgumentCaptor.forClass(String.class);
        verify(execution).executePurposeControlled(any(), any(), any(), payload.capture(), any());
        var messages = JSON.readTree(payload.getValue()).path("messages");
        assertEquals(history.get(0).accountingText(), messages.get(0).path("text").asText());
        assertEquals(history.get(1).accountingText(), messages.get(1).path("text").asText());
    }

    @Test
    void outcomeRejectsUnsafeReasonsAndAvailableResultsWithoutTheirReferences() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionContextSummary.Outcome.unavailable("raw provider text\n"));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionContextSummary.Outcome(SessionContextSummary.Status.AVAILABLE,
                        "SUMMARY_AVAILABLE", Optional.empty(), Optional.empty(), Optional.of(
                        new SessionContextSummary.Result(new ResultRef("configuration"), new ResultRef("invocation"),
                                "digest", 1, "摘要"))));
        assertEquals("SUMMARY_DISABLED", SessionContextSummary.disabled()
                .summarize(request(() -> false, Duration.ofSeconds(1))).reason());
    }

    private SessionContextSummary.Request request(java.util.function.BooleanSupplier cancelled, Duration remaining) {
        var identity = run();
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(
                identity.owner().sessionRef().tenantId(), identity.owner().requireUser()), identity.owner(),
                Set.of("sessions.read"), 1, new ResultRef("verified"));
        var call = new ModelToolCall("call-1", "lookup",
                new TypedPayload("test.arguments.v1", "{\"query\":\"状态\"}"));
        var second = new ModelToolCall("call-2", "fetch",
                new TypedPayload("test.arguments.v1", "{\"query\":\"详情\"}"));
        var history = List.of(
                new ModelMessage("m1", ModelRole.USER, "查询状态"),
                new ModelMessage("m2", ModelRole.ASSISTANT, "", List.of(call, second),
                        Optional.empty(), Optional.empty()),
                new ModelMessage("m3", ModelRole.TOOL, "状态已完成", List.of(),
                        Optional.of("call-1"), Optional.empty()),
                new ModelMessage("m4", ModelRole.TOOL, "详情已完成", List.of(),
                        Optional.of("call-2"), Optional.empty()));
        return new SessionContextSummary.Request(new SessionPreparations.Key(identity, "loop", 2), actor,
                "digest", history, List.of(new ModelMessage("input", ModelRole.USER, "整理5项精确事实与来源，置顶不计入。")),
                321, remaining, cancelled);
    }

    private RunIdentity run() {
        var tenant = new TenantId("tenant");
        var input = new InputId("input");
        var owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("42"), new EndUserId(1));
        return new RunIdentity(owner, new AgentId((owner).target().id()), input, new com.skillforge.runtime.api.RunRef(tenant, "run"), new TraceEnvelope("trace", "span", new InputId("root"), Optional.of(new com.skillforge.runtime.api.EventId("origin-cause"))), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
    }
}

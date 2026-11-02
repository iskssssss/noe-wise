package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.RunView;
import com.skillforge.institution.integration.SemanticMemoryExtractionReports.*;
import com.skillforge.runtime.api.ResultRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 离线协议检查，不调用模型，不提供C语义效果证据。 */
class SemanticMemoryExtractionEvaluationTest {
    private static final ObjectMapper JSON = SemanticMemoryExtractionEvaluation.reportJson(new ObjectMapper());
    private static final String CONFIGURATION = "system-agent-config:fixture:1";
    @TempDir Path temporary;

    @Test void allAuthoredCasesHaveValidTypedV2SourcesAndUniqueHumanLabels() throws Exception {
        var parser = new MemoryExtractionCandidates(JSON); var ids = new HashSet<String>();
        for (String split : List.of("dev", "heldout")) {
            byte[] raw = Files.readAllBytes(suiteFile(split)); Suite suite = JSON.readValue(raw, Suite.class);
            SemanticMemoryExtractionEvaluation.validateSuite(suite, parser);
            // 报告类型必须保留原场景与标签字段，不因typed改造丢弃外部协议信息。
            assertEquals(JSON.readTree(raw), JSON.valueToTree(suite));
            for (var scenario : suite.scenarios()) {
                assertTrue(ids.add(scenario.id()));
                var aliases = new HashSet<String>(); scenario.input().existingFacts().forEach(f -> aliases.add(f.id()));
                for (var expected : scenario.expected().requiredFacts()) {
                    assertFalse(expected.meaning().isBlank());
                    if (expected.relation().target() != null) assertTrue(aliases.contains(expected.relation().target()));
                }
            }
        }
        assertEquals(32, ids.size());
    }

    @Test void semanticLabelsRemainUnknownAndDoNotBecomeWordMatchingPasses() throws Exception {
        Scenario scenario = suite("dev").scenarios().getFirst(); var assertions = new ArrayList<Assertion>();
        SemanticMemoryExtractionEvaluation.semanticAssertions(JSON, assertions, scenario.expected(), List.of());
        assertTrue(assertions.stream().anyMatch(a -> a.code().equals("C_CRITICAL_STABLE:summary-first")));
        assertTrue(assertions.stream().allMatch(a -> a.outcome().equals("UNKNOWN") && a.method().equals("SEMANTIC_REVIEW_REQUIRED")));
    }

    @Test void controlledCompletedRunsPreserveFullMatrixAndSourceTimesWithoutClaimingSemanticPass() throws Exception {
        var execution = mock(SystemAgentExecution.class);
        when(execution.executePurposeControlled(eq("memory-extraction"), eq("fixture-tenant"), anyString(), anyString(), any()))
                .thenAnswer(call -> {
                    var input = new MemoryExtractionCandidates(JSON).decodeInput(call.getArgument(3, String.class));
                    assertEquals(Instant.parse("2026-10-23T02:00:00Z"), input.sources().getFirst().occurredAt());
                    return result("COMPLETED", "{\"schemaVersion\":2,\"candidates\":[]}", 3L, 2L);
                });
        Path output = temporary.resolve("completed");
        SemanticMemoryExtractionEvaluation.evaluate(execution, JSON, suiteFile("dev"), output, "fixture-tenant", limits(), configuration());
        Batch batch = JSON.readValue(Files.readAllBytes(output.resolve("batch.json")), Batch.class);
        assertEquals("COMPLETED", batch.outcome); assertEquals(16, batch.callsAttempted);
        assertEquals(16, batch.systemAgentCalls.size()); assertEquals(80L, batch.chargedOrReservedTokens);
        assertTrue(batch.samples.stream().allMatch(item -> "UNKNOWN".equals(item.outcome())));
        assertThrows(IllegalArgumentException.class, () -> SemanticMemoryExtractionEvaluation.evaluate(
                execution, JSON, suiteFile("dev"), output, "fixture-tenant", limits(), configuration()));
    }

    @Test void unfinishedRunStopsWithoutReplayingAndKeepsUnrunPositions() throws Exception {
        var execution = mock(SystemAgentExecution.class);
        when(execution.executePurposeControlled(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(result("RUNNING", null, null, null));
        Path output = temporary.resolve("unknown");
        assertThrows(IllegalStateException.class, () -> SemanticMemoryExtractionEvaluation.evaluate(
                execution, JSON, suiteFile("heldout"), output, "fixture-tenant", limits(), configuration()));
        verify(execution, times(1)).executePurposeControlled(anyString(), anyString(), anyString(), anyString(), any());
        Batch batch = JSON.readValue(Files.readAllBytes(output.resolve("batch.json")), Batch.class);
        assertEquals("STOPPED", batch.outcome); assertEquals(16, batch.samples.size());
        assertEquals(15, batch.samples.stream().filter(item -> "NOT_RUN".equals(item.outcome())).count());
        assertEquals(10L, batch.chargedOrReservedTokens);
    }

    @Test void malformedCompletedOutputIsSavedAndNeverCountedAsEmptyExtraction() throws Exception {
        var execution = mock(SystemAgentExecution.class);
        when(execution.executePurposeControlled(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(result("COMPLETED", "not JSON", 1L, 1L));
        Path output = temporary.resolve("invalid");
        assertThrows(IllegalStateException.class, () -> SemanticMemoryExtractionEvaluation.evaluate(
                execution, JSON, suiteFile("dev"), output, "fixture-tenant", limits(), configuration()));
        Sample sample = JSON.readValue(Files.readAllBytes(output.resolve("sample-CD01-1.json")), Sample.class);
        assertEquals("not JSON", sample.stages.getFirst().rawOutput); assertNull(sample.stages.getFirst().parsedCandidates);
        assertEquals("UNKNOWN", sample.outcome);
    }

    @Test void knownProtocolFailureContinuesToNextIndependentSampleButUnknownStopsWithoutReplay() throws Exception {
        var execution = mock(SystemAgentExecution.class);
        when(execution.executePurposeControlled(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(result("FAILED", "not JSON", 3L, 2L),
                        result("COMPLETED", "{\"schemaVersion\":2,\"candidates\":[]}", 3L, 2L),
                        result("RUNNING", null, null, null));
        Path output = temporary.resolve("known-then-unknown");
        assertThrows(IllegalStateException.class, () -> SemanticMemoryExtractionEvaluation.evaluate(
                execution, JSON, suiteFile("dev"), output, "fixture-tenant", limits(), configuration()));
        verify(execution, times(3)).executePurposeControlled(anyString(), anyString(), anyString(), anyString(), any());
        Batch batch = JSON.readValue(Files.readAllBytes(output.resolve("batch.json")), Batch.class);
        assertEquals("STOPPED", batch.outcome); assertEquals(3, batch.callsAttempted);
        assertEquals(1, batch.protocolFailures); assertEquals("FAIL", batch.protocolOutcome);
        assertEquals(20L, batch.chargedOrReservedTokens);
        assertEquals("FAIL", batch.samples.getFirst().outcome());
        assertEquals("UNKNOWN", batch.samples.get(1).outcome());
        assertEquals(13, batch.samples.stream().filter(item -> "NOT_RUN".equals(item.outcome())).count());
        Sample failed = JSON.readValue(Files.readAllBytes(output.resolve("sample-CD01-1.json")), Sample.class);
        assertEquals("not JSON", failed.stages.getFirst().rawOutput); assertNull(failed.stages.getFirst().parsedCandidates);
    }

    @Test void typedDatabaseReceiptsKeepTerminalUsageAndAttemptCorrelationChecks() {
        var rows = new DatabaseReceipts(List.of(new SystemAgentRun(1, "COMPLETED", 3L, 2L, null)),
                List.of(new ExternalInvocation("invocation", "attempt", "SUCCEEDED", "MODEL_CHAT", null)),
                List.of(new UsageEvent("usage", "attempt", "measured", "PROVIDER_REPORTED", 3L, 2L)),
                List.of(new BudgetAttempt("attempt", "AUXILIARY_MODEL", "SETTLED")), null, null);
        var calls = List.of(new SystemAgentCall("fixture", "1", "COMPLETED", CONFIGURATION, "system-agent-invocation:1",
                "invocation", null, Usage.observed(3L, 2L), 5));
        SemanticMemoryExtractionLiveAcceptance.verifyReceipts(rows, 1, calls);
        var mismatch = new DatabaseReceipts(rows.systemAgentRuns(), rows.externalInvocations(), rows.usageEvents(),
                List.of(new BudgetAttempt("other-attempt", "AUXILIARY_MODEL", "SETTLED")), null, null);
        assertThrows(AssertionError.class, () -> SemanticMemoryExtractionLiveAcceptance.verifyReceipts(mismatch, 1, calls));
        var unknownUsage = new DatabaseReceipts(rows.systemAgentRuns(), rows.externalInvocations(),
                List.of(new UsageEvent("usage", "attempt", "measured", "UNKNOWN", null, null)), rows.budgetAttempts(), null, null);
        assertThrows(AssertionError.class, () -> SemanticMemoryExtractionLiveAcceptance.verifyReceipts(unknownUsage, 1, calls));
        var preflight = new DatabaseReceipts(List.of(), List.of(), List.of(), List.of(), null, null);
        SemanticMemoryExtractionLiveAcceptance.verifyReceipts(preflight, 0, List.of());
        assertThrows(AssertionError.class, () -> SemanticMemoryExtractionLiveAcceptance.verifyReceipts(
                new DatabaseReceipts(List.of(), List.of(), rows.usageEvents(), List.of(), null, null), 0, List.of()));
    }

    @Test void reportNamingStaysCamelCaseWhileDatabaseRowsKeepTheirOriginalColumnNames() throws Exception {
        var configured = new ObjectMapper().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        var report = SemanticMemoryExtractionEvaluation.reportJson(configured);
        var sample = new Sample(); sample.sampleId = "protocol"; sample.inputSha256 = "frozen";
        var tree = report.readTree(report.writeValueAsBytes(sample));
        assertEquals("protocol", tree.path("sampleId").asText()); assertFalse(tree.has("sample_id"));
        var database = report.readTree(report.writeValueAsBytes(new SystemAgentRun(1, "COMPLETED", 3L, 2L, null)));
        assertEquals(3, database.path("input_tokens").asLong()); assertFalse(database.has("inputTokens"));
    }

    private static SystemAgentExecution.PurposeResult result(String status, String output, Long input, Long generated) {
        return new SystemAgentExecution.PurposeResult(new RunView("fixture-run", "fixture", 1L, false, status, output,
                "FAILED".equals(status) ? "memory_extraction_json_invalid" : null,
                input, generated, 5, Instant.parse("2026-10-23T02:00:00Z")), new ResultRef(CONFIGURATION), new ResultRef("system-agent-invocation:fixture-run"), 0);
    }
    private static Configuration configuration() {
        return new Configuration("fixture", 1, "fixture", "protocol-only", "https://fixture.invalid", "fixture-hash", "fixture", 1,
                CONFIGURATION, "PROTOCOL_ONLY", "fixture-prompt-hash", "NOT_RUN", "CONTROLLED_PROTOCOL_ONLY", "CONTROLLED_PROTOCOL_ONLY", "NOT_RUN", "UNKNOWN");
    }
    private static SemanticMemoryExtractionEvaluation.Limits limits() {
        return new SemanticMemoryExtractionEvaluation.Limits(16, 2, 10, 160, Duration.ofSeconds(10), Duration.ofMinutes(5), CONFIGURATION);
    }
    private static Suite suite(String split) throws Exception { return JSON.readValue(Files.readAllBytes(suiteFile(split)), Suite.class); }
    private static Path suiteFile(String split) {
        Path root = Path.of("").toAbsolutePath(); while (root != null && !Files.exists(root.resolve(".git"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("repository missing");
        return root.resolve("tools/memory_evaluation/semantic-c/" + split + ".json");
    }
}

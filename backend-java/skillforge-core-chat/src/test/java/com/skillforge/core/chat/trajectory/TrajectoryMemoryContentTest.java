package com.skillforge.core.chat.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.application.BillingTraceQueryService;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationScope;
import com.skillforge.core.chat.observation.SessionObservationService;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.knowledge.KnowledgeObservationAccess;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.ExecutionObservation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 通过真实冻结Tool封套验证历史Memory正文不能借会话观测资格回放。 */
class TrajectoryMemoryContentTest {
    @Test
    void originalToolBusinessPathsAndLargeNumbersAreNotBlankedByPublicAnswerHeuristics() throws Exception {
        String original = "{\"content\":[{\"type\":\"text\",\"text\":\"D:\\\\project\\\\SkillForge\\\\报告.txt\"}],"
                + "\"structuredContent\":{\"account_note\":\"password=业务说明\",\"record_id\":9007199254740993}}";
        var limits = new com.skillforge.runtime.json.CodecLimits(1_000_000, 10_000, 128);
        var payloads = new com.skillforge.runtime.session.SessionRuntimePayloads();
        var result = new com.skillforge.runtime.api.port.ToolPortal.ToolOutcome.Succeeded(
                new TypedPayload("skillforge.runtime.tool.result.v1", original), new com.skillforge.runtime.api.ResultRef("tool-result"), List.of());
        var saved = new AgentExecutionCodec(limits).encodeOutcome(new com.skillforge.runtime.core.CoreRuntime(limits, payloads)
                .portalResults(payloads, payloads, payloads).encodeTool(com.skillforge.runtime.api.port.PortalCallKind.TOOL_INVOKE, result));
        var fixture = new Fixture(SessionObservationScope.Kind.TENANT, "other-tool-request-v2.json", saved);
        var tool = fixture.page().items().stream().filter(node -> node.kind() == TrajectoryView.Kind.TOOL).findFirst().orElseThrow();
        var output = fixture.service.content(fixture.scope, new TrajectoryQuery.Content(7, "session", "root", tool.output().ref()));
        assertEquals("FULL", output.availability());
        assertTrue(((String) output.value()).contains("password=业务说明"));
        assertTrue(((String) output.value()).contains("9007199254740993"));
        assertTrue(((String) output.value()).contains("报告.txt"));
    }

    @Test
    void tenantAndPlatformContentKeepHistoricalMemoryCountsWithoutForgottenOrRevokedFactBodies() throws Exception {
        for (var kind : SessionObservationScope.Kind.values()) {
            var fixture = new Fixture(kind, "memory-tool-request-v2.json");
            var page = fixture.page();
            var tool = page.items().stream().filter(node -> node.kind() == TrajectoryView.Kind.TOOL).findFirst().orElseThrow();
            var output = fixture.service.content(fixture.scope, new TrajectoryQuery.Content(7, "session", "root", tool.output().ref()));
            var metadata = fixture.json.readTree((String) output.value());

            assertEquals("PARTIAL", output.availability());
            assertTrue(metadata.path("success").booleanValue());
            assertEquals("lexical", metadata.path("retrieval_mode").textValue());
            assertEquals(2, metadata.path("recorded_fact_count").intValue());
            assertEquals("CURRENT_SOURCE_AUTHORIZATION_REQUIRED", metadata.path("facts_availability").textValue());
            assertFalse(metadata.toString().contains("PRIVATE"));
            assertFalse(metadata.has("facts"));
            assertFalse(fixture.json.writeValueAsString(page).contains("PRIVATE"));

            var input = fixture.service.content(fixture.scope, new TrajectoryQuery.Content(7, "session", "root", tool.input().ref()));
            assertEquals("FULL", input.availability());
            assertEquals("controlled memory query", fixture.json.readTree((String) input.value()).path("query").textValue());
        }
    }

    @Test
    void sameNamedMcpAndOtherPlatformToolsKeepTheirAuthorizedBusinessOutput() throws Exception {
        for (String request : List.of("mcp-memory-tool-request-v2.json", "other-tool-request-v2.json")) {
            var fixture = new Fixture(SessionObservationScope.Kind.TENANT, request);
            var tool = fixture.page().items().stream().filter(node -> node.kind() == TrajectoryView.Kind.TOOL).findFirst().orElseThrow();
            var output = fixture.service.content(fixture.scope, new TrajectoryQuery.Content(7, "session", "root", tool.output().ref()));
            assertEquals("FULL", output.availability());
            assertTrue(fixture.json.writeValueAsString(output.value()).contains("PRIVATE_FORGOTTEN_BODY"));
        }
    }

    private static final class Fixture {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final SessionObservationScope scope = mock(SessionObservationScope.class);
        final TrajectoryProjectionService service;

        @SuppressWarnings("unchecked")
        Fixture(SessionObservationScope.Kind kind, String request) throws Exception {
            this(kind, request, null);
        }

        @SuppressWarnings("unchecked")
        Fixture(SessionObservationScope.Kind kind, String request, TypedPayload result) throws Exception {
            when(scope.actorId()).thenReturn("99");
            when(scope.kind()).thenReturn(kind);
            var repository = mock(TrajectoryRepository.class);
            when(repository.productSession(any())).thenReturn(new TrajectoryRepository.ProductSession("tenant", "9", "agent"));
            var run = TrajectoryGraphTest.run("main", "session", "input");
            when(repository.runs(any())).thenReturn(List.of(run));
            when(repository.edges(anyLong(), any())).thenReturn(List.of());
            when(repository.attempts(anyLong(), any())).thenReturn(List.of(TrajectoryGraphTest.attempt(run, "memory-tool", "memory-attempt", "TOOL", "SUCCEEDED")));
            when(repository.events(anyLong(), any())).thenReturn(List.of(
                    TrajectoryGraphTest.event(run, "model", "step:model", "model-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 1),
                    TrajectoryGraphTest.event(run, "memory-tool", "step:model", "memory-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 2)));
            when(repository.material(7, "request:memory-attempt")).thenReturn(material(AgentExecutionCodec.TOOL_REQUEST_SCHEMA, request));
            when(repository.material(7, "result:memory-attempt")).thenReturn(result == null
                    ? material(AgentExecutionCodec.OUTCOME_SCHEMA, "memory-tool-result-v1.json") : result);
            when(repository.now()).thenReturn(Instant.parse("2026-10-25T02:00:00Z"));
            var properties = new SkillForgeProperties();
            properties.setSecretKey("trajectory-memory-secret-0123456789abcdef0123456789");
            var cache = mock(TrajectorySnapshotCache.class);
            var captured = new AtomicReference<TrajectoryProjectionService.Snapshot>();
            when(cache.save(any())).thenAnswer(call -> { captured.set(call.getArgument(0)); return "01234567-89ab-cdef-0123-456789abcdef"; });
            when(cache.read(anyString(), anyString(), anyString())).thenAnswer(call -> captured.get());
            var reads = mock(PlatformObservationAccess.class);
            when(reads.read(anyLong(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
            service = new TrajectoryProjectionService(repository, mock(SessionObservationAuthorizer.class), mock(SessionObservationService.class),
                    new CryptoService(properties), json, mock(ObservationReadAudit.class), reads,
                    mock(BillingTraceQueryService.class), cache,
                    mock(ObjectProvider.class), mock(KnowledgeObservationAccess.class), mock(com.skillforge.core.model.invocation.ModelCallRecordReader.class), 1_000_000, 10_000, 128);
        }

        TrajectoryView.ObservationPage page() {
            return service.nodes(scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 100, null, null));
        }
    }

    private static TypedPayload material(String schema, String name) throws Exception {
        try (var input = Objects.requireNonNull(TrajectoryMemoryContentTest.class.getResourceAsStream("/fixtures/trajectory/" + name))) {
            return new TypedPayload(schema, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}

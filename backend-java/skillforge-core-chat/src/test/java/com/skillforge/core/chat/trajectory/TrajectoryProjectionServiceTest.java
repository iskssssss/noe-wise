package com.skillforge.core.chat.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.application.BillingTraceQueryService;
import com.skillforge.core.billing.application.BillingTraceFacts;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationScope;
import com.skillforge.core.chat.observation.SessionObservationService;
import com.skillforge.core.chat.observation.SessionObservationView;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.knowledge.KnowledgeObservationAccess;
import com.skillforge.core.model.invocation.ModelCallRecord;
import com.skillforge.core.model.invocation.ModelCallRecordReader;
import com.skillforge.core.model.invocation.ModelCallRecordStatus;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.session.ExecutionObservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class TrajectoryProjectionServiceTest {
    @Test
    void summaryAndNodePagesReadOnlyGraphFactsWhileDetailKeepsTheOriginalEventRead() throws Exception {
        var fixture = new Fixture(); var resource = new TrajectoryQuery.Resource(7, "session", "root");
        var summary = fixture.service.summary(fixture.scope, resource);
        var first = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 1, null, null));
        assertEquals(1, first.items().size()); assertTrue(first.nextCursor() != null);
        var next = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, first.nextCursor(), 1, null, null));
        assertFalse(first.items().getFirst().nodeId().equals(next.items().getFirst().nodeId()));
        verify(fixture.repository, never()).events(anyLong(), any());
        verify(fixture.repository, times(3)).graphEvents(anyLong(), any());
        var detail = fixture.service.detail(fixture.scope, resource);
        assertEquals(summary, detail.summary());
        verify(fixture.repository).events(anyLong(), any());
        verify(fixture.repository, times(3)).graphEvents(anyLong(), any());
    }

    @Test
    void tenantReadsActualModelMaterialsWithoutAnAdditionalDiagnosticPermission() throws Exception {
        var fixture = new Fixture();
        when(fixture.scope.kind()).thenReturn(SessionObservationScope.Kind.TENANT);
        var run = TrajectoryGraphTest.run("main", "session", "input");
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(List.of(
                TrajectoryGraphTest.attempt(run, "model", "model-attempt", "MODEL", "SUCCEEDED")));
        var actualRequest = TrajectoryModelContentTest.request();
        var actualOutcome = TrajectoryModelContentTest.outcome();
        when(fixture.repository.material(7, "request:model-attempt")).thenReturn(actualRequest);
        when(fixture.repository.material(7, "result:model-attempt")).thenReturn(actualOutcome);
        var page = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 100, null, null));
        var attempt = page.items().stream().filter(node -> node.kind() == TrajectoryView.Kind.LLM).findFirst().orElseThrow()
                .attempts().getFirst();
        verify(fixture.repository, never()).material(anyLong(), anyString());
        var input = fixture.service.content(fixture.scope, new TrajectoryQuery.Content(7, "session", "root", attempt.input().ref()));
        assertEquals("FULL", input.availability());
        assertTrue(input.value().toString().contains("原始用户输入"));
        assertFalse(input.value().toString().contains("private-binding"));
        var output = fixture.service.content(fixture.scope, new TrajectoryQuery.Content(7, "session", "root", attempt.output().ref()));
        var value = new ObjectMapper().findAndRegisterModules().readTree((String) output.value());
        assertEquals("完整供应商推理", value.path("reasoning_content").asText());
        assertTrue(value.toString().contains("password=业务原文"));
        assertFalse(value.toString().contains("private-result-reference"));
        when(fixture.scope.actorId()).thenReturn("another-actor");
        assertThrows(ApiException.class, () -> fixture.service.content(fixture.scope,
                new TrajectoryQuery.Content(7, "session", "root", attempt.input().ref())));
    }

    @Test
    void tenantReadsManifestModelContentUsingOnlyTheAuthorizedRunLeafBatch() throws Exception {
        var fixture = new Fixture();
        when(fixture.scope.kind()).thenReturn(SessionObservationScope.Kind.TENANT);
        var run = TrajectoryGraphTest.run("main", "session", "input");
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(List.of(
                TrajectoryGraphTest.attempt(run, "model", "model-attempt", "MODEL", "SUCCEEDED")));
        var codec = new com.skillforge.runtime.session.AgentExecutionCodec(new com.skillforge.runtime.json.CodecLimits(1_000_000, 10_000, 128));
        var encoded = codec.encodeModelRequestMaterials(codec.decodeModelRequest(TrajectoryModelContentTest.request()));
        var references = codec.modelRequestReferences(encoded.root().content());
        when(fixture.repository.material(7, "request:model-attempt")).thenReturn(encoded.root().content());
        when(fixture.repository.material(7, "result:model-attempt")).thenReturn(TrajectoryModelContentTest.outcome());
        when(fixture.repository.modelMaterials(7, "main", "request:model-attempt", references)).thenAnswer(ignored ->
                references.stream().map(ref -> encoded.parts().stream().filter(part -> part.reference().equals(ref)).findFirst()).toList());
        var page = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 100, null, null));
        var attempt = page.items().stream().filter(node -> node.kind() == TrajectoryView.Kind.LLM).findFirst().orElseThrow().attempts().getFirst();
        verify(fixture.repository, never()).modelMaterials(anyLong(), anyString(), anyString(), any());
        var input = fixture.service.content(fixture.scope, new TrajectoryQuery.Content(7, "session", "root", attempt.input().ref()));
        assertEquals("FULL", input.availability());
        assertTrue(input.value().toString().contains("原始用户输入"));
        assertFalse(input.value().toString().contains("private-binding"));
        verify(fixture.repository).modelMaterials(7, "main", "request:model-attempt", references);
        when(fixture.repository.modelMaterials(7, "main", "request:model-attempt", references)).thenReturn(
                references.stream().map(ref -> Optional.<com.skillforge.runtime.session.spi.ExecutionStore.Material>empty()).toList());
        assertThrows(IllegalArgumentException.class, () -> fixture.service.content(fixture.scope,
                new TrajectoryQuery.Content(7, "session", "root", attempt.input().ref())));
    }

    @Test
    void coreContentReadsDoNotCollectUnrelatedAuxiliaryStatusesOrCosts() throws Exception {
        var fixture = new Fixture();
        var run = TrajectoryGraphTest.run("main", "session", "input");
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(List.of(
                TrajectoryGraphTest.attempt(run, "model", "model-attempt", "MODEL", "SUCCEEDED")));
        var auxiliary = java.util.stream.IntStream.range(0, 200)
                .mapToObj(index -> TrajectoryGraphTest.auxiliary(run, "aux-" + index, "call-" + index, "SUCCEEDED", 1L, 1L)).toList();
        when(fixture.repository.auxiliaryAttempts(anyLong(), any())).thenReturn(auxiliary);
        when(fixture.repository.material(7, "request:model-attempt")).thenReturn(TrajectoryModelContentTest.request());
        when(fixture.repository.material(7, "result:model-attempt")).thenReturn(TrajectoryModelContentTest.outcome());
        var page = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 100, "model", null));
        var model = page.items().stream().flatMap(node -> node.attempts().stream())
                .filter(attempt -> "model-attempt".equals(attempt.attemptId())).findFirst().orElseThrow();
        verify(fixture.modelRecords).statuses(7, auxiliary.stream().map(attempt -> new ModelCallRecordReader.Key(attempt.invocationId(), attempt.attemptId())).toList());
        verify(fixture.billing).effectiveCostsForScopes(anyLong(), any());
        clearInvocations(fixture.repository, fixture.modelRecords, fixture.billing, fixture.authorizer);

        assertEquals("FULL", fixture.service.content(fixture.scope,
                new TrajectoryQuery.Content(7, "session", "root", model.input().ref())).availability());
        assertEquals("FULL", fixture.service.content(fixture.scope,
                new TrajectoryQuery.Content(7, "session", "root", model.output().ref())).availability());
        verifyNoInteractions(fixture.modelRecords, fixture.billing);
        verify(fixture.repository, never()).auxiliaryAttempts(anyLong(), any());
        verify(fixture.authorizer, times(2)).requireSession(fixture.scope, 7, "session");
        System.out.println("trajectory same-fixture: auxiliary=200; fullSnapshotStatusReads=1; coreContentStatusReads=0; coreContentCostReads=0");
    }

    @Test
    void descendantAttemptLookupUsesCurrentVisibleClosureWithoutAuxiliaryOrBillingReads() throws Exception {
        var fixture = new Fixture();
        var parent = TrajectoryGraphTest.run("main", "session", "input");
        var child = TrajectoryGraphTest.run("child", "child-session", "child-input");
        when(fixture.repository.runs(any())).thenReturn(List.of(parent, child));
        when(fixture.repository.edges(anyLong(), any())).thenReturn(List.of(
                new TrajectoryRepository.Edge("main", "delegate-action", "delegate-attempt", "child", "DELEGATION")));
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(List.of(
                TrajectoryGraphTest.attempt(parent, "delegate-action", "delegate-attempt", "DELEGATION", "SUCCEEDED"),
                TrajectoryGraphTest.attempt(child, "child-model", "child-attempt", "MODEL", "SUCCEEDED")));
        var resource = new TrajectoryQuery.Resource(7, "session", "root");
        assertEquals("child", fixture.service.requireDescendantAttempt(fixture.scope, resource,
                "child-session", child.rootInputId(), "child-attempt"));
        when(fixture.scope.kind()).thenReturn(SessionObservationScope.Kind.TENANT);
        assertThrows(ApiException.class, () -> fixture.service.requireDescendantAttempt(fixture.scope, resource,
                "child-session", child.rootInputId(), "child-attempt"));
        verifyNoInteractions(fixture.billing, fixture.modelRecords);
        verify(fixture.repository, never()).auxiliaryAttempts(anyLong(), any());
        verify(fixture.repository, never()).material(anyLong(), anyString());
        verify(fixture.authorizer, times(2)).requireSession(fixture.scope, 7, "session");
    }

    @Test
    void overviewWithoutEligibleAuxiliaryCallsDoesNotOpenAModelStatusRead() throws Exception {
        var fixture = new Fixture();
        fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 100, null, null));
        verifyNoInteractions(fixture.modelRecords);
    }

    @Test
    void auxiliaryMaterialsAreLazyExactAndKeepSnapshotAvailability() throws Exception {
        var fixture = new Fixture();
        var run = TrajectoryGraphTest.run("main", "session", "input");
        when(fixture.repository.auxiliaryAttempts(anyLong(), any())).thenReturn(List.of(
                TrajectoryGraphTest.auxiliary(run, "aux-attempt", "aux-call", "SUCCEEDED", 3L, 1L)));
        when(fixture.modelRecords.statuses(7, List.of(new ModelCallRecordReader.Key("aux-call", "aux-attempt")))).thenReturn(java.util.Map.of(new ModelCallRecordReader.Key("aux-call", "aux-attempt"), new ModelCallRecordStatus(true, false)));
        var first = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 100, null, null));
        var model = first.items().stream().filter(node -> node.nodeId().startsWith("auxiliary:")).findFirst().orElseThrow();
        assertEquals("AVAILABLE", model.attempts().getFirst().input().availability());
        assertEquals("NOT_RECORDED", model.attempts().getFirst().output().availability());
        verify(fixture.modelRecords, never()).find(anyLong(), anyString(), anyString());
        when(fixture.modelRecords.statuses(7, List.of(new ModelCallRecordReader.Key("aux-call", "aux-attempt")))).thenReturn(java.util.Map.of(new ModelCallRecordReader.Key("aux-call", "aux-attempt"), new ModelCallRecordStatus(true, true)));
        var same = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", first.snapshot(), null, 100, null, null));
        assertEquals("NOT_RECORDED", same.items().stream().filter(node -> node.nodeId().equals(model.nodeId())).findFirst().orElseThrow()
                .attempts().getFirst().output().availability());
        String rawRequest = "{\"messages\":[\"实际辅助输入" + "原文".repeat(10_000) + "\"],\"value\":9007199254740993}";
        var request = new com.skillforge.runtime.api.TypedPayload("skillforge.model.actual-request.v1", rawRequest);
        when(fixture.modelRecords.find(7, "aux-call", "aux-attempt")).thenReturn(Optional.of(
                new ModelCallRecord(7, "aux-call", "aux-attempt", request, Optional.empty())));
        clearInvocations(fixture.repository, fixture.billing, fixture.modelRecords, fixture.authorizer);
        var content = fixture.service.content(fixture.scope, new TrajectoryQuery.Content(7, "session", "root", model.input().ref()));
        assertEquals("FULL", content.availability());
        assertTrue(content.value().toString().contains("实际辅助输入"));
        assertEquals(rawRequest, content.value());
        verify(fixture.repository).auxiliaryAttempts(7, List.of("main"));
        verify(fixture.modelRecords).find(7, "aux-call", "aux-attempt");
        verify(fixture.modelRecords, never()).statuses(anyLong(), any());
        verifyNoInteractions(fixture.billing);
        verify(fixture.authorizer).requireSession(fixture.scope, 7, "session");
        when(fixture.modelRecords.find(7, "aux-call", "aux-attempt")).thenReturn(Optional.of(
                new ModelCallRecord(8, "aux-call", "aux-attempt", request, Optional.empty())));
        assertThrows(ApiException.class, () -> fixture.service.content(fixture.scope,
                new TrajectoryQuery.Content(7, "session", "root", model.input().ref())));
    }

    @Test
    void searchFindsUnloadedNodesAndReturnsTheExactAncestorChain() throws Exception {
        var fixture = new Fixture();
        var result = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 1, null, "needle"));
        assertEquals(1, result.items().size());
        assertEquals("needle-tool", result.items().getFirst().name());
        assertEquals(3, result.ancestors().size());
        assertTrue(result.ancestors().stream().anyMatch(node -> node.kind() == TrajectoryView.Kind.ENTRY));
        verify(fixture.authorizer, atLeastOnce()).requireSession(fixture.scope, 7, "session");
    }

    @Test
    void snapshotAndContentReferencesRejectDifferentResourcesBeforeReadingMaterials() throws Exception {
        var fixture = new Fixture();
        var page = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 10, null, null));
        assertThrows(ApiException.class, () -> fixture.service.nodes(fixture.scope,
                new TrajectoryQuery.Nodes(8, "session", "root", page.snapshot(), null, 10, null, null)));
        var tool = page.items().stream().filter(node -> node.kind() == TrajectoryView.Kind.TOOL).findFirst().orElseThrow();
        assertThrows(ApiException.class, () -> fixture.service.content(fixture.scope,
                new TrajectoryQuery.Content(8, "session", "root", tool.input().ref())));
        verify(fixture.repository, never()).material(anyLong(), anyString());
    }

    @Test
    void sourceEventDeepLinkLocatesItsOperationAndCursorBindsSearch() throws Exception {
        var fixture = new Fixture();
        var page = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 1, "event:main:2", null));
        assertEquals("needle-tool", page.items().getFirst().nodeId());
        var step = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 1, "step:model", null));
        assertEquals("step:model", step.items().getFirst().nodeId());
        var first = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 1, null, null));
        assertTrue(first.nextCursor() != null);
        assertThrows(ApiException.class, () -> fixture.service.nodes(fixture.scope,
                new TrajectoryQuery.Nodes(7, "session", "root", first.snapshot(), first.nextCursor(), 1, null, "changed")));
    }

    @Test
    void wireSnapshotRemainsSmallAndDoesNotExposeFrozenAttemptReferences() throws Exception {
        var fixture = new Fixture();
        var run = TrajectoryGraphTest.run("main", "session", "input");
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(java.util.stream.IntStream.range(0, 500)
                .mapToObj(index -> TrajectoryGraphTest.attempt(run, "needle-tool", "attempt-" + index, "TOOL", "SUCCEEDED")).toList());
        var page = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 1, null, null));
        assertTrue(page.snapshot().length() < 1000);
        assertTrue(page.nextCursor().length() < 2500);
        assertFalse(page.snapshot().contains("request:"));
        assertThrows(ApiException.class, () -> fixture.service.nodes(fixture.scope,
                new TrajectoryQuery.Nodes(7, "session", "root", page.snapshot() + "tampered", null, 1, null, null)));
    }

    @Test
    void cachedSnapshotRechecksRevokedChildReadRightsBeforeReturningEventsOrAttempts() throws Exception {
        var fixture = new Fixture();
        when(fixture.scope.kind()).thenReturn(SessionObservationScope.Kind.TENANT);
        var main = TrajectoryGraphTest.run("main", "session", "input");
        var child = TrajectoryGraphTest.run("child", "child-session", "child-input");
        when(fixture.repository.runs(any())).thenReturn(List.of(main, child));
        when(fixture.repository.edges(anyLong(), any())).thenReturn(List.of(new TrajectoryRepository.Edge("main", "needle-tool", "tool-attempt", "child", "DELEGATION")));
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(List.of(TrajectoryGraphTest.attempt(main, "needle-tool", "tool-attempt", "TOOL", "SUCCEEDED"),
                TrajectoryGraphTest.attempt(child, "child-tool", "child-attempt", "TOOL", "SUCCEEDED")));
        when(fixture.repository.auxiliaryAttempts(anyLong(), any())).thenReturn(List.of(
                TrajectoryGraphTest.auxiliary(child, "child-auxiliary", "child-auxiliary-call", "SUCCEEDED", 4L, 2L)));
        when(fixture.repository.graphEvents(anyLong(), any())).thenAnswer(call -> {
            List<TrajectoryRepository.RunFact> runs = call.getArgument(1);
            var events = new java.util.ArrayList<TrajectoryRepository.EventFact>();
            events.add(TrajectoryGraphTest.event(main, "needle-tool", "step:model", "tool-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 1));
            if (runs.stream().anyMatch(run -> run.runId().equals("child")))
                events.add(TrajectoryGraphTest.event(child, "child-tool", "step:child-model", "child-attempt", "child-input", ExecutionObservation.Status.SUCCEEDED, 1));
            return events;
        });
        var source = mock(TrajectorySourceAuthorizer.class);
        when(fixture.sourceAuthorizers.getIfAvailable()).thenReturn(source);
        when(source.canRead(any(), any(), any())).thenReturn(true);
        var first = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 100, null, null));
        assertTrue(first.items().stream().anyMatch(node -> "child".equals(node.runId())));
        var childContent = first.items().stream().filter(node -> "child-tool".equals(node.actionId())).findFirst().orElseThrow().input().ref();
        when(source.canRead(any(), any(), any())).thenReturn(false);
        var revoked = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", first.snapshot(), null, 100, null, null));
        assertTrue(revoked.items().stream().noneMatch(node -> "child".equals(node.runId())));
        assertTrue(revoked.items().stream().flatMap(node -> node.attempts().stream()).noneMatch(attempt -> "child-attempt".equals(attempt.attemptId())));
        assertTrue(revoked.items().stream().flatMap(node -> node.attempts().stream()).noneMatch(attempt -> "child-auxiliary".equals(attempt.attemptId())));
        assertTrue(revoked.items().stream().anyMatch(node -> "REDACTED".equals(node.status())));
        assertEquals("PARTIAL", revoked.coverage());
        assertThrows(ApiException.class, () -> fixture.service.content(fixture.scope, new TrajectoryQuery.Content(7, "session", "root", childContent)));
    }

    @Test
    void inputReferenceCreatedWhileRunningStillReadsTheImmutableRequestAfterCompletion() throws Exception {
        var fixture = new Fixture();
        var run = TrajectoryGraphTest.run("main", "session", "input");
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(List.of(TrajectoryGraphTest.attempt(run, "needle-tool", "tool-attempt", "TOOL", null)));
        when(fixture.repository.graphEvents(anyLong(), any())).thenReturn(List.of(TrajectoryGraphTest.event(run, "needle-tool", "step:model", "tool-attempt",
                "input", ExecutionObservation.Status.REGISTERED, 1)));
        var running = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 100, null, null));
        String reference = running.items().stream().filter(node -> node.kind() == TrajectoryView.Kind.TOOL).findFirst().orElseThrow().input().ref();
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(List.of(TrajectoryGraphTest.attempt(run, "needle-tool", "tool-attempt", "TOOL", "SUCCEEDED")));
        // 资源由当前正式AgentExecutionCodec生成；宿主只按已验证引用交给Runtime安全reader。
        String golden;
        try (var source = getClass().getResourceAsStream("/fixtures/trajectory/tool-request-v2.json")) {
            golden = new String(java.util.Objects.requireNonNull(source).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        when(fixture.repository.material(7, "request:tool-attempt")).thenReturn(
                new com.skillforge.runtime.api.TypedPayload("skillforge.runtime.execution.tool-request.v2", golden));
        var read = fixture.service.content(fixture.scope, new TrajectoryQuery.Content(7, "session", "root", reference));
        assertEquals("FULL", read.availability());
        assertEquals("{\"label\":\"immutable request\"}", read.value());
        verify(fixture.repository, never()).material(7, "result:tool-attempt");
    }

    @Test
    void auxiliaryUsageAndExactPartialCostsIncludeRealAttemptsOnceAndPreserveUnknowns() throws Exception {
        var fixture = new Fixture();
        var run = TrajectoryGraphTest.run("main", "session", "input");
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(List.of(
                TrajectoryGraphTest.attempt(run, "model", "model-attempt", "MODEL", "SUCCEEDED"),
                TrajectoryGraphTest.attempt(run, "needle-tool", "tool-attempt", "TOOL", "SUCCEEDED")));
        when(fixture.repository.auxiliaryAttempts(anyLong(), any())).thenReturn(List.of(
                TrajectoryGraphTest.auxiliary(run, "model-attempt", "duplicate", "SUCCEEDED", 99L, 99L),
                TrajectoryGraphTest.auxiliary(run, "aux-known", "aux-call", "SUCCEEDED", 7L, 3L),
                TrajectoryGraphTest.auxiliary(run, "aux-unknown", "unknown-call", "UNRESOLVED", null, null)));
        var coreCost = new BillingTraceFacts.EffectiveCost("core-cost", "model-attempt", 7, "session", "main", "root", "priced",
                new java.math.BigDecimal("9007199254740993.125"));
        when(fixture.billing.effectiveCostsForScopes(anyLong(), any())).thenReturn(List.of(coreCost, coreCost,
                new BillingTraceFacts.EffectiveCost("aux-cost", "aux-known", 7, "session", "main", "root", "unresolved", new java.math.BigDecimal("-0.010")),
                new BillingTraceFacts.EffectiveCost("foreign", "aux-unknown", 8, "session", "main", "root", "priced", java.math.BigDecimal.TEN)));
        var detail = fixture.service.detail(fixture.scope, new TrajectoryQuery.Resource(7, "session", "root"));
        assertEquals(3, detail.summary().modelCalls()); assertEquals(1, detail.summary().toolCalls());
        assertEquals(3, detail.usage().coverage().totalAttempts()); assertEquals(2, detail.usage().coverage().unknownAttempts());
        assertEquals("7", detail.usage().totals().promptTokens()); assertEquals("3", detail.usage().totals().completionTokens());
        assertEquals(java.util.Map.of("CNY", "9007199254740993.115"), detail.usageSummary().amountByCurrency());
        assertEquals(new TrajectoryView.CostCoverage(TrajectoryView.CostStatus.PARTIAL, 2, 1, 1, 1), detail.usageSummary().costCoverage());
        var wire = new ObjectMapper().writeValueAsString(detail.usageSummary());
        assertTrue(wire.contains("\"CNY\":\"9007199254740993.115\""));
    }

    @Test
    void knownZeroCostMissingCostAndNoCostFactsRemainDifferent() throws Exception {
        var fixture = new Fixture();
        var resource = new TrajectoryQuery.Resource(7, "session", "root");
        var none = fixture.service.detail(fixture.scope, resource).usageSummary();
        assertEquals(TrajectoryView.CostStatus.NO_COSTS, none.costCoverage().status()); assertTrue(none.amountByCurrency().isEmpty());
        var run = TrajectoryGraphTest.run("main", "session", "input");
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(List.of(TrajectoryGraphTest.attempt(run, "model", "model-attempt", "MODEL", "SUCCEEDED")));
        var missing = fixture.service.detail(fixture.scope, resource).usageSummary();
        assertEquals(TrajectoryView.CostStatus.UNKNOWN, missing.costCoverage().status());
        assertEquals(1, missing.costCoverage().missingModelAttempts()); assertTrue(missing.amountByCurrency().isEmpty());
        when(fixture.billing.effectiveCostsForScopes(anyLong(), any())).thenReturn(List.of(new BillingTraceFacts.EffectiveCost(
                "zero", "model-attempt", 7, "session", "main", "root", "priced", java.math.BigDecimal.ZERO)));
        var zero = fixture.service.detail(fixture.scope, resource).usageSummary();
        assertEquals(TrajectoryView.CostStatus.COMPLETE, zero.costCoverage().status());
        assertEquals(java.util.Map.of("CNY", "0"), zero.amountByCurrency());
        when(fixture.billing.effectiveCostsForScopes(anyLong(), any())).thenReturn(List.of(new BillingTraceFacts.EffectiveCost(
                "partial-delta", "model-attempt", 7, "session", "main", "root", "unresolved", new java.math.BigDecimal("-0.125"))));
        var partial = fixture.service.detail(fixture.scope, resource).usageSummary();
        assertEquals(TrajectoryView.CostStatus.PARTIAL, partial.costCoverage().status());
        assertEquals(0, partial.costCoverage().pricedEvents()); assertEquals(java.util.Map.of("CNY", "-0.125"), partial.amountByCurrency());
    }

    @Test
    void cachedAuxiliaryAttemptsDoNotChangeWithLaterReports() throws Exception {
        var fixture = new Fixture();
        var run = TrajectoryGraphTest.run("main", "session", "input");
        when(fixture.repository.auxiliaryAttempts(anyLong(), any())).thenReturn(List.of(TrajectoryGraphTest.auxiliary(run, "original-aux", "aux-call", "UNRESOLVED", null, null)));
        var first = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", null, null, 100, null, null));
        when(fixture.repository.auxiliaryAttempts(anyLong(), any())).thenReturn(List.of(TrajectoryGraphTest.auxiliary(run, "later-aux", "later-call", "SUCCEEDED", 9L, 2L)));
        var later = fixture.service.nodes(fixture.scope, new TrajectoryQuery.Nodes(7, "session", "root", first.snapshot(), null, 100, null, null));
        assertTrue(later.items().stream().flatMap(node -> node.attempts().stream()).anyMatch(attempt -> "original-aux".equals(attempt.attemptId())));
        assertTrue(later.items().stream().flatMap(node -> node.attempts().stream()).noneMatch(attempt -> "later-aux".equals(attempt.attemptId())));
    }

    @Test
    void memberUsageCarriesItsActualSessionAndRootForPrivateDiagnosticLocation() throws Exception {
        var fixture = new Fixture();
        var main = TrajectoryGraphTest.run("main", "session", "input");
        var child = new TrajectoryRepository.RunFact("child", "member-session", "member-root", "member-input", "member-trace", "member-span",
                "19", "COMPLETED", "FINISHED", main.createdSecond(), main.createdNano(), main.updatedSecond(), main.updatedNano(), 100, 100);
        when(fixture.repository.runs(any())).thenReturn(List.of(main, child));
        when(fixture.repository.edges(anyLong(), any())).thenReturn(List.of(new TrajectoryRepository.Edge("main", "needle-tool", "tool-attempt", "child", "DELEGATION")));
        when(fixture.repository.attempts(anyLong(), any())).thenReturn(List.of(
                TrajectoryGraphTest.attempt(main, "needle-tool", "tool-attempt", "TOOL", "SUCCEEDED"),
                TrajectoryGraphTest.attempt(child, "member-model", "member-model-attempt", "MODEL", "SUCCEEDED")));
        var detail = fixture.service.detail(fixture.scope, new TrajectoryQuery.Resource(7, "session", "root"));
        var attempt = detail.usage().attempts().getFirst();
        assertEquals("member-session", attempt.sourceSessionId());
        assertEquals("member-root", attempt.sourceRootInputId());
        assertTrue(attempt.diagnosticAvailable());
    }

    private static final class Fixture {
        final TrajectoryRepository repository = mock(TrajectoryRepository.class);
        final SessionObservationAuthorizer authorizer = mock(SessionObservationAuthorizer.class);
        final SessionObservationScope scope = mock(SessionObservationScope.class);
        final SessionObservationService sessions = mock(SessionObservationService.class);
        final BillingTraceQueryService billing = mock(BillingTraceQueryService.class);
        final ModelCallRecordReader modelRecords = mock(ModelCallRecordReader.class);
        @SuppressWarnings("unchecked")
        final ObjectProvider<TrajectorySourceAuthorizer> sourceAuthorizers = mock(ObjectProvider.class);
        final TrajectoryProjectionService service;
        Fixture() throws Exception {
            when(scope.actorId()).thenReturn("99"); when(scope.kind()).thenReturn(SessionObservationScope.Kind.PLATFORM_ADMIN);
            when(repository.productSession(any())).thenReturn(new TrajectoryRepository.ProductSession("tenant", "9", "agent"));
            var run = TrajectoryGraphTest.run("main", "session", "input");
            when(repository.runs(any())).thenReturn(List.of(run));
            when(repository.edges(anyLong(), any())).thenReturn(List.of());
            when(repository.attempts(anyLong(), any())).thenReturn(List.of(TrajectoryGraphTest.attempt(run, "needle-tool", "tool-attempt", "TOOL", "SUCCEEDED")));
            var observations = List.of(
                    TrajectoryGraphTest.event(run, "model", "step:model", "model-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 1),
                    TrajectoryGraphTest.event(run, "needle-tool", "step:model", "tool-attempt", "input", ExecutionObservation.Status.SUCCEEDED, 2));
            when(repository.events(anyLong(), any())).thenReturn(observations);
            when(repository.graphEvents(anyLong(), any())).thenReturn(observations);
            when(repository.now()).thenReturn(Instant.parse("2026-10-25T02:00:00Z"));
            var messages = mock(SessionObservationView.MessagePage.class);
            when(messages.items()).thenReturn(List.of());
            when(sessions.messages(any(), any())).thenReturn(messages);
            var properties = new SkillForgeProperties(); properties.setSecretKey("trajectory-test-secret-0123456789abcdef0123456789");
            var cache = mock(TrajectorySnapshotCache.class);
            var captured = new AtomicReference<TrajectoryProjectionService.Snapshot>();
            when(cache.save(any())).thenAnswer(call -> { captured.set(call.getArgument(0)); return "01234567-89ab-cdef-0123-456789abcdef"; });
            when(cache.read(anyString(), anyString(), anyString())).thenAnswer(call -> captured.get());
            var reads = mock(PlatformObservationAccess.class);
            when(reads.read(anyLong(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
            service = new TrajectoryProjectionService(repository, authorizer, sessions,
                    new CryptoService(properties), new ObjectMapper().findAndRegisterModules(), mock(ObservationReadAudit.class),
                    reads,
                    billing, cache,
                    sourceAuthorizers, mock(KnowledgeObservationAccess.class), modelRecords, 1_000_000, 10_000, 128);
        }
    }
}

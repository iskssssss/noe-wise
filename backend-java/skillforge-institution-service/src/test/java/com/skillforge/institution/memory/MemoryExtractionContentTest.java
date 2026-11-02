package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.ExtractionInput;
import com.skillforge.institution.memory.MemoryExtractionMaterial.FrozenInput;
import com.skillforge.institution.memory.MemoryExtractionMaterial.PreparedData;
import com.skillforge.institution.memory.MemoryExtractionMapper.CandidateRow;
import com.skillforge.institution.memory.MemoryExtractionMapper.Job;
import com.skillforge.institution.group.GroupMemoryExtractionInput;
import com.skillforge.institution.group.GroupMemoryExtractionQueue;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/** 正式来源、冻结输入、逐项回执与原 Runtime 命令的内存夹具，不代表真实模型或数据库验收。 */
class MemoryExtractionContentTest {
    private static final String EXTRACTOR = "memory-extractor-v2", POLICY = "memory-extraction-policy-v2";
    private static final Instant TIME = Instant.parse("2026-10-23T00:00:00.123456789Z");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MemoryExtractionMapper mapper = mock(MemoryExtractionMapper.class);
    private final MemoryExtractionAgent extractor = mock(MemoryExtractionAgent.class);
    private final MemoryExtractionPolicyMapper policy = mock(MemoryExtractionPolicyMapper.class);
    private final MemoryExtractionReuse reuse = mock(MemoryExtractionReuse.class);
    private final MemoryCandidateGovernance governance = mock(MemoryCandidateGovernance.class);
    private final MemoryRuntime runtime = mock(MemoryRuntime.class);
    private final ActiveMemoryTenantMapper tenants = mock(ActiveMemoryTenantMapper.class);
    private final GroupMemoryExtractionInput groups = mock(GroupMemoryExtractionInput.class);
    private final GroupMemoryExtractionQueue groupQueue = mock(GroupMemoryExtractionQueue.class);
    private final List<CandidateRow> rows = new ArrayList<>();
    private FrozenInput input;
    private String modelStatus, status = "READY", memoryType = "inbound-hydrate";
    private boolean group;
    private MemoryExtractionWorker worker;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        when(tenants.after(anyLong())).thenReturn(List.of("1"));
        when(mapper.next("1", EXTRACTOR, POLICY)).thenAnswer(call ->
                List.of("READY", "WAITING", "ACK_PENDING").contains(status) ? job() : null);
        when(mapper.claim(any(), eq(EXTRACTOR))).thenReturn(1);
        when(mapper.lockClaim(any(), eq(EXTRACTOR))).thenAnswer(call -> call.getArgument(0, Job.class).claimToken());
        when(mapper.input(any(), eq(EXTRACTOR))).thenAnswer(call -> input);
        when(mapper.freezeInput(any(), eq(EXTRACTOR), any(FrozenInput.class))).thenAnswer(call -> { input = call.getArgument(2); return 1; });
        when(mapper.modelResult(any(), eq(EXTRACTOR), anyString(), any(), any(), any()))
                .thenAnswer(call -> { modelStatus = call.getArgument(2); return 1; });
        when(mapper.candidates(any(), eq(EXTRACTOR))).thenAnswer(call -> List.copyOf(rows));
        when(mapper.insertCandidate(any(), eq(EXTRACTOR), anyInt(), anyString(), any(MemoryExtractionCandidates.Candidate.class))).thenAnswer(call -> {
            rows.add(new CandidateRow(call.getArgument(2), call.getArgument(3), "READY", call.getArgument(4), null, null, null, null, null, null)); return 1;
        });
        when(mapper.prepareCandidate(any(), eq(EXTRACTOR), anyInt(), any(PreparedData.class))).thenAnswer(call -> {
            int ordinal = call.getArgument(2); var old = rows.get(ordinal); PreparedData prepared = call.getArgument(3);
            rows.set(ordinal, new CandidateRow(ordinal, old.commandKey(), "PREPARED", old.candidate(), prepared,
                    prepared.targetFactId(), prepared.targetRevision(), null, null, null)); return 1;
        });
        when(mapper.candidateResult(any(), eq(EXTRACTOR), anyInt(), anyString(), any(), any(), any())).thenAnswer(call -> {
            int ordinal = call.getArgument(2); var old = rows.get(ordinal);
            rows.set(ordinal, new CandidateRow(ordinal, old.commandKey(), call.getArgument(3), old.candidate(), old.prepared(),
                    old.targetFactId(), old.targetRevision(), call.getArgument(4), call.getArgument(5), call.getArgument(6))); return 1;
        });
        when(mapper.finish(any(), eq(EXTRACTOR), anyString(), any(), any(), any())).thenAnswer(call -> { status = call.getArgument(2); return 1; });
        when(policy.automaticExtractionEnabled("1", "agent")).thenReturn(true);
        when(reuse.related(any(), eq(7L), eq("user:7"))).thenReturn(List.of());
        when(reuse.reuseOrCreate(any(), any(), any())).thenAnswer(call -> ((Function<Optional<MemoryFact>, ?>)call.getArgument(2)).apply(Optional.empty()));
        when(governance.withSlot(any(), any(), any())).thenAnswer(call -> ((Supplier<?>)call.getArgument(2)).get());
        when(governance.evaluate(any(), any(), any(), any())).thenReturn(new MemoryCandidateGovernance.Decision(false, List.of(), Optional.empty()));
        when(governance.evaluateGroup(any(), any(), any(), any(), any())).thenReturn(new MemoryCandidateGovernance.Decision(false, List.of(), Optional.empty()));
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any())).thenReturn(model(MemoryExtractionAgent.Status.COMPLETED, List.of()));
        source("{\"content\":[{\"type\":\"text\",\"text\":\"请记住本地部署偏好\"}]}");
        worker = new MemoryExtractionWorker(mapper, mock(PlatformTransactionManager.class), governance, runtime, extractor, reuse, json, policy, tenants, groups, groupQueue);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void modelDispatchBindsOriginalUsageIdentityBeforeUnknownCanReturn() {
        when(extractor.extract(anyString(),anyString(),any(ExtractionInput.class),any(Runnable.class),any()))
                .thenAnswer(call->{
                    call.getArgument(3,Runnable.class).run();
                    Consumer<MemoryExtractionAgent.DispatchBinding> bind=call.getArgument(4);
                    bind.accept(new MemoryExtractionAgent.DispatchBinding("system-run","system-config","system-invocation"));
                    assertEquals("WAITING",modelStatus,"首次模型回执出现前，归属必须在领取事务中绑定");
                    verify(mapper).modelResult(any(),eq(EXTRACTOR),eq("WAITING"),eq("system-run"),eq("system-config"),eq("system-invocation"));
                    return model(MemoryExtractionAgent.Status.UNKNOWN,List.of());
                });
        worker.poll();
        assertEquals("UNKNOWN",status);
        verify(mapper).modelResult(any(),eq(EXTRACTOR),eq("UNKNOWN"),eq("system-run"),eq("system-config"),eq("system-invocation"));
        verifyNoInteractions(runtime);
    }

    @Test void lostBindingFenceStopsBeforeModelDispatchCanContinue() {
        when(mapper.modelResult(any(),eq(EXTRACTOR),eq("WAITING"),any(),any(),any())).thenReturn(0);
        when(extractor.extract(anyString(),anyString(),any(ExtractionInput.class),any(Runnable.class),any()))
                .thenAnswer(call->{
                    Consumer<MemoryExtractionAgent.DispatchBinding> bind=call.getArgument(4);
                    bind.accept(new MemoryExtractionAgent.DispatchBinding("system-run","system-config","system-invocation"));
                    fail("原领取失效时不能继续派发模型");
                    return null;
                });
        worker.poll();
        verify(mapper,never()).insertCandidate(any(),anyString(),anyInt(),anyString(),any());
        verifyNoInteractions(runtime);
    }

    @Test void failedGroupHandoffDoesNotStopPersonalExtraction() {
        when(groupQueue.enqueue(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("group_source_unavailable"));
        worker.poll();
        verify(extractor).extract(eq("1"), anyString(), any(ExtractionInput.class), any(Runnable.class), any());
        assertEquals("SKIPPED", status);
    }

    @Test void groupMemberOwnerUsesDescribedMemberAndPreservesOriginalAuthorForGovernance() throws Exception {
        groupSource();
        var candidate = groupCandidate("group_member", "user:8");
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any()))
                .thenReturn(model(MemoryExtractionAgent.Status.COMPLETED, List.of(candidate)));
        when(governance.evaluateGroup(any(), any(), any(), any(), eq(new EndUserId(7))))
                .thenReturn(new MemoryCandidateGovernance.Decision(true,
                        List.of("memory_group_member_subject_confirmation_required"), Optional.empty()));
        when(runtime.mutate(any())).thenAnswer(call -> recorded(call.getArgument(0)));

        worker.poll();

        var calls = ArgumentCaptor.forClass(MemoryRuntime.MutationCall.class);
        verify(runtime).mutate(calls.capture());
        var call = calls.getValue();
        var draft = ((MemoryRuntime.Mutation.Create) call.mutation()).draft();
        assertEquals(new MemoryOwner.GroupMember(new GroupBindingRef(new TenantId("1"), "21"), new EndUserId(8)), draft.owner());
        assertEquals(FactStatus.PENDING_CONFIRM, draft.initialStatus());
        assertInstanceOf(SessionSubject.Group.class, call.context().runIdentity().owner().subject());
        assertEquals("COMPLETED", status);
        verify(reuse, never()).related(any(), anyLong(), anyString());
    }

    @Test void groupFrozenSubjectsSurviveMembershipChangesAndModelWaitWithoutRebindingInput() {
        groupSource();
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any()))
                .thenReturn(model(MemoryExtractionAgent.Status.PENDING, List.of()),
                        model(MemoryExtractionAgent.Status.COMPLETED, List.of()));
        worker.poll();
        assertEquals("WAITING", status);
        assertEquals(List.of(7L, 8L), input.groupSubjects());
        when(groups.source(any(), eq("integration"), eq("21"), any()))
                .thenReturn(new GroupMemoryExtractionInput.Material("本地部署", TIME, 7, List.of(7L, 8L, 9L)));
        worker.poll();
        var captures = ArgumentCaptor.forClass(ExtractionInput.class);
        verify(extractor, times(2)).extract(eq("1"), anyString(), captures.capture(), any(Runnable.class), any());
        assertEquals(captures.getAllValues().getFirst(), captures.getAllValues().getLast());
        assertEquals(List.of(7L, 8L), captures.getValue().group().subjectEndUserIds());
        assertEquals("SKIPPED", status);
    }

    @Test void pausedGroupRetainsJobWithoutModelDispatch() {
        groupSource();
        when(groups.allowed(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        worker.poll();
        assertEquals("WAITING", status);
        verifyNoInteractions(extractor);
        verify(groups, never()).source(any(), anyString(), anyString(), any());
        assertNull(input);
    }

    @Test void groupPauseAtModelClaimStopsDispatchWithFrozenInputRetained() {
        groupSource();
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any()))
                .thenAnswer(call -> {
                    when(groups.allowed(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);
                    call.getArgument(3, Runnable.class).run();
                    fail("已暂停的群不能完成新模型受理");
                    return null;
                });
        worker.poll();
        assertEquals("WAITING", status);
        assertNotNull(input);
        assertTrue(rows.isEmpty());
        verifyNoInteractions(runtime);
    }

    @Test void suppressedSourceCandidateDoesNotStopIndependentCandidateOrReinterpret() throws Exception {
        var first = candidates("stable").getFirst();
        var second = new MemoryExtractionCandidates.Candidate(first.type(), first.subject(), "location", first.object(), first.signal(),
                first.sensitive(), first.scope(), first.temporal(), first.conditions(), first.evidence(), first.relation(), first.uncertainties());
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any()))
                .thenReturn(model(MemoryExtractionAgent.Status.COMPLETED, List.of(first, second)));
        when(governance.evaluate(any(), any(), eq(first), any()))
                .thenThrow(new IllegalArgumentException("memory_forgotten_source_suppressed"));
        when(runtime.mutate(any())).thenAnswer(call -> recorded(call.getArgument(0)));
        worker.poll();
        assertEquals("COMPLETED", status);
        assertEquals("FAILED", rows.getFirst().status());
        assertEquals("RECORDED", rows.getLast().status());
        verify(extractor).extract(eq("1"), anyString(), any(ExtractionInput.class), any(Runnable.class), any());
        verify(runtime).mutate(any());
    }

    private void groupSource() {
        group = true; memoryType = "group-hydrate";
        when(groups.allowed(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(groups.source(any(), eq("integration"), eq("21"), any()))
                .thenReturn(new GroupMemoryExtractionInput.Material("本地部署", TIME, 7, List.of(7L, 8L)));
        when(groups.source(any(), eq("integration"), eq("21"), any(), anyList()))
                .thenAnswer(call -> new GroupMemoryExtractionInput.Material("本地部署", TIME, 7, call.getArgument(4)));
        when(reuse.relatedGroup(any(), any(), anyList())).thenReturn(List.of());
    }

    private MemoryExtractionCandidates.Candidate groupCandidate(String scope, String subject) throws Exception {
        var original = candidates("stable").getFirst();
        return new MemoryExtractionCandidates.Candidate(original.type(), subject, original.predicate(), original.object(),
                original.signal(), original.sensitive(), scope, original.temporal(), original.conditions(), original.evidence(),
                original.relation(), original.uncertainties());
    }

    @Test void orderedContentIgnoresImageMetadataAndRootLegacyText() throws Exception {
        source("""
            {"content":[{"type":"text","text":"请记住："},{"type":"image","media":{"id":"secret-image"}},
            {"type":"text","text":"本地部署。"}],"text":"不能读取的旧根字段"}
            """);
        worker.poll();
        var capture = ArgumentCaptor.forClass(ExtractionInput.class);
        verify(extractor).extract(eq("1"), anyString(), capture.capture(), any(Runnable.class), any());
        ExtractionInput modelInput = capture.getValue();
        assertEquals("请记住：本地部署。", modelInput.sources().getFirst().text());
        assertEquals("s0", modelInput.sources().getFirst().id());
        assertEquals("SKIPPED", status);
    }

    @Test void imageOnlyAndLegacyRootTextHaveNoProviderCall() {
        source("{\"content\":[{\"type\":\"image\",\"media\":{\"id\":\"secret\"}}],\"text\":\"old\"}");
        worker.poll();
        verifyNoInteractions(extractor);
        assertEquals("SKIPPED", status);
    }

    @Test void disabledStopsBeforeFormalSourceRead() {
        when(policy.automaticExtractionEnabled("1", "agent")).thenReturn(false);
        worker.poll();
        verify(reuse, never()).source(any(), any());
        verifyNoInteractions(extractor);
        assertEquals("SKIPPED", status);
    }

    @Test void explicitMetadataCandidateComesOnlyFromOriginalCommittedSource() throws Exception {
        explicitSourceCandidate(true);
    }

    @Test void explicitRootCandidateComesOnlyFromOriginalCommittedSource() throws Exception {
        explicitSourceCandidate(false);
    }

    private void explicitSourceCandidate(boolean metadata) throws Exception {
        memoryType = "preference";
        var candidate = candidates("stable").getFirst();
        String encoded = new MemoryExtractionCandidates(json).encodeCandidate(candidate);
        String field = metadata ? "\"metadata\":{\"memoryCandidate\":" + encoded + "}"
                : "\"memoryCandidate\":" + encoded;
        source("{\"content\":[{\"type\":\"text\",\"text\":\"请记住本地部署偏好\"}]," + field + "}");
        when(runtime.mutate(any())).thenAnswer(call -> recorded(call.getArgument(0)));
        worker.poll();
        assertEquals("COMPLETED", status);
        assertEquals(candidate, rows.getFirst().candidate());
        verifyNoInteractions(extractor);
        verify(runtime).mutate(any());
    }

    @Test void explicitCandidateCannotUseItsOwnBodyAsSourceEvidence() throws Exception {
        memoryType = "preference";
        String encoded = new MemoryExtractionCandidates(json).encodeCandidate(candidates("stable").getFirst());
        source("{\"content\":[{\"type\":\"text\",\"text\":\"完全不同的正式来源\"}],\"metadata\":{\"memoryCandidate\":" + encoded + "}}");
        worker.poll();
        assertEquals("FAILED", status);
        verify(mapper).finish(any(), eq(EXTRACTOR), eq("FAILED"), isNull(), isNull(), eq("memory_extraction_evidence_invalid"));
        verifyNoInteractions(extractor, runtime);
    }

    @Test void waitingResumesExactFrozenInputAndOperationWithoutRebuildingLatest() {
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any())).thenReturn(
                model(MemoryExtractionAgent.Status.PENDING, List.of()), model(MemoryExtractionAgent.Status.COMPLETED, List.of()));
        worker.poll(); assertEquals("WAITING", status);
        FrozenInput original = input;
        worker.poll(); assertEquals("SKIPPED", status);
        assertEquals(original, input);
        var keys = ArgumentCaptor.forClass(String.class); var inputs = ArgumentCaptor.forClass(ExtractionInput.class);
        verify(extractor, times(2)).extract(eq("1"), keys.capture(), inputs.capture(), any(Runnable.class), any());
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        assertEquals(inputs.getAllValues().get(0), inputs.getAllValues().get(1));
        verify(reuse, times(2)).source(any(), any());
        verify(reuse, times(1)).related(any(), anyLong(), anyString());
    }

    @Test void unknownIsRetainedAndNeverAutomaticallyReclaimed() {
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any())).thenReturn(model(MemoryExtractionAgent.Status.UNKNOWN, List.of()));
        worker.poll(); worker.poll();
        assertEquals("UNKNOWN", status); assertNotNull(input);
        verify(extractor, times(1)).extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any());
    }

    @Test void jsonbNumericNormalizationCannotChangeOriginalModelFingerprint() throws Exception {
        var fact = new MemoryFact(new MemoryFactRef(new TenantId("1"), "private-fact"),
                new MemoryOwner.UserAgent(new TenantId("1"), new EndUserId(7), new AgentId("agent")),
                new MemoryTypeRef("preference"), new RetentionClassRef("standard"), "v2", "user:7", "quota",
                new TypedPayload("skillforge.memory.preference.v2", """
                    {"value":{"quota":1.0E20},"conditions":[],"temporal":{"kind":"stable","from":null,"to":null}}
                    """), List.of(new EventId("original-source")), new SessionRef(new TenantId("1"), "original-session"),
                Optional.empty(), Optional.empty(), EXTRACTOR, 1, TIME, TIME, Optional.empty(), Optional.empty(),
                0.8, 0.5, false, Optional.empty(), Optional.empty(), 1, FactStatus.ACTIVE);
        when(reuse.related(any(), anyLong(), anyString())).thenReturn(List.of(fact));
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any())).thenReturn(
                model(MemoryExtractionAgent.Status.PENDING, List.of()), model(MemoryExtractionAgent.Status.COMPLETED, List.of()));
        worker.poll();
        var normalized = new MemoryFact(fact.ref(), fact.owner(), fact.type(), fact.retentionClass(), fact.schemaVersion(),
                fact.subject(), fact.predicate(), new TypedPayload(fact.object().schemaId(),
                        fact.object().canonicalJson().replace("1.0E20", "100000000000000000000")), fact.sourceEventIds(),
                fact.sourceSessionRef(), fact.sourceExecutionRef(), fact.exportPath(), fact.extractorVersion(), fact.revision(),
                fact.createdAt(), fact.validFrom(), fact.validTo(), fact.expiresAt(), fact.confidence(), fact.importance(),
                fact.reviewRequired(), fact.derivedFrom(), fact.supersededBy(), fact.decayScore(), fact.status());
        when(reuse.modelReference(any(), any())).thenReturn(normalized);
        worker.poll();
        var inputs = ArgumentCaptor.forClass(ExtractionInput.class);
        verify(extractor, times(2)).extract(eq("1"), anyString(), inputs.capture(), any(Runnable.class), any());
        var contract = new MemoryExtractionCandidates(json);
        assertEquals(contract.encodeInput(inputs.getAllValues().get(0)), contract.encodeInput(inputs.getAllValues().get(1)));
        assertFalse(contract.encodeInput(inputs.getAllValues().getFirst()).contains("private-fact"));
    }

    @Test void malformedTextFailsBeforeProviderWithStableReason() {
        source("{\"content\":[{\"type\":\"text\",\"text\":{\"secret\":\"value\"}}]}");
        worker.poll(); assertEquals("FAILED", status); verifyNoInteractions(extractor);
        verify(mapper).finish(any(), eq(EXTRACTOR), eq("FAILED"), isNull(), isNull(), eq("memory_inbound_text_invalid"));
    }

    @Test void completedModelCandidatesAreNotRegeneratedAfterPartialWriteAndOriginalDraftKeySurvives() throws Exception {
        var candidates = candidates("stable");
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any())).thenReturn(model(MemoryExtractionAgent.Status.COMPLETED, candidates));
        var pending = mock(MemoryRuntime.PendingMutation.class);
        when(runtime.mutate(any())).thenReturn(new MemoryRuntime.MutationResult.Pending(pending,
                new MemoryRuntime.Failure("memory_log_ack_pending", Optional.empty())));
        worker.poll(); assertEquals("ACK_PENDING", status);
        PreparedData draft = rows.getFirst().prepared(); String key = rows.getFirst().commandKey();
        when(runtime.mutate(any())).thenAnswer(call -> recorded(call.getArgument(0)));
        worker.poll(); assertEquals("COMPLETED", status);
        assertEquals(key, rows.getFirst().commandKey()); assertEquals(draft, rows.getFirst().prepared());
        verify(extractor, times(1)).extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any());
        var writes = ArgumentCaptor.forClass(MemoryRuntime.MutationCall.class);
        verify(runtime, times(2)).mutate(writes.capture());
        var first = (MemoryRuntime.Mutation.Create) writes.getAllValues().get(0).mutation();
        var second = (MemoryRuntime.Mutation.Create) writes.getAllValues().get(1).mutation();
        assertEquals(first, second); assertEquals(writes.getAllValues().get(0).occurredAt(), writes.getAllValues().get(1).occurredAt());
    }

    @Test void productionSnakeCaseMapperCannotChangeTypedPreparationOrAckReplay() throws Exception {
        var productionJson = json.copy().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        worker = new MemoryExtractionWorker(mapper, mock(PlatformTransactionManager.class), governance, runtime,
                extractor, reuse, productionJson, policy, tenants, groups, groupQueue);
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any()))
                .thenReturn(model(MemoryExtractionAgent.Status.COMPLETED, candidates("stable")));
        when(runtime.mutate(any())).thenReturn(new MemoryRuntime.MutationResult.Pending(mock(MemoryRuntime.PendingMutation.class),
                new MemoryRuntime.Failure("memory_log_ack_pending", Optional.empty())))
                .thenAnswer(call -> recorded(call.getArgument(0)));

        worker.poll();

        assertEquals("ACK_PENDING", status, "无existing fact的首次正式写入也必须成功准备");
        PreparedData prepared = rows.getFirst().prepared();
        assertEquals("standard", prepared.retentionClass());
        assertEquals("v2", prepared.schemaVersion());
        assertEquals("skillforge.memory.preference.v2", prepared.payloadSchemaId());
        assertEquals(TIME, prepared.validFrom());

        worker.poll();

        assertEquals("COMPLETED", status);
        var writes = ArgumentCaptor.forClass(MemoryRuntime.MutationCall.class);
        verify(runtime, times(2)).mutate(writes.capture());
        assertEquals(writes.getAllValues().get(0).mutation(), writes.getAllValues().get(1).mutation());
        assertEquals(TIME, writes.getAllValues().get(1).occurredAt());
        verify(extractor, times(1)).extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any());
    }

    @Test void preparedWithOriginalJournalCompletesAckDespiteLaterGovernanceDifference() throws Exception {
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any()))
                .thenReturn(model(MemoryExtractionAgent.Status.COMPLETED, candidates("stable")));
        when(runtime.mutate(any())).thenThrow(new SimulatedProcessStop())
                .thenAnswer(call -> recorded(call.getArgument(0)));
        assertThrows(SimulatedProcessStop.class, worker::poll);
        assertEquals("PREPARED", rows.getFirst().status());
        PreparedData prepared = rows.getFirst().prepared();
        String commandKey = rows.getFirst().commandKey();
        clearInvocations(governance, reuse);
        when(reuse.hasRecordedMutation(any(), any())).thenReturn(true);
        when(governance.evaluate(any(), any(), any(), any())).thenReturn(
                new MemoryCandidateGovernance.Decision(true, List.of("memory_possible_difference_review_required"), Optional.empty()));

        worker.poll();

        assertEquals("COMPLETED", status);
        assertEquals(prepared, rows.getFirst().prepared());
        verify(reuse).hasRecordedMutation(any(), eq(new IdempotencyKey(commandKey)));
        verify(reuse, never()).reuseOrCreate(any(), any(), any());
        verify(governance, never()).evaluate(any(), any(), any(), any());
        verify(extractor, times(1)).extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any());
        var writes = ArgumentCaptor.forClass(MemoryRuntime.MutationCall.class);
        verify(runtime, times(2)).mutate(writes.capture());
        var original = writes.getAllValues().get(0);
        var recovered = writes.getAllValues().get(1);
        assertEquals(original.mutation(), recovered.mutation());
        assertEquals(original.occurredAt(), recovered.occurredAt());
        assertEquals(original.context().runIdentity(), recovered.context().runIdentity());
        assertEquals(original.context().identity(), recovered.context().identity());
    }

    @Test void unreadableOriginalJournalKeepsPreparedMaterialUnknownWithoutNewSubmission() throws Exception {
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any()))
                .thenReturn(model(MemoryExtractionAgent.Status.COMPLETED, candidates("stable")));
        when(runtime.mutate(any())).thenThrow(new SimulatedProcessStop());
        assertThrows(SimulatedProcessStop.class, worker::poll);
        PreparedData prepared = rows.getFirst().prepared();
        clearInvocations(governance, reuse, mapper);
        when(reuse.hasRecordedMutation(any(), any())).thenThrow(new IllegalStateException("journal-unavailable"));

        worker.poll();

        assertEquals("UNKNOWN", status);
        assertEquals("PREPARED", rows.getFirst().status());
        assertEquals(prepared, rows.getFirst().prepared());
        verify(runtime, times(1)).mutate(any());
        verify(extractor, times(1)).extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any());
        verify(governance, never()).evaluate(any(), any(), any(), any());
        verify(reuse, never()).reuseOrCreate(any(), any(), any());
        verify(mapper, never()).candidateResult(any(), anyString(), anyInt(), anyString(), any(), any(), any());
    }

    @Test void taskInterpretationBecomesShortEpisodeAndPreservesTemporalConditions() throws Exception {
        when(extractor.extract(anyString(), anyString(), any(ExtractionInput.class), any(Runnable.class), any())).thenReturn(model(MemoryExtractionAgent.Status.COMPLETED, candidates("task")));
        when(runtime.mutate(any())).thenAnswer(call -> recorded(call.getArgument(0)));
        worker.poll(); assertEquals("COMPLETED", status);
        var writes = ArgumentCaptor.forClass(MemoryRuntime.MutationCall.class); verify(runtime).mutate(writes.capture());
        var draft = ((MemoryRuntime.Mutation.Create)writes.getValue().mutation()).draft();
        assertEquals("episode", draft.type().value()); assertEquals(MemoryScope.USER_AGENT, draft.scope());
        assertEquals(Optional.of(TIME.plusSeconds(86400)), draft.expiresAt());
        JsonNode body = json.readTree(draft.object().canonicalJson());
        assertEquals("task", body.path("temporal").path("kind").textValue());
        assertEquals("工作时", body.path("conditions").get(0).textValue());
    }

    @Test void lostClaimCannotInvokeProviderOrPersistInput() {
        when(mapper.lockClaim(any(), eq(EXTRACTOR))).thenReturn(null);
        worker.poll(); verifyNoInteractions(extractor); assertNull(input);
    }

    private Job job() {
        return new Job("1", "event", memoryType, "key", "agent", group ? "GROUP" : "AGENT", group ? "21" : "agent", 7,
                "session", group ? null : "execution", group ? "group.memory.source" : "inbound.message", POLICY,
                input == null ? null : input.sourceTime().getEpochSecond(), input == null ? null : input.sourceTime().getNano(),
                modelStatus, null, null, null, null, group ? "integration" : null, group ? "21" : null);
    }

    private void source(String payload) {
        var source = mock(CommittedSessionEvent.class);
        when(source.agentId()).thenReturn(new AgentId("agent")); when(source.eventType()).thenReturn("inbound.message");
        when(source.executionRef()).thenReturn(Optional.of(new ExecutionRef(new TenantId("1"), "execution")));
        when(source.occurredAt()).thenReturn(TIME); when(source.payload()).thenReturn(new TypedPayload("inbound.v1", payload));
        when(reuse.source(any(), eq(new EventId("event")))).thenReturn(source);
    }

    private List<MemoryExtractionCandidates.Candidate> candidates(String temporal) throws Exception {
        var contract = new MemoryExtractionCandidates(json);
        ExtractionInput input = contract.typedInput(7, List.of(new MemoryExtractionCandidates.Source("s0", "请记住本地部署偏好", TIME)), List.of());
        return contract.parse("""
            {"schemaVersion":2,"candidates":[{"type":"preference","subject":"user:7","predicate":"hosting",
             "object":{"text":"本地部署"},"signal":"explicit_save","sensitive":false,"scope":"user_agent",
             "temporal":{"kind":"TEMPORAL","from":null,"to":null},"conditions":["工作时"],
             "evidence":[{"sourceId":"s0","quote":"本地部署"}],"relation":{"kind":"new","target":null},"uncertainties":[]}]}
            """.replace("TEMPORAL", temporal), input);
    }

    private MemoryRuntime.MutationResult recorded(MemoryRuntime.MutationCall call) {
        MemoryFactDraft d = ((MemoryRuntime.Mutation.Create)call.mutation()).draft();
        var fact = new MemoryFact(new MemoryFactRef(new TenantId("1"), "saved-fact"), d.owner(), d.type(), d.retentionClass(),
                d.schemaVersion(), d.subject(), d.predicate(), d.object(), d.sourceEventIds(), d.sourceSessionRef(), d.sourceExecutionRef(),
                d.exportPath(), d.extractorVersion(), 1, TIME, d.validFrom(), d.validTo(), d.expiresAt(), d.confidence(), d.importance(),
                d.reviewRequired(), d.derivedFrom(), Optional.empty(), 1, d.initialStatus());
        return new MemoryRuntime.MutationResult.Recorded(fact, mock(CommittedSessionEvent.class), false);
    }

    private static MemoryExtractionAgent.Result model(MemoryExtractionAgent.Status status, List<MemoryExtractionCandidates.Candidate> candidates) {
        return new MemoryExtractionAgent.Result(status, candidates, "system-run", "system-config", "system-invocation", "memory_model_" + status.name().toLowerCase());
    }

    /** 模拟原Memory提交后进程立即退出，父候选结果事务没有机会保存。 */
    private static final class SimulatedProcessStop extends Error {}
}

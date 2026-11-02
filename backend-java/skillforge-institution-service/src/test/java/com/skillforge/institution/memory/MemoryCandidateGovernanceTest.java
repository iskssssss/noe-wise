package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Candidate;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Evidence;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Relation;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Temporal;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactReadBatch;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactRead;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 用人工业务样例验证候选治理；模拟读取不能替代真实数据库与正式授权验收。 */
class MemoryCandidateGovernanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TenantId TENANT = new TenantId("1");
    private static final EndUserId USER = new EndUserId(7);
    private static final AgentId AGENT = new AgentId("followup-agent");
    private static final SessionRef SESSION = new SessionRef(TENANT, "source-session");
    private static final Instant JANUARY = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEBRUARY = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant MARCH = Instant.parse("2026-03-01T00:00:00Z");
    private static final ResultRef AUDIT = new ResultRef("governance-test-audit");
    private final JdbcMemoryPort memories = mock(JdbcMemoryPort.class);
    private final MemoryAccessPolicy access = mock(MemoryAccessPolicy.class);
    private final PortCallContext context = context(Set.of("memory:extract"));
    private final MemoryCandidateGovernance governance = new MemoryCandidateGovernance(memories, access);

    @BeforeEach
    void allowCurrentFacts() {
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Allowed(AUDIT));
        related(List.of());
        when(memories.extractionGovernance(any(), any())).thenReturn(JdbcMemoryPort.ExtractionGovernance.ALLOWED);
    }

    @Test
    void forgottenSourceCannotCreateNewCandidateOrStructuredProposal() {
        when(memories.extractionGovernance(any(), any())).thenReturn(JdbcMemoryPort.ExtractionGovernance.FORGOTTEN_SOURCE);
        var proposal = new Proposal("电子邮件");
        assertEquals("memory_forgotten_source_suppressed", assertThrows(IllegalArgumentException.class,
                () -> evaluate(proposal)).getMessage());
        assertEquals("memory_forgotten_source_suppressed", assertThrows(IllegalArgumentException.class,
                () -> governance.evaluateStructured(context, proposal.draft(), false)).getMessage());
    }

    @Test
    void supersededSourceCannotRaceNewCandidateBackIntoActive() {
        when(memories.extractionGovernance(any(), any())).thenReturn(JdbcMemoryPort.ExtractionGovernance.SUPERSEDED_SOURCE);
        assertEquals("memory_superseded_source_suppressed", assertThrows(IllegalArgumentException.class,
                () -> evaluate(new Proposal("电话"))).getMessage());
    }

    @Test
    void newExplicitExpressionAfterForgetRequiresExistingConfirmationFlow() {
        when(memories.extractionGovernance(any(), any())).thenReturn(JdbcMemoryPort.ExtractionGovernance.FORGOTTEN_SLOT_REVIEW);
        assertReview(evaluate(new Proposal("电子邮件")), "memory_forgotten_slot_confirmation_required");
    }

    @Test
    void groupMemberReviewUsesCommittedSourceAuthorInsteadOfSystemRequester() {
        var proposal = new Proposal("电子邮件");
        proposal.scope = "group_member";
        proposal.subject = "user:8";
        proposal.owner = new MemoryOwner.GroupMember(new com.skillforge.runtime.api.GroupBindingRef(TENANT, "group"), new EndUserId(8));
        assertReview(governance.evaluateGroup(context, proposal.draft(), proposal.candidate(), Optional.empty(), USER),
                "memory_group_member_subject_confirmation_required");
        assertNoReview(governance.evaluateGroup(context, proposal.draft(), proposal.candidate(), Optional.empty(), new EndUserId(8)));
    }

    @Test
    void preferencesUnderDifferentConditionsMayCoexist() {
        var home = new Proposal("电话");
        home.conditions = List.of("在家时");
        var travelling = new Proposal("电子邮件");
        travelling.conditions = List.of("出差时");
        related(List.of(fact("home-contact", 3, home.draft())));

        assertNoReview(evaluate(travelling));
    }

    @Test
    void preferencesInDisjointAndTouchingIntervalsMayCoexist() {
        var january = new Proposal("电话");
        january.from = JANUARY;
        january.to = Optional.of(FEBRUARY);
        related(List.of(fact("january-contact", 2, january.draft())));
        var february = new Proposal("电子邮件");
        february.from = FEBRUARY;
        february.to = Optional.of(MARCH);
        assertNoReview(evaluate(february));

        var march = new Proposal("短信");
        march.from = MARCH;
        assertNoReview(evaluate(march));
    }

    @Test
    void differentValuesInSamePreferenceSlotOnlySignalPossibleDifference() {
        related(List.of(fact("previous-contact", 4, new Proposal("电话").draft())));
        var decision = evaluate(new Proposal("电子邮件"));

        assertReview(decision, "memory_possible_difference_review_required");
        assertTrue(decision.revisionTarget().isEmpty());
        assertNoReview(evaluate(new Proposal("电话")));
    }

    @Test
    void newEpisodeSupplementsPriorEpisodeWithoutInventingConflict() {
        var previous = new Proposal("预约了周一面诊");
        previous.type = "episode";
        previous.temporal = new Temporal("unknown", null, null);
        related(List.of(fact("prior-episode", 1, previous.draft())));
        var followup = new Proposal("周二已完成随访");
        followup.type = "episode";
        followup.temporal = new Temporal("unknown", null, null);

        assertNoReview(evaluate(followup));
    }

    @Test
    void correctionFreezesOriginalFactAndRevisionEvenWhenCurrentHeadChanges() {
        var original = fact("preferred-contact", 7, new Proposal("电话").draft());
        var correction = new Proposal("电子邮件");
        correction.relation = new Relation("correction", "existing-1");
        when(memories.findCurrent(any(CurrentFactReadBatch.class))).thenReturn(List.of(Optional.of(original)));

        var first = governance.evaluate(context, correction.draft(), correction.candidate(), Optional.of(original));
        assertReview(first, "memory_revision_confirmation_required");
        assertEquals(Optional.of(new MemoryCandidateGovernance.RevisionTarget(original.ref(), 7)), first.revisionTarget());

        var advanced = fact("preferred-contact", 8, new Proposal("短信").draft());
        when(memories.findCurrent(any(CurrentFactReadBatch.class))).thenReturn(List.of(Optional.of(advanced)));
        var changed = governance.evaluate(context, correction.draft(), correction.candidate(), Optional.of(original));

        assertReview(changed, "memory_revision_target_changed", "memory_revision_confirmation_required");
        assertEquals(first.revisionTarget(), changed.revisionTarget());
    }

    @Test
    void possibleConflictRequiresReviewWithoutClaimingConfirmedCorrection() {
        var original = fact("preferred-contact", 4, new Proposal("电话").draft());
        var candidate = new Proposal("电子邮件");
        candidate.relation = new Relation("possible_conflict", "existing-1");
        when(memories.findCurrent(any(CurrentFactReadBatch.class))).thenReturn(List.of(Optional.of(original)));

        var decision = governance.evaluate(context, candidate.draft(), candidate.candidate(), Optional.of(original));
        assertReview(decision, "memory_possible_conflict_review_required");
        assertEquals(Optional.of(new MemoryCandidateGovernance.RevisionTarget(original.ref(), 4)), decision.revisionTarget());
    }

    @Test
    void correctionRejectsMissingOrIncompatibleTargetBeforeCurrentRead() {
        var correction = new Proposal("电子邮件");
        correction.relation = new Relation("correction", "existing-1");
        assertEquals("memory_relation_target_missing", assertThrows(IllegalArgumentException.class,
                () -> governance.evaluate(context, correction.draft(), correction.candidate(), Optional.empty())).getMessage());

        var wrongOwner = new Proposal("电话");
        wrongOwner.owner = new MemoryOwner.UserAgent(TENANT, new EndUserId(8), AGENT);
        var wrongType = new Proposal("预约记录");
        wrongType.type = "episode";
        var wrongSubject = new Proposal("电话");
        wrongSubject.subject = "user:8";
        var wrongPredicate = new Proposal("电话");
        wrongPredicate.predicate = "appointment_channel";
        var otherConditions = new Proposal("电话");
        otherConditions.conditions = List.of("出差时");
        var endedBeforeCorrection = new Proposal("电话");
        endedBeforeCorrection.from = JANUARY;
        endedBeforeCorrection.to = Optional.of(FEBRUARY);
        correction.from = FEBRUARY;

        for (Proposal incompatible : List.of(wrongOwner, wrongType, wrongSubject, wrongPredicate,
                otherConditions, endedBeforeCorrection)) {
            var target = fact("incompatible-target", 3, incompatible.draft());
            assertEquals("memory_relation_target_incompatible", assertThrows(IllegalArgumentException.class,
                    () -> governance.evaluate(context, correction.draft(), correction.candidate(), Optional.of(target))).getMessage());
        }
        verifyNoInteractions(memories);
    }

    @Test
    void sharingSensitiveAndUncertainInterpretationsRequireExplicitReview() {
        var shared = new Proposal("电子邮件");
        shared.scope = "user";
        shared.owner = new MemoryOwner.User(TENANT, USER);
        assertReview(evaluate(shared), "memory_user_scope_confirmation_required");

        var sensitive = new Proposal("正在服用处方药");
        sensitive.sensitive = true;
        assertReview(evaluate(sensitive), "memory_sensitive_review_required");

        var unknownPreference = new Proposal("电子邮件");
        unknownPreference.temporal = new Temporal("unknown", null, null);
        assertReview(evaluate(unknownPreference), "memory_temporal_review_required");

        var observed = new Proposal("电子邮件");
        observed.signal = "observed";
        assertReview(evaluate(observed), "memory_preference_confirmation_required");

        var repeatedQuote = new Proposal("电子邮件");
        repeatedQuote.signal = "repeated_confirmed";
        repeatedQuote.evidence = List.of(new Evidence("source-1", "请通过邮件联系"),
                new Evidence("source-1", "我希望邮件联系"));
        assertReview(evaluate(repeatedQuote), "memory_independent_confirmation_required");

        var uncertain = new Proposal("电子邮件");
        uncertain.uncertainties = List.of("未明确指的是工作日还是周末");
        assertReview(evaluate(uncertain), "memory_interpretation_review_required");
    }

    @Test
    void independentlyConfirmedPreferenceAndAuthorizedSharingCanProceed() {
        var repeated = new Proposal("电子邮件");
        repeated.signal = "repeated_confirmed";
        repeated.evidence = List.of(new Evidence("source-1", "请通过邮件联系"),
                new Evidence("source-2", "仍然请用邮件"));
        assertNoReview(evaluate(repeated));

        var shared = new Proposal("电子邮件");
        shared.scope = "user";
        shared.owner = new MemoryOwner.User(TENANT, USER);
        assertNoReview(governance.evaluate(context(Set.of("memory:extract", "memory:manage")),
                shared.draft(), shared.candidate(), Optional.empty()));
    }

    @Test
    void readAndAuthorizationFailuresCannotBeMistakenForAbsenceOfDifferences() {
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Denied("read-denied", AUDIT));
        assertReview(evaluate(new Proposal("电子邮件")), "memory_current_access_denied");
        verifyNoInteractions(memories);

        when(access.authorize(any())).thenThrow(new IllegalStateException("authorization-unavailable"));
        assertReview(evaluate(new Proposal("电子邮件")), "memory_current_fact_read_unavailable");

        doReturn(new MemoryAccessPolicy.Decision.Allowed(AUDIT)).when(access).authorize(any());
        when(memories.relatedCurrentFacts(eq(context), any(MemoryOwner.class), any(MemoryTypeRef.class),
                anyString(), anyString())).thenThrow(new IllegalStateException("database-unavailable"));
        assertReview(evaluate(new Proposal("电子邮件")), "memory_current_fact_read_unavailable");
    }

    @Test
    void unavailableOrUnauthorizedRevisionReadRetainsFrozenPendingTarget() {
        var target = fact("preferred-contact", 9, new Proposal("电话").draft());
        var correction = new Proposal("电子邮件");
        correction.relation = new Relation("correction", "existing-1");
        when(memories.findCurrent(any(CurrentFactReadBatch.class))).thenThrow(new IllegalStateException("database-unavailable"));
        var failedRead = governance.evaluate(context, correction.draft(), correction.candidate(), Optional.of(target));
        assertReview(failedRead, "memory_current_fact_read_unavailable", "memory_revision_confirmation_required");
        assertEquals(Optional.of(new MemoryCandidateGovernance.RevisionTarget(target.ref(), 9)), failedRead.revisionTarget());

        doReturn(List.of(Optional.of(target))).when(memories).findCurrent(any(CurrentFactReadBatch.class));
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Denied("read-denied", AUDIT));
        var denied = governance.evaluate(context, correction.draft(), correction.candidate(), Optional.of(target));
        assertReview(denied, "memory_current_access_denied", "memory_revision_confirmation_required");
        assertEquals(failedRead.revisionTarget(), denied.revisionTarget());
        // 草稿查询权限拒绝后不再读事实；只保留第一次已授权但失败的读取。
        verify(memories, times(1)).findCurrent(new CurrentFactReadBatch(List.of(new CurrentFactRead(context, target.ref()))));
    }

    private MemoryCandidateGovernance.Decision evaluate(Proposal proposal) {
        return governance.evaluate(context, proposal.draft(), proposal.candidate(), Optional.empty());
    }

    private void related(List<MemoryFact> facts) {
        when(memories.relatedCurrentFacts(any(PortCallContext.class), any(MemoryOwner.class),
                any(MemoryTypeRef.class), anyString(), anyString())).thenReturn(facts);
    }

    private static void assertNoReview(MemoryCandidateGovernance.Decision decision) {
        assertFalse(decision.reviewRequired());
        assertEquals(List.of(), decision.reasons());
        assertEquals(Optional.empty(), decision.revisionTarget());
    }

    private static void assertReview(MemoryCandidateGovernance.Decision decision, String... reasons) {
        assertTrue(decision.reviewRequired());
        assertEquals(List.of(reasons), decision.reasons());
    }

    /** 显式提供可信调用上下文，令批量读取确实经过正式引用的租户校验。 */
    private static PortCallContext context(Set<String> scopes) {
        var owner = SessionOwner.user(SESSION, AGENT, USER);
        var input = new InputId("governance-input");
        var run = new RunIdentity(owner, AGENT, input, new RunRef(TENANT, "governance-run"),
                new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("causation"),
                new RunRequester.EndUser(USER), Optional.empty(), new RunOriginRef(TENANT, "input:governance-input"));
        var identity = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT, "memory-extractor"),
                owner, scopes, 1, new ResultRef("verified"));
        return new PortCallContext(run, identity,
                new InvocationControl(Instant.parse("2100-01-01T00:00:00Z"), () -> false));
    }

    /** 测试事实从独立样例构造；版本与引用由用例指定，预期不由生产判定函数生成。 */
    private static MemoryFact fact(String id, long revision, MemoryFactDraft draft) {
        return new MemoryFact(new MemoryFactRef(TENANT, id), draft.owner(), draft.type(), draft.retentionClass(),
                draft.schemaVersion(), draft.subject(), draft.predicate(), draft.object(), draft.sourceEventIds(),
                draft.sourceSessionRef(), draft.sourceExecutionRef(), draft.exportPath(), draft.extractorVersion(),
                revision, JANUARY, draft.validFrom(), draft.validTo(), draft.expiresAt(), draft.confidence(),
                draft.importance(), false, Optional.empty(), Optional.empty(), 0, FactStatus.ACTIVE);
    }

    /** 每个用例只改所讨论的业务维度；正文复用正式 v2 值契约，不复制治理算法。 */
    private static final class Proposal {
        private final String value;
        private String type = "preference";
        private String subject = "user:7";
        private String predicate = "contact_channel";
        private String signal = "explicit_save";
        private boolean sensitive;
        private String scope = "user_agent";
        private Temporal temporal = new Temporal("stable", null, null);
        private List<String> conditions = List.of();
        private List<Evidence> evidence = List.of(new Evidence("source-1", "请通过邮件联系"));
        private Relation relation = new Relation("new", null);
        private List<String> uncertainties = List.of();
        private MemoryOwner owner = new MemoryOwner.UserAgent(TENANT, USER, AGENT);
        private Instant from = JANUARY;
        private Optional<Instant> to = Optional.empty();

        private Proposal(String value) { this.value = value; }

        private Candidate candidate() {
            return new Candidate(type, subject, predicate, JSON.createObjectNode().put("channel", value),
                    signal, sensitive, scope, temporal, conditions, evidence, relation, uncertainties);
        }

        private MemoryFactDraft draft() {
            return new MemoryFactDraft(owner, new MemoryTypeRef(type), new RetentionClassRef("standard"), "v2",
                    subject, predicate, new TypedPayload("skillforge.memory." + type + ".v2",
                    MemoryContent.encode(MemoryContent.interpretedContent(candidate()))), List.of(new EventId("source-event")),
                    SESSION, Optional.empty(), Optional.empty(), "memory-extractor-v2", from, to, Optional.empty(),
                    0.9, 0.7, false, Optional.empty(), FactStatus.ACTIVE);
        }
    }
}

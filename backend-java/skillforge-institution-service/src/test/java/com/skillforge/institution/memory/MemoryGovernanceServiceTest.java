package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactReadBatch;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.MemoryPort.MemoryMutationResult;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.memory.MemorySourcePolicy;
import com.skillforge.runtime.memory.MemoryTypeRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** 通过真实 Runtime 核对宿主 CAS/领域错误映射；数据库与浏览器不由本单测替代。 */
class MemoryGovernanceServiceTest {
    private static final TenantId TENANT = new TenantId("101");
    private static final AgentId AGENT = new AgentId("102");
    private static final EndUserId USER = new EndUserId(7);
    private static final SessionRef SESSION = new SessionRef(TENANT, "103");
    private static final SessionOwner OWNER = SessionOwner.user(SESSION, AGENT, USER);
    private static final MemoryFactRef REF = new MemoryFactRef(TENANT, "104");
    private static final MemoryFactRef ORIGINAL_REF = new MemoryFactRef(TENANT, "105");
    private static final MemoryTypeRef TYPE = new MemoryTypeRef("preference");
    private static final RetentionClassRef RETENTION = new RetentionClassRef("standard");
    private static final EventId SOURCE = new EventId("source-memory-governance");
    private static final ResultRef ACCESS_AUDIT = new ResultRef("access-memory-governance");
    private static final ResultRef SOURCE_AUDIT = new ResultRef("source-audit-memory-governance");
    private static final ResultRef CONFLICT_AUDIT = new ResultRef("cas-audit-memory-governance");
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant ACCEPTED = Instant.parse("2026-01-01T01:00:00Z");
    private static final String DRAFT_JSON = "{\"value\":{\"text\":\"correction draft\"},\"conditions\":[],\"temporal\":{\"kind\":\"stable\",\"from\":null,\"to\":null}}";

    @Test
    void pageOmitsFactWhoseStatusChangedAfterCandidateSelection() {
        MemoryFact current = fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.FORGOTTEN);
        Fixture fixture = new Fixture(current);
        when(fixture.mapper.pageOwners(eq(TENANT.value()), eq("ACTIVE"), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(201))).thenReturn(List.of(
                new MemoryGovernanceMapper.PageOwner(REF.factId(), USER.value(), AGENT.value(),
                        SESSION.sessionId(), 0, CREATED.getEpochSecond(), CREATED.getNano())));
        when(fixture.memories.findCurrent(any(CurrentFactReadBatch.class))).thenReturn(List.of(Optional.of(current)));
        var page = fixture.service.page(TENANT.value(),
                new MemoryGovernanceService.ListQuery("ACTIVE", null, null, null, null, 100), null, null);
        assertEquals(List.of(), page.items());
        assertEquals(null, page.nextCursor());
    }

    /** 对已被遗忘的事实使用旧 revision 纠正，保留原草稿命令并采用 Port 的真实冲突类型。 */
    @Test
    void staleCorrectionPreservesDraftAndCurrentFactAndMapsConflictTo409() {
        MemoryFact current = fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.FORGOTTEN);
        Fixture fixture = new Fixture(current);
        fixture.conflict();

        ApiException rejected = assertThrows(ApiException.class, () -> fixture.correct(3));

        assertEquals(HttpStatus.CONFLICT, rejected.getStatus());
        assertEquals("Memory revision 已变化，当前 revision=4", rejected.getDetail());
        ArgumentCaptor<MemoryCommand> command = ArgumentCaptor.forClass(MemoryCommand.class);
        verify(fixture.memories).submit(command.capture());
        MemoryCommand.Supersede submitted = (MemoryCommand.Supersede) command.getValue();
        assertEquals(REF, submitted.current());
        assertEquals(3, submitted.expectedRevision());
        assertEquals("corrected predicate", submitted.replacement().predicate());
        assertEquals(MemoryContent.decode(DRAFT_JSON), MemoryContent.decode(submitted.replacement().object().canonicalJson()));
        assertEquals(Optional.of(REF), submitted.replacement().derivedFrom());
        assertEquals(current.sourceEventIds(), submitted.replacement().sourceEventIds());
        assertEquals(current, fixture.current);
        verify(fixture.log, never()).append(any());
    }

    /** 同一个遗忘状态下的 stale 状态迁移仍为 CAS 冲突，而同 revision 非法迁移保持领域拒绝。 */
    @Test
    void staleTransitionMaps409AndExactRevisionIllegalTransitionRemains422() {
        MemoryFact current = fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.FORGOTTEN);
        Fixture stale = new Fixture(current);
        stale.conflict();
        ApiException conflict = assertThrows(ApiException.class, () -> stale.service.transition(
                TENANT.value(), REF.factId(), 3, FactStatus.FORGOTTEN, "forget", "stale-forget"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        ArgumentCaptor<MemoryCommand> command = ArgumentCaptor.forClass(MemoryCommand.class);
        verify(stale.memories).submit(command.capture());
        assertEquals(3, ((MemoryCommand.Transition) command.getValue()).expectedRevision());
        verify(stale.log, never()).append(any());

        Fixture exact = new Fixture(current);
        ApiException domain = assertThrows(ApiException.class, () -> exact.service.transition(
                TENANT.value(), REF.factId(), 4, FactStatus.FORGOTTEN, "forget", "exact-forget"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, domain.getStatus());
        assertEquals("memory_transition_not_allowed", domain.getDetail());
        exact.assertNoWrites();
    }

    /** exact revision 不能纠正非 ACTIVE 事实；非 CAS Stopped 仍返回原 422。 */
    @Test
    void exactRevisionCorrectionOnForgottenFactRemainsDomainRejection() {
        Fixture fixture = new Fixture(fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.FORGOTTEN));
        ApiException rejected = assertThrows(ApiException.class, () -> fixture.correct(4));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
        assertEquals("memory_supersede_source_mismatch", rejected.getDetail());
        fixture.assertNoWrites();
    }

    /** stale revision 与 hostile 当前事实同时出现时，身份、scope、type、owner 拒绝优先。 */
    @Test
    void staleRevisionDoesNotTurnCurrentFactIdentityFailuresInto409() {
        MemoryFact current = fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.ACTIVE);
        List<MemoryFact> hostile = List.of(
                fact(new MemoryFactRef(TENANT, "105"), USER, MemoryScope.USER_AGENT, TYPE, FactStatus.ACTIVE),
                fact(REF, USER, MemoryScope.SESSION, TYPE, FactStatus.ACTIVE),
                fact(REF, USER, MemoryScope.USER_AGENT, new MemoryTypeRef("episode"), FactStatus.ACTIVE),
                fact(REF, new EndUserId(8), MemoryScope.USER_AGENT, TYPE, FactStatus.ACTIVE));
        List<String> reasons = List.of("memory_current_fact_mismatch", "memory_current_fact_mismatch",
                "memory_current_fact_mismatch", "memory_fact_owner_mismatch");
        for (int index = 0; index < hostile.size(); index++) {
            Fixture fixture = new Fixture(current);
            when(fixture.memories.findCurrent(any(PortCallContext.class), eq(REF)))
                    .thenReturn(Optional.of(current), Optional.of(hostile.get(index)));
            ApiException rejected = assertThrows(ApiException.class, () -> fixture.correct(3));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
            assertEquals(reasons.get(index), rejected.getDetail());
            fixture.assertNoWrites();
        }
    }

    /** stale revision 仍须经过来源策略与操作权限，拒绝不触及 Port 提交或 memory.written。 */
    @Test
    void staleRevisionPreservesSourceAndAccessPolicyRejections() {
        MemoryFact current = fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.ACTIVE);
        Fixture sourceDenied = new Fixture(current);
        when(sourceDenied.sources.authorize(any())).thenReturn(
                new MemorySourcePolicy.Decision.Denied("memory_source_denied", SOURCE_AUDIT));
        ApiException source = assertThrows(ApiException.class, () -> sourceDenied.correct(3));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, source.getStatus());
        assertEquals("memory_source_denied", source.getDetail());
        sourceDenied.assertNoWrites();

        Fixture accessDenied = new Fixture(current);
        when(accessDenied.access.authorize(any())).thenReturn(
                new MemoryAccessPolicy.Decision.Denied("memory_access_denied", ACCESS_AUDIT));
        ApiException access = assertThrows(ApiException.class, () -> accessDenied.correct(3));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, access.getStatus());
        assertEquals("memory_access_denied", access.getDetail());
        accessDenied.assertNoWrites();
    }

    /** 内容审核同意不能代替 USER 范围同意；缺失或错配范围均不得提交 mutation。 */
    @Test
    void pendingUserApprovalRequiresExplicitMatchingScopeWithoutWrites() {
        for (MemoryScope confirmed : new MemoryScope[] {null, MemoryScope.USER_AGENT, MemoryScope.SESSION}) {
            Fixture fixture = new Fixture(fact(REF, USER, MemoryScope.USER, TYPE, FactStatus.PENDING_CONFIRM));

            ApiException rejected = assertThrows(ApiException.class, () -> fixture.service.review(
                    TENANT.value(), REF.factId(), 4, true, confirmed, "scope-review-denied"));

            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
            assertEquals(confirmed == null ? "memory_user_scope_confirmation_required"
                    : "memory_review_scope_mismatch", rejected.getDetail());
            fixture.assertNoWrites();
        }
    }

    /** correct 与通用 transition 即使伪造 review reason，也不能跳过 USER 待确认边界。 */
    @Test
    void pendingUserCannotBeActivatedViaCorrectionOrGenericRestore() {
        for (long revision : List.of(3L, 4L)) {
            Fixture correction = new Fixture(fact(REF, USER, MemoryScope.USER, TYPE, FactStatus.PENDING_CONFIRM));
            ApiException corrected = assertThrows(ApiException.class, () -> correction.correct(revision));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, corrected.getStatus());
            assertEquals("memory_user_scope_confirmation_required", corrected.getDetail());
            correction.assertNoWrites();

            Fixture restore = new Fixture(fact(REF, USER, MemoryScope.USER, TYPE, FactStatus.PENDING_CONFIRM));
            ApiException restored = assertThrows(ApiException.class, () -> restore.service.transition(
                    TENANT.value(), REF.factId(), revision, FactStatus.ACTIVE,
                    "memory_user_scope_review_approved", "forged-review-reason"));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, restored.getStatus());
            assertEquals("memory_user_scope_confirmation_required", restored.getDetail());
            restore.assertNoWrites();
        }
    }

    /** 明确范围批准与无范围拒绝仍交给 Runtime/Port 做旧 revision 的 CAS 判定。 */
    @Test
    void explicitUserReviewAndScopelessRejectionPreserveCasAndCommandIdentity() {
        for (boolean approved : List.of(true, false)) {
            Fixture fixture = new Fixture(fact(REF, USER, MemoryScope.USER, TYPE, FactStatus.PENDING_CONFIRM));
            fixture.conflict();
            String key = approved ? "confirmed-user-review" : "reject-user-review";

            ApiException conflict = assertThrows(ApiException.class, () -> fixture.service.review(
                    TENANT.value(), REF.factId(), 3, approved, approved ? MemoryScope.USER : null, key));

            assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
            ArgumentCaptor<MemoryCommand> command = ArgumentCaptor.forClass(MemoryCommand.class);
            verify(fixture.memories).submit(command.capture());
            MemoryCommand.Transition submitted = (MemoryCommand.Transition) command.getValue();
            assertEquals(REF, submitted.fact());
            assertEquals(3, submitted.expectedRevision());
            assertEquals(key, submitted.idempotencyKey().value());
            assertEquals(approved ? FactStatus.ACTIVE : FactStatus.REJECTED, submitted.target());
            assertEquals(approved ? "memory_user_scope_review_approved" : "memory_review_rejected", submitted.reasonCode());
            verify(fixture.log, never()).append(any());
        }
    }

    /** 当前已 ACTIVE 时，同键旧 revision 的审核重投仍须到达 Port 原幂等/CAS 分支。 */
    @Test
    void reviewedUserReplayIsNotBlockedByAnAddedPendingOnlyPrecondition() {
        Fixture fixture = new Fixture(fact(REF, USER, MemoryScope.USER, TYPE, FactStatus.ACTIVE));
        fixture.conflict();

        ApiException conflict = assertThrows(ApiException.class, () -> fixture.service.review(
                TENANT.value(), REF.factId(), 3, true, MemoryScope.USER, "original-review-key"));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        ArgumentCaptor<MemoryCommand> command = ArgumentCaptor.forClass(MemoryCommand.class);
        verify(fixture.memories).submit(command.capture());
        assertEquals("original-review-key", ((MemoryCommand.Transition) command.getValue()).idempotencyKey().value());
        assertEquals(3, ((MemoryCommand.Transition) command.getValue()).expectedRevision());
        verify(fixture.log, never()).append(any());
    }

    /** 批准固定修订时激活已有提案，由真实 Runtime 校验双 CAS 回执并记入原来源。 */
    @Test
    void approvedRevisionProposalUsesAcceptRevisionAndReturnsExistingProposal() throws Exception {
        MemoryFact original = fact(ORIGINAL_REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.ACTIVE);
        MemoryFact proposal = revisionProposal(ORIGINAL_REF, 4, Optional.of(ORIGINAL_REF));
        Fixture fixture = new Fixture(proposal);
        fixture.revisionPair(original, proposal);
        MemoryFact activated = head(proposal, 5, FactStatus.ACTIVE);
        when(fixture.memories.submit(any())).thenReturn(new MemoryMutationResult.Applied(activated, Optional.of(original), false));
        fixture.appendSuccessfully();

        MemoryFact result = fixture.service.review(TENANT.value(), REF.factId(), 4, true,
                MemoryScope.USER_AGENT, "accept-existing-revision");

        assertEquals(activated, result);
        var commands = ArgumentCaptor.forClass(MemoryCommand.class);
        verify(fixture.memories).submit(commands.capture());
        MemoryCommand.AcceptRevision submitted = assertInstanceOf(MemoryCommand.AcceptRevision.class, commands.getValue());
        assertEquals(ORIGINAL_REF, submitted.current());
        assertEquals(4, submitted.expectedRevision());
        assertEquals(REF, submitted.proposal());
        assertEquals(4, submitted.expectedProposalRevision());
        assertEquals("accept-existing-revision", submitted.idempotencyKey().value());
        assertEquals(proposal.object(), result.object());
        assertEquals(proposal.sourceEventIds(), result.sourceEventIds());
        var events = ArgumentCaptor.forClass(AppendSessionEvent.class);
        verify(fixture.log).append(events.capture());
        assertEquals("ACCEPT_REVISION", new ObjectMapper().readTree(
                events.getValue().event().payload().canonicalJson()).path("operation").textValue());
    }

    /** 提案关联必须来自唯一准备记录；零条和多条都不能当作已准备完成。 */
    @Test
    void missingOrAmbiguousTypedRevisionBindingReturns503BeforeMutation() throws Exception {
        MemoryFact proposal = revisionProposal(ORIGINAL_REF, 4, Optional.of(ORIGINAL_REF));
        for (List<MemoryGovernanceMapper.RevisionTargetRow> bindings : List.of(
                List.<MemoryGovernanceMapper.RevisionTargetRow>of(),
                List.of(new MemoryGovernanceMapper.RevisionTargetRow(ORIGINAL_REF.factId(), 4),
                        new MemoryGovernanceMapper.RevisionTargetRow(ORIGINAL_REF.factId(), 4)))) {
            Fixture fixture = new Fixture(proposal);
            when(fixture.mapper.revisionTargets(TENANT.value(), REF.factId())).thenReturn(bindings);

            ApiException rejected = assertThrows(ApiException.class, () -> fixture.review("unprepared-revision"));

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getStatus());
            assertEquals("memory_revision_proposal_not_prepared", rejected.getDetail());
            fixture.assertNoWrites();
        }
    }

    /** payload、derivedFrom 和准备记录必须共同指向同一固定对象及版本。 */
    @Test
    void mismatchedRevisionBindingOrDerivedReferenceIsRejectedBeforeMutation() throws Exception {
        MemoryFact proposal = revisionProposal(ORIGINAL_REF, 4, Optional.of(ORIGINAL_REF));
        for (MemoryGovernanceMapper.RevisionTargetRow binding : List.of(
                new MemoryGovernanceMapper.RevisionTargetRow("other-target", 4),
                new MemoryGovernanceMapper.RevisionTargetRow(ORIGINAL_REF.factId(), 5))) {
            Fixture fixture = new Fixture(proposal);
            when(fixture.mapper.revisionTargets(TENANT.value(), REF.factId())).thenReturn(List.of(binding));
            assertInvalidProposal(fixture);
        }
        Fixture missingDerived = new Fixture(revisionProposal(ORIGINAL_REF, 4, Optional.empty()));
        assertInvalidProposal(missingDerived);
        Fixture wrongDerived = new Fixture(revisionProposal(ORIGINAL_REF, 4,
                Optional.of(new MemoryFactRef(TENANT, "different-derived-target"))));
        assertInvalidProposal(wrongDerived);
    }

    /** 待审修订只能审核原目标，不能借手工纠正创建第三个ACTIVE事实。 */
    @Test
    void pendingRevisionCannotBypassReviewThroughManualCorrection() throws Exception {
        Fixture fixture = new Fixture(revisionProposal(ORIGINAL_REF, 4, Optional.of(ORIGINAL_REF)));

        ApiException rejected = assertThrows(ApiException.class, () -> fixture.correct(4));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
        assertEquals("memory_revision_review_required", rejected.getDetail());
        fixture.assertNoWrites();
    }

    /** 手工纠正正文不继承传入的修订控制元数据，原关联只能由宿主准备链授予。 */
    @Test
    void manualCorrectionRemovesSuppliedRevisionProposalMetadata() throws Exception {
        Fixture fixture = new Fixture(fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.ACTIVE));
        fixture.conflict();
        String payload = MemoryContent.encode(MemoryContent.withRevisionProposal(MemoryContent.decode(DRAFT_JSON),
                ORIGINAL_REF.factId(), 9));

        ApiException conflict = assertThrows(ApiException.class, () -> fixture.service.correct(TENANT.value(),
                REF.factId(), 3, "user:7", "theme", payload, .8, .7, "manual-correction-metadata"));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        var commands = ArgumentCaptor.forClass(MemoryCommand.class);
        verify(fixture.memories).submit(commands.capture());
        MemoryCommand.Supersede command = assertInstanceOf(MemoryCommand.Supersede.class, commands.getValue());
        assertEquals(MemoryContent.decode(DRAFT_JSON), MemoryContent.decode(command.replacement().object().canonicalJson()));
        assertEquals(Optional.of(REF), command.replacement().derivedFrom());
        verify(fixture.log, never()).append(any());
    }

    /** 事实已写入但日志回执未知仍是可恢复的503，不能向调用方冒充审核已完成。 */
    @Test
    void reviewPendingSessionLogReturns503WithOriginalReason() {
        MemoryFact pending = fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.PENDING_CONFIRM);
        Fixture fixture = new Fixture(pending);
        when(fixture.memories.submit(any())).thenReturn(new MemoryMutationResult.Applied(
                head(pending, 5, FactStatus.ACTIVE), Optional.of(pending), false));
        when(fixture.log.append(any())).thenThrow(new IllegalStateException("connection-lost-after-append"));

        ApiException rejected = assertThrows(ApiException.class, () -> fixture.review("pending-review"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getStatus());
        assertEquals("memory_session_log_outcome_unknown", rejected.getDetail());
        verify(fixture.memories).submit(any());
        verify(fixture.log).append(any());
    }

    /** Port提交响应丢失不能回报422或自动换键，必须保留未知结果入口。 */
    @Test
    void reviewUnknownSubmissionReturns503WithoutInventingWrittenEvent() {
        Fixture fixture = new Fixture(fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.PENDING_CONFIRM));
        when(fixture.memories.submit(any())).thenThrow(new IllegalStateException("connection-lost-after-submit"));

        ApiException rejected = assertThrows(ApiException.class, () -> fixture.review("unknown-review"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getStatus());
        assertEquals("memory_submit_outcome_unknown", rejected.getDetail());
        verify(fixture.log, never()).append(any());
    }

    /** 日志响应丢失后从宿主重投审核，当前head推进也不得改变受理身份或原事件时间。 */
    @Test
    void duplicateReviewAfterHeadAdvanceKeepsReceiptIdentityAndOccurredAt() {
        MemoryFact pending = fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.PENDING_CONFIRM);
        MemoryFact active = head(pending, 5, FactStatus.ACTIVE);
        Fixture fixture = new Fixture(pending);
        fixture.frozenReceipt("persisted-review-key");
        when(fixture.memories.findCurrent(any(PortCallContext.class), eq(REF)))
                .thenReturn(Optional.of(pending), Optional.of(pending), Optional.of(active), Optional.of(active));
        when(fixture.memories.submit(any())).thenReturn(
                new MemoryMutationResult.Applied(active, Optional.of(pending), false),
                new MemoryMutationResult.Applied(active, Optional.of(pending), true));
        fixture.loseFirstAppendResponse();

        ApiException unknown = assertThrows(ApiException.class, () -> fixture.review("requested-review-key"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unknown.getStatus());
        assertEquals(active, fixture.review("requested-review-key"));

        assertStableRetries(fixture, "persisted-review-key");
    }

    /** 纠正重投保留相同replacement及原事件，不能因读取已归档head而重新生成身份。 */
    @Test
    void duplicateCorrectionAfterHeadAdvanceKeepsReceiptIdentityAndOccurredAt() {
        MemoryFact original = fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.ACTIVE);
        Fixture fixture = new Fixture(original);
        fixture.frozenReceipt("persisted-correction-key");
        when(fixture.memories.findCurrent(any(PortCallContext.class), eq(REF))).thenReturn(
                Optional.of(original), Optional.of(original),
                Optional.of(head(original, 5, FactStatus.ARCHIVED)), Optional.of(head(original, 5, FactStatus.ARCHIVED)));
        var output = new java.util.concurrent.atomic.AtomicReference<MemoryFact>();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(fixture.memories.submit(any())).thenAnswer(call -> {
            MemoryCommand.Supersede command = assertInstanceOf(MemoryCommand.Supersede.class, call.getArgument(0));
            MemoryFact replacement = created(new MemoryFactRef(TENANT, "corrected-fact"), command.replacement());
            output.set(replacement);
            return new MemoryMutationResult.Applied(replacement, Optional.of(original), attempts.getAndIncrement() > 0);
        });
        fixture.loseFirstAppendResponse();

        ApiException unknown = assertThrows(ApiException.class, () -> fixture.correct(4));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unknown.getStatus());
        MemoryFact corrected = fixture.correct(4);
        assertEquals(output.get(), corrected);
        assertEquals(new MemoryFactRef(TENANT, "corrected-fact"), corrected.ref());
        assertStableRetries(fixture, "persisted-correction-key");
    }

    private static void assertInvalidProposal(Fixture fixture) {
        ApiException rejected = assertThrows(ApiException.class, () -> fixture.review("invalid-revision"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
        assertEquals("memory_revision_proposal_invalid", rejected.getDetail());
        fixture.assertNoWrites();
    }

    /** deadline允许更新；受理身份、授权身份、命令与SessionLog的全部不可变字段必须一致。 */
    private static void assertStableRetries(Fixture fixture, String frozenKey) {
        var commands = ArgumentCaptor.forClass(MemoryCommand.class);
        verify(fixture.memories, times(2)).submit(commands.capture());
        List<MemoryCommand> submissions = commands.getAllValues();
        PortCallContext firstContext;
        PortCallContext secondContext;
        if (submissions.getFirst() instanceof MemoryCommand.Transition first) {
            MemoryCommand.Transition second = assertInstanceOf(MemoryCommand.Transition.class, submissions.getLast());
            assertEquals(frozenKey, first.idempotencyKey().value());
            assertEquals(first.idempotencyKey(), second.idempotencyKey());
            assertEquals(first.fact(), second.fact());
            assertEquals(first.expectedRevision(), second.expectedRevision());
            assertEquals(first.target(), second.target());
            assertEquals(first.reasonCode(), second.reasonCode());
            firstContext = first.context();
            secondContext = second.context();
        } else {
            MemoryCommand.Supersede first = assertInstanceOf(MemoryCommand.Supersede.class, submissions.getFirst());
            MemoryCommand.Supersede second = assertInstanceOf(MemoryCommand.Supersede.class, submissions.getLast());
            assertEquals(frozenKey, first.idempotencyKey().value());
            assertEquals(first.idempotencyKey(), second.idempotencyKey());
            assertEquals(first.current(), second.current());
            assertEquals(first.expectedRevision(), second.expectedRevision());
            assertEquals(first.replacement(), second.replacement());
            firstContext = first.context();
            secondContext = second.context();
        }
        assertEquals(firstContext.runIdentity(), secondContext.runIdentity());
        assertEquals(firstContext.identity(), secondContext.identity());
        var events = ArgumentCaptor.forClass(AppendSessionEvent.class);
        verify(fixture.log, times(2)).append(events.capture());
        assertEquals(events.getAllValues().getFirst(), events.getAllValues().getLast());
        assertEquals(ACCEPTED, events.getAllValues().getFirst().event().occurredAt());
        var requests = ArgumentCaptor.forClass(MemoryGovernanceRequest.class);
        verify(fixture.requests, times(2)).accept(requests.capture());
        assertEquals(requests.getAllValues().getFirst(), requests.getAllValues().getLast());
        assertEquals(TENANT.value(), requests.getAllValues().getFirst().tenantId());
        assertEquals(REF.factId(), requests.getAllValues().getFirst().factId());
        assertEquals(4L, requests.getAllValues().getFirst().expectedRevision());
        assertEquals(submissions.getFirst() instanceof MemoryCommand.Transition
                ? MemoryGovernanceRequest.Operation.REVIEW : MemoryGovernanceRequest.Operation.CORRECT,
                requests.getAllValues().getFirst().operation());
    }

    /** 修订标记由宿主添加，模型值本身不能提供准备记录或derivedFrom授权。 */
    private static MemoryFact revisionProposal(MemoryFactRef target, long expectedRevision,
            Optional<MemoryFactRef> derivedFrom) throws Exception {
        MemoryFact base = fact(REF, USER, MemoryScope.USER_AGENT, TYPE, FactStatus.PENDING_CONFIRM);
        String payload = MemoryContent.encode(MemoryContent.withRevisionProposal(MemoryContent.decode(DRAFT_JSON),
                target.factId(), expectedRevision));
        return new MemoryFact(base.ref(), base.owner(), base.type(), base.retentionClass(), base.schemaVersion(),
                base.subject(), base.predicate(), new TypedPayload(base.object().schemaId(), payload),
                base.sourceEventIds(), base.sourceSessionRef(), base.sourceExecutionRef(), base.exportPath(),
                base.extractorVersion(), base.revision(), base.createdAt(), base.validFrom(), base.validTo(),
                base.expiresAt(), base.confidence(), base.importance(), true, derivedFrom,
                Optional.empty(), base.decayScore(), base.status());
    }

    private static MemoryFact head(MemoryFact original, long revision, FactStatus status) {
        return new MemoryFact(original.ref(), original.owner(), original.type(), original.retentionClass(),
                original.schemaVersion(), original.subject(), original.predicate(), original.object(),
                original.sourceEventIds(), original.sourceSessionRef(), original.sourceExecutionRef(), original.exportPath(),
                original.extractorVersion(), revision, original.createdAt(), original.validFrom(), original.validTo(),
                original.expiresAt(), original.confidence(), original.importance(), original.reviewRequired(),
                original.derivedFrom(), original.supersededBy(), original.decayScore(), status);
    }

    /** 窄Port按正式Create/Supersede契约返回草稿全值，具体业务预期由上层用例单独断言。 */
    private static MemoryFact created(MemoryFactRef ref, MemoryFactDraft draft) {
        return new MemoryFact(ref, draft.owner(), draft.type(), draft.retentionClass(), draft.schemaVersion(),
                draft.subject(), draft.predicate(), draft.object(), draft.sourceEventIds(), draft.sourceSessionRef(),
                draft.sourceExecutionRef(), draft.exportPath(), draft.extractorVersion(), 1, ACCEPTED,
                draft.validFrom(), draft.validTo(), draft.expiresAt(), draft.confidence(), draft.importance(),
                draft.reviewRequired(), draft.derivedFrom(), Optional.empty(), 0, draft.initialStatus());
    }

    private static CommittedSessionEvent committed(AppendSessionEvent append) {
        var target = append.target();
        var event = append.event();
        return new CommittedSessionEvent(target.owner(), target.agentId(), target.inputId(), target.runRef(),
                target.executionRef(), target.actionRef(), target.delegationRef(), event.eventId(), 2,
                event.eventType(), event.eventVersion(), event.trace(), event.payload(), event.occurredAt());
    }

    /** 合法基线只改变被测当前事实的身份或状态，保留已提交来源和类型前提。 */
    private static MemoryFact fact(MemoryFactRef ref, EndUserId user, MemoryScope scope,
            MemoryTypeRef type, FactStatus status) {
        return new MemoryFact(
                ref,
                switch (scope) { case SESSION -> new MemoryOwner.Session(SESSION); case USER -> new MemoryOwner.User((ref).tenantId(), user); case USER_AGENT -> new MemoryOwner.UserAgent((ref).tenantId(), user, AGENT); default -> throw new IllegalArgumentException("personal fixture scope"); },
                type,
                RETENTION,
                "v2",
                "user:7",
                "theme",
                new TypedPayload("skillforge.memory.preference.v2", DRAFT_JSON),
                List.of(SOURCE),
                SESSION,
                Optional.empty(),
                Optional.empty(),
                "extractor-v1",
                4,
                CREATED,
                CREATED,
                Optional.empty(),
                Optional.empty(),
                0.8,
                0.7,
                status == FactStatus.PENDING_CONFIRM,
                Optional.empty(),
                Optional.empty(),
                0,
                status);
    }

    /** 宿主服务与 Runtime 为真实组件；提交回执、当前事实和已提交来源由窄 Port 提供。 */
    private static final class Fixture {
        private final MemoryFact current;
        private final JdbcMemoryPort memories = mock(JdbcMemoryPort.class);
        private final SessionLogPort log = mock(SessionLogPort.class);
        private final MemoryAccessPolicy access = mock(MemoryAccessPolicy.class);
        private final MemorySourcePolicy sources = mock(MemorySourcePolicy.class);
        private final MemoryGovernanceMapper mapper = mock(MemoryGovernanceMapper.class);
        private final MemoryGovernanceRequests requests = mock(MemoryGovernanceRequests.class);
        private final MemoryGovernanceService service;

        private Fixture(MemoryFact current) {
            this.current = current;
            when(mapper.owner(TENANT.value(), REF.factId())).thenReturn(new MemoryGovernanceMapper.Owner(
                    REF.factId(), USER.value(), AGENT.value(), SESSION.sessionId(), 0));
            when(memories.findCurrent(any(PortCallContext.class), eq(REF))).thenReturn(Optional.of(current));
            when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Allowed(ACCESS_AUDIT));
            when(sources.authorize(any())).thenReturn(new MemorySourcePolicy.Decision.Allowed(SOURCE_AUDIT));
            InputId input = new InputId("source-memory-governance");
            TraceEnvelope trace = new TraceEnvelope("source-trace", "source-span", input, Optional.empty());
            when(log.findByEvent(OWNER, SOURCE)).thenReturn(Optional.of(new CommittedSessionEvent(
                    OWNER, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    SOURCE, 1, "source.recorded", "1", trace, new TypedPayload("source-event", "{}"), CREATED)));
            MemoryTypeRegistry types = type -> Optional.of(new MemoryTypeRegistry.Registration(type,
                    Set.of(MemoryScope.USER_AGENT, MemoryScope.SESSION, MemoryScope.USER),
                    List.of(new MemoryTypeRegistry.Schema("v2", "skillforge.memory.preference.v2")), RETENTION,
                    false, false, (schema, payload) -> new MemoryTypeRegistry.Validation.Allowed()));
            MemoryRuntime runtime = new MemoryRuntime(memories, log, access, types, sources,
                    memories::findCurrent, Clock.systemUTC());
            when(requests.accept(any(MemoryGovernanceRequest.class))).thenAnswer(call -> {
                MemoryGovernanceRequest request = call.getArgument(0);
                return new MemoryGovernanceRequests.Receipt(request.key(), request.fingerprint(), CREATED);
            });
            service = new MemoryGovernanceService(mapper, memories, runtime, requests);
        }

        private void conflict() {
            when(memories.submit(any())).thenReturn(new MemoryMutationResult.Conflict(REF, 4, CONFLICT_AUDIT));
        }

        private void revisionPair(MemoryFact original, MemoryFact proposal) {
            when(mapper.revisionTargets(TENANT.value(), proposal.ref().factId())).thenReturn(
                    List.of(new MemoryGovernanceMapper.RevisionTargetRow(original.ref().factId(), original.revision())));
            when(memories.findCurrent(any(CurrentFactReadBatch.class)))
                    .thenReturn(List.of(Optional.of(original), Optional.of(proposal)));
        }

        private void appendSuccessfully() {
            when(log.append(any())).thenAnswer(call -> new SessionLogPort.AppendResult.Appended(
                    committed(call.getArgument(0))));
        }

        private void loseFirstAppendResponse() {
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            when(log.append(any())).thenAnswer(call -> {
                if (attempts.getAndIncrement() == 0) throw new IllegalStateException("connection-lost-after-append");
                return new SessionLogPort.AppendResult.Replayed(committed(call.getArgument(0)));
            });
        }

        private void frozenReceipt(String key) {
            doAnswer(call -> {
                MemoryGovernanceRequest request = call.getArgument(0);
                return new MemoryGovernanceRequests.Receipt(key, request.fingerprint(), ACCEPTED);
            }).when(requests).accept(any(MemoryGovernanceRequest.class));
        }

        private MemoryFact review(String key) {
            return service.review(TENANT.value(), REF.factId(), 4, true, MemoryScope.USER_AGENT, key);
        }

        private MemoryFact correct(long revision) {
            return service.correct(TENANT.value(), REF.factId(), revision, "user:7", "corrected predicate",
                    DRAFT_JSON, 0.8, 0.7, "governance-correction");
        }

        private void assertNoWrites() {
            verify(memories, never()).submit(any());
            verify(log, never()).append(any());
        }
    }
}

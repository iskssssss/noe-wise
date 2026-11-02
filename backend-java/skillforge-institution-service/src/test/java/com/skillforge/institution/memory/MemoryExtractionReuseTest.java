package com.skillforge.institution.memory;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.memory.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 自动精确复用必须重复通过类型、读写访问及已提交来源授权，策略拒绝不能抵达存储。 */
class MemoryExtractionReuseTest {
    private final JdbcMemoryPort memories = mock(JdbcMemoryPort.class);
    private final MemoryAccessPolicy access = mock(MemoryAccessPolicy.class);
    private final MemorySourcePolicy sources = mock(MemorySourcePolicy.class);
    private final MemoryTypeRegistry types = mock(MemoryTypeRegistry.class);
    private final SessionLogPort log = mock(SessionLogPort.class);
    private final MemoryExtractionReuse reuse = new MemoryExtractionReuse(memories, access, sources, types, log);
    private final TenantId tenant = new TenantId("1");
    private final SessionRef session = new SessionRef(tenant, "source-session");
    private final AgentId agent = new AgentId("agent");
    private final EndUserId user = new EndUserId(7);
    private final MemoryTypeRef type = new MemoryTypeRef("preference");
    private final ResultRef audit = new ResultRef("test-access-audit");
    private final PortCallContext context = context();
    private final MemoryFactDraft draft = draft();

    @Test
    void journalReadFailureCannotBeMisreadAsMissingOriginalMutation() {
        var key = new IdempotencyKey("original-command");
        when(memories.hasRecordedMutation(context, key)).thenThrow(new IllegalArgumentException("unavailable-journal"));
        assertEquals("memory_extraction_journal_read_failed", assertThrows(IllegalStateException.class,
                () -> reuse.hasRecordedMutation(context, key)).getMessage());
        verifyNoInteractions(access, sources, log);
    }

    @Test
    void frozenAliasReadsOnlyItsImmutableRevisionAndRechecksModelDisclosurePermission() {
        var alias = new MemoryExtractionMaterial.FactAlias("f0", "original-fact", 3);
        var ref = new MemoryFactRef(tenant, alias.factId());
        var original = mock(MemoryFact.class);
        when(original.ref()).thenReturn(ref); when(original.revision()).thenReturn(3L);
        when(original.scope()).thenReturn(MemoryScope.USER_AGENT); when(original.owner()).thenReturn(draft.owner());
        when(original.type()).thenReturn(type);
        when(memories.findRevision(context, ref, 3)).thenReturn(Optional.of(original));
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Allowed(audit));
        assertSame(original, reuse.modelReference(context, alias));
        verify(memories).findRevision(context, ref, 3);
        verifyNoMoreInteractions(memories);
        verify(access).authorize(argThat(request -> request.operation() == MemoryAccessPolicy.Operation.QUERY
                && request.owner().equals(draft.owner()) && request.factRef().isEmpty()));
    }

    @Test
    void missingImmutableRevisionCannotFallBackToCurrentHead() {
        var alias = new MemoryExtractionMaterial.FactAlias("f0", "original-fact", 3);
        var ref = new MemoryFactRef(tenant, alias.factId());
        when(memories.findRevision(context, ref, 3)).thenReturn(Optional.empty());
        assertEquals("memory_relation_target_missing", assertThrows(IllegalArgumentException.class,
                () -> reuse.reference(context, alias)).getMessage());
        verify(memories).findRevision(context, ref, 3);
        verifyNoMoreInteractions(memories);
        verifyNoInteractions(access);
    }

    @Test
    void relatedSnapshotsCannotReadStorageWithoutCurrentQueryPermission() {
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Denied("read-denied", audit));
        assertTrue(reuse.related(context, user.value(), "user:7").isEmpty());
        verifyNoInteractions(memories, log, sources);
        verify(access, times(4)).authorize(argThat(request -> request.operation() == MemoryAccessPolicy.Operation.QUERY));
    }

    @Test
    void existingFactSnapshotsUseValidQueryPolicyAndKeepAuthoritativeOwnerAndTypeChecks() {
        var fact = new MemoryFact(new MemoryFactRef(tenant, "existing"), draft.owner(), draft.type(), draft.retentionClass(),
                draft.schemaVersion(), draft.subject(), draft.predicate(), draft.object(), draft.sourceEventIds(),
                draft.sourceSessionRef(), draft.sourceExecutionRef(), draft.exportPath(), draft.extractorVersion(), 1,
                Instant.EPOCH, draft.validFrom(), draft.validTo(), draft.expiresAt(), draft.confidence(), draft.importance(),
                false, Optional.empty(), Optional.empty(), 1, FactStatus.ACTIVE);
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Allowed(audit));
        when(memories.relatedCurrentFacts(eq(context), any(MemoryOwner.class), any(MemoryTypeRef.class), eq("user:7"), eq(64)))
                .thenReturn(List.of(fact));
        assertEquals(List.of(fact), reuse.related(context, user.value(), "user:7"));
        verify(access, times(5)).authorize(argThat(request -> request.operation() == MemoryAccessPolicy.Operation.QUERY
                && request.factRef().isEmpty()));
    }

    @Test
    void sourceDeniedDoesNotReachStorageEvenWhenCreateAndQueryAreAllowed() {
        permitType();
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Allowed(audit));
        var source = committedSource();
        when(log.findByEvent(context.runIdentity().owner(), new EventId("source-event"))).thenReturn(Optional.of(source));
        when(sources.authorize(any())).thenReturn(new MemorySourcePolicy.Decision.Denied("denied", audit));
        assertEquals("memory_extraction_reuse_source_denied", assertThrows(IllegalArgumentException.class,
                () -> reuse.reuseOrCreate(context, draft, existing -> "unsafe")).getMessage());
        verifyNoInteractions(memories);
    }

    /** USER ACTIVE 复用也必须携带原草稿经过相同来源策略；拒绝不读存储也不执行创建回调。 */
    @Test
    void activeUserReuseCannotBypassSourceScopeDenial() {
        permitType();
        MemoryFactDraft userDraft = draft(MemoryScope.USER);
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Allowed(audit));
        var source = committedSource();
        when(log.findByEvent(context.runIdentity().owner(), new EventId("source-event")))
                .thenReturn(Optional.of(source));
        when(sources.authorize(any())).thenReturn(new MemorySourcePolicy.Decision.Denied(
                "memory_user_scope_review_required", audit));
        var callbackReached = new java.util.concurrent.atomic.AtomicBoolean();

        assertEquals("memory_extraction_reuse_source_denied", assertThrows(IllegalArgumentException.class,
                () -> reuse.reuseOrCreate(context, userDraft, existing -> {
                    callbackReached.set(true);
                    return "unsafe";
                })).getMessage());

        assertFalse(callbackReached.get());
        verify(sources).authorize(argThat(request -> request.context().equals(context)
                && request.draft().equals(userDraft) && request.sources().equals(List.of(source))));
        verifyNoInteractions(memories);
    }

    @Test
    void userWritePermissionCannotReplaceReadPermissionForReuse() {
        permitType();
        when(access.authorize(any())).thenAnswer(call -> {
            var request = call.getArgument(0, MemoryAccessPolicy.Request.class);
            return request.operation() == MemoryAccessPolicy.Operation.CREATE
                    ? new MemoryAccessPolicy.Decision.Allowed(audit)
                    : new MemoryAccessPolicy.Decision.Denied("read-denied", audit);
        });
        assertEquals("memory_extraction_reuse_read_denied", assertThrows(IllegalArgumentException.class,
                () -> reuse.reuseOrCreate(context, draft, existing -> "unsafe")).getMessage());
        verifyNoInteractions(memories, log, sources);
    }

    @Test
    @SuppressWarnings("unchecked")
    void allowedExactSourcePassesAuditReferencesAndFreezesOriginalTime() {
        permitType();
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Allowed(audit));
        var source = committedSource();
        when(log.findByEvent(context.runIdentity().owner(), new EventId("source-event"))).thenReturn(Optional.of(source));
        when(sources.authorize(any())).thenReturn(new MemorySourcePolicy.Decision.Allowed(new ResultRef("source-audit")));
        when(memories.reuseOrCreateExtractedPreference(eq(context), eq(draft), eq(audit), eq(new ResultRef("source-audit")), any()))
                .thenAnswer(call -> ((Function<Optional<MemoryFact>, String>) call.getArgument(4)).apply(Optional.empty()));
        assertEquals("create", reuse.reuseOrCreate(context, draft, existing -> existing.isEmpty() ? "create" : "reused"));
        assertEquals(Instant.EPOCH, reuse.sourceTime(context, new EventId("source-event")));
        verify(access, times(2)).authorize(any());
        verify(sources).authorize(argThat(request -> request.sources().equals(List.of(source)) && request.draft().equals(draft)));
    }

    /** 明确注册生产所需scope/schema，不能通过缺省类型容忍进入复用。 */
    private void permitType() {
        when(types.find(type)).thenReturn(Optional.of(new MemoryTypeRegistry.Registration(type, Set.of(MemoryScope.USER_AGENT, MemoryScope.USER),
                List.of(new MemoryTypeRegistry.Schema("v2", "skillforge.memory.preference.v2")), new RetentionClassRef("standard"),
                false, false, (version, value) -> new MemoryTypeRegistry.Validation.Allowed())));
    }

    /** 测试只模拟已经由SessionLog精确读取的envelope，不代表模型或真实数据库验证。 */
    private CommittedSessionEvent committedSource() {
        var source = mock(CommittedSessionEvent.class);
        when(source.owner()).thenReturn(context.runIdentity().owner());
        when(source.eventId()).thenReturn(new EventId("source-event"));
        when(source.eventType()).thenReturn("inbound.message");
        when(source.executionRef()).thenReturn(Optional.empty());
        when(source.sessionSequence()).thenReturn(1L);
        when(source.occurredAt()).thenReturn(Instant.EPOCH);
        return source;
    }

    /** 当前actor与来源owner相同，并显式拥有extract权限。 */
    private PortCallContext context() {
        var owner = SessionOwner.user(session, agent, user);
        var input = new InputId("input");
        var run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("causation"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant, "memory-extractor"), owner,
                Set.of("memory:extract"), 1, new ResultRef("verified"));
        return new PortCallContext(run, identity, new InvocationControl(Instant.parse("2100-01-01T00:00:00Z"), () -> false));
    }

    /** 无导出、未要求审核的精确偏好草稿；不同scope由各自类型/访问策略另行决定。 */
    private MemoryFactDraft draft() {
        return draft(MemoryScope.USER_AGENT);
    }

    /** USER 的 agent 为空，其来源仍是同一已提交 owner Session。 */
    private MemoryFactDraft draft(MemoryScope scope) {
        return new MemoryFactDraft(
                switch (scope) { case SESSION -> new MemoryOwner.Session(session); case USER -> new MemoryOwner.User(session.tenantId(), user); case USER_AGENT -> new MemoryOwner.UserAgent(session.tenantId(), user, agent); default -> throw new IllegalArgumentException("personal fixture scope"); },
                type,
                new RetentionClassRef("standard"),
                "v2",
                "user:7",
                "theme",
                new TypedPayload("skillforge.memory.preference.v2", "{\"value\":{\"text\":\"dark\"},\"conditions\":[],\"temporal\":{\"kind\":\"stable\",\"from\":null,\"to\":null}}"),
                List.of(new EventId("source-event")),
                session,
                Optional.empty(),
                Optional.empty(),
                "memory-extractor-v2",
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty(),
                0.8,
                0.5,
                false,
                Optional.empty(),
                FactStatus.ACTIVE);
    }
}

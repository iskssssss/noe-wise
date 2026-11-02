package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.PrincipalRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 验证 Memory Port 的身份闭包、草稿不变量、CAS 命令和失败重试语义。 */
class MemoryPortContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final TenantId OTHER_TENANT = new TenantId("tenant-2");
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final EndUserId USER = new EndUserId(7);
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final InputId INPUT = new InputId("input-1");
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final PortCallContext CONTEXT = context();

    /** 验证 Memory Port 的公开方法集合与返回类型精确一致。 */
    @Test
    void exposesExactMemoryPortMethods() throws NoSuchMethodException {
        Set<String> methodNames = Arrays.stream(MemoryPort.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("query", "submit", "findCurrent"), methodNames);
        assertEquals(MemoryPort.MemoryQueryOutcome.class,
                MemoryPort.class.getMethod(
                        "query", MemoryPort.MemoryQueryCall.class).getReturnType());
        assertEquals(MemoryPort.MemoryMutationResult.class,
                MemoryPort.class.getMethod(
                        "submit", MemoryPort.MemoryCommand.class).getReturnType());
        assertEquals(List.class, MemoryPort.class.getMethod(
                "findCurrent", MemoryPort.CurrentFactReadBatch.class).getReturnType());
    }

    /** 验证 Memory Port 所有 record 字段、顺序与泛型 ABI 精确匹配主文档。 */
    @Test
    void freezesMemoryPortRecordAbi() {
        assertEquals(List.of("context:PortCallContext", "fact:MemoryFactRef"),
                componentSignatures(MemoryPort.CurrentFactRead.class));
        assertEquals(List.of("reads:java.util.List<CurrentFactRead>"),
                componentSignatures(MemoryPort.CurrentFactReadBatch.class));
        assertEquals(List.of("context:PortCallContext", "query:MemoryQuery"),
                componentSignatures(MemoryPort.MemoryQueryCall.class));
        assertEquals(List.of(
                        "owner:MemoryOwner",
                        "type:MemoryTypeRef", "retentionClass:RetentionClassRef",
                        "schemaVersion:String", "subject:String", "predicate:String",
                        "object:TypedPayload", "sourceEventIds:java.util.List<EventId>",
                        "sourceSessionRef:SessionRef",
                        "sourceExecutionRef:java.util.Optional<ExecutionRef>",
                        "exportPath:java.util.Optional<String>", "extractorVersion:String",
                        "validFrom:java.time.Instant", "validTo:java.util.Optional<java.time.Instant>",
                        "expiresAt:java.util.Optional<java.time.Instant>", "confidence:double",
                        "importance:double", "reviewRequired:boolean",
                        "derivedFrom:java.util.Optional<MemoryFactRef>", "initialStatus:FactStatus"),
                componentSignatures(MemoryPort.MemoryFactDraft.class));
        assertEquals(List.of(
                        "queryId:String", "candidates:java.util.List<MemoryFact>",
                        "resultRef:ResultRef", "evaluatedAt:java.time.Instant", "diagnostics:MemoryRetrievalDiagnostics"),
                componentSignatures(MemoryPort.MemoryQueryResult.class));
        assertEquals(List.of("result:MemoryQueryResult"),
                componentSignatures(MemoryPort.MemoryQueryOutcome.Retrieved.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(MemoryPort.MemoryQueryOutcome.Failed.class));
        assertEquals(List.of(
                        "context:PortCallContext", "idempotencyKey:IdempotencyKey",
                        "fact:MemoryFactDraft"),
                componentSignatures(MemoryPort.MemoryCommand.Create.class));
        assertEquals(List.of(
                        "context:PortCallContext", "idempotencyKey:IdempotencyKey",
                        "current:MemoryFactRef", "expectedRevision:long",
                        "replacement:MemoryFactDraft"),
                componentSignatures(MemoryPort.MemoryCommand.Supersede.class));
        assertEquals(List.of(
                        "context:PortCallContext", "idempotencyKey:IdempotencyKey",
                        "fact:MemoryFactRef", "expectedRevision:long", "target:FactStatus",
                        "reasonCode:String"),
                componentSignatures(MemoryPort.MemoryCommand.Transition.class));
        assertEquals(List.of("context:PortCallContext", "idempotencyKey:IdempotencyKey",
                        "current:MemoryFactRef", "expectedRevision:long", "proposal:MemoryFactRef", "expectedProposalRevision:long"),
                componentSignatures(MemoryPort.MemoryCommand.AcceptRevision.class));
        assertEquals(List.of("fact:MemoryFact", "before:java.util.Optional<MemoryFact>", "replayed:boolean"),
                componentSignatures(MemoryPort.MemoryMutationResult.Applied.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(MemoryPort.MemoryMutationResult.Rejected.class));
        assertEquals(List.of(
                        "fact:MemoryFactRef", "currentRevision:long", "auditRef:ResultRef"),
                componentSignatures(MemoryPort.MemoryMutationResult.Conflict.class));
        assertEquals(Set.of(
                        MemoryPort.MemoryQueryOutcome.Retrieved.class,
                        MemoryPort.MemoryQueryOutcome.Failed.class),
                Set.of(MemoryPort.MemoryQueryOutcome.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                        MemoryPort.MemoryCommand.Create.class,
                        MemoryPort.MemoryCommand.Supersede.class,
                        MemoryPort.MemoryCommand.Transition.class,
                        MemoryPort.MemoryCommand.AcceptRevision.class),
                Set.of(MemoryPort.MemoryCommand.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                        MemoryPort.MemoryMutationResult.Applied.class,
                        MemoryPort.MemoryMutationResult.Rejected.class,
                        MemoryPort.MemoryMutationResult.Conflict.class),
                Set.of(MemoryPort.MemoryMutationResult.class.getPermittedSubclasses()));
    }

    @Test
    void currentBatchPreservesDuplicatesButRejectsTenantDriftAndOversizedInput() {
        var read = new MemoryPort.CurrentFactRead(CONTEXT, new MemoryFactRef(TENANT, "current"));
        var mutable = new ArrayList<>(List.of(read, read));
        var batch = new MemoryPort.CurrentFactReadBatch(mutable);
        mutable.clear();
        assertEquals(List.of(read, read), batch.reads());
        assertThrows(UnsupportedOperationException.class, () -> batch.reads().clear());
        assertEquals(List.of(), new MemoryPort.CurrentFactReadBatch(List.of()).reads());
        assertEquals(200, new MemoryPort.CurrentFactReadBatch(java.util.Collections.nCopies(200, read)).reads().size());
        assertThrows(IllegalArgumentException.class, () ->
                new MemoryPort.CurrentFactReadBatch(java.util.Collections.nCopies(201, read)));
        assertThrows(NullPointerException.class, () -> new MemoryPort.CurrentFactReadBatch(Arrays.asList(read, null)));
        assertThrows(IllegalArgumentException.class, () ->
                new MemoryPort.CurrentFactRead(CONTEXT, new MemoryFactRef(OTHER_TENANT, "foreign")));

        SessionRef foreignSession = new SessionRef(OTHER_TENANT, "foreign-session");
        SessionOwner foreignOwner = SessionOwner.user(foreignSession, AGENT, USER);
        var foreignRun = new RunIdentity(foreignOwner, foreignOwner.target().requireAgent(), INPUT, new RunRef(OTHER_TENANT, "foreign-run"), CONTEXT.runIdentity().trace(), new ResultRef("foreign-causation"), new com.skillforge.runtime.api.RunRequester.EndUser(foreignOwner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(foreignOwner.sessionRef().tenantId(), INPUT.value()));
        var foreignContext = new PortCallContext(foreignRun,
                new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(OTHER_TENANT, USER), foreignOwner,
                        Set.of("runtime:memory"), 1, new ResultRef("foreign-verification")), CONTEXT.control());
        var foreignRead = new MemoryPort.CurrentFactRead(foreignContext, new MemoryFactRef(OTHER_TENANT, "foreign"));
        assertThrows(IllegalArgumentException.class, () ->
                new MemoryPort.CurrentFactReadBatch(List.of(read, foreignRead)));
    }

    /** 验证 Memory 查询必须与当前租户、用户、Agent 和 Session 完全一致。 */
    @Test
    void validatesMemoryQueryOwnerClosure() {
        MemoryQuery valid = query(TENANT, USER, AGENT, Optional.of(SESSION),
                Set.of(MemoryScope.SESSION));
        assertEquals(valid, new MemoryPort.MemoryQueryCall(CONTEXT, valid).query());
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryQueryCall(
                CONTEXT,
                query(TENANT, new EndUserId(8), AGENT, Optional.empty(),
                        Set.of(MemoryScope.USER_AGENT))));
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryQueryCall(
                CONTEXT,
                query(TENANT, USER, AGENT,
                        Optional.of(new SessionRef(TENANT, "session-2")),
                        Set.of(MemoryScope.SESSION))));
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryQueryCall(
                CONTEXT,
                query(OTHER_TENANT, USER, AGENT, Optional.empty(),
                        Set.of(MemoryScope.USER_AGENT))));
    }

    /** 验证草稿来源列表防御复制且 SESSION/USER_AGENT 绑定与初始状态 fail closed。 */
    @Test
    void validatesMemoryFactDraftBoundary() {
        ArrayList<EventId> mutableEvents = new ArrayList<>(List.of(new EventId("event-1")));
        MemoryPort.MemoryFactDraft sessionDraft = draft(
                USER, AGENT, Optional.of(SESSION), MemoryScope.SESSION,
                mutableEvents, SESSION, Optional.empty(), Optional.empty(), FactStatus.ACTIVE,
                0.8, 0.7);
        mutableEvents.add(new EventId("event-2"));

        assertEquals(List.of(new EventId("event-1")), sessionDraft.sourceEventIds());
        assertNotSame(mutableEvents, sessionDraft.sourceEventIds());
        assertThrows(UnsupportedOperationException.class,
                () -> sessionDraft.sourceEventIds().add(new EventId("event-3")));
        assertThrows(IllegalArgumentException.class, () -> draft(
                USER, AGENT, Optional.empty(), MemoryScope.SESSION,
                List.of(new EventId("event-1")), SESSION,
                Optional.empty(), Optional.empty(), FactStatus.ACTIVE, 0.8, 0.7));
        assertThrows(IllegalArgumentException.class, () -> draft(
                USER, AGENT, Optional.of(SESSION), MemoryScope.USER_AGENT,
                List.of(new EventId("event-1")), SESSION,
                Optional.empty(), Optional.empty(), FactStatus.ACTIVE, 0.8, 0.7));
        assertThrows(IllegalArgumentException.class, () -> draft(
                USER, AGENT, Optional.empty(), MemoryScope.USER_AGENT,
                List.of(), SESSION,
                Optional.empty(), Optional.empty(), FactStatus.ACTIVE, 0.8, 0.7));
        assertThrows(IllegalArgumentException.class, () -> draft(
                USER, AGENT, Optional.empty(), MemoryScope.USER_AGENT,
                List.of(new EventId("event-1")), SESSION,
                Optional.empty(), Optional.empty(), FactStatus.ARCHIVED, 0.8, 0.7));
        assertThrows(IllegalArgumentException.class, () -> draft(
                USER, AGENT, Optional.empty(), MemoryScope.USER_AGENT,
                List.of(new EventId("event-1")), SESSION,
                Optional.of(new ExecutionRef(OTHER_TENANT, "execution-1")),
                Optional.empty(), FactStatus.ACTIVE, 0.8, 0.7));
        assertThrows(IllegalArgumentException.class, () -> draft(
                USER, AGENT, Optional.empty(), MemoryScope.USER_AGENT,
                List.of(new EventId("event-1")), SESSION,
                Optional.empty(), Optional.of(new MemoryFactRef(OTHER_TENANT, "fact-1")),
                FactStatus.ACTIVE, Double.NaN, 0.7));
        MemoryPort.MemoryFactDraft userDraft = new MemoryPort.MemoryFactDraft(testOwner((SESSION).tenantId(), USER, Optional.empty(), Optional.empty(), MemoryScope.USER),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "likes",
                new TypedPayload("memory-fact", "{}"),
                List.of(new EventId("event-1")),
                SESSION,
                Optional.empty(),
                Optional.empty(),
                "extractor-v1",
                NOW,
                Optional.empty(),
                Optional.empty(),
                0.8,
                0.7,
                false,
                Optional.empty(),
                FactStatus.ACTIVE);
        assertEquals(new MemoryOwner.User(TENANT, USER), userDraft.owner());
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryFactDraft(testOwner((SESSION).tenantId(), USER, Optional.of(AGENT), Optional.empty(), MemoryScope.USER),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "likes",
                new TypedPayload("memory-fact", "{}"),
                List.of(new EventId("event-1")),
                SESSION,
                Optional.empty(),
                Optional.empty(),
                "extractor-v1",
                NOW,
                Optional.empty(),
                Optional.empty(),
                0.8,
                0.7,
                false,
                Optional.empty(),
                FactStatus.ACTIVE));
    }

    /** 验证 Create/Supersede/Transition 命令绑定当前 owner、租户与正 revision。 */
    @Test
    void validatesMemoryCommands() {
        MemoryPort.MemoryFactDraft validDraft = userAgentDraft(USER, AGENT, SESSION);
        MemoryPort.MemoryCommand.Create create = new MemoryPort.MemoryCommand.Create(
                CONTEXT, new IdempotencyKey("idem-create"), validDraft);
        assertEquals(validDraft, create.fact());
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryCommand.Create(
                CONTEXT, new IdempotencyKey("idem-create"),
                userAgentDraft(new EndUserId(8), AGENT, SESSION)));
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryCommand.Create(
                CONTEXT, new IdempotencyKey("idem-create"),
                userAgentDraft(USER, AGENT, new SessionRef(TENANT, "session-2"))));
        MemoryPort.MemoryCommand.Create userCreate = new MemoryPort.MemoryCommand.Create(
                CONTEXT, new IdempotencyKey("idem-create-user"),
                new MemoryPort.MemoryFactDraft(testOwner((SESSION).tenantId(), USER, Optional.empty(), Optional.empty(), MemoryScope.USER),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "likes",
                new TypedPayload("memory-fact", "{}"),
                List.of(new EventId("event-1")),
                SESSION,
                Optional.empty(),
                Optional.empty(),
                "extractor-v1",
                NOW,
                Optional.empty(),
                Optional.empty(),
                0.8,
                0.7,
                false,
                Optional.empty(),
                FactStatus.ACTIVE));
        assertEquals(MemoryScope.USER, userCreate.fact().scope());
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryCommand.Supersede(
                CONTEXT, new IdempotencyKey("idem-supersede"),
                new MemoryFactRef(OTHER_TENANT, "fact-1"), 1, validDraft));
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryCommand.Supersede(
                CONTEXT, new IdempotencyKey("idem-supersede"),
                new MemoryFactRef(TENANT, "fact-1"), 0, validDraft));
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryCommand.Transition(
                CONTEXT, new IdempotencyKey("idem-transition"),
                new MemoryFactRef(TENANT, "fact-1"), 1, FactStatus.ARCHIVED, " "));
    }

    /** 验证 Memory 读取失败只能使用 NONE 或稳定键幂等重试。 */
    @Test
    void validatesMemoryQueryFailureRetryModes() {
        PortFailure idempotentFailure = new PortFailure(
                "store_unavailable", RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                new ResultRef("failure-1"));
        assertEquals(RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                new MemoryPort.MemoryQueryOutcome.Failed(idempotentFailure)
                        .failure().retryMode());
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryPort.MemoryQueryOutcome.Failed(new PortFailure(
                        "store_unavailable", RetryMode.RESULT_QUERYABLE,
                        new ResultRef("failure-1"))));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryPort.MemoryMutationResult.Conflict(
                        new MemoryFactRef(TENANT, "fact-1"), 0,
                        new ResultRef("audit-1")));
    }

    /** 遗忘与审核拒绝是独立治理动作；待确认来源也保留正式 FORGOTTEN 命令。 */
    @Test
    void preservesExplicitForgottenTargetWithoutMappingToReviewRejection() {
        var command = new MemoryPort.MemoryCommand.Transition(CONTEXT,
                new IdempotencyKey("forget-pending-fact"), new MemoryFactRef(TENANT, "pending-fact"),
                1, FactStatus.FORGOTTEN, "owner-forget");
        assertEquals(FactStatus.FORGOTTEN, command.target());
        assertEquals("owner-forget", command.reasonCode());
    }

    /** 验证查询候选按稳定 MemoryFactRef 去重而不是按完整 revision 记录去重。 */
    @Test
    void rejectsDuplicateCandidateFactIdentityAcrossRevisions() {
        MemoryFact firstRevision = memoryFact(new MemoryFactRef(TENANT, "fact-1"), 1);
        MemoryFact secondRevision = memoryFact(new MemoryFactRef(TENANT, "fact-1"), 2);

        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryQueryResult("query-1",
                List.of(firstRevision, secondRevision),
                new ResultRef("query-result-1"),
                NOW,
                testDiagnostics(List.of(firstRevision, secondRevision))));
    }

    @Test
    void acceptanceRequiresDistinctSameTenantReferencesAndTwoPositiveVersions() {
        MemoryFactRef current = new MemoryFactRef(TENANT, "current");
        MemoryFactRef proposal = new MemoryFactRef(TENANT, "proposal");
        var command = new MemoryPort.MemoryCommand.AcceptRevision(CONTEXT, new IdempotencyKey("accept"), current, 2, proposal, 3);
        assertEquals(proposal, command.proposal());
        assertEquals(3, command.expectedProposalRevision());
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryCommand.AcceptRevision(CONTEXT,
                new IdempotencyKey("same"), current, 1, current, 1));
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryCommand.AcceptRevision(CONTEXT,
                new IdempotencyKey("zero"), current, 0, proposal, 1));
        assertThrows(IllegalArgumentException.class, () -> new MemoryPort.MemoryCommand.AcceptRevision(CONTEXT,
                new IdempotencyKey("foreign"), current, 1, new MemoryFactRef(OTHER_TENANT, "proposal"), 1));
    }

    /** 构造测试使用的 MemoryQuery。 */
    private static MemoryQuery query(
            TenantId tenantId,
            EndUserId endUserId,
            AgentId agentId,
            Optional<SessionRef> sessionRef,
            Set<MemoryScope> scopes) {
        return new MemoryQuery("query-1",
                testQueryOwner(tenantId, endUserId, agentId, sessionRef),
                Optional.empty(),
                "remember",
                Optional.empty(),
                scopes,
                Set.of(new MemoryTypeRef("preference")),
                MemoryQueryProfile.DEFAULT,
                NOW,
                MemoryRetrievalPolicy.lexicalV1(),
                10,
                1_000,
                4_000,
                true);
    }

    /** 构造可按身份、作用域、来源和评分维度变化的 MemoryFactDraft。 */
    private static MemoryPort.MemoryFactDraft draft(
            EndUserId endUserId,
            AgentId agentId,
            Optional<SessionRef> sessionRef,
            MemoryScope scope,
            List<EventId> sourceEventIds,
            SessionRef sourceSessionRef,
            Optional<ExecutionRef> sourceExecutionRef,
            Optional<MemoryFactRef> derivedFrom,
            FactStatus initialStatus,
            double confidence,
            double importance) {
        return new MemoryPort.MemoryFactDraft(testOwner((sourceSessionRef).tenantId(), endUserId, Optional.of(agentId), sessionRef, scope),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "likes",
                new TypedPayload("memory-fact", "{}"),
                sourceEventIds,
                sourceSessionRef,
                sourceExecutionRef,
                Optional.empty(),
                "extractor-v1",
                NOW,
                Optional.empty(),
                Optional.empty(),
                confidence,
                importance,
                false,
                derivedFrom,
                initialStatus);
    }

    /** 构造测试使用的跨 Session USER_AGENT 事实草稿。 */
    private static MemoryPort.MemoryFactDraft userAgentDraft(
            EndUserId endUserId,
            AgentId agentId,
            SessionRef sourceSessionRef) {
        return draft(
                endUserId, agentId, Optional.empty(), MemoryScope.USER_AGENT,
                List.of(new EventId("event-1")), sourceSessionRef,
                Optional.empty(), Optional.empty(), FactStatus.PENDING_CONFIRM, 0.8, 0.7);
    }

    /** 构造测试使用的完整 Port 调用上下文。 */
    private static PortCallContext context() {
        SessionOwner owner = SessionOwner.user(SESSION, AGENT, USER);
        TraceEnvelope trace = new TraceEnvelope(
                "trace-1", "span-1", INPUT, Optional.of(new EventId("cause-1")));
        RunIdentity runIdentity = new RunIdentity(owner, owner.target().requireAgent(), INPUT, new RunRef(TENANT, "run-1"), trace, new ResultRef("causation-identity-1"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), INPUT.value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, USER), owner,
                Set.of("runtime:memory"), 1, new ResultRef("verification-1"));
        return new PortCallContext(
                runIdentity, identity,
                new InvocationControl(NOW.plusSeconds(30), () -> false));
    }

    /** 构造仅 revision 可变的合法 USER_AGENT MemoryFact。 */
    private static MemoryFact memoryFact(MemoryFactRef ref, long revision) {
        return new MemoryFact(ref,
                testOwner((ref).tenantId(), USER, Optional.of(AGENT), Optional.empty(), MemoryScope.USER_AGENT),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "likes",
                new TypedPayload("memory-fact", "{}"),
                List.of(new EventId("event-1")),
                SESSION,
                Optional.empty(),
                Optional.empty(),
                "extractor-v1",
                revision,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty(),
                0.8,
                0.7,
                false,
                Optional.empty(),
                Optional.empty(),
                0.1,
                FactStatus.ACTIVE);
    }

    /** 返回 record component 的名称与完整泛型类型，冻结字段顺序和 ABI。 */
    private static List<String> componentSignatures(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + stableTypeName(component))
                .toList();
    }

    /** 压缩稳定 SDK 与 java.lang 前缀，并保留集合与 Optional 泛型参数。 */
    private static String stableTypeName(RecordComponent component) {
        return component.getGenericType().getTypeName()
                .replace("com.skillforge.runtime.api.port.MemoryPort$", "")
                .replace("com.skillforge.runtime.api.port.", "")
                .replace("com.skillforge.runtime.api.", "")
                .replace("java.lang.", "")
                .replace('$', '.');
    }

    private static MemoryOwner testOwner(com.skillforge.runtime.api.TenantId tenant,
            com.skillforge.runtime.api.EndUserId user, Optional<com.skillforge.runtime.api.AgentId> agent,
            Optional<com.skillforge.runtime.api.SessionRef> session, MemoryScope scope) {
        java.util.Objects.requireNonNull(session);
        return switch (scope) {
            case SESSION -> new MemoryOwner.Session(session.orElseThrow(() -> new IllegalArgumentException("session required")));
            case USER_AGENT -> {
                if (session.isPresent()) throw new IllegalArgumentException("agent fact has no session owner");
                yield new MemoryOwner.UserAgent(tenant, user, agent.orElseThrow(() -> new IllegalArgumentException("agent required")));
            }
            case USER -> {
                if (session.isPresent() || agent.isPresent()) throw new IllegalArgumentException("user owner has no agent/session");
                yield new MemoryOwner.User(tenant, user);
            }
            default -> throw new IllegalArgumentException("use typed group owner in group fixtures");
        };
    }

    private static MemoryOwner testQueryOwner(com.skillforge.runtime.api.TenantId tenant,
            com.skillforge.runtime.api.EndUserId user, com.skillforge.runtime.api.AgentId agent,
            Optional<com.skillforge.runtime.api.SessionRef> session) {
        java.util.Objects.requireNonNull(session);
        if (session.isPresent()) {
            if (!session.orElseThrow().tenantId().equals(tenant)) throw new IllegalArgumentException("foreign session");
            return new MemoryOwner.Session(session.orElseThrow());
        }
        return new MemoryOwner.UserAgent(tenant, user, agent);
    }

    /** 受控 fixture 的词法候选诊断，不表示真实检索得分。 */
    private static com.skillforge.runtime.api.MemoryRetrievalDiagnostics testDiagnostics(List<MemoryFact> facts) {
        var candidates = new java.util.ArrayList<com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Candidate>();
        for (int index = 0; index < facts.size(); index++) {
            MemoryFact fact = facts.get(index);
            candidates.add(new com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Candidate(fact.ref(), fact.revision(),
                    Optional.of(1d), Optional.of(index + 1), Optional.empty(), Optional.empty(), 1d / (61 + index)));
        }
        return new com.skillforge.runtime.api.MemoryRetrievalDiagnostics(com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Mode.LEXICAL,
                Optional.empty(), facts.size(), 0, candidates, facts.stream().map(fact -> fact.ref().factId()).toList());
    }
}

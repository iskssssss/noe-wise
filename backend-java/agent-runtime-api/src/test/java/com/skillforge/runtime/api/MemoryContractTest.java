package com.skillforge.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证 Memory 唯一公开值对象的字段、作用域、双时态和引用闭包。 */
class MemoryContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final EndUserId USER = new EndUserId(7);
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    /** 验证 Memory record 字段顺序和封闭枚举与 02 §5.8 精确一致。 */
    @Test
    void freezesMemoryFieldClosureAndEnums() {
        assertEquals(
                List.of("SESSION", "USER_AGENT", "USER", "GROUP", "GROUP_MEMBER"),
                Arrays.stream(MemoryScope.values()).map(Enum::name).toList());
        assertEquals(
                List.of("DEFAULT", "RECENT_EPISODIC"),
                Arrays.stream(MemoryQueryProfile.values()).map(Enum::name).toList());
        assertEquals(
                List.of("ACTIVE", "DECAYED", "PENDING_CONFIRM", "REJECTED", "ARCHIVED", "FORGOTTEN"),
                Arrays.stream(FactStatus.values()).map(Enum::name).toList());
        assertEquals(
                List.of("tenantId:TenantId", "executionId:String"),
                componentSignatures(ExecutionRef.class));
        assertEquals(
                List.of("tenantId:TenantId", "factId:String"),
                componentSignatures(MemoryFactRef.class));
        assertEquals(List.of("value:String"), componentSignatures(MemoryTypeRef.class));
        assertEquals(List.of("value:String"), componentSignatures(RetentionClassRef.class));
        assertEquals(
                List.of(
                        "ref:MemoryFactRef", "owner:MemoryOwner",
                        "type:MemoryTypeRef", "retentionClass:RetentionClassRef",
                        "schemaVersion:String", "subject:String", "predicate:String",
                        "object:TypedPayload", "sourceEventIds:java.util.List<EventId>",
                        "sourceSessionRef:SessionRef",
                        "sourceExecutionRef:java.util.Optional<ExecutionRef>",
                        "exportPath:java.util.Optional<String>", "extractorVersion:String",
                        "revision:long", "createdAt:java.time.Instant", "validFrom:java.time.Instant",
                        "validTo:java.util.Optional<java.time.Instant>",
                        "expiresAt:java.util.Optional<java.time.Instant>", "confidence:double",
                        "importance:double", "reviewRequired:boolean",
                        "derivedFrom:java.util.Optional<MemoryFactRef>",
                        "supersededBy:java.util.Optional<MemoryFactRef>", "decayScore:double",
                        "status:FactStatus"),
                componentSignatures(MemoryFact.class));
        assertEquals(
                List.of(
                        "queryId:String", "owner:MemoryOwner", "subjectEndUserId:java.util.Optional<EndUserId>",
                        "intent:String", "taskConstraint:java.util.Optional<MemoryTaskConstraint>", "scopes:java.util.Set<MemoryScope>",
                        "types:java.util.Set<MemoryTypeRef>", "profile:MemoryQueryProfile",
                        "asOf:java.time.Instant", "retrievalPolicy:MemoryRetrievalPolicy", "maxItems:int",
                        "tokenBudget:int", "characterBudget:int", "requireProvenance:boolean"),
                componentSignatures(MemoryQuery.class));
        assertEquals(
                List.of(
                        "queryId:String", "candidateIds:java.util.List<String>",
                        "selectedIds:java.util.List<String>", "injectedIds:java.util.List<String>",
                        "referencedIds:java.util.List<String>"),
                componentSignatures(MemoryRetrievalTrace.class));
    }

    /** 验证 SESSION 事实绑定来源 Session，并对来源集合执行防御性复制。 */
    @Test
    void validatesSessionFactAndDefensiveCopies() {
        ArrayList<EventId> sourceEvents = new ArrayList<>(List.of(new EventId("event-1")));
        MemoryFact fact = memoryFact(
                MemoryScope.SESSION, Optional.of(SESSION), SESSION,
                Optional.of(new ExecutionRef(TENANT, "execution-1")),
                Optional.of(new MemoryFactRef(TENANT, "parent-1")), Optional.empty(),
                sourceEvents, Optional.of(NOW.plusSeconds(60)), Optional.of(NOW.plusSeconds(120)),
                0.8, 0.7, 0.1);
        sourceEvents.add(new EventId("event-2"));

        assertEquals(List.of(new EventId("event-1")), fact.sourceEventIds());
        assertNotSame(sourceEvents, fact.sourceEventIds());
        assertThrows(UnsupportedOperationException.class,
                () -> fact.sourceEventIds().add(new EventId("event-3")));
        assertEquals(SESSION, ((MemoryOwner.Session) fact.owner()).sessionRef());
        assertEquals(SESSION, fact.sourceSessionRef());
    }

    /** 验证 USER_AGENT 事实不绑定当前 Session，但仍保留同租户来源 Session。 */
    @Test
    void validatesUserAgentScope() {
        MemoryFact fact = memoryFact(
                MemoryScope.USER_AGENT, Optional.empty(), SESSION, Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(new EventId("event-1")),
                Optional.empty(), Optional.empty(), 1, 0, 0.5);

        assertEquals(MemoryScope.USER_AGENT == fact.scope() ? new MemoryOwner.UserAgent(TENANT, USER, AGENT) : new MemoryOwner.User(TENANT, USER), fact.owner());
        assertEquals(SESSION, fact.sourceSessionRef());
        assertEquals(new MemoryOwner.UserAgent(TENANT, USER, AGENT), fact.owner());
        assertThrows(IllegalArgumentException.class,
                () -> memoryFact(
                        MemoryScope.USER_AGENT, Optional.of(SESSION), SESSION, Optional.empty(),
                        Optional.empty(), Optional.empty(), List.of(new EventId("event-1")),
                        Optional.empty(), Optional.empty(), 1, 0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new MemoryFact(new MemoryFactRef(TENANT, "fact-1"),
                testOwner((new MemoryFactRef(TENANT, "fact-1")).tenantId(), USER, Optional.empty(), Optional.empty(), MemoryScope.USER_AGENT),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "likes",
                new TypedPayload("memory-v1", "{}"),
                List.of(new EventId("event-1")),
                SESSION,
                Optional.empty(),
                Optional.empty(),
                "extractor-v1",
                1,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty(),
                1,
                0,
                false,
                Optional.empty(),
                Optional.empty(),
                0.5,
                FactStatus.ACTIVE));
    }

    /** 验证 USER 事实无归属 agent、不绑定当前 Session，并拒绝缺字段或误标为 USER_AGENT。 */
    @Test
    void validatesUserScope() {
        MemoryFact fact = new MemoryFact(new MemoryFactRef(TENANT, "fact-1"),
                testOwner((new MemoryFactRef(TENANT, "fact-1")).tenantId(), USER, Optional.empty(), Optional.empty(), MemoryScope.USER),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "likes",
                new TypedPayload("memory-v1", "{}"),
                List.of(new EventId("event-1")),
                SESSION,
                Optional.empty(),
                Optional.empty(),
                "extractor-v1",
                1,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty(),
                1,
                0,
                false,
                Optional.empty(),
                Optional.empty(),
                0,
                FactStatus.ACTIVE);
        assertEquals(new MemoryOwner.User(TENANT, USER), fact.owner());
        assertEquals(MemoryScope.USER_AGENT == fact.scope() ? new MemoryOwner.UserAgent(TENANT, USER, AGENT) : new MemoryOwner.User(TENANT, USER), fact.owner());
        assertEquals(SESSION, fact.sourceSessionRef());
        assertThrows(IllegalArgumentException.class, () -> new MemoryFact(new MemoryFactRef(TENANT, "fact-1"),
                testOwner((new MemoryFactRef(TENANT, "fact-1")).tenantId(), USER, Optional.of(AGENT), Optional.empty(), MemoryScope.USER),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "likes",
                new TypedPayload("memory-v1", "{}"),
                List.of(new EventId("event-1")),
                SESSION,
                Optional.empty(),
                Optional.empty(),
                "extractor-v1",
                1,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty(),
                1,
                0,
                false,
                Optional.empty(),
                Optional.empty(),
                0,
                FactStatus.ACTIVE));
    }

    /** 验证 MemoryFact 对 Optional、租户、作用域、时态、评分和 revision fail closed。 */
    @Test
    void rejectsInvalidMemoryFacts() {
        assertThrows(NullPointerException.class,
                () -> rawFact(
                        null, MemoryScope.SESSION, SESSION, Optional.empty(), Optional.empty(),
                        Optional.empty(), 1, NOW, Optional.empty(), Optional.empty(), 0.8, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> memoryFact(
                        MemoryScope.SESSION, Optional.empty(), SESSION, Optional.empty(),
                        Optional.empty(), Optional.empty(), List.of(new EventId("event-1")),
                        Optional.empty(), Optional.empty(), 0.8, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> memoryFact(
                        MemoryScope.SESSION, Optional.of(SESSION),
                        new SessionRef(TENANT, "other-session"), Optional.empty(),
                        Optional.empty(), Optional.empty(), List.of(new EventId("event-1")),
                        Optional.empty(), Optional.empty(), 0.8, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> memoryFact(
                        MemoryScope.SESSION, Optional.of(SESSION), SESSION,
                        Optional.of(new ExecutionRef(new TenantId("tenant-2"), "execution-1")),
                        Optional.empty(), Optional.empty(), List.of(new EventId("event-1")),
                        Optional.empty(), Optional.empty(), 0.8, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> memoryFact(
                        MemoryScope.SESSION, Optional.of(SESSION), SESSION, Optional.empty(),
                        Optional.of(new MemoryFactRef(new TenantId("tenant-2"), "parent-1")),
                        Optional.empty(), List.of(new EventId("event-1")),
                        Optional.empty(), Optional.empty(), 0.8, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> memoryFact(
                        MemoryScope.SESSION, Optional.of(SESSION), SESSION, Optional.empty(),
                        Optional.empty(), Optional.empty(), List.of(new EventId("event-1")),
                        Optional.of(NOW.minusSeconds(1)), Optional.empty(), 0.8, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> memoryFact(
                        MemoryScope.SESSION, Optional.of(SESSION), SESSION, Optional.empty(),
                        Optional.empty(), Optional.empty(), List.of(new EventId("event-1")),
                        Optional.empty(), Optional.empty(), Double.NaN, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> memoryFact(
                        MemoryScope.SESSION, Optional.of(SESSION), SESSION, Optional.empty(),
                        Optional.of(new MemoryFactRef(TENANT, "fact-1")), Optional.empty(),
                        List.of(new EventId("event-1")), Optional.empty(), Optional.empty(),
                        0.8, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> rawFact(
                        Optional.of(SESSION), MemoryScope.SESSION, SESSION, Optional.empty(),
                        Optional.empty(), Optional.empty(), 0, NOW, Optional.empty(), Optional.empty(),
                        0.8, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> memoryFact(
                        MemoryScope.SESSION, Optional.of(SESSION), SESSION, Optional.empty(),
                        Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                        0.8, 0.7, 0.1));
    }

    /** 验证 MemoryQuery 的作用域、身份、预算、评分、Optional 与集合不可变性。 */
    @Test
    void validatesMemoryQuery() {
        HashSet<MemoryScope> scopes = new HashSet<>(Set.of(MemoryScope.SESSION));
        HashSet<MemoryTypeRef> types = new HashSet<>(Set.of(new MemoryTypeRef("preference")));
        MemoryQuery query = new MemoryQuery("query-1",
                testQueryOwner(TENANT, USER, AGENT, Optional.of(SESSION)),
                Optional.empty(),
                "answer",
                Optional.empty(),
                scopes,
                types,
                MemoryQueryProfile.DEFAULT,
                NOW,
                MemoryRetrievalPolicy.lexicalV1(),
                10,
                100,
                1_000,
                true);
        scopes.add(MemoryScope.USER_AGENT);
        types.add(new MemoryTypeRef("profile"));

        assertEquals(Set.of(MemoryScope.SESSION), query.scopes());
        assertEquals(Set.of(new MemoryTypeRef("preference")), query.types());
        assertThrows(UnsupportedOperationException.class,
                () -> query.scopes().add(MemoryScope.USER_AGENT));
        assertThrows(NullPointerException.class,
                () -> new MemoryQuery("query-1",
                testQueryOwner(TENANT, USER, AGENT, null),
                Optional.empty(),
                "answer",
                Optional.empty(),
                Set.of(MemoryScope.SESSION),
                Set.of(),
                MemoryQueryProfile.DEFAULT,
                NOW,
                MemoryRetrievalPolicy.lexicalV1(),
                10,
                100,
                1_000,
                true));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryQuery("query-1",
                testQueryOwner(TENANT, USER, AGENT, Optional.empty()),
                Optional.empty(),
                "answer",
                Optional.empty(),
                Set.of(MemoryScope.SESSION),
                Set.of(),
                MemoryQueryProfile.DEFAULT,
                NOW,
                MemoryRetrievalPolicy.lexicalV1(),
                10,
                100,
                1_000,
                true));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryQuery("query-1",
                testQueryOwner(TENANT, USER, AGENT, Optional.of(new SessionRef(new TenantId("tenant-2"), "session-2"))),
                Optional.empty(),
                "answer",
                Optional.empty(),
                Set.of(MemoryScope.USER_AGENT),
                Set.of(),
                MemoryQueryProfile.RECENT_EPISODIC,
                NOW,
                MemoryRetrievalPolicy.lexicalV1(),
                10,
                100,
                1_000,
                true));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryQuery("query-1",
                testQueryOwner(TENANT, USER, AGENT, Optional.empty()),
                Optional.empty(),
                "answer",
                Optional.empty(),
                Set.of(MemoryScope.USER_AGENT),
                Set.of(),
                MemoryQueryProfile.RECENT_EPISODIC,
                NOW,
                new MemoryRetrievalPolicy("invalid", "1", "projection", "analyzer", 64, 60, 1.1, Optional.empty(), MemoryRetrievalPolicy.VectorFailurePolicy.FAIL),
                10,
                100,
                1_000,
                true));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryQuery("query-1",
                testQueryOwner(TENANT, USER, AGENT, Optional.empty()),
                Optional.empty(),
                "answer",
                Optional.empty(),
                Set.of(MemoryScope.USER_AGENT),
                Set.of(),
                MemoryQueryProfile.RECENT_EPISODIC,
                NOW,
                MemoryRetrievalPolicy.lexicalV1(),
                0,
                100,
                1_000,
                true));
    }

    /** 验证 Memory 检索轨迹只能逐阶段收窄并对全部列表防御复制。 */
    @Test
    void validatesMemoryRetrievalTrace() {
        ArrayList<String> candidates = new ArrayList<>(List.of("fact-1", "fact-2"));
        MemoryRetrievalTrace trace = new MemoryRetrievalTrace(
                "query-1", candidates, List.of("fact-1"), List.of("fact-1"), List.of("fact-1"));
        candidates.clear();

        assertEquals(List.of("fact-1", "fact-2"), trace.candidateIds());
        assertThrows(UnsupportedOperationException.class,
                () -> trace.selectedIds().add("fact-2"));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryRetrievalTrace(
                        "query-1", List.of("fact-1"), List.of("fact-2"), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryRetrievalTrace(
                        "query-1", List.of("fact-1", "fact-1"), List.of(), List.of(), List.of()));
    }

    /** 构造带可变来源列表的 MemoryFact，以覆盖防御复制与常用可选字段。 */
    private static MemoryFact memoryFact(
            MemoryScope scope,
            Optional<SessionRef> sessionRef,
            SessionRef sourceSessionRef,
            Optional<ExecutionRef> sourceExecutionRef,
            Optional<MemoryFactRef> derivedFrom,
            Optional<MemoryFactRef> supersededBy,
            List<EventId> sourceEvents,
            Optional<Instant> validTo,
            Optional<Instant> expiresAt,
            double confidence,
            double importance,
            double decayScore) {
        return new MemoryFact(new MemoryFactRef(TENANT, "fact-1"),
                testOwner((new MemoryFactRef(TENANT, "fact-1")).tenantId(), USER, Optional.of(AGENT), sessionRef, scope),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "likes",
                new TypedPayload("memory-v1", "{}"),
                sourceEvents,
                sourceSessionRef,
                sourceExecutionRef,
                Optional.of("profile.preference"),
                "extractor-v1",
                1,
                NOW,
                NOW,
                validTo,
                expiresAt,
                confidence,
                importance,
                false,
                derivedFrom,
                supersededBy,
                decayScore,
                FactStatus.ACTIVE);
    }

    /** 构造可覆盖空 Optional 与 revision 边界的原始 MemoryFact。 */
    private static MemoryFact rawFact(
            Optional<SessionRef> sessionRef,
            MemoryScope scope,
            SessionRef sourceSessionRef,
            Optional<ExecutionRef> sourceExecutionRef,
            Optional<MemoryFactRef> derivedFrom,
            Optional<MemoryFactRef> supersededBy,
            long revision,
            Instant validFrom,
            Optional<Instant> validTo,
            Optional<Instant> expiresAt,
            double confidence,
            double importance,
            double decayScore) {
        return new MemoryFact(new MemoryFactRef(TENANT, "fact-1"),
                testOwner((new MemoryFactRef(TENANT, "fact-1")).tenantId(), USER, Optional.of(AGENT), sessionRef, scope),
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v1",
                "user",
                "likes",
                new TypedPayload("memory-v1", "{}"),
                List.of(new EventId("event-1")),
                sourceSessionRef,
                sourceExecutionRef,
                Optional.empty(),
                "extractor-v1",
                revision,
                NOW,
                validFrom,
                validTo,
                expiresAt,
                confidence,
                importance,
                false,
                derivedFrom,
                supersededBy,
                decayScore,
                FactStatus.ACTIVE);
    }

    /** 返回 record component 的名称与完整泛型类型，冻结公开字段级 ABI。 */
    private static List<String> componentSignatures(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + stableTypeName(component))
                .toList();
    }

    /** 只压缩稳定根包与 java.lang 前缀，保留原始类型和全部泛型参数。 */
    private static String stableTypeName(RecordComponent component) {
        return component.getGenericType().getTypeName()
                .replace("java.lang.", "")
                .replace("com.skillforge.runtime.api.", "");
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
}

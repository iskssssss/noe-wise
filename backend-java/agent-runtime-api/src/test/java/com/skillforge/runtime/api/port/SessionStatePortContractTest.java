package com.skillforge.runtime.api.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 验证 Session Store 与 SessionLog Port 的字段级 ABI、状态机和读取边界。 */
class SessionStatePortContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final TenantId OTHER_TENANT = new TenantId("tenant-2");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final SessionOwner OWNER = SessionOwner.user(
            SESSION, new AgentId("agent-1"), new EndUserId(7));
    private static final InputId INPUT = new InputId("input-1");
    private static final InputId ROOT_INPUT = new InputId("root-input-1");
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    /** 验证两个 Port 的公开方法、record 字段、泛型返回与封闭联合精确匹配事实源。 */
    @Test
    void freezesSessionPortAbi() throws ReflectiveOperationException {
        assertExactPortMethods(SessionStorePort.class, List.of(
                "compareAndSet(ChangeSessionStatus):SessionCasResult",
                "create(CreateSession):SessionCreateResult",
                "find(SessionLookup):java.util.Optional<SessionRecord>"));
        assertEquals(SessionStorePort.SessionCreateResult.class,
                SessionStorePort.class.getMethod(
                        "create", SessionStorePort.CreateSession.class).getReturnType());
        assertGenericReturn(
                SessionStorePort.class.getMethod("find", SessionStorePort.SessionLookup.class),
                Optional.class, SessionStorePort.SessionRecord.class);
        assertEquals(SessionStorePort.SessionCasResult.class,
                SessionStorePort.class.getMethod(
                        "compareAndSet", SessionStorePort.ChangeSessionStatus.class).getReturnType());
        assertExactPortMethods(SessionLogPort.class, List.of(
                "append(AppendSessionEvent):AppendResult",
                "appendBatch(java.util.List<AppendSessionEvent>):java.util.List<AppendResult>",
                "checkedAppendBatch(java.util.List<AppendSessionEvent>):java.util.List<AppendSessionEvent>",
                "findByEvent(SessionOwner,EventId):java.util.Optional<CommittedSessionEvent>",
                "findByEvents(SessionOwner,java.util.Set<EventId>):java.util.Map<EventId, CommittedSessionEvent>",
                "findFirst(SessionOwner,InputId,RunRef):java.util.Optional<CommittedSessionEvent>",
                "findUserSource(SessionRef,EndUserId,EventId):java.util.Optional<CommittedSessionEvent>",
                "historyRetention(SessionOwner):java.util.Optional<HistoryRetention>",
                "latest(SessionOwner):java.util.Optional<CommittedSessionEvent>",
                "readAfter(ReadSessionLog):java.util.List<CommittedSessionEvent>",
                "readAfter(ReadSessionLog,RunRef):java.util.List<CommittedSessionEvent>",
                "readAfter(ReadSessionLog,RunRef,java.util.Set<String>):java.util.List<CommittedSessionEvent>",
                "readAfter(ReadSessionLog,java.util.Set<String>):java.util.List<CommittedSessionEvent>"));
        assertEquals(SessionLogPort.AppendResult.class,
                SessionLogPort.class.getMethod(
                        "append", SessionLogPort.AppendSessionEvent.class).getReturnType());
        assertGenericReturn(
                SessionLogPort.class.getMethod(
                        "findByEvent", SessionOwner.class, EventId.class),
                Optional.class, CommittedSessionEvent.class);
        assertGenericReturn(
                SessionLogPort.class.getMethod(
                        "readAfter", SessionLogPort.ReadSessionLog.class),
                List.class, CommittedSessionEvent.class);

        assertEquals(List.of(
                        "owner:SessionOwner", "channel:String", "scopeKey:String",
                        "personaVersion:String", "status:SessionStatus",
                        "sessionRevision:long", "lastSequence:long",
                        "createdAt:java.time.Instant", "updatedAt:java.time.Instant"),
                componentSignatures(SessionStorePort.SessionRecord.class));
        assertEquals(List.of(
                        "owner:SessionOwner", "channel:String", "scopeKey:String",
                        "personaVersion:String", "causationInputId:InputId",
                        "trace:TraceEnvelope", "requestedAt:java.time.Instant"),
                componentSignatures(SessionStorePort.CreateSession.class));
        assertEquals(List.of("owner:SessionOwner"),
                componentSignatures(SessionStorePort.SessionLookup.class));
        assertEquals(List.of(
                        "owner:SessionOwner", "expectedStatus:SessionStatus",
                        "targetStatus:SessionStatus", "expectedRevision:long",
                        "causationInputId:InputId", "trace:TraceEnvelope",
                        "requestedAt:java.time.Instant"),
                componentSignatures(SessionStorePort.ChangeSessionStatus.class));
        assertEquals(List.of("session:SessionRecord"),
                componentSignatures(SessionStorePort.SessionCreateResult.Created.class));
        assertEquals(List.of("session:SessionRecord"),
                componentSignatures(SessionStorePort.SessionCreateResult.Replayed.class));
        assertEquals(List.of("currentRevision:long", "auditRef:ResultRef"),
                componentSignatures(SessionStorePort.SessionCreateResult.Conflict.class));
        assertEquals(List.of("session:SessionRecord"),
                componentSignatures(SessionStorePort.SessionCasResult.Applied.class));
        assertEquals(List.of("session:SessionRecord"),
                componentSignatures(SessionStorePort.SessionCasResult.Replayed.class));
        assertEquals(List.of("current:SessionRecord", "auditRef:ResultRef"),
                componentSignatures(SessionStorePort.SessionCasResult.Conflict.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(SessionStorePort.SessionCasResult.NotFound.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(SessionStorePort.SessionCasResult.Rejected.class));
        assertEquals(List.of("target:SessionProjectionTarget", "event:SessionEventDraft"),
                componentSignatures(SessionLogPort.AppendSessionEvent.class));
        assertEquals(List.of("event:CommittedSessionEvent"),
                componentSignatures(SessionLogPort.AppendResult.Appended.class));
        assertEquals(List.of("event:CommittedSessionEvent"),
                componentSignatures(SessionLogPort.AppendResult.Replayed.class));
        assertEquals(List.of("conflict:PortConflict"),
                componentSignatures(SessionLogPort.AppendResult.Conflict.class));
        assertEquals(List.of("owner:SessionOwner", "afterSequence:long", "limit:int"),
                componentSignatures(SessionLogPort.ReadSessionLog.class));

        assertEquals(List.of("CREATED", "ACTIVE", "CLOSED"),
                Arrays.stream(SessionStorePort.SessionStatus.values()).map(Enum::name).toList());
        assertEquals(Set.of(
                        SessionStorePort.SessionCreateResult.Created.class,
                        SessionStorePort.SessionCreateResult.Replayed.class,
                        SessionStorePort.SessionCreateResult.Conflict.class),
                Set.of(SessionStorePort.SessionCreateResult.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                        SessionStorePort.SessionCasResult.Applied.class,
                        SessionStorePort.SessionCasResult.Replayed.class,
                        SessionStorePort.SessionCasResult.Conflict.class,
                        SessionStorePort.SessionCasResult.NotFound.class,
                        SessionStorePort.SessionCasResult.Rejected.class),
                Set.of(SessionStorePort.SessionCasResult.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                        SessionLogPort.AppendResult.Appended.class,
                        SessionLogPort.AppendResult.Replayed.class,
                        SessionLogPort.AppendResult.Conflict.class),
                Set.of(SessionLogPort.AppendResult.class.getPermittedSubclasses()));
    }

    /** 验证 SessionRecord 的文本、revision、零起始 sequence 与时间顺序。 */
    @Test
    void validatesSessionRecordFacts() {
        SessionStorePort.SessionRecord record = sessionRecord(
                SessionStorePort.SessionStatus.CREATED, 1, 0, NOW, NOW);

        assertEquals(0, record.lastSequence());
        assertThrows(IllegalArgumentException.class, () -> sessionRecord(
                SessionStorePort.SessionStatus.CREATED, 0, 0, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> sessionRecord(
                SessionStorePort.SessionStatus.CREATED, 1, -1, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> sessionRecord(
                SessionStorePort.SessionStatus.CREATED, 1, 0, NOW, NOW.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new SessionStorePort.SessionRecord(
                OWNER, " ", "scope-1", "persona-v1", SessionStorePort.SessionStatus.CREATED,
                1, 0, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new SessionStorePort.SessionRecord(
                OWNER, "chat", "scope-1", "persona-v1", null,
                1, 0, NOW, NOW));
    }

    /** 验证创建命令保留当前因果输入与独立 root input，不把两者错误合并。 */
    @Test
    void validatesCreateSessionFactsWithoutConflatingTraceRoot() {
        SessionStorePort.CreateSession command = new SessionStorePort.CreateSession(
                OWNER, "chat", "scope-1", "persona-v1", INPUT, trace(), NOW);

        assertEquals(INPUT, command.causationInputId());
        assertEquals(ROOT_INPUT, command.trace().rootInputId());
        assertThrows(IllegalArgumentException.class, () -> new SessionStorePort.CreateSession(
                OWNER, "chat", " ", "persona-v1", INPUT, trace(), NOW));
        assertThrows(IllegalArgumentException.class, () -> new SessionStorePort.SessionLookup(null));
    }

    /** 验证 Session 状态命令只允许 CREATED→ACTIVE 与 ACTIVE→CLOSED。 */
    @Test
    void enforcesExactSessionStatusTransitions() {
        assertEquals(SessionStorePort.SessionStatus.ACTIVE,
                change(SessionStorePort.SessionStatus.CREATED,
                        SessionStorePort.SessionStatus.ACTIVE, 1).targetStatus());
        assertEquals(SessionStorePort.SessionStatus.CLOSED,
                change(SessionStorePort.SessionStatus.ACTIVE,
                        SessionStorePort.SessionStatus.CLOSED, 2).targetStatus());

        assertRejectedTransition(
                SessionStorePort.SessionStatus.CREATED, SessionStorePort.SessionStatus.CREATED);
        assertRejectedTransition(
                SessionStorePort.SessionStatus.ACTIVE, SessionStorePort.SessionStatus.ACTIVE);
        assertRejectedTransition(
                SessionStorePort.SessionStatus.CLOSED, SessionStorePort.SessionStatus.CLOSED);
        assertRejectedTransition(
                SessionStorePort.SessionStatus.ACTIVE, SessionStorePort.SessionStatus.CREATED);
        assertRejectedTransition(
                SessionStorePort.SessionStatus.CLOSED, SessionStorePort.SessionStatus.ACTIVE);
        assertRejectedTransition(
                SessionStorePort.SessionStatus.CREATED, SessionStorePort.SessionStatus.CLOSED);
        assertThrows(IllegalArgumentException.class, () -> change(
                SessionStorePort.SessionStatus.CREATED, SessionStorePort.SessionStatus.ACTIVE, 0));
    }

    /** 验证 Session 创建与 CAS 结果拒绝缺失记录、失败事实和非正 revision。 */
    @Test
    void validatesSessionMutationResults() {
        SessionStorePort.SessionRecord record = sessionRecord(
                SessionStorePort.SessionStatus.ACTIVE, 2, 1, NOW, NOW.plusSeconds(1));
        assertEquals(record, new SessionStorePort.SessionCreateResult.Created(record).session());
        assertEquals(record, new SessionStorePort.SessionCasResult.Applied(record).session());
        assertThrows(IllegalArgumentException.class,
                () -> new SessionStorePort.SessionCreateResult.Created(null));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionStorePort.SessionCreateResult.Conflict(
                        0, new ResultRef("audit-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionStorePort.SessionCasResult.Conflict(record, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionStorePort.SessionCasResult.NotFound(null));
    }

    /** 验证 SessionLog 命令保留投影身份，并允许当前 input 与 trace root 不同。 */
    @Test
    void validatesSessionLogAppendIdentityBoundary() {
        SessionProjectionTarget target = target(OWNER, Optional.of(new RunRef(TENANT, "run-1")));
        SessionEventDraft event = event();
        SessionLogPort.AppendSessionEvent command = new SessionLogPort.AppendSessionEvent(target, event);

        assertEquals(INPUT, command.target().inputId());
        assertEquals(ROOT_INPUT, command.event().trace().rootInputId());
        assertThrows(IllegalArgumentException.class,
                () -> new SessionLogPort.AppendSessionEvent(null, event));
        assertThrows(IllegalArgumentException.class,
                () -> target(OWNER, Optional.of(new RunRef(OTHER_TENANT, "run-2"))));
    }

    /** 验证 SessionLog cursor 从零开始读取且 limit 必须为正。 */
    @Test
    void validatesSessionLogReadBoundary() {
        assertEquals(0, new SessionLogPort.ReadSessionLog(OWNER, 0, 10).afterSequence());
        assertEquals(9, new SessionLogPort.ReadSessionLog(OWNER, 9, 10).afterSequence());
        assertThrows(IllegalArgumentException.class,
                () -> new SessionLogPort.ReadSessionLog(OWNER, -1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionLogPort.ReadSessionLog(OWNER, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionLogPort.ReadSessionLog(null, 0, 10));
    }

    /** 验证 AppendResult 仅包装已提交事件或稳定冲突事实。 */
    @Test
    void validatesAppendResults() {
        CommittedSessionEvent committed = committedEvent();
        assertEquals(committed, new SessionLogPort.AppendResult.Appended(committed).event());
        assertEquals(committed, new SessionLogPort.AppendResult.Replayed(committed).event());
        assertThrows(IllegalArgumentException.class,
                () -> new SessionLogPort.AppendResult.Appended(null));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionLogPort.AppendResult.Conflict(null));
    }

    /** 构造测试使用的 SessionRecord。 */
    private static SessionStorePort.SessionRecord sessionRecord(
            SessionStorePort.SessionStatus status,
            long revision,
            long lastSequence,
            Instant createdAt,
            Instant updatedAt) {
        return new SessionStorePort.SessionRecord(
                OWNER, "chat", "scope-1", "persona-v1", status,
                revision, lastSequence, createdAt, updatedAt);
    }

    /** 构造测试使用的 Session 状态变更命令。 */
    private static SessionStorePort.ChangeSessionStatus change(
            SessionStorePort.SessionStatus expected,
            SessionStorePort.SessionStatus target,
            long revision) {
        return new SessionStorePort.ChangeSessionStatus(
                OWNER, expected, target, revision, INPUT, trace(), NOW);
    }

    /** 断言一个状态对不属于 Session 的两条合法迁移。 */
    private static void assertRejectedTransition(
            SessionStorePort.SessionStatus expected,
            SessionStorePort.SessionStatus target) {
        assertThrows(IllegalArgumentException.class, () -> change(expected, target, 1));
    }

    /** 构造包含可选 Run 引用的 Session 投影目标。 */
    private static SessionProjectionTarget target(
            SessionOwner owner,
            Optional<RunRef> runRef) {
        return new SessionProjectionTarget(
                owner, INPUT, runRef, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** 构造当前事件的 trace root 与投影 input 不同的合法事件草稿。 */
    private static SessionEventDraft event() {
        return new SessionEventDraft(
                new EventId("event-1"), "session.message", "v1", "fingerprint-1",
                trace(), new TypedPayload("session-event", "{}"), NOW);
    }

    /** 构造带正 Session sequence 的已提交事件。 */
    private static CommittedSessionEvent committedEvent() {
        return new CommittedSessionEvent(
                OWNER, INPUT, Optional.of(new RunRef(TENANT, "run-1")),
                Optional.empty(), Optional.empty(), Optional.empty(),
                new EventId("event-1"), 1, "session.message", "v1", trace(),
                new TypedPayload("session-event", "{}"), NOW);
    }

    /** 构造与当前 input 不同 root input 的合法 trace，覆盖恢复/回调链。 */
    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-1", "span-1", ROOT_INPUT,
                Optional.of(new EventId("caused-by-1")));
    }

    /** 精确冻结非泛型接口及其全部公开方法 ABI。 */
    private static void assertExactPortMethods(Class<?> type, List<String> expectedSignatures) {
        assertTrue(type.isInterface(), type.getName());
        assertFalse(type.isAnnotation(), type.getName());
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
        assertEquals(null, type.getEnclosingClass(), type.getName());
        assertFalse(type.isSealed(), type.getName());
        assertEquals(0, type.getTypeParameters().length, type.getName());
        assertEquals(0, type.getInterfaces().length, type.getName());
        assertEquals(0, type.getGenericInterfaces().length, type.getName());
        List<Method> methods = Arrays.stream(type.getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .toList();
        for (Method method : methods) {
            // 精确登记当前批量与派生读取入口，其余仍为纯抽象；完整签名在下方逐项校验。
            boolean batchValidator = type == SessionLogPort.class && method.getName().equals("checkedAppendBatch");
            boolean derivedRead = type == SessionLogPort.class && (Set.of("appendBatch", "findByEvents", "findFirst", "findUserSource", "latest")
                    .contains(method.getName()) || method.getName().equals("readAfter") && method.getParameterCount() > 1
                    || methodSignature(method).equals("historyRetention(SessionOwner):java.util.Optional<HistoryRetention>"));
            assertTrue(Modifier.isPublic(method.getModifiers()), method.toString());
            assertEquals(!derivedRead && !batchValidator, Modifier.isAbstract(method.getModifiers()), method.toString());
            assertEquals(derivedRead, method.isDefault(), method.toString());
            assertEquals(batchValidator, Modifier.isStatic(method.getModifiers()), method.toString());
            assertEquals(0, method.getTypeParameters().length, method.toString());
            assertEquals(0, method.getGenericExceptionTypes().length, method.toString());
        }
        assertEquals(expectedSignatures, methods.stream()
                .map(SessionStatePortContractTest::methodSignature)
                .sorted()
                .toList());
    }

    /** 生成保留参数与返回泛型的稳定方法签名，确保同名重载无法被折叠。 */
    private static String methodSignature(Method method) {
        String parameters = Arrays.stream(method.getGenericParameterTypes())
                .map(SessionStatePortContractTest::stableTypeName)
                .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + "):"
                + stableTypeName(method.getGenericReturnType());
    }

    /** 验证方法返回的 raw type 和唯一泛型参数。 */
    private static void assertGenericReturn(Method method, Class<?> rawType, Class<?> argument) {
        assertEquals(rawType, method.getReturnType());
        ParameterizedType genericType = (ParameterizedType) method.getGenericReturnType();
        assertEquals(List.of(argument), Arrays.asList(genericType.getActualTypeArguments()));
    }

    /** 返回 record component 的名称与完整泛型类型，冻结字段顺序和 ABI。 */
    private static List<String> componentSignatures(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + stableTypeName(component))
                .toList();
    }

    /** 压缩稳定 SDK 与 java.lang 前缀，并保留集合与 Optional 泛型参数。 */
    private static String stableTypeName(RecordComponent component) {
        return stableTypeName(component.getGenericType());
    }

    /** 压缩任意反射类型中的稳定 SDK 与 java.lang 前缀。 */
    private static String stableTypeName(Type type) {
        return type.getTypeName()
                .replace("com.skillforge.runtime.api.port.SessionStorePort$", "")
                .replace("com.skillforge.runtime.api.port.SessionLogPort$", "")
                .replace("com.skillforge.runtime.api.port.", "")
                .replace("com.skillforge.runtime.api.", "")
                .replace("java.lang.", "")
                .replace('$', '.');
    }
}

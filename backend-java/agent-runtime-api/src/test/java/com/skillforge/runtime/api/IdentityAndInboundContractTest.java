package com.skillforge.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证公开身份、访问 envelope、Inbound、取消命令与幂等 receipt 契约。 */
class IdentityAndInboundContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final EndUserId USER = new EndUserId(7);
    private static final AuthenticationRef AUTHENTICATION = new AuthenticationRef("auth-1");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final RunRef RUN = new RunRef(TENANT, "run-1");
    private static final InputId INPUT = new InputId("input-1");
    private static final EventId EVENT = new EventId("event-1");
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    /** 验证所有身份值对象和完整 AccessEnvelope 的正常构造。 */
    @Test
    void constructsCompleteIdentitiesAndAccessEnvelope() {
        AccessEnvelope access = access(TENANT);

        assertEquals(AUTHENTICATION, access.authenticationRef());
        assertEquals(TENANT, access.tenantId());
        assertEquals(Optional.of(AGENT), access.agentId());
        assertEquals(SessionSubject.user(USER), access.subject());
        assertEquals(new RequestId("request-1"), access.requestId());
        assertEquals("chat", access.channel());
        assertEquals("user", access.source());
    }

    /** 验证字符串身份、正整数用户身份和访问 trace 对缺失值 fail closed。 */
    @Test
    void rejectsMissingIdentityAndAccessTrace() {
        assertThrows(NullPointerException.class, () -> new TenantId(null));
        assertThrows(IllegalArgumentException.class, () -> new TenantId("  "));
        assertThrows(IllegalArgumentException.class, () -> new AgentId("  "));
        assertThrows(IllegalArgumentException.class, () -> new EndUserId(0));
        assertThrows(IllegalArgumentException.class, () -> new InputId("  "));
        assertThrows(IllegalArgumentException.class, () -> new EventId("  "));
        assertThrows(IllegalArgumentException.class, () -> new RequestId("  "));
        assertThrows(IllegalArgumentException.class, () -> new ResultRef("  "));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticationRef("  "));
        assertThrows(NullPointerException.class, () -> new SessionRef(null, "session-1"));
        assertThrows(IllegalArgumentException.class, () -> new RunRef(TENANT, "  "));
        assertThrows(NullPointerException.class,
                () -> new AccessEnvelope(null, TENANT, com.skillforge.runtime.api.SessionTarget.agent(AGENT), java.util.Optional.of(AGENT), com.skillforge.runtime.api.SessionSubject.user(USER), new RequestId("request-1"), "chat", "user", "trace", "span"));
        assertThrows(NullPointerException.class,
                () -> new AccessEnvelope(AUTHENTICATION, null, com.skillforge.runtime.api.SessionTarget.agent(AGENT), java.util.Optional.of(AGENT), com.skillforge.runtime.api.SessionSubject.user(USER), new RequestId("request-1"), "chat", "user", "trace", "span"));
        assertThrows(NullPointerException.class,
                () -> new AccessEnvelope(AUTHENTICATION, TENANT, com.skillforge.runtime.api.SessionTarget.agent(null), java.util.Optional.of(null), com.skillforge.runtime.api.SessionSubject.user(USER), new RequestId("request-1"), "chat", "user", "trace", "span"));
        assertThrows(NullPointerException.class,
                () -> new AccessEnvelope(AUTHENTICATION, TENANT, com.skillforge.runtime.api.SessionTarget.agent(AGENT), java.util.Optional.of(AGENT), com.skillforge.runtime.api.SessionSubject.user(null), new RequestId("request-1"), "chat", "user", "trace", "span"));
        assertThrows(NullPointerException.class,
                () -> new AccessEnvelope(AUTHENTICATION, TENANT, com.skillforge.runtime.api.SessionTarget.agent(AGENT), java.util.Optional.of(AGENT), com.skillforge.runtime.api.SessionSubject.user(USER), null, "chat", "user", "trace", "span"));
        assertThrows(IllegalArgumentException.class,
                () -> new AccessEnvelope(AUTHENTICATION, TENANT, com.skillforge.runtime.api.SessionTarget.agent(AGENT), java.util.Optional.of(AGENT), com.skillforge.runtime.api.SessionSubject.user(USER), new RequestId("request-1"), " ", "user", "trace", "span"));
        assertThrows(IllegalArgumentException.class,
                () -> new AccessEnvelope(AUTHENTICATION, TENANT, com.skillforge.runtime.api.SessionTarget.agent(AGENT), java.util.Optional.of(AGENT), com.skillforge.runtime.api.SessionSubject.user(USER), new RequestId("request-1"), "chat", " ", "trace", "span"));
        assertThrows(IllegalArgumentException.class,
                () -> new AccessEnvelope(AUTHENTICATION, TENANT, com.skillforge.runtime.api.SessionTarget.agent(AGENT), java.util.Optional.of(AGENT), com.skillforge.runtime.api.SessionSubject.user(USER), new RequestId("request-1"), "chat", "user", " ", "span"));
        assertThrows(IllegalArgumentException.class,
                () -> new AccessEnvelope(AUTHENTICATION, TENANT, com.skillforge.runtime.api.SessionTarget.agent(AGENT), java.util.Optional.of(AGENT), com.skillforge.runtime.api.SessionSubject.user(USER), new RequestId("request-1"), "chat", "user", "trace", " "));
    }

    /** 验证只读 query 和事件订阅拒绝跨租户引用与负游标。 */
    @Test
    void validatesReadAccessTenantAndSequence() {
        TenantId otherTenant = new TenantId("tenant-2");

        assertThrows(IllegalArgumentException.class,
                () -> new RunQuery(access(TENANT), new RunRef(otherTenant, "run-2")));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionQuery(access(TENANT), new SessionRef(otherTenant, "session-2")));
        assertThrows(IllegalArgumentException.class,
                () -> new EventStreamRequest(access(TENANT), SESSION, -1, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new EventStreamRequest(access(TENANT), SESSION, 0, 0));
        EventStreamRequest request = new EventStreamRequest(access(TENANT), SESSION, 0, 100);
        assertEquals(0, request.afterSequence());
        assertEquals(100, request.limit());
    }

    /** 验证 TraceEnvelope 只允许因果事件缺省，并规范化 null Optional。 */
    @Test
    void validatesTraceEnvelopeAndNormalizesOptional() {
        TraceEnvelope trace = new TraceEnvelope("trace", "span", INPUT, null);

        assertEquals(Optional.empty(), trace.causedByEventId());
        assertThrows(NullPointerException.class,
                () -> new TraceEnvelope("trace", "span", null, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceEnvelope(" ", "span", INPUT, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceEnvelope("trace", " ", INPUT, Optional.empty()));
    }

    /** 验证六类 Inbound、显式 RunTarget 和完整幂等身份可正常构造。 */
    @Test
    void constructsAllInboundKindsWithExplicitTargets() {
        assertEquals(6, InboundKind.values().length);

        for (InboundKind kind : InboundKind.values()) {
            InboundEnvelope envelope = inbound(kind, new RunTarget.NewRun(), SESSION);
            assertInstanceOf(RunTarget.NewRun.class, envelope.runTarget());
            assertEquals(INPUT, envelope.inputId());
            assertEquals(AUTHENTICATION, envelope.authenticationRef());
            assertEquals(Optional.empty(), envelope.causationIdentityRef());
        }

        InboundEnvelope delayed = new InboundEnvelope(AUTHENTICATION, Optional.of(new ResultRef("identity-1")), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, InboundKind.RECOVERY, new RunTarget.ExistingRun(RUN), "runtime", "agent_delegation", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value()));
        assertEquals(Optional.of(new ResultRef("identity-1")), delayed.causationIdentityRef());
    }

    /** 验证 Inbound 对缺失幂等身份、trace、target 和跨租户 ExistingRun fail closed。 */
    @Test
    void rejectsIncompleteOrCrossTenantInbound() {
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(null, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, InboundKind.MESSAGE, new RunTarget.NewRun(), "chat", "user", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), null, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, InboundKind.MESSAGE, new RunTarget.NewRun(), "chat", "user", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(TENANT, INPUT.value())));
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(null), null, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, InboundKind.MESSAGE, new RunTarget.NewRun(), "chat", "user", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(null), new com.skillforge.runtime.api.RunRequester.EndUser(null), INPUT, EVENT, InboundKind.MESSAGE, new RunTarget.NewRun(), "chat", "user", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), null, EVENT, InboundKind.MESSAGE, new RunTarget.NewRun(), "chat", "user", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), "input-fixture")));
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, null, InboundKind.MESSAGE, new RunTarget.NewRun(), "chat", "user", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, null, new RunTarget.NewRun(), "chat", "user", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, InboundKind.MESSAGE, null, "chat", "user", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, InboundKind.MESSAGE, new RunTarget.NewRun(), "chat", "user", null, payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(IllegalArgumentException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, InboundKind.MESSAGE, new RunTarget.NewRun(), " ", "user", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(IllegalArgumentException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, InboundKind.MESSAGE, new RunTarget.NewRun(), "chat", " ", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, InboundKind.MESSAGE, new RunTarget.NewRun(), "chat", "user", trace(), null, NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(NullPointerException.class,
                () -> new InboundEnvelope(AUTHENTICATION, Optional.empty(), SESSION, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, InboundKind.MESSAGE, new RunTarget.NewRun(), "chat", "user", trace(), payload(), null, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SESSION.tenantId(), INPUT.value())));
        assertThrows(IllegalArgumentException.class,
                () -> inbound(
                        InboundKind.RECOVERY,
                        new RunTarget.ExistingRun(new RunRef(new TenantId("tenant-2"), "run-2")),
                        SESSION));
    }

    /** 验证取消命令只接受 COMMAND、相等 ExistingRun、同租户和正 expected revision。 */
    @Test
    void validatesCancelCommandTargetAndRevision() {
        InboundEnvelope command = inbound(InboundKind.COMMAND, new RunTarget.ExistingRun(RUN), SESSION);
        CancelCommand cancel = new CancelCommand(command, RUN, 3);

        assertEquals(RUN, cancel.runRef());
        assertThrows(IllegalArgumentException.class,
                () -> new CancelCommand(inbound(InboundKind.MESSAGE, new RunTarget.ExistingRun(RUN), SESSION), RUN, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new CancelCommand(inbound(InboundKind.COMMAND, new RunTarget.NewRun(), SESSION), RUN, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new CancelCommand(command, new RunRef(TENANT, "run-2"), 3));
        assertThrows(IllegalArgumentException.class, () -> new CancelCommand(command, RUN, 0));
    }

    /** 验证 ACCEPTED/REJECTED receipt 的完整字段矩阵和 replay 正交语义。 */
    @Test
    void validatesReceiptStatusMatrixAndReplayFlag() {
        InputReceipt accepted = new InputReceipt(
                SESSION, INPUT, InputReceiptStatus.ACCEPTED, true, 1,
                Optional.of(RUN), null, null, NOW);
        InputReceipt rejected = new InputReceipt(
                SESSION, INPUT, InputReceiptStatus.REJECTED, false, 2,
                Optional.empty(), Optional.of("policy_denied"),
                Optional.of(new ResultRef("result-1")), NOW);

        assertTrue(accepted.replayed());
        assertFalse(rejected.replayed());
        assertEquals(Optional.empty(), accepted.rejectionReasonCode());
        assertEquals(Optional.empty(), accepted.resultRef());
        assertThrows(IllegalArgumentException.class,
                () -> new InputReceipt(
                        SESSION, INPUT, InputReceiptStatus.ACCEPTED, false, 1,
                        Optional.empty(), Optional.empty(), Optional.empty(), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new InputReceipt(
                        SESSION, INPUT, InputReceiptStatus.REJECTED, false, 1,
                        Optional.empty(), Optional.empty(), Optional.of(new ResultRef("result-1")), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new InputReceipt(
                        SESSION, INPUT, InputReceiptStatus.REJECTED, false, 1,
                        Optional.empty(), Optional.of("denied"), Optional.empty(), NOW));
    }

    /** 验证 receipt 的 ledger revision 和可选 RunRef tenant 边界。 */
    @Test
    void rejectsInvalidReceiptRevisionAndTenant() {
        assertThrows(IllegalArgumentException.class,
                () -> new InputReceipt(
                        SESSION, INPUT, InputReceiptStatus.ACCEPTED, false, 0,
                        Optional.of(RUN), Optional.empty(), Optional.empty(), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new InputReceipt(
                        SESSION, INPUT, InputReceiptStatus.REJECTED, false, 1,
                        Optional.of(new RunRef(new TenantId("tenant-2"), "run-2")),
                        Optional.of("denied"), Optional.of(new ResultRef("result-1")), NOW));
    }

    /** 验证幂等指纹冲突异常只暴露稳定 InputId 与审计 ResultRef。 */
    @Test
    void exposesStableIdempotencyConflictReferences() {
        ResultRef auditRef = new ResultRef("audit-1");
        IdempotencyConflictException conflict = new IdempotencyConflictException(INPUT, auditRef);

        assertEquals(INPUT, conflict.inputId());
        assertEquals(auditRef, conflict.auditRef());
        assertThrows(NullPointerException.class, () -> new IdempotencyConflictException(null, auditRef));
    }

    /** 验证访问失败异常只公开稳定原因码和安全审计引用。 */
    @Test
    void exposesStableRuntimeAccessFailureReferences() {
        ResultRef auditRef = new ResultRef("audit-2");
        RuntimeAccessException denied = new RuntimeAccessException("policy_denied", auditRef);

        assertEquals("policy_denied", denied.reasonCode());
        assertEquals(auditRef, denied.auditRef());
        assertEquals("runtime access verification or authorization failed", denied.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeAccessException(" ", auditRef));
        assertThrows(NullPointerException.class,
                () -> new RuntimeAccessException("policy_denied", null));
    }

    /** 构造具有指定租户的完整只读访问 envelope。 */
    private static AccessEnvelope access(TenantId tenantId) {
        return new AccessEnvelope(AUTHENTICATION, tenantId, com.skillforge.runtime.api.SessionTarget.agent(AGENT), java.util.Optional.of(AGENT), com.skillforge.runtime.api.SessionSubject.user(USER), new RequestId("request-1"), "chat", "user", "trace-1", "span-1");
    }

    /** 构造具有指定 kind、RunTarget 和 Session 的完整 Unified Inbound。 */
    private static InboundEnvelope inbound(
            InboundKind kind,
            RunTarget runTarget,
            SessionRef sessionRef) {
        return new InboundEnvelope(AUTHENTICATION, null, sessionRef, com.skillforge.runtime.api.SessionTarget.agent(AGENT), AGENT, com.skillforge.runtime.api.SessionSubject.user(USER), new com.skillforge.runtime.api.RunRequester.EndUser(USER), INPUT, EVENT, kind, runTarget, "chat", "user", trace(), payload(), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(sessionRef.tenantId(), INPUT.value()));
    }

    /** 构造测试使用的完整 trace envelope。 */
    private static TraceEnvelope trace() {
        return new TraceEnvelope("trace-1", "span-1", INPUT, Optional.empty());
    }

    /** 构造测试使用的最小 schema 化 payload。 */
    private static TypedPayload payload() {
        return new TypedPayload("message.v1", "{}");
    }
}

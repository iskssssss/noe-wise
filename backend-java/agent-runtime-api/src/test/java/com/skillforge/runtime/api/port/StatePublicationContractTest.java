package com.skillforge.runtime.api.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RuntimeEvent;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证跨 Run/Ledger/异步域共享的事件 publication 与工作身份契约。 */
class StatePublicationContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final RunRef RUN = new RunRef(TENANT, "run-1");
    private static final InputId INPUT = new InputId("input-1");
    private static final EventId EVENT = new EventId("event-1");
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final SessionOwner OWNER = SessionOwner.user(
            SESSION, new AgentId("agent-1"), new EndUserId(7));

    /** 未实现持久USER原来源读取的适配器必须显式拒绝，不能回退为普通历史查询。 */
    @Test
    void unsupportedUserSourceReadFailsClosedAndRequiresCompleteIdentity() {
        SessionLogPort unsupported = new SessionLogPort() {
            @Override public AppendResult append(AppendSessionEvent command) { throw new AssertionError("unexpected append"); }
            @Override public Optional<CommittedSessionEvent> findByEvent(SessionOwner owner, EventId event) {
                throw new AssertionError("must not reconstruct original owner");
            }
            @Override public List<CommittedSessionEvent> readAfter(ReadSessionLog query) {
                throw new AssertionError("must not read ordinary history");
            }
        };
        assertEquals("session_user_source_read_unsupported", assertThrows(UnsupportedOperationException.class,
                () -> unsupported.findUserSource(SESSION, OWNER.requireUser(), EVENT)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> unsupported.findUserSource(null, OWNER.requireUser(), EVENT));
        assertThrows(IllegalArgumentException.class, () -> unsupported.findUserSource(SESSION, null, EVENT));
        assertThrows(IllegalArgumentException.class, () -> unsupported.findUserSource(SESSION, OWNER.requireUser(), null));
    }

    /** 验证 Session event 从草稿到已提交事实时分配正 sequence 并保留关联。 */
    @Test
    void validatesSessionEventFacts() {
        SessionEventDraft draft = draft();
        CommittedSessionEvent committed = new CommittedSessionEvent(
                OWNER, INPUT, Optional.of(RUN), Optional.empty(), Optional.empty(), Optional.empty(),
                EVENT, 1, "run.queued", "v1", trace(), payload(), NOW);

        assertEquals(EVENT, draft.eventId());
        assertEquals(1, committed.sessionSequence());
        assertThrows(IllegalArgumentException.class,
                () -> new CommittedSessionEvent(
                        OWNER, INPUT, Optional.of(RUN), Optional.empty(), Optional.empty(), Optional.empty(),
                        EVENT, 0, "run.queued", "v1", trace(), payload(), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionProjectionTarget(
                        OWNER, INPUT,
                        Optional.of(new RunRef(new TenantId("tenant-2"), "run-2")),
                        Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionProjectionTarget(
                        OWNER, INPUT, null, Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new CommittedSessionEvent(
                        OWNER, INPUT, null, Optional.empty(), Optional.empty(), Optional.empty(),
                        EVENT, 1, "run.queued", "v1", trace(), payload(), NOW));
    }

    /** 验证 publication 只接受 canonical source 与相同目标引用。 */
    @Test
    void validatesCanonicalStatePublication() {
        StateEventPublication publication = publication();

        assertEquals(Set.of("stream-1"), publication.runtimeEventDestinationIds());
        assertThrows(IllegalArgumentException.class,
                () -> publication("unknown", "run-1"));
        assertThrows(IllegalArgumentException.class,
                () -> publication("run", "run-2"));
        assertThrows(IllegalArgumentException.class,
                () -> new PublicationClaimRequest(TENANT, "worker-1", 0, Duration.ofSeconds(10)));
        assertThrows(IllegalArgumentException.class,
                () -> new PublicationClaimRequest(TENANT, "worker-1", 1, Duration.ZERO));
        SessionProjectionTarget executionTarget = new SessionProjectionTarget(
                OWNER, INPUT, Optional.empty(),
                Optional.of(new ExecutionRef(TENANT, "execution-1")),
                Optional.empty(), Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> new StateEventPublication(
                        TENANT, "execution", "execution-1", 1, new ResultRef("fact-1"),
                        executionTarget,
                        new LedgerEvent(
                                new SessionEventDraft(
                                        EVENT, "execution.started", "v1", "fingerprint-1",
                                        trace(), payload(), NOW),
                                "execution.started", "ledger-fingerprint-1", payload()),
                        Set.of("stream-1"), 1, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new StateEventPublication(
                        TENANT, "run", "run-1", 1, new ResultRef("fact-1"),
                        new SessionProjectionTarget(
                                OWNER, INPUT, Optional.of(RUN), Optional.empty(),
                                Optional.empty(), Optional.empty()),
                        new LedgerEvent(
                                new SessionEventDraft(
                                        EVENT, "run.unknown", "v1", "fingerprint-1",
                                        trace(), payload(), NOW),
                                "run.unknown", "ledger-fingerprint-1", payload()),
                        Set.of("stream-1"), 1, NOW));
    }

    /** 验证 claim、ACK、retry 与 mutation result 使用同租户 fence 和正 revision。 */
    @Test
    void validatesPublicationLeaseMutations() {
        StateEventPublication publication = publication();
        LeaseFence fence = new LeaseFence(TENANT, "claim-1", "worker-1", 1, NOW.plusSeconds(30));
        StateEventClaim claim = new StateEventClaim(publication, fence);
        PublicationAcknowledge acknowledge = new PublicationAcknowledge(
                TENANT, "run", "run-1", EVENT, fence, 1, new ResultRef("projection-1"));
        PublicationRetry retry = new PublicationRetry(
                TENANT, "run", "run-1", EVENT, fence, 1, NOW.plusSeconds(5),
                new PortFailure("sink_unavailable", RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                        new ResultRef("failure-1")));

        assertEquals(publication, claim.publication());
        assertEquals(1, acknowledge.expectedPublicationRevision());
        assertEquals(NOW.plusSeconds(5), retry.availableAt());
        assertEquals(publication, new PublicationMutationResult.Applied(publication).publication());
        assertThrows(IllegalArgumentException.class,
                () -> new StateEventClaim(
                        publication,
                        new LeaseFence(new TenantId("tenant-2"), "claim-1", "worker-1", 1, NOW)));
    }

    /** 验证后台工作与规范 RuntimeEvent 的身份引用完整且同租户。 */
    @Test
    void validatesRuntimeWorkAndCommittedEvent() {
        RuntimeWorkIdentity work = new RuntimeWorkIdentity(
                OWNER, Optional.of(RUN), INPUT, trace(), new ResultRef("identity-1"));
        CommittedRuntimeEvent committed = CommittedRuntimeEvent.from(
                publication(), committedSession(), StatePublicationContractTest::decodeQueued);

        assertEquals(Optional.of(RUN), work.owningRunRef());
        assertEquals(new RuntimeEvent.Queued(
                EVENT, SESSION, RUN, 1, 1, NOW, INPUT, trace(), "v1"), committed.event());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeWorkIdentity(
                        OWNER,
                        Optional.of(new RunRef(new TenantId("tenant-2"), "run-2")),
                        INPUT, trace(), new ResultRef("identity-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeWorkIdentity(
                        OWNER, null, INPUT, trace(), new ResultRef("identity-1")));
        CommittedSessionEvent mismatched = new CommittedSessionEvent(
                OWNER, INPUT, Optional.of(RUN), Optional.empty(), Optional.empty(), Optional.empty(),
                new EventId("event-2"), 1, "run.queued", "v1", trace(), payload(), NOW);
        assertThrows(IllegalArgumentException.class,
                () -> CommittedRuntimeEvent.from(
                        publication(), mismatched, StatePublicationContractTest::decodeQueued));
        assertThrows(IllegalArgumentException.class,
                () -> CommittedRuntimeEvent.from(
                        publication(), committedSession(),
                        (eventType, eventVersion, committedPayload) ->
                                new CommittedRuntimeEvent.DecodedRuntimeEventBody.Started()));
    }

    /** 验证专有失败字段只取自接收原 committed payload 的注册 decoder 结果。 */
    @Test
    void decodesSubtypeBodyFromCommittedPayload() {
        TypedPayload failedPayload = new TypedPayload(
                "run.failed.v1", "{\"reasonCode\":\"model_failed\",\"resultRef\":\"result-1\"}");
        SessionEventDraft failedDraft = new SessionEventDraft(
                EVENT, "run.failed", "v1", "fingerprint-failed", trace(), failedPayload, NOW);
        SessionProjectionTarget target = new SessionProjectionTarget(
                OWNER, INPUT, Optional.of(RUN), Optional.empty(), Optional.empty(), Optional.empty());
        StateEventPublication failedPublication = new StateEventPublication(
                TENANT, "run", "run-1", 4, new ResultRef("fact-failed"), target,
                new LedgerEvent(failedDraft, "run.failed", "ledger-failed", failedPayload),
                Set.of("stream-1"), 1, NOW);
        CommittedSessionEvent failedSessionEvent = new CommittedSessionEvent(
                OWNER, INPUT, Optional.of(RUN), Optional.empty(), Optional.empty(), Optional.empty(),
                EVENT, 8, "run.failed", "v1", trace(), failedPayload, NOW);

        CommittedRuntimeEvent committed = CommittedRuntimeEvent.from(
                failedPublication,
                failedSessionEvent,
                (eventType, eventVersion, committedPayload) -> {
                    assertEquals("run.failed", eventType);
                    assertEquals("v1", eventVersion);
                    assertEquals(failedPayload, committedPayload);
                    return new CommittedRuntimeEvent.DecodedRuntimeEventBody.Failed(
                            "model_failed", new ResultRef("result-1"));
                });

        RuntimeEvent.Failed event = (RuntimeEvent.Failed) committed.event();
        assertEquals("model_failed", event.reasonCode());
        assertEquals(new ResultRef("result-1"), event.resultRef());
        assertEquals(4, event.runRevision());
        assertEquals(8, event.sessionSequence());
    }

    /** 构造最小规范 Session event 草稿。 */
    private static SessionEventDraft draft() {
        return new SessionEventDraft(
                EVENT, "run.queued", "v1", "fingerprint-1", trace(), payload(), NOW);
    }

    /** 构造与 publication 完全一致的已提交 Session event。 */
    private static CommittedSessionEvent committedSession() {
        return new CommittedSessionEvent(
                OWNER, INPUT, Optional.of(RUN), Optional.empty(), Optional.empty(), Optional.empty(),
                EVENT, 1, "run.queued", "v1", trace(), payload(), NOW);
    }

    /** 构造 run source 的完整 publication。 */
    private static StateEventPublication publication() {
        return publication("run", "run-1");
    }

    /** 构造指定 source 的 publication 以覆盖负向矩阵。 */
    private static StateEventPublication publication(String sourceKind, String sourceId) {
        SessionProjectionTarget target = new SessionProjectionTarget(
                OWNER, INPUT, Optional.of(RUN), Optional.empty(), Optional.empty(), Optional.empty());
        return new StateEventPublication(
                TENANT, sourceKind, sourceId, 1, new ResultRef("fact-1"), target,
                new LedgerEvent(draft(), "run.queued", "ledger-fingerprint-1", payload()),
                Set.of("stream-1"), 1, NOW);
    }

    /** 断言 decoder 收到原 committed payload 后返回对应 queued body。 */
    private static CommittedRuntimeEvent.DecodedRuntimeEventBody decodeQueued(
            String eventType, String eventVersion, TypedPayload committedPayload) {
        assertEquals("run.queued", eventType);
        assertEquals("v1", eventVersion);
        assertEquals(payload(), committedPayload);
        return new CommittedRuntimeEvent.DecodedRuntimeEventBody.Queued();
    }

    /** 构造测试使用的完整 trace。 */
    private static TraceEnvelope trace() {
        return new TraceEnvelope("trace-1", "span-1", INPUT, Optional.empty());
    }

    /** 构造测试使用的 schema 化载荷。 */
    private static TypedPayload payload() {
        return new TypedPayload("event.v1", "{}");
    }
}

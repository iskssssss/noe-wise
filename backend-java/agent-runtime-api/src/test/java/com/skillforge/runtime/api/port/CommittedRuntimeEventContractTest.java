package com.skillforge.runtime.api.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** CommittedRuntimeEvent只描述已保存事实的规范投影；不声明独立Outbox或外部投递责任。 */
class CommittedRuntimeEventContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final SessionTarget TARGET = SessionTarget.agent(AGENT);
    private static final EndUserId END_USER = new EndUserId(7);
    private static final SessionOwner OWNER = SessionOwner.user(SESSION, TARGET, END_USER);
    private static final InputId INPUT = new InputId("input-1");
    private static final RunRef RUN = new RunRef(TENANT, "run-1");
    private static final EventId EVENT = new EventId("event-1");
    private static final Instant NOW = Instant.parse("2026-09-05T01:00:00Z");

    /** 验证 CommittedRuntimeEvent 工厂逐一投影九类已提交 Run lifecycle body。 */
    @Test
    void projectsEveryCommittedRuntimeEventBody() {
        TypedPayload progressedDetail = payload("run.progressed.detail.v1");
        RunOutcome.Suspended suspendedOutcome = new RunOutcome.Suspended(
                LoopSuspendReason.DELEGATED, "delegation-1", 2);
        RuntimeReport completedReport = new RuntimeReport("done", List.of(), List.of());
        ResultRef failureResult = new ResultRef("run-failure-1");
        RuntimeReport partialReport = new RuntimeReport("partial", List.of(), List.of());
        InputId cancelRequestId = new InputId("cancel-input-1");

        // 九个断言共同冻结 event 元数据来源和每个 subtype 的专有字段映射。
        CommittedRuntimeEvent queued = committedRuntimeEvent(
                "run.queued", new CommittedRuntimeEvent.DecodedRuntimeEventBody.Queued());
        assertEquals(new RuntimeEvent.Queued(
                EVENT, SESSION, RUN, 1, 1, NOW, INPUT, trace(), "v1"), queued.event());
        assertEquals(new RuntimeEvent.Started(
                EVENT, SESSION, RUN, 1, 1, NOW, INPUT, trace(), "v1"),
                committedRuntimeEvent(
                        "run.started",
                        new CommittedRuntimeEvent.DecodedRuntimeEventBody.Started()).event());
        assertEquals(new RuntimeEvent.Progressed(
                        EVENT, SESSION, RUN, 1, 1, NOW, INPUT, trace(), "v1", progressedDetail),
                committedRuntimeEvent(
                        "run.progressed",
                        new CommittedRuntimeEvent.DecodedRuntimeEventBody.Progressed(
                                progressedDetail)).event());
        assertEquals(new RuntimeEvent.Suspended(
                        EVENT, SESSION, RUN, 1, 1, NOW, INPUT, trace(), "v1", suspendedOutcome),
                committedRuntimeEvent(
                        "run.suspended",
                        new CommittedRuntimeEvent.DecodedRuntimeEventBody.Suspended(
                                suspendedOutcome)).event());
        assertEquals(new RuntimeEvent.Resumed(
                EVENT, SESSION, RUN, 1, 1, NOW, INPUT, trace(), "v1"),
                committedRuntimeEvent(
                        "run.resumed",
                        new CommittedRuntimeEvent.DecodedRuntimeEventBody.Resumed()).event());
        assertEquals(new RuntimeEvent.Completed(
                        EVENT, SESSION, RUN, 1, 1, NOW, INPUT, trace(), "v1", completedReport),
                committedRuntimeEvent(
                        "run.completed",
                        new CommittedRuntimeEvent.DecodedRuntimeEventBody.Completed(
                                completedReport)).event());
        assertEquals(new RuntimeEvent.Failed(
                        EVENT, SESSION, RUN, 1, 1, NOW, INPUT, trace(), "v1",
                        "model_failed", failureResult),
                committedRuntimeEvent(
                        "run.failed",
                        new CommittedRuntimeEvent.DecodedRuntimeEventBody.Failed(
                                "model_failed", failureResult)).event());
        assertEquals(new RuntimeEvent.Terminated(
                        EVENT, SESSION, RUN, 1, 1, NOW, INPUT, trace(), "v1",
                        LoopTerminationReason.BUSINESS_MODEL_CALL_BUDGET_EXCEEDED, partialReport),
                committedRuntimeEvent(
                        "run.terminated",
                        new CommittedRuntimeEvent.DecodedRuntimeEventBody.Terminated(
                                LoopTerminationReason.BUSINESS_MODEL_CALL_BUDGET_EXCEEDED,
                                partialReport)).event());
        assertEquals(new RuntimeEvent.Cancelled(
                        EVENT, SESSION, RUN, 1, 1, NOW, INPUT, trace(), "v1", cancelRequestId),
                committedRuntimeEvent(
                        "run.cancelled",
                        new CommittedRuntimeEvent.DecodedRuntimeEventBody.Cancelled(
                                cancelRequestId)).event());

        // 工厂外层元数据必须来自已经互证的 publication 与 committed event。
        assertEquals(OWNER, queued.owner());
        assertEquals(AGENT, queued.agentId());
        assertEquals(END_USER, queued.endUserId());
        assertEquals(INPUT, queued.causationInputId());
        assertEquals(trace(), queued.trace());
        assertEquals("v1", queued.eventVersion());
        assertEquals(new ResultRef("run-fact-1"), queued.committedFactRef());
    }

    /** 验证工厂拒绝 publication 与 committed Session event 的六类事实漂移。 */
    @Test
    void rejectsCommittedRuntimeEventFactDrift() {
        StateEventPublication publication = statePublication();

        // 每个变体只改变一个已提交字段，确保工厂不能跨事实拼装 façade event。
        assertThrows(IllegalArgumentException.class, () -> CommittedRuntimeEvent.from(
                publication,
                committedSessionEvent(
                        OWNER, INPUT, "run.started", "v1", trace(),
                        payload("run.queued.v1"), NOW),
                ignoredDecoder()));
        assertThrows(IllegalArgumentException.class, () -> CommittedRuntimeEvent.from(
                publication,
                committedSessionEvent(
                        OWNER, INPUT, "run.queued", "v2", trace(),
                        payload("run.queued.v1"), NOW),
                ignoredDecoder()));
        assertThrows(IllegalArgumentException.class, () -> CommittedRuntimeEvent.from(
                publication,
                committedSessionEvent(
                        OWNER, INPUT, "run.queued", "v1",
                        new TraceEnvelope("trace-other", "span-other", INPUT, Optional.empty()),
                        payload("run.queued.v1"), NOW),
                ignoredDecoder()));
        assertThrows(IllegalArgumentException.class, () -> CommittedRuntimeEvent.from(
                publication,
                committedSessionEvent(
                        OWNER, INPUT, "run.queued", "v1", trace(),
                        payload("run.queued.changed.v1"), NOW),
                ignoredDecoder()));
        assertThrows(IllegalArgumentException.class, () -> CommittedRuntimeEvent.from(
                publication,
                committedSessionEvent(
                        OWNER, INPUT, "run.queued", "v1", trace(),
                        payload("run.queued.v1"), NOW.plusSeconds(1)),
                ignoredDecoder()));
        assertThrows(IllegalArgumentException.class, () -> CommittedRuntimeEvent.from(
                publication,
                committedSessionEvent(
                        OWNER, new InputId("input-other"), "run.queued", "v1", trace(),
                        payload("run.queued.v1"), NOW),
                ignoredDecoder()));
    }

    /** 验证工厂拒绝缺失 decoder、空 body、未知事件类型和 subtype 错配。 */
    @Test
    void rejectsInvalidCommittedRuntimeEventDecoderResults() {
        StateEventPublication publication = statePublication();
        CommittedSessionEvent committed = committedSessionEvent();

        // decoder 是从私有 committed payload 形成公开 body 的唯一注册边界。
        assertThrows(IllegalArgumentException.class, () ->
                CommittedRuntimeEvent.from(publication, committed, null));
        assertThrows(IllegalArgumentException.class, () -> CommittedRuntimeEvent.from(
                publication, committed,
                (eventType, eventVersion, committedPayload) -> null));
        assertThrows(IllegalArgumentException.class, () -> CommittedRuntimeEvent.from(
                publication,
                committedSessionEvent(
                        OWNER, INPUT, "run.unknown", "v1", trace(),
                        payload("run.queued.v1"), NOW),
                ignoredDecoder()));
        assertThrows(IllegalArgumentException.class, () -> CommittedRuntimeEvent.from(
                publication, committed,
                (eventType, eventVersion, committedPayload) ->
                        new CommittedRuntimeEvent.DecodedRuntimeEventBody.Started()));
    }

    /** 构造指定 lifecycle type 与已解码 body 的规范 RuntimeEvent 投影。 */
    private static CommittedRuntimeEvent committedRuntimeEvent(
            String eventType,
            CommittedRuntimeEvent.DecodedRuntimeEventBody body) {
        TypedPayload eventPayload = payload(eventType + ".v1");
        return CommittedRuntimeEvent.from(
                statePublication(eventType, "v1", trace(), eventPayload, NOW),
                committedSessionEvent(
                        OWNER, INPUT, eventType, "v1", trace(), eventPayload, NOW),
                (decodedEventType, eventVersion, committedPayload) -> body);
    }

    /** 返回仅用于前置事实校验负向用例且不会改变结果的 queued decoder。 */
    private static CommittedRuntimeEvent.RuntimeEventPayloadDecoder ignoredDecoder() {
        return (eventType, eventVersion, committedPayload) ->
                new CommittedRuntimeEvent.DecodedRuntimeEventBody.Queued();
    }

    /** 构造原 Run state publication。 */
    private static StateEventPublication statePublication() {
        return statePublication(
                "run.queued", "v1", trace(), payload("run.queued.v1"), NOW);
    }

    /** 构造指定已提交 Session event 元数据的原 Run state publication。 */
    private static StateEventPublication statePublication(
            String eventType,
            String eventVersion,
            TraceEnvelope eventTrace,
            TypedPayload eventPayload,
            Instant occurredAt) {
        SessionEventDraft draft = new SessionEventDraft(
                EVENT, eventType, eventVersion, "run-event-fingerprint-1",
                eventTrace, eventPayload, occurredAt);
        SessionProjectionTarget target = new SessionProjectionTarget(
                OWNER, INPUT, Optional.of(RUN), Optional.empty(), Optional.empty(), Optional.empty());
        return new StateEventPublication(
                TENANT, "run", RUN.runId(), 1, new ResultRef("run-fact-1"), target,
                new LedgerEvent(
                        draft, eventType, "run-ledger-fingerprint-1",
                        payload(eventType + ".private.v1")),
                Set.of("sink-1"), 1, NOW);
    }

    /** 构造与 publication 完全一致的已提交 Session event。 */
    private static CommittedSessionEvent committedSessionEvent() {
        return committedSessionEvent(
                OWNER, INPUT, "run.queued", "v1", trace(), payload("run.queued.v1"), NOW);
    }

    /** 构造用于逐字段事实互证的已提交 Session event。 */
    private static CommittedSessionEvent committedSessionEvent(
            SessionOwner owner,
            InputId inputId,
            String eventType,
            String eventVersion,
            TraceEnvelope eventTrace,
            TypedPayload eventPayload,
            Instant occurredAt) {
        return new CommittedSessionEvent(
                owner, inputId, Optional.of(RUN), Optional.empty(), Optional.empty(), Optional.empty(),
                EVENT, 1, eventType, eventVersion, eventTrace, eventPayload, occurredAt);
    }

    /** 构造稳定 trace。 */
    private static TraceEnvelope trace() {
        return new TraceEnvelope("trace-1", "span-1", INPUT, Optional.empty());
    }

    /** 构造 provider-neutral typed payload。 */
    private static TypedPayload payload(String schemaId) {
        return new TypedPayload(schemaId, "{}");
    }

}

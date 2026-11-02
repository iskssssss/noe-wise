package com.skillforge.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证运行快照、结果、报告、Session 活动集和九类公共事件的强类型约束。 */
class SnapshotAndEventContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final RunRef RUN = new RunRef(TENANT, "run-1");
    private static final InputId INPUT = new InputId("input-1");
    private static final TraceEnvelope TRACE = new TraceEnvelope(
            "trace-1", "span-1", INPUT, Optional.empty());
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    /** 验证运行中三态不携带 outcome，五个结果态只接受同构 outcome。 */
    @Test
    void enforcesRunSnapshotOutcomeMatrix() {
        for (RunStatus status : List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RESUMING)) {
            new RunSnapshot(RUN, SESSION, status, 1, Optional.empty(), NOW);
            assertThrows(IllegalArgumentException.class,
                    () -> new RunSnapshot(RUN, SESSION, status, 1, Optional.of(completed()), NOW));
        }

        assertMatchingSnapshot(RunStatus.SUSPENDED, suspended());
        assertMatchingSnapshot(RunStatus.COMPLETED, completed());
        assertMatchingSnapshot(RunStatus.TERMINATED, terminated());
        assertMatchingSnapshot(RunStatus.FAILED, failed());
        assertMatchingSnapshot(RunStatus.CANCELLED, cancelled(5), 5);
        assertThrows(IllegalArgumentException.class,
                () -> new RunSnapshot(
                        RUN, SESSION, RunStatus.COMPLETED, 1, Optional.of(failed()), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new RunSnapshot(RUN, SESSION, RunStatus.FAILED, 1, Optional.empty(), NOW));
    }

    /** 验证 RunSnapshot 拒绝跨租户引用、非正 revision 和不相等的取消 revision。 */
    @Test
    void validatesRunSnapshotIdentityAndRevision() {
        SessionRef otherSession = new SessionRef(new TenantId("tenant-2"), "session-2");

        assertThrows(IllegalArgumentException.class,
                () -> new RunSnapshot(RUN, otherSession, RunStatus.RUNNING, 1, Optional.empty(), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new RunSnapshot(RUN, SESSION, RunStatus.RUNNING, 0, Optional.empty(), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new RunSnapshot(
                        RUN, SESSION, RunStatus.CANCELLED, 4, Optional.of(cancelled(3)), NOW));
    }

    /** 验证五类 RunOutcome 的必填字段与正 revision。 */
    @Test
    void validatesAllRunOutcomes() {
        assertThrows(NullPointerException.class, () -> new RunOutcome.Completed(null));
        assertThrows(IllegalArgumentException.class,
                () -> new RunOutcome.Suspended(LoopSuspendReason.AGENT_HITL, " ", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RunOutcome.Suspended(LoopSuspendReason.AGENT_HITL, "pending-1", 0));
        assertThrows(NullPointerException.class,
                () -> new RunOutcome.Terminated(null, report()));
        assertThrows(IllegalArgumentException.class,
                () -> new RunOutcome.Failed(" ", new ResultRef("result-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new RunOutcome.Cancelled(new InputId("cancel-1"), 0));
    }

    /** 验证 RuntimeReport 与 SessionSnapshot 对输入集合进行防御性复制。 */
    @Test
    void defensivelyCopiesPublicCollections() {
        List<Presentation> presentations = new ArrayList<>();
        List<ExportedValue> exports = new ArrayList<>();
        RuntimeReport report = new RuntimeReport("", presentations, exports);
        List<RunRef> activeRuns = new ArrayList<>(List.of(RUN));
        SessionSnapshot snapshot = new SessionSnapshot(SESSION, 1, 0, activeRuns, NOW);

        presentations.add(new Presentation("text", payload()));
        exports.add(new ExportedValue("answer", payload()));
        activeRuns.clear();

        assertEquals(List.of(), report.presentations());
        assertEquals(List.of(), report.exportedValues());
        assertEquals(List.of(RUN), snapshot.activeRuns());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.activeRuns().clear());
    }

    /** 验证 SessionSnapshot 拒绝无效 revision、重复活动运行和跨租户引用。 */
    @Test
    void validatesSessionSnapshotActiveRuns() {
        RunRef otherRun = new RunRef(new TenantId("tenant-2"), "run-2");

        assertThrows(IllegalArgumentException.class,
                () -> new SessionSnapshot(SESSION, 0, 0, List.of(), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionSnapshot(SESSION, 1, -1, List.of(), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionSnapshot(SESSION, 1, 0, List.of(RUN, RUN), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionSnapshot(SESSION, 1, 0, List.of(otherRun), NOW));
    }

    /** 验证 TypedPayload、Presentation 与 ExportedValue 拒绝空白 schema 或类型身份。 */
    @Test
    void validatesTypedReportValues() {
        assertThrows(IllegalArgumentException.class, () -> new TypedPayload(" ", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new TypedPayload("schema.v1", " "));
        assertThrows(IllegalArgumentException.class, () -> new Presentation(" ", payload()));
        assertThrows(IllegalArgumentException.class, () -> new ExportedValue(" ", payload()));
        assertThrows(NullPointerException.class, () -> new RuntimeReport(null, List.of(), List.of()));
    }

    /** 验证九类 RuntimeEvent 均可构造并保留正 sessionSequence 与 runRevision。 */
    @Test
    void constructsAllNineRuntimeEvents() {
        List<RuntimeEvent> events = List.of(
                new RuntimeEvent.Queued(eventId(1), SESSION, RUN, 1, 1, NOW, INPUT, TRACE, "v1"),
                new RuntimeEvent.Started(eventId(2), SESSION, RUN, 2, 2, NOW, INPUT, TRACE, "v1"),
                new RuntimeEvent.Progressed(eventId(3), SESSION, RUN, 3, 3, NOW,
                        INPUT, TRACE, "v1", payload()),
                new RuntimeEvent.Suspended(eventId(4), SESSION, RUN, 4, 4, NOW,
                        INPUT, TRACE, "v1", suspended()),
                new RuntimeEvent.Resumed(eventId(5), SESSION, RUN, 5, 5, NOW, INPUT, TRACE, "v1"),
                new RuntimeEvent.Completed(eventId(6), SESSION, RUN, 6, 6, NOW,
                        INPUT, TRACE, "v1", report()),
                new RuntimeEvent.Failed(
                        eventId(7), SESSION, RUN, 7, 7, NOW, INPUT, TRACE, "v1",
                        "runtime_failed", new ResultRef("result-7")),
                new RuntimeEvent.Terminated(
                        eventId(8), SESSION, RUN, 8, 8, NOW, INPUT, TRACE, "v1",
                        LoopTerminationReason.BUSINESS_MODEL_CALL_BUDGET_EXCEEDED, report()),
                new RuntimeEvent.Cancelled(
                        eventId(9), SESSION, RUN, 9, 9, NOW, INPUT, TRACE, "v1",
                        new InputId("cancel-9")));

        assertEquals(9, events.size());
        assertEquals(9, events.getLast().sessionSequence());
        assertEquals(9, events.getLast().runRevision());
        assertEquals(INPUT, events.getLast().causationInputId());
        assertEquals(TRACE, events.getLast().trace());
        assertEquals("v1", events.getLast().eventVersion());
    }

    /** 验证公共事件拒绝非正序列、非正 revision、跨租户和缺失专有载荷。 */
    @Test
    void rejectsInvalidRuntimeEventFacts() {
        RunRef otherRun = new RunRef(new TenantId("tenant-2"), "run-2");

        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeEvent.Queued(
                        eventId(1), SESSION, RUN, 0, 1, NOW, INPUT, TRACE, "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeEvent.Queued(
                        eventId(1), SESSION, RUN, 1, 0, NOW, INPUT, TRACE, "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeEvent.Queued(
                        eventId(1), SESSION, otherRun, 1, 1, NOW, INPUT, TRACE, "v1"));
        assertThrows(NullPointerException.class,
                () -> new RuntimeEvent.Progressed(
                        eventId(1), SESSION, RUN, 1, 1, NOW, INPUT, TRACE, "v1", null));
        assertThrows(NullPointerException.class,
                () -> new RuntimeEvent.Queued(
                        eventId(1), SESSION, RUN, 1, 1, NOW, null, TRACE, "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeEvent.Queued(
                        eventId(1), SESSION, RUN, 1, 1, NOW, INPUT, TRACE, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeEvent.Failed(
                        eventId(1), SESSION, RUN, 1, 1, NOW, INPUT, TRACE, "v1",
                        " ", new ResultRef("result-1")));
    }

    /** 构造并断言指定状态接受其同构 outcome。 */
    private static void assertMatchingSnapshot(RunStatus status, RunOutcome outcome) {
        assertMatchingSnapshot(status, outcome, 1);
    }

    /** 使用指定运行 revision 构造并断言同构 outcome。 */
    private static void assertMatchingSnapshot(
            RunStatus status,
            RunOutcome outcome,
            long runRevision) {
        RunSnapshot snapshot = new RunSnapshot(
                RUN, SESSION, status, runRevision, Optional.of(outcome), NOW);
        assertEquals(outcome, snapshot.outcome().orElseThrow());
    }

    /** 构造测试使用的完成 outcome。 */
    private static RunOutcome.Completed completed() {
        return new RunOutcome.Completed(report());
    }

    /** 构造测试使用的挂起 outcome。 */
    private static RunOutcome.Suspended suspended() {
        return new RunOutcome.Suspended(LoopSuspendReason.AGENT_HITL, "pending-1", 2);
    }

    /** 构造测试使用的终止 outcome。 */
    private static RunOutcome.Terminated terminated() {
        return new RunOutcome.Terminated(LoopTerminationReason.BUSINESS_MODEL_CALL_BUDGET_EXCEEDED, report());
    }

    /** 构造测试使用的失败 outcome。 */
    private static RunOutcome.Failed failed() {
        return new RunOutcome.Failed("runtime_failed", new ResultRef("result-1"));
    }

    /** 构造测试使用且带指定 revision 的取消 outcome。 */
    private static RunOutcome.Cancelled cancelled(long revision) {
        return new RunOutcome.Cancelled(new InputId("cancel-1"), revision);
    }

    /** 构造测试使用的空公开报告。 */
    private static RuntimeReport report() {
        return new RuntimeReport("", List.of(), List.of());
    }

    /** 构造测试使用的 schema 化 payload。 */
    private static TypedPayload payload() {
        return new TypedPayload("detail.v1", "{}");
    }

    /** 构造测试使用的事件身份。 */
    private static EventId eventId(int index) {
        return new EventId("event-" + index);
    }
}

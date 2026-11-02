package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.LoopTerminationReason;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunOutcome;
import com.skillforge.runtime.api.RuntimeEvent;
import com.skillforge.runtime.api.RuntimeReport;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;

/** 固定由原 publication 与已提交 Session event 证明的 RuntimeEvent 投影。 */
public final class CommittedRuntimeEvent {

    private final RuntimeEvent event;
    private final SessionOwner owner;
    private final AgentId agentId;
    private final EndUserId endUserId;
    private final InputId causationInputId;
    private final TraceEnvelope trace;
    private final String eventVersion;
    private final ResultRef committedFactRef;

    /** 仅允许工厂在完成两份已提交事实的交叉校验后构造。 */
    private CommittedRuntimeEvent(
            RuntimeEvent event,
            SessionOwner owner,
            AgentId agentId,
            EndUserId endUserId,
            InputId causationInputId,
            TraceEnvelope trace,
            String eventVersion,
            ResultRef committedFactRef) {
        this.event = event;
        this.owner = owner;
        this.agentId = agentId;
        this.endUserId = endUserId;
        this.causationInputId = causationInputId;
        this.trace = trace;
        this.eventVersion = eventVersion;
        this.committedFactRef = committedFactRef;
    }

    /** 从原 publication、append 结果和注册 payload decoder 创建规范投影。 */
    public static CommittedRuntimeEvent from(
            StateEventPublication publication,
            CommittedSessionEvent sessionEvent,
            RuntimeEventPayloadDecoder decoder) {
        publication = PortContracts.requireNonNull(publication, "publication");
        sessionEvent = PortContracts.requireNonNull(sessionEvent, "sessionEvent");
        decoder = PortContracts.requireNonNull(decoder, "decoder");
        if (!publication.sourceKind().equals("run")
                || publication.runtimeEventDestinationIds().isEmpty()) {
            throw new IllegalArgumentException("runtime event requires a run publication destination");
        }
        SessionProjectionTarget committedTarget = new SessionProjectionTarget(
                sessionEvent.owner(), sessionEvent.agentId(), sessionEvent.inputId(), sessionEvent.runRef(),
                sessionEvent.executionRef(), sessionEvent.actionRef(), sessionEvent.delegationRef());
        if (!publication.projectionTarget().equals(committedTarget)) {
            throw new IllegalArgumentException("session event must match publication projection target");
        }
        validateSessionEvent(publication.event().sessionEvent(), sessionEvent);
        DecodedRuntimeEventBody body = PortContracts.requireNonNull(
                decoder.decode(
                        sessionEvent.eventType(), sessionEvent.eventVersion(), sessionEvent.payload()),
                "decodedRuntimeEventBody");
        if (!matchesEventType(sessionEvent.eventType(), body)) {
            throw new IllegalArgumentException("decoded runtime event body must match eventType");
        }
        RuntimeEvent event = buildRuntimeEvent(publication, sessionEvent, body);
        return new CommittedRuntimeEvent(
                event,
                sessionEvent.owner(),
                sessionEvent.agentId(),
                sessionEvent.owner().requireUser(),
                sessionEvent.inputId(),
                sessionEvent.trace(),
                sessionEvent.eventVersion(),
                publication.committedFactRef());
    }

    /** 校验 append 结果没有改变原事件的身份、版本、trace、载荷或时间。 */
    private static void validateSessionEvent(
            SessionEventDraft draft, CommittedSessionEvent committed) {
        if (!draft.eventId().equals(committed.eventId())
                || !draft.eventType().equals(committed.eventType())
                || !draft.eventVersion().equals(committed.eventVersion())
                || !draft.trace().equals(committed.trace())
                || !draft.payload().equals(committed.payload())
                || !draft.occurredAt().equals(committed.occurredAt())) {
            throw new IllegalArgumentException("committed session event must match publication event");
        }
    }

    /** 只从注册 decoder 的封闭 body 与已提交元数据构造 façade event。 */
    private static RuntimeEvent buildRuntimeEvent(
            StateEventPublication publication,
            CommittedSessionEvent committed,
            DecodedRuntimeEventBody body) {
        RunRef runRef = committed.runRef().orElseThrow(
                () -> new IllegalArgumentException("runtime event requires committed runRef"));
        return switch (body) {
            case DecodedRuntimeEventBody.Queued ignored -> new RuntimeEvent.Queued(
                    committed.eventId(), committed.owner().sessionRef(), runRef,
                    committed.sessionSequence(), publication.sourceRevision(), committed.occurredAt(),
                    committed.inputId(), committed.trace(), committed.eventVersion());
            case DecodedRuntimeEventBody.Started ignored -> new RuntimeEvent.Started(
                    committed.eventId(), committed.owner().sessionRef(), runRef,
                    committed.sessionSequence(), publication.sourceRevision(), committed.occurredAt(),
                    committed.inputId(), committed.trace(), committed.eventVersion());
            case DecodedRuntimeEventBody.Progressed progressed -> new RuntimeEvent.Progressed(
                    committed.eventId(), committed.owner().sessionRef(), runRef,
                    committed.sessionSequence(), publication.sourceRevision(), committed.occurredAt(),
                    committed.inputId(), committed.trace(), committed.eventVersion(),
                    progressed.detail());
            case DecodedRuntimeEventBody.Suspended suspended -> new RuntimeEvent.Suspended(
                    committed.eventId(), committed.owner().sessionRef(), runRef,
                    committed.sessionSequence(), publication.sourceRevision(), committed.occurredAt(),
                    committed.inputId(), committed.trace(), committed.eventVersion(),
                    suspended.outcome());
            case DecodedRuntimeEventBody.Resumed ignored -> new RuntimeEvent.Resumed(
                    committed.eventId(), committed.owner().sessionRef(), runRef,
                    committed.sessionSequence(), publication.sourceRevision(), committed.occurredAt(),
                    committed.inputId(), committed.trace(), committed.eventVersion());
            case DecodedRuntimeEventBody.Completed completed -> new RuntimeEvent.Completed(
                    committed.eventId(), committed.owner().sessionRef(), runRef,
                    committed.sessionSequence(), publication.sourceRevision(), committed.occurredAt(),
                    committed.inputId(), committed.trace(), committed.eventVersion(),
                    completed.report());
            case DecodedRuntimeEventBody.Failed failed -> new RuntimeEvent.Failed(
                    committed.eventId(), committed.owner().sessionRef(), runRef,
                    committed.sessionSequence(), publication.sourceRevision(), committed.occurredAt(),
                    committed.inputId(), committed.trace(), committed.eventVersion(),
                    failed.reasonCode(), failed.resultRef());
            case DecodedRuntimeEventBody.Terminated terminated -> new RuntimeEvent.Terminated(
                    committed.eventId(), committed.owner().sessionRef(), runRef,
                    committed.sessionSequence(), publication.sourceRevision(), committed.occurredAt(),
                    committed.inputId(), committed.trace(), committed.eventVersion(),
                    terminated.reason(), terminated.partialReport());
            case DecodedRuntimeEventBody.Cancelled cancelled -> new RuntimeEvent.Cancelled(
                    committed.eventId(), committed.owner().sessionRef(), runRef,
                    committed.sessionSequence(), publication.sourceRevision(), committed.occurredAt(),
                    committed.inputId(), committed.trace(), committed.eventVersion(),
                    cancelled.cancelRequestId());
        };
    }

    /** 将 eventType 与注册 decoder 返回的封闭 body 一一对应。 */
    private static boolean matchesEventType(String eventType, DecodedRuntimeEventBody body) {
        return switch (eventType) {
            case "run.queued" -> body instanceof DecodedRuntimeEventBody.Queued;
            case "run.started" -> body instanceof DecodedRuntimeEventBody.Started;
            case "run.progressed" -> body instanceof DecodedRuntimeEventBody.Progressed;
            case "run.suspended" -> body instanceof DecodedRuntimeEventBody.Suspended;
            case "run.resumed" -> body instanceof DecodedRuntimeEventBody.Resumed;
            case "run.completed" -> body instanceof DecodedRuntimeEventBody.Completed;
            case "run.failed" -> body instanceof DecodedRuntimeEventBody.Failed;
            case "run.terminated" -> body instanceof DecodedRuntimeEventBody.Terminated;
            case "run.cancelled" -> body instanceof DecodedRuntimeEventBody.Cancelled;
            default -> false;
        };
    }

    /** 按 eventType/eventVersion 注册并只解码本次 committed payload。 */
    @FunctionalInterface
    public interface RuntimeEventPayloadDecoder {
        /** 返回从指定 committed payload 解出的封闭事件专有 body。 */
        DecodedRuntimeEventBody decode(
                String eventType, String eventVersion, TypedPayload committedPayload);
    }

    /** 封闭注册 decoder 可以返回的九类 Run lifecycle 专有 body。 */
    public sealed interface DecodedRuntimeEventBody
            permits DecodedRuntimeEventBody.Queued,
                    DecodedRuntimeEventBody.Started,
                    DecodedRuntimeEventBody.Progressed,
                    DecodedRuntimeEventBody.Suspended,
                    DecodedRuntimeEventBody.Resumed,
                    DecodedRuntimeEventBody.Completed,
                    DecodedRuntimeEventBody.Failed,
                    DecodedRuntimeEventBody.Terminated,
                    DecodedRuntimeEventBody.Cancelled {

        /** 表示无专有字段的 queued 事件。 */
        record Queued() implements DecodedRuntimeEventBody {}

        /** 表示无专有字段的 started 事件。 */
        record Started() implements DecodedRuntimeEventBody {}

        /** 表示从 committed payload 解出的进度详情。 */
        record Progressed(TypedPayload detail) implements DecodedRuntimeEventBody {
            /** 拒绝缺失详情。 */
            public Progressed { detail = PortContracts.requireNonNull(detail, "detail"); }
        }

        /** 表示从 committed payload 解出的挂起结果。 */
        record Suspended(RunOutcome.Suspended outcome) implements DecodedRuntimeEventBody {
            /** 拒绝缺失挂起结果。 */
            public Suspended { outcome = PortContracts.requireNonNull(outcome, "outcome"); }
        }

        /** 表示无专有字段的 resumed 事件。 */
        record Resumed() implements DecodedRuntimeEventBody {}

        /** 表示从 committed payload 解出的完成报告。 */
        record Completed(RuntimeReport report) implements DecodedRuntimeEventBody {
            /** 拒绝缺失报告。 */
            public Completed { report = PortContracts.requireNonNull(report, "report"); }
        }

        /** 表示从 committed payload 解出的稳定失败字段。 */
        record Failed(String reasonCode, ResultRef resultRef) implements DecodedRuntimeEventBody {
            /** 拒绝空白原因或缺失结果引用。 */
            public Failed {
                reasonCode = PortContracts.requireText(reasonCode, "reasonCode");
                resultRef = PortContracts.requireNonNull(resultRef, "resultRef");
            }
        }

        /** 表示从 committed payload 解出的终止原因和部分报告。 */
        record Terminated(LoopTerminationReason reason, RuntimeReport partialReport)
                implements DecodedRuntimeEventBody {
            /** 拒绝缺失终止原因或部分报告。 */
            public Terminated {
                reason = PortContracts.requireNonNull(reason, "reason");
                partialReport = PortContracts.requireNonNull(partialReport, "partialReport");
            }
        }

        /** 表示从 committed payload 解出的取消请求身份。 */
        record Cancelled(InputId cancelRequestId) implements DecodedRuntimeEventBody {
            /** 拒绝缺失取消请求身份。 */
            public Cancelled {
                cancelRequestId = PortContracts.requireNonNull(cancelRequestId, "cancelRequestId");
            }
        }
    }

    /** 返回已验证的 façade RuntimeEvent。 */
    public RuntimeEvent event() { return event; }

    /** 返回投影目标 Agent。 */
    public AgentId agentId() { return agentId; }

    /** 返回由 publication 与 Session event 共同证明的完整 Session owner。 */
    public SessionOwner owner() { return owner; }

    /** 返回投影目标终端用户。 */
    public EndUserId endUserId() { return endUserId; }

    /** 返回生成该事件的因果输入。 */
    public InputId causationInputId() { return causationInputId; }

    /** 返回已提交 Session event 的 trace。 */
    public TraceEnvelope trace() { return trace; }

    /** 返回已提交 Session event 的 schema 版本。 */
    public String eventVersion() { return eventVersion; }

    /** 返回原领域 mutation 的已提交事实引用。 */
    public ResultRef committedFactRef() { return committedFactRef; }
}

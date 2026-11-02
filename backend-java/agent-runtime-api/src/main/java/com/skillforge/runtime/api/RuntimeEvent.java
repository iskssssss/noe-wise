package com.skillforge.runtime.api;

import java.time.Instant;

/**
 * 表示已提交 façade run 生命周期事件，并统一暴露 Session 序列与 Run revision。
 */
public sealed interface RuntimeEvent
        permits RuntimeEvent.Queued,
                RuntimeEvent.Started,
                RuntimeEvent.Progressed,
                RuntimeEvent.Suspended,
                RuntimeEvent.Resumed,
                RuntimeEvent.Completed,
                RuntimeEvent.Failed,
                RuntimeEvent.Terminated,
                RuntimeEvent.Cancelled {

    /** 返回已提交事件的稳定身份。 */
    EventId eventId();

    /** 返回事件所属 Session。 */
    SessionRef sessionRef();

    /** 返回事件所属持久运行。 */
    RunRef runRef();

    /** 返回 Session 内严格递增的已提交事件序列。 */
    long sessionSequence();

    /** 返回同一 RunRef 单调递增的运行 revision。 */
    long runRevision();

    /** 返回事件实际提交发生的时间。 */
    Instant occurredAt();

    /** 返回产生该事件的已提交输入。 */
    InputId causationInputId();

    /** 返回原事件的完整 trace 与根因果链。 */
    TraceEnvelope trace();

    /** 返回已提交事件载荷的 schema 版本。 */
    String eventVersion();

    /** 表示已经创建可领取持久事实的排队事件。 */
    record Queued(
            EventId eventId,
            SessionRef sessionRef,
            RunRef runRef,
            long sessionSequence,
            long runRevision,
            Instant occurredAt,
            InputId causationInputId,
            TraceEnvelope trace,
            String eventVersion) implements RuntimeEvent {

        /** 校验已提交事件的身份、正序列、正 revision、时间和租户。 */
        public Queued {
            ApiContracts.validateEvent(
                    eventId, sessionRef, runRef, sessionSequence, runRevision, occurredAt);
            validateSource(causationInputId, trace, eventVersion);
        }
    }

    /** 表示 AgentRun 已进入 running 的启动事件。 */
    record Started(
            EventId eventId,
            SessionRef sessionRef,
            RunRef runRef,
            long sessionSequence,
            long runRevision,
            Instant occurredAt,
            InputId causationInputId,
            TraceEnvelope trace,
            String eventVersion) implements RuntimeEvent {

        /** 校验已提交事件的身份、正序列、正 revision、时间和租户。 */
        public Started {
            ApiContracts.validateEvent(
                    eventId, sessionRef, runRef, sessionSequence, runRevision, occurredAt);
            validateSource(causationInputId, trace, eventVersion);
        }
    }

    /** 表示已经提交 step 或 observation 的进展事件。 */
    record Progressed(
            EventId eventId,
            SessionRef sessionRef,
            RunRef runRef,
            long sessionSequence,
            long runRevision,
            Instant occurredAt,
            InputId causationInputId,
            TraceEnvelope trace,
            String eventVersion,
            TypedPayload detail) implements RuntimeEvent {

        /** 校验公共事件字段并拒绝缺失的 schema 化进展详情。 */
        public Progressed {
            ApiContracts.validateEvent(
                    eventId, sessionRef, runRef, sessionSequence, runRevision, occurredAt);
            validateSource(causationInputId, trace, eventVersion);
            detail = ApiContracts.requireNonNull(detail, "detail");
        }
    }

    /** 表示四类 Loop 原因之一已经形成持久挂起事实。 */
    record Suspended(
            EventId eventId,
            SessionRef sessionRef,
            RunRef runRef,
            long sessionSequence,
            long runRevision,
            Instant occurredAt,
            InputId causationInputId,
            TraceEnvelope trace,
            String eventVersion,
            RunOutcome.Suspended outcome) implements RuntimeEvent {

        /** 校验公共事件字段并拒绝缺失的挂起结果。 */
        public Suspended {
            ApiContracts.validateEvent(
                    eventId, sessionRef, runRef, sessionSequence, runRevision, occurredAt);
            validateSource(causationInputId, trace, eventVersion);
            outcome = ApiContracts.requireNonNull(outcome, "outcome");
        }
    }

    /** 表示恢复 CAS 已提交的恢复事件。 */
    record Resumed(
            EventId eventId,
            SessionRef sessionRef,
            RunRef runRef,
            long sessionSequence,
            long runRevision,
            Instant occurredAt,
            InputId causationInputId,
            TraceEnvelope trace,
            String eventVersion) implements RuntimeEvent {

        /** 校验已提交事件的身份、正序列、正 revision、时间和租户。 */
        public Resumed {
            ApiContracts.validateEvent(
                    eventId, sessionRef, runRef, sessionSequence, runRevision, occurredAt);
            validateSource(causationInputId, trace, eventVersion);
        }
    }

    /** 表示运行正常完成并带有最终公开报告。 */
    record Completed(
            EventId eventId,
            SessionRef sessionRef,
            RunRef runRef,
            long sessionSequence,
            long runRevision,
            Instant occurredAt,
            InputId causationInputId,
            TraceEnvelope trace,
            String eventVersion,
            RuntimeReport report) implements RuntimeEvent {

        /** 校验公共事件字段并拒绝缺失的完成报告。 */
        public Completed {
            ApiContracts.validateEvent(
                    eventId, sessionRef, runRef, sessionSequence, runRevision, occurredAt);
            validateSource(causationInputId, trace, eventVersion);
            report = ApiContracts.requireNonNull(report, "report");
        }
    }

    /** 表示 RunRef 分配后的编排或技术失败终态。 */
    record Failed(
            EventId eventId,
            SessionRef sessionRef,
            RunRef runRef,
            long sessionSequence,
            long runRevision,
            Instant occurredAt,
            InputId causationInputId,
            TraceEnvelope trace,
            String eventVersion,
            String reasonCode,
            ResultRef resultRef) implements RuntimeEvent {

        /** 校验公共事件字段、稳定失败原因和结果审计引用。 */
        public Failed {
            ApiContracts.validateEvent(
                    eventId, sessionRef, runRef, sessionSequence, runRevision, occurredAt);
            validateSource(causationInputId, trace, eventVersion);
            reasonCode = ApiContracts.requireText(reasonCode, "reasonCode");
            resultRef = ApiContracts.requireNonNull(resultRef, "resultRef");
        }
    }

    /** 表示 Loop 按稳定原因终止并携带已提交部分报告。 */
    record Terminated(
            EventId eventId,
            SessionRef sessionRef,
            RunRef runRef,
            long sessionSequence,
            long runRevision,
            Instant occurredAt,
            InputId causationInputId,
            TraceEnvelope trace,
            String eventVersion,
            LoopTerminationReason reason,
            RuntimeReport partialReport) implements RuntimeEvent {

        /** 校验公共事件字段、终止原因和部分报告。 */
        public Terminated {
            ApiContracts.validateEvent(
                    eventId, sessionRef, runRef, sessionSequence, runRevision, occurredAt);
            validateSource(causationInputId, trace, eventVersion);
            reason = ApiContracts.requireNonNull(reason, "reason");
            partialReport = ApiContracts.requireNonNull(partialReport, "partialReport");
        }
    }

    /** 表示运行已经按取消输入收敛到取消终态。 */
    record Cancelled(
            EventId eventId,
            SessionRef sessionRef,
            RunRef runRef,
            long sessionSequence,
            long runRevision,
            Instant occurredAt,
            InputId causationInputId,
            TraceEnvelope trace,
            String eventVersion,
            InputId cancelRequestId) implements RuntimeEvent {

        /** 校验公共事件字段并拒绝缺失的取消输入身份。 */
        public Cancelled {
            ApiContracts.validateEvent(
                    eventId, sessionRef, runRef, sessionSequence, runRevision, occurredAt);
            validateSource(causationInputId, trace, eventVersion);
            cancelRequestId = ApiContracts.requireNonNull(cancelRequestId, "cancelRequestId");
        }
    }

    private static void validateSource(
            InputId causationInputId, TraceEnvelope trace, String eventVersion) {
        ApiContracts.requireNonNull(causationInputId, "causationInputId");
        ApiContracts.requireNonNull(trace, "trace");
        ApiContracts.requireText(eventVersion, "eventVersion");
    }
}

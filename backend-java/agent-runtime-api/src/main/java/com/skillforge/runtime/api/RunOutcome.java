package com.skillforge.runtime.api;

/**
 * 将 Loop 和 AgentRun 权威事实投影为 façade 可穷尽处理的五类结果。
 */
public sealed interface RunOutcome
        permits RunOutcome.Completed,
                RunOutcome.Suspended,
                RunOutcome.Terminated,
                RunOutcome.Failed,
                RunOutcome.Cancelled {

    /** 表示 Loop 正常完成并生成公开报告。 */
    record Completed(RuntimeReport report) implements RunOutcome {

        /** 拒绝缺失的完成报告。 */
        public Completed {
            report = ApiContracts.requireNonNull(report, "report");
        }
    }

    /** 表示 Loop 已通过 checkpoint 持久挂起。 */
    record Suspended(
            LoopSuspendReason reason,
            String suspendRef,
            long checkpointRevision) implements RunOutcome {

        /** 拒绝缺失原因、空白挂起引用与无效 checkpoint revision。 */
        public Suspended {
            reason = ApiContracts.requireNonNull(reason, "reason");
            suspendRef = ApiContracts.requireText(suspendRef, "suspendRef");
            checkpointRevision = ApiContracts.requirePositive(
                    checkpointRevision,
                    "checkpointRevision");
        }
    }

    /** 表示 Loop 按稳定终止原因结束并保留已提交的部分报告。 */
    record Terminated(
            LoopTerminationReason reason,
            RuntimeReport partialReport) implements RunOutcome {

        /** 拒绝缺失终止原因或部分报告。 */
        public Terminated {
            reason = ApiContracts.requireNonNull(reason, "reason");
            partialReport = ApiContracts.requireNonNull(partialReport, "partialReport");
        }
    }

    /** 表示 RunRef 分配后发生的稳定编排或技术失败。 */
    record Failed(String reasonCode, ResultRef resultRef) implements RunOutcome {

        /** 拒绝空白原因码或缺失结果审计引用。 */
        public Failed {
            reasonCode = ApiContracts.requireText(reasonCode, "reasonCode");
            resultRef = ApiContracts.requireNonNull(resultRef, "resultRef");
        }
    }

    /** 表示运行已经按某个取消输入收敛到取消终态。 */
    record Cancelled(InputId cancelRequestId, long runRevision) implements RunOutcome {

        /** 拒绝缺失取消输入或无效运行 revision。 */
        public Cancelled {
            cancelRequestId = ApiContracts.requireNonNull(cancelRequestId, "cancelRequestId");
            runRevision = ApiContracts.requirePositive(runRevision, "runRevision");
        }
    }
}

package com.skillforge.runtime.api;

import java.time.Instant;
import java.util.Optional;

/** 表示一个持久 RunRef 的状态、revision 与同构 outcome 快照。 */
public record RunSnapshot(
        RunRef runRef,
        SessionRef sessionRef,
        RunStatus status,
        long runRevision,
        Optional<RunOutcome> outcome,
        Instant updatedAt) {

    /** 拒绝跨租户身份、无效 revision 以及状态与 outcome 不同构的快照。 */
    public RunSnapshot {
        runRef = ApiContracts.requireNonNull(runRef, "runRef");
        sessionRef = ApiContracts.requireNonNull(sessionRef, "sessionRef");
        status = ApiContracts.requireNonNull(status, "status");
        runRevision = ApiContracts.requirePositive(runRevision, "runRevision");
        outcome = ApiContracts.normalize(outcome);
        updatedAt = ApiContracts.requireNonNull(updatedAt, "updatedAt");
        ApiContracts.requireSameTenant(sessionRef, runRef);

        // 运行中状态禁止伪造结果；终态和挂起态必须携带精确的同构结果。
        Class<? extends RunOutcome> expectedOutcome = switch (status) {
            case QUEUED, RUNNING, RESUMING -> null;
            case SUSPENDED -> RunOutcome.Suspended.class;
            case COMPLETED -> RunOutcome.Completed.class;
            case TERMINATED -> RunOutcome.Terminated.class;
            case FAILED -> RunOutcome.Failed.class;
            case CANCELLED -> RunOutcome.Cancelled.class;
        };
        if (expectedOutcome == null && outcome.isPresent()) {
            throw new IllegalArgumentException(status + " snapshot must not carry outcome");
        }
        if (expectedOutcome != null
                && (outcome.isEmpty() || !expectedOutcome.isInstance(outcome.get()))) {
            throw new IllegalArgumentException(status + " snapshot requires matching outcome");
        }

        // 取消 outcome 的 revision 与承载它的运行快照必须精确相等。
        if (outcome.orElse(null) instanceof RunOutcome.Cancelled cancelled
                && cancelled.runRevision() != runRevision) {
            throw new IllegalArgumentException("cancelled outcome runRevision must match snapshot");
        }
    }
}

package com.skillforge.runtime.api.port;

import java.time.Instant;

/** 固定一次外部调用的最早截止时间与取消信号。 */
public record InvocationControl(Instant deadlineAt, CancellationSignal cancellationSignal) {
    /** 拒绝缺失截止时间或取消信号。 */
    public InvocationControl {
        deadlineAt = PortContracts.requireNonNull(deadlineAt, "deadlineAt");
        cancellationSignal = PortContracts.requireNonNull(cancellationSignal, "cancellationSignal");
    }
}

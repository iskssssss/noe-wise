package com.skillforge.core.model;

import java.time.Instant;
import java.util.Objects;

/** 显式配置测试事实；修订 0 表示未保存输入。接受参数不证明实际执行或缓存命中。 */
public record ModelCompatibilityReport(long draftRevision, Instant testedAt, Status status,
        Check configurationAcceptance, Check streaming, Check toolContinuation,
        long durationMs, String reasonCode) {
    public enum Status { PASSED, FAILED, UNKNOWN, NOT_RUN }
    public record Check(Status status, long durationMs, String reasonCode) {
        public Check { Objects.requireNonNull(status); if (durationMs < 0) throw new IllegalArgumentException("negative duration"); }
    }
    public ModelCompatibilityReport {
        if (draftRevision < 0 || durationMs < 0) throw new IllegalArgumentException("invalid compatibility report");
        Objects.requireNonNull(testedAt); Objects.requireNonNull(status);
        Objects.requireNonNull(configurationAcceptance); Objects.requireNonNull(streaming); Objects.requireNonNull(toolContinuation);
    }
}

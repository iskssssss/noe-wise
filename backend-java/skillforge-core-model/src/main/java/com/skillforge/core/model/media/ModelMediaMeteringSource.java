package com.skillforge.core.model.media;

import com.skillforge.core.billing.metering.CostContext;
import java.util.Objects;

/** 受信宿主提供的首次媒体调用来源；发送前仅在统一用量起始事件冻结一次。 */
public record ModelMediaMeteringSource(CostContext context, String toolInvocationId, String toolAttemptId) {
    public ModelMediaMeteringSource {
        Objects.requireNonNull(context, "model_media_metering_source_required");
        if (context.traceContext() == null || context.runId() == null || context.attributionRunId() == null
                || context.sessionId() == null || context.rootInputId() == null || context.agentId() == null
                || toolInvocationId == null || toolInvocationId.isBlank() || toolAttemptId == null || toolAttemptId.isBlank())
            throw new IllegalArgumentException("model_media_metering_source_required");
    }
    @Override public String toString() { return "ModelMediaMeteringSource[REDACTED]"; }
}

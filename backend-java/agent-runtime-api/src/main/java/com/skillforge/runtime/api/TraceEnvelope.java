package com.skillforge.runtime.api;

import java.util.Optional;

/** 携带 Unified Inbound 的完整 trace、span、根输入与可选因果事件。 */
public record TraceEnvelope(
        String traceId,
        String spanId,
        InputId rootInputId,
        Optional<EventId> causedByEventId) {

    /** 拒绝不完整 trace，并将缺省因果事件规范化为空 Optional。 */
    public TraceEnvelope {
        traceId = ApiContracts.requireText(traceId, "traceId");
        spanId = ApiContracts.requireText(spanId, "spanId");
        rootInputId = ApiContracts.requireNonNull(rootInputId, "rootInputId");
        causedByEventId = ApiContracts.normalize(causedByEventId);
    }
}

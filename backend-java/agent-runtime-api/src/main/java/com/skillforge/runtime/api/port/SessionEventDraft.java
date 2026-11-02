package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import java.time.Instant;

/** 表示尚未分配 Session sequence 的规范事件草稿。 */
public record SessionEventDraft(
        EventId eventId,
        String eventType,
        String eventVersion,
        String eventFingerprint,
        TraceEnvelope trace,
        TypedPayload payload,
        Instant occurredAt) {
    /** 拒绝缺失事件身份、schema、指纹、trace、载荷或时间。 */
    public SessionEventDraft {
        eventId = PortContracts.requireNonNull(eventId, "eventId");
        eventType = PortContracts.requireText(eventType, "eventType");
        eventVersion = PortContracts.requireText(eventVersion, "eventVersion");
        eventFingerprint = PortContracts.requireText(eventFingerprint, "eventFingerprint");
        trace = PortContracts.requireNonNull(trace, "trace");
        payload = PortContracts.requireNonNull(payload, "payload");
        occurredAt = PortContracts.requireNonNull(occurredAt, "occurredAt");
    }
}

package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.TypedPayload;

/** 同时承载公开 Session 事件草稿与账本私有载荷。 */
public record LedgerEvent(
        SessionEventDraft sessionEvent,
        String ledgerEventType,
        String ledgerFingerprint,
        TypedPayload privatePayload) {
    /** 拒绝缺失公开/私有事件或空白账本身份。 */
    public LedgerEvent {
        sessionEvent = PortContracts.requireNonNull(sessionEvent, "sessionEvent");
        ledgerEventType = PortContracts.requireText(ledgerEventType, "ledgerEventType");
        ledgerFingerprint = PortContracts.requireText(ledgerFingerprint, "ledgerFingerprint");
        privatePayload = PortContracts.requireNonNull(privatePayload, "privatePayload");
    }
}

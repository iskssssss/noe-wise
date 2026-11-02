package com.skillforge.core.chat.session;

import java.util.Objects;

/** Outbox 锁行保存的投影处理回执；错误码只指示该来源版本的永久拒绝。 */
public record SessionOutboxProjectionRecord(Long sourceSequence, String sourceEventId,
        String policyVersion, String errorCode) {
    public boolean rejects(SessionOutboxProjectionSource source) {
        return errorCode != null && Objects.equals(sourceSequence, source.sequence())
                && Objects.equals(sourceEventId, source.eventId())
                && Objects.equals(policyVersion, source.policyVersion());
    }
}

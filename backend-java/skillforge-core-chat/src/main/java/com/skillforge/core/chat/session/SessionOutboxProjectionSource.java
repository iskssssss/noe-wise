package com.skillforge.core.chat.session;

/** 某次投影的不可变日志水位和当前公开政策；不使用 Outbox 传输序号替代来源序号。 */
public record SessionOutboxProjectionSource(long sequence, String eventId, String policyVersion) {
    public SessionOutboxProjectionSource {
        if (sequence < 0 || eventId == null || (sequence > 0 && eventId.isBlank())
                || policyVersion == null || policyVersion.isBlank())
            throw new IllegalArgumentException("projection source must include a valid watermark and policy");
    }
}

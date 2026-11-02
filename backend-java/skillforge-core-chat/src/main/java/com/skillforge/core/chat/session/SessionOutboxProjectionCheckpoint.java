package com.skillforge.core.chat.session;

/** 与本批 Outbox 输出原子提交的公开投影状态；拒绝回执不推进此水位。 */
public record SessionOutboxProjectionCheckpoint(long sequence, String eventId, String policyVersion, String stateJson) {
    public SessionOutboxProjectionCheckpoint {
        if (sequence < 0 || eventId == null || policyVersion == null || stateJson == null)
            throw new IllegalArgumentException("projection_checkpoint_invalid");
    }
    public static SessionOutboxProjectionCheckpoint empty(String policy) {
        return new SessionOutboxProjectionCheckpoint(0, "", policy, "{}");
    }
    public boolean covers(SessionOutboxProjectionSource source) {
        return policyVersion.equals(source.policyVersion()) && (sequence > source.sequence()
                || sequence == source.sequence() && eventId.equals(source.eventId()));
    }
}

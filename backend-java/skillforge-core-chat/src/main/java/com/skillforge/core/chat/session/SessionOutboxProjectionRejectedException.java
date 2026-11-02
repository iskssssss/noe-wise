package com.skillforge.core.chat.session;

/** 同一不可变来源和政策已经永久拒绝；仅跳过该投影，不能生成完成或发布事实。 */
public final class SessionOutboxProjectionRejectedException extends RuntimeException {
    private final String errorCode;
    public SessionOutboxProjectionRejectedException(String errorCode) {
        super("session_outbox_projection_rejected");
        if (errorCode == null || errorCode.isBlank())
            throw new IllegalArgumentException("projection rejection code is required");
        this.errorCode = errorCode;
    }
    public String errorCode() { return errorCode; }
}

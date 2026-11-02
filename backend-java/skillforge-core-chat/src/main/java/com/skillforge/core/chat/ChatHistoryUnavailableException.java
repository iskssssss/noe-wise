package com.skillforge.core.chat;

import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 当前不可变历史与公开协议不匹配；错误正文不包含原快照、内部身份或解析细节。 */
public final class ChatHistoryUnavailableException extends ApiException {
    public static final String CODE = "CHAT_HISTORY_UNSUPPORTED";
    public static final String MESSAGE = "该会话历史不符合当前格式，无法读取。请新建会话继续。";

    public enum Reason {
        UNSUPPORTED_ACTION_FORMAT,
        UNREADABLE_ACTION_SNAPSHOT,
        UNSUPPORTED_RUNTIME_EVENT,
        UNREADABLE_RUNTIME_EVENT
    }

    private final Reason reason;
    private final boolean newlyRecorded;

    public ChatHistoryUnavailableException(Reason reason, Throwable cause) {
        this(reason, cause, false);
    }

    private ChatHistoryUnavailableException(Reason reason, Throwable cause, boolean newlyRecorded) {
        super(HttpStatus.CONFLICT, CODE, MESSAGE, false);
        this.reason = java.util.Objects.requireNonNull(reason);
        this.newlyRecorded = newlyRecorded;
        if (cause != null) initCause(cause);
    }

    public Reason reason() { return reason; }
    public boolean newlyRecorded() { return newlyRecorded; }
    public ChatHistoryUnavailableException withRecorded(boolean recorded) {
        return new ChatHistoryUnavailableException(reason, getCause(), recorded);
    }
}

package com.skillforge.core.foundation.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 非会话控制操作及其逐项结果状态。 */
public enum ControlOperationStatus {
    ACCEPTED("accepted"),
    PROCESSING("processing"),
    REVIEW_REQUIRED("review_required"),
    SUCCEEDED("succeeded"),
    PARTIALLY_SUCCEEDED("partially_succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    ControlOperationStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /** 持久化与 HTTP 使用同一声明值，未知状态不得恢复为默认状态。 */
    @JsonCreator
    public static ControlOperationStatus fromValue(String value) {
        for (ControlOperationStatus status : values()) {
            if (status.value.equals(value)) return status;
        }
        throw new IllegalArgumentException("unknown control operation status");
    }

    public boolean terminal() {
        return this == SUCCEEDED || this == PARTIALLY_SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** 状态机允许的单向迁移；终态没有后继。 */
    public boolean canTransitionTo(ControlOperationStatus next) {
        if (next == null || terminal() || next == this) {
            return false;
        }
        return switch (this) {
            case ACCEPTED -> next == PROCESSING || next == REVIEW_REQUIRED || next == CANCELLED;
            case PROCESSING -> next == REVIEW_REQUIRED || next == SUCCEEDED
                    || next == PARTIALLY_SUCCEEDED || next == FAILED || next == CANCELLED;
            case REVIEW_REQUIRED -> next == PROCESSING || next == FAILED;
            default -> false;
        };
    }
}

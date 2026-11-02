package com.skillforge.core.foundation.contract;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/** 控制操作中的单项结果；部分失败不得被折叠成整体成功。 */
public record ControlOperationItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String itemKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String resourceRef,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ControlOperationStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"})
        String resultRef,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"})
        String errorCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean retryable) {

    public ControlOperationItem {
        itemKey = required(itemKey, "itemKey");
        resourceRef = required(resourceRef, "resourceRef");
        Objects.requireNonNull(status, "status");
        resultRef = blankToNull(resultRef);
        errorCode = blankToNull(errorCode);
        if (status == ControlOperationStatus.SUCCEEDED && resultRef == null) {
            throw new IllegalArgumentException("succeeded item requires resultRef");
        }
        if (status == ControlOperationStatus.FAILED && errorCode == null) {
            throw new IllegalArgumentException("failed item requires errorCode");
        }
        if (retryable && status != ControlOperationStatus.FAILED) {
            throw new IllegalArgumentException("only failed item can be retryable");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

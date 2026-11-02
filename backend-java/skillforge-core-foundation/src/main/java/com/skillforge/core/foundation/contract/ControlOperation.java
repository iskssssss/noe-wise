package com.skillforge.core.foundation.contract;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 非会话控制操作的不可变受理/结果投影。 */
public record ControlOperation(
        String operationId,
        String rootOperationId,
        String tenantId,
        String integrationId,
        String operationType,
        ControlOperationStatus status,
        String idempotencyKey,
        String requestFingerprint,
        List<ControlOperationItem> items,
        Instant acceptedAt,
        Instant completedAt) {

    public ControlOperation {
        operationId = required(operationId, "operationId");
        rootOperationId = required(rootOperationId, "rootOperationId");
        tenantId = required(tenantId, "tenantId");
        integrationId = required(integrationId, "integrationId");
        operationType = required(operationType, "operationType");
        Objects.requireNonNull(status, "status");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        requestFingerprint = required(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(items, "items");
        List<ControlOperationItem> copy = List.copyOf(items);
        Set<String> keys = new HashSet<>();
        for (ControlOperationItem item : copy) {
            Objects.requireNonNull(item, "items must not contain null");
            if (!keys.add(item.itemKey())) {
                throw new IllegalArgumentException("duplicate operation item: " + item.itemKey());
            }
        }
        items = copy;
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (status.terminal() && completedAt == null) {
            throw new IllegalArgumentException("terminal operation requires completedAt");
        }
        if (!status.terminal() && completedAt != null) {
            throw new IllegalArgumentException("non-terminal operation cannot have completedAt");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

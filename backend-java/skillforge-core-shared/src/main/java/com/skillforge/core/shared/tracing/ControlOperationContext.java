package com.skillforge.core.shared.tracing;

import java.util.Objects;

/** 资源发布、知识摄取和对账等控制操作的持久因果上下文。 */
public record ControlOperationContext(
        String operationId,
        String rootOperationId,
        String traceId,
        String spanId,
        String eventId,
        String causedByEventId) implements InvocationContext {

    public ControlOperationContext {
        operationId = requireText(operationId, "operationId");
        rootOperationId = requireText(rootOperationId, "rootOperationId");
        traceId = requireText(traceId, "traceId");
        spanId = requireText(spanId, "spanId");
        eventId = requireText(eventId, "eventId");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

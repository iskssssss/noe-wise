package com.skillforge.core.shared.tracing;

import java.util.Objects;

/** 会话输入的持久业务因果与当前技术尝试关联。 */
public record TraceContext(
        String inputId,
        String rootInputId,
        String traceId,
        String spanId,
        String eventId,
        String causedByEventId) implements InvocationContext {

    public TraceContext {
        inputId = requireText(inputId, "inputId");
        rootInputId = requireText(rootInputId, "rootInputId");
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

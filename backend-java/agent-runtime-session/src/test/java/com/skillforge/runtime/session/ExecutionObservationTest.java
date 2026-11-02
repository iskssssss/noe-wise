package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import org.junit.jupiter.api.Test;

/** 公开观测协议只保留可验证的关联与状态，不能把缺失时间或技术Trace补造为成功。 */
class ExecutionObservationTest {
    private static final CodecLimits LIMITS = new CodecLimits(100_000, 10_000, 32);

    @Test void currentProtocolRoundTripsWithUnknownTimingAndAbsentResumeTrace() {
        var fact = fact("attempt-2", "reply-input", null, null, ExecutionObservation.Status.UNKNOWN);
        var payload = ExecutionObservation.encode(fact, LIMITS);
        assertEquals(fact, ExecutionObservation.decode(payload, LIMITS));
        assertNull(fact.elapsedNanos());
        assertNull(fact.traceId());
        assertNull(fact.spanId());
        assertFalse(payload.canonicalJson().contains("prompt"));
        assertFalse(payload.canonicalJson().contains("canonicalJson"));
    }

    @Test void retriedAttemptKeepsLogicalOperationButHasIndependentIdempotentEvent() {
        var first = fact("attempt-1", "original-input", "trace-1", "span-1", ExecutionObservation.Status.REGISTERED);
        var retry = fact("attempt-2", "reply-input", null, null, ExecutionObservation.Status.REGISTERED);
        assertEquals(first.operationId(), retry.operationId());
        assertEquals(first.stepId(), retry.stepId());
        assertEquals(ExecutionObservation.event(first, LIMITS), ExecutionObservation.event(first, LIMITS));
        assertNotEquals(ExecutionObservation.event(first, LIMITS).id(), ExecutionObservation.event(retry, LIMITS).id());
    }

    @Test void rejectsUnknownSchemaFieldsMissingIdentityAndInvalidState() {
        var encoded = ExecutionObservation.encode(fact("attempt", "input", "trace", "span", ExecutionObservation.Status.SUCCEEDED), LIMITS);
        assertThrows(IllegalArgumentException.class, () -> ExecutionObservation.decode(new TypedPayload("old-observation", encoded.canonicalJson()), LIMITS));
        for (String document : java.util.List.of(
                encoded.canonicalJson().replace("\"operationId\":", "\"unknown\":true,\"operationId\":"),
                encoded.canonicalJson().replace("\"operationId\":\"action-1\"", "\"operationId\":null"),
                encoded.canonicalJson().replace("\"status\":\"SUCCEEDED\"", "\"status\":\"DEFAULT_SUCCESS\"")))
            assertThrows(IllegalArgumentException.class, () -> ExecutionObservation.decode(new TypedPayload(ExecutionObservation.SCHEMA, document), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> fact("attempt", "input", "trace", null, ExecutionObservation.Status.SUCCEEDED));
    }

    private static ExecutionObservation.Fact fact(String attempt, String input, String trace, String span, ExecutionObservation.Status status) {
        return new ExecutionObservation.Fact("action-1", "step:model-1", ExecutionObservation.Kind.TOOL, "search", status,
                "run-1", "action-1", attempt, "step:model-1", "1", input, "root-input", trace, span, null, null, null);
    }
}

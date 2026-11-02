package com.skillforge.core.shared.logging;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SafeFailureDiagnosticsTest {
    @Test
    void internalDiagnosticNeverReadsMessageCauseOrFileAndConstrainsInjectedFrames() {
        var failure = new IllegalStateException("synthetic-secret", new RuntimeException("cause-secret")) {
            @Override public String getMessage() { throw new AssertionError("must not read message"); }
        };
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("Injected\nClass", "method\rInjected", "secret-file", 42)});
        var diagnostic = SafeFailureDiagnostics.of(failure);
        assertEquals("Injected_Class.method_Injected:42", diagnostic.stackTop());
        assertNull(diagnostic.httpStatus());
        assertFalse(diagnostic.toString().contains("secret"));
    }
    @Test
    void httpDiagnosticContainsOnlyStatusAndType() {
        var failure = org.springframework.web.client.HttpServerErrorException.create(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "sensitive-status-text", null,
                "sensitive-body".getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
        var diagnostic = SafeFailureDiagnostics.of(failure);
        assertEquals(503, diagnostic.httpStatus());
        assertEquals("", diagnostic.stackTop());
        assertFalse(diagnostic.toString().contains("sensitive"));
        var wrapped = SafeFailureDiagnostics.of(new IllegalStateException("sensitive-wrapper", failure));
        assertEquals(503, wrapped.httpStatus());
        assertFalse(wrapped.toString().contains("sensitive"));
    }

    @Test
    void causeCyclesAreBounded() {
        var first = new RuntimeException("secret-first");
        var second = new RuntimeException("secret-second");
        first.initCause(second);
        second.initCause(first);
        var diagnostic = SafeFailureDiagnostics.of(first);
        assertNull(diagnostic.httpStatus());
        assertFalse(diagnostic.toString().contains("secret"));
    }
}

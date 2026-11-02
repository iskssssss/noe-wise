package com.skillforge.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AgentRuntimeFailureDiagnosticTest {
    @Test
    void logsStableTagsAndBoundedLocationsWithoutMessagesCausesOrSuppressedSecrets() {
        var logger = (Logger) LoggerFactory.getLogger(AgentRuntimeFailureDiagnosticTest.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var cause = new IllegalArgumentException("cause-secret-body");
            var failure = new IllegalStateException("message-secret-token", cause);
            failure.addSuppressed(new IllegalStateException("suppressed-secret-password"));
            failure.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("unsafe\nClass", "method\r\nInjected", "filename-secret", 42)});
            AgentRuntimeFailureDiagnostic.warn(logger, "测试失败", "session", "raw-sensitive-session-id",
                    "runtime_test_failure", failure);
            AgentRuntimeFailureDiagnostic.warn(logger, "测试失败", "session", "raw-sensitive-session-id",
                    "runtime_test_failure", failure);

            assertThat(appender.list).hasSize(2);
            String output = appender.list.getFirst().getFormattedMessage();
            assertThat(output).isEqualTo(appender.list.getLast().getFormattedMessage())
                    .contains("reason=runtime_test_failure", "cause=java.lang.IllegalArgumentException",
                            "suppressed=java.lang.IllegalStateException", "unsafe_Class.method__Injected:42")
                    .doesNotContain("message-secret-token", "cause-secret-body", "suppressed-secret-password",
                            "filename-secret", "raw-sensitive-session-id", "\n", "\r");
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            assertThat(AgentRuntimeFailureDiagnostic.tag("raw-sensitive-session-id")).hasSize(64)
                    .isNotEqualTo(AgentRuntimeFailureDiagnostic.tag("another-session"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void doesNotReadExceptionMessagesAndBoundsCyclicAndLargeFailureGraphs() {
        var first = new MessageForbiddenException();
        var second = new MessageForbiddenException();
        first.initCause(second);
        second.initCause(first);
        for (int index = 0; index < 30; index++) first.addSuppressed(new MessageForbiddenException());

        assertThat(AgentRuntimeFailureDiagnostic.details(first)).hasSizeLessThan(20_000).contains("truncated");
    }

    private static final class MessageForbiddenException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        @Override public String getMessage() { throw new AssertionError("diagnostic read an untrusted message"); }
        @Override public String toString() { throw new AssertionError("diagnostic read an untrusted representation"); }
    }
}

package com.skillforge.core.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/** 两个宿主共用 Boot 原生编码器，日志正文与异常不得破坏逐行 JSON 边界。 */
class HostStructuredLoggingTest {
    @Test
    void encodesControlCharactersCorrelationAndExceptionInOneJsonRecord() throws Exception {
        String message = "处理失败 event=host_request result=failed 路径=C:\\临时\\文件 \"引号\"\n换行\r\t制表\u0001";
        var environment = new MockEnvironment().withProperty("skillforge.secret-key", "do-not-serialize-secret");
        var context = new LoggerContext();
        var encoder = new StructuredLogEncoder();
        try {
            context.putObject(Environment.class.getName(), environment);
            encoder.setContext(context);
            encoder.setFormat("logstash");
            encoder.start();
            var event = new LoggingEvent(getClass().getName(), context.getLogger(getClass()),
                    Level.ERROR, message, new IllegalStateException("受控失败原因"), null);
            event.setMDCPropertyMap(Map.of("trace_id", "request-123", "tenant_id", "7",
                    "user_id", "11", "module", "chat", "action", "submit"));
            String encoded = new String(encoder.encode(event), StandardCharsets.UTF_8);
            assertThat(encoded.lines().count()).isEqualTo(1);
            var document = new ObjectMapper().readTree(encoded);
            assertThat(document.path("message").asText()).isEqualTo(message);
            assertThat(document.path("level").asText()).isEqualTo("ERROR");
            assertThat(document.path("trace_id").asText()).isEqualTo("request-123");
            assertThat(document.path("tenant_id").asText()).isEqualTo("7");
            assertThat(document.path("user_id").asText()).isEqualTo("11");
            assertThat(document.path("module").asText()).isEqualTo("chat");
            assertThat(document.path("action").asText()).isEqualTo("submit");
            assertThat(document.path("stack_trace").asText()).contains("受控失败原因", "IllegalStateException");
            assertThat(encoded).doesNotContain("do-not-serialize-secret");
        } finally {
            encoder.stop();
            context.stop();
        }
    }
}

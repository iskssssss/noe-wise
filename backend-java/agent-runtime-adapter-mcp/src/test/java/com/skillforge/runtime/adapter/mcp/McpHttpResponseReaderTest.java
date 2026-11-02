package com.skillforge.runtime.adapter.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 用流读取边界证明限额和 SSE 完成条件，不依赖网络调度或大响应分配。 */
class McpHttpResponseReaderTest {
    private static final String RESPONSE = "{\"jsonrpc\":\"2.0\",\"id\":\"r\",\"result\":{}}";

    @Test void stopsAtFirstByteBeyondLimit() {
        for (String type : List.of("application/json", "text/event-stream")) {
            AtomicInteger reads = new AtomicInteger();
            InputStream endless = new InputStream() {
                @Override public int read() { reads.incrementAndGet(); return ' '; }
            };
            var failure = assertThrows(IOException.class,
                    () -> McpHttpResponseReader.packet(endless, type, "r", 64, () -> {}));
            assertEquals("mcp_response_limit", failure.getMessage());
            assertEquals(65, reads.get());
        }
    }

    @Test void acceptsExactJsonLimitAndRejectsMalformedUtf8() throws Exception {
        byte[] bytes = RESPONSE.getBytes(StandardCharsets.UTF_8);
        assertEquals("r", McpHttpResponseReader.packet(new ByteArrayInputStream(bytes),
                "application/json", "r", bytes.length, () -> {}).path("id").asText());
        assertThrows(IOException.class, () -> McpHttpResponseReader.packet(
                new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}),
                "application/json", "r", 64, () -> {}));
    }

    @Test void returnsCompletedSseWithoutWaitingForEof() throws Exception {
        for (String separator : List.of("\n", "\r", "\r\n")) {
            byte[] bytes = (": heartbeat" + separator + "event: message" + separator
                    + "id: 7" + separator + "data: " + RESPONSE + separator + separator)
                    .getBytes(StandardCharsets.UTF_8);
            InputStream open = new ByteArrayInputStream(bytes) {
                @Override public synchronized int read() {
                    if (available() == 0) fail("完整事件后不得继续读取或等待 EOF");
                    return super.read();
                }
            };
            assertEquals("r", McpHttpResponseReader.packet(open, "text/event-stream",
                    "r", 1024, () -> {}).path("id").asText());
        }
    }

    @Test void rejectsIncompleteSseAndHonorsCancellation() {
        byte[] bytes = ("data: " + RESPONSE + "\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> McpHttpResponseReader.packet(
                new ByteArrayInputStream(bytes), "text/event-stream", "r", 1024, () -> {}));
        assertThrows(IllegalStateException.class, () -> McpHttpResponseReader.packet(
                new ByteArrayInputStream(bytes), "text/event-stream", "r", 1024,
                () -> { throw new IllegalStateException("cancelled"); }));
    }
}

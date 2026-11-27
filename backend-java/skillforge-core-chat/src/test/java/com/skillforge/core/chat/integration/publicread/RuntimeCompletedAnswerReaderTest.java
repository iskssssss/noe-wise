package com.skillforge.core.chat.integration.publicread;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RuntimeCompletedAnswerReaderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RuntimeCompletedAnswerReader reader = new RuntimeCompletedAnswerReader(json);
    private final PublicOutputGuard.Scope guard = new PublicOutputGuard.Scope(List.of("private-material"));
    private final TenantId tenant = new TenantId("7");

    @Test void onlySavedCompletionPublishesStableFinalMessageWithOriginalSourceIdentity() throws Exception {
        var candidate = event(1, "model-result", "execution.model-result", "candidate.v1", "{}");
        assertTrue(reader.read(List.of(candidate), guard).isEmpty());
        var done = completed(2, "COMPLETED", "公开答案");
        var answer = reader.read(List.of(candidate, done), guard).getFirst();
        assertEquals("公开答案", answer.content()); assertEquals("completed", answer.state());
        assertEquals("final", answer.phase()); assertEquals(1, answer.messageVersion());
        assertEquals("run-done", answer.messageId()); assertEquals("root-1", answer.rootInputId());
        assertEquals(done.occurredAt(), answer.createdAt()); assertEquals(2, answer.sequence());
        assertEquals(answer, reader.read(List.of(done), guard).getFirst());
        assertEquals(answer.messageId(), answer.wire("session-1").get("message_id"));
    }

    @Test void privacyGuardAndFailedPartialOutputPreserveHonestPublicState() throws Exception {
        var blocked = reader.read(List.of(completed(1, "COMPLETED", "private-material")), guard).getFirst();
        assertEquals(PublicOutputGuard.SAFE_MESSAGE, blocked.content()); assertEquals("failed", blocked.state());
        assertFalse(blocked.message().toString().contains("private-material"));
        var partial = reader.read(List.of(completed(1, "FAILED", "已核实的局部结果")), guard).getFirst();
        assertEquals("已核实的局部结果", partial.content()); assertEquals("failed", partial.state());
        var cancelled = reader.read(List.of(completed(1, "CANCELLED", "  ")), guard).getFirst();
        assertEquals("cancelled", cancelled.state());
        assertTrue(cancelled.content().contains("已取消"));
    }

    @Test void emptyFailurePublishesSafeNoticeWithOriginalIdentityWithoutInventingSuccess() throws Exception {
        for (String status : List.of("FAILED", "TERMINATED")) {
            var saved = completed(3, status, "");
            var answer = reader.read(List.of(saved), guard).getFirst();
            assertEquals("failed", answer.state());
            assertFalse(answer.content().isBlank());
            assertEquals(saved.event().id().value(), answer.messageId());
            assertEquals(saved.occurredAt(), answer.createdAt());
            assertEquals(3, answer.sequence());
        }
        assertTrue(reader.read(List.of(completed(1, "COMPLETED", "")), guard).isEmpty());
        var batch = event(4, "batch-rejected", AgentExecutionProgram.COMPLETED_EVENT, AgentExecutionProgram.COMPLETED_SCHEMA,
                json.writeValueAsString(Map.of("text", "", "status", "FAILED", "reason", "tool_batch_ineligible", "inputId", "input-1",
                        "rootInputId", "root-1", "traceId", "trace-1", "spanId", "span-1", "agentId", "9")));
        var answer = reader.read(List.of(batch), guard).getFirst();
        assertTrue(answer.content().contains("这组操作未执行"));
        assertTrue(answer.content().contains("拆成单步"));
        assertFalse(answer.content().contains("tool_batch_ineligible"));
    }

    @Test void rateLimitExplainsTheFailureWithoutExposingDiagnosticsOrReplacingPartialOutput() throws Exception {
        var saved = event(5, "rate-limited", AgentExecutionProgram.COMPLETED_EVENT, AgentExecutionProgram.COMPLETED_SCHEMA,
                json.writeValueAsString(Map.of("text", "", "status", "FAILED", "reason", "model_rate_limited", "inputId", "input-1",
                        "rootInputId", "root-1", "traceId", "trace-1", "spanId", "span-1", "agentId", "9")));
        var answer = reader.read(List.of(saved), guard).getFirst();
        assertEquals("failed", answer.state());
        assertEquals("rate-limited", answer.messageId());
        assertTrue(answer.content().contains("模型服务当前限流"));
        assertTrue(answer.content().contains("核查模型额度或限流状态"));
        assertTrue(answer.content().contains("核对已执行的操作"));
        assertFalse(answer.content().contains("model_rate_limited"));
        assertEquals("已核实的局部结果", RuntimeCompletedAnswerReader.completionText("已核实的局部结果", "FAILED", "model_rate_limited"));
    }

    @Test void corruptedCompletionDuplicateSourcesAndCrossTenantBatchFailClosed() throws Exception {
        var done = completed(1, "COMPLETED", "公开答案");
        assertThrows(RuntimeCompletedAnswerReader.FormatException.class, () -> reader.read(List.of(done, done), guard));
        var foreign = new ExecutionStore.SavedEvent(new RunRef(new TenantId("8"), "run-2"), 2, 1,
                Instant.EPOCH, new ExecutionStore.Event(new EventId("foreign"), done.event().type(), done.event().data()));
        assertThrows(RuntimeCompletedAnswerReader.FormatException.class, () -> reader.read(List.of(done, foreign), guard));
        var malformed = event(1, "broken", AgentExecutionProgram.COMPLETED_EVENT, AgentExecutionProgram.COMPLETED_SCHEMA, "{\"status\":\"COMPLETED\",\"text\":\"答案\"}");
        assertThrows(RuntimeCompletedAnswerReader.FormatException.class, () -> reader.read(List.of(malformed), guard));
        var active = completed(1, "SUSPENDED", "尚未完成");
        assertThrows(RuntimeCompletedAnswerReader.FormatException.class, () -> reader.read(List.of(active), guard));
    }

    private ExecutionStore.SavedEvent completed(long sequence, String status, String output) throws Exception {
        return event(sequence, "run-done", AgentExecutionProgram.COMPLETED_EVENT, AgentExecutionProgram.COMPLETED_SCHEMA,
                json.writeValueAsString(Map.of("text", output, "status", status, "reason", "reason", "inputId", "input-1",
                        "rootInputId", "root-1", "traceId", "trace-1", "spanId", "span-1", "agentId", "9")));
    }
    private ExecutionStore.SavedEvent event(long sequence, String id, String type, String schema, String body) {
        return new ExecutionStore.SavedEvent(new RunRef(tenant, "run-1"), sequence, 3,
                Instant.EPOCH.plusNanos(sequence), new ExecutionStore.Event(new EventId(id), type, new TypedPayload(schema, body)));
    }
}

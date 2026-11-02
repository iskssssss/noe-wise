package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionOutboxProjectionCheckpoint;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 刷新历史与实时流消费同一公开事实，不查询整Run或从私有结果恢复回复。 */
class AgentRuntimeExecutionHistoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PublicOutputGuard guard = new PublicOutputGuard("history-private-artifacts");

    @Test void previewNeverBecomesAnAuthoritativeHistoryMessageOrModelHistory() {
        var history = new AgentRuntimeExecutionHistory(json, guard.capture());
        var preview = base(); preview.put("preview_message_id", "assistant-preview:run-1");
        preview.put("content", "尚未采纳的候选"); preview.put("authoritative", false); preview.put("state", "streaming");
        history.accept(output(1, "message.preview", preview));
        history.accept(output(2, "message.preview", preview));
        assertTrue(history.snapshot("session-1", "o2").messages().isEmpty());
    }

    @Test void responseClosesOnlyItsGenerationAndCompletionClosesTheRemainingRunWait() {
        var history = new AgentRuntimeExecutionHistory(json, guard.capture());
        history.accept(output(1, "execution.accepted", base()));
        history.accept(output(2, "hitl.required", pending("1")));
        history.accept(output(3, "interaction.resolved", resolved("1")));
        assertTrue(history.snapshot("session-1", "o3").pendingInteractions().isEmpty());
        history.accept(output(4, "hitl.required", pending("2")));
        history.accept(output(5, "interaction.resolved", resolved("1")));
        assertEquals("2", ((Map<?, ?>) history.snapshot("session-1", "o5").pendingInteractions().getFirst().get("tool_wait")).get("generation"));
        var completion = base(); completion.put("status", "cancelled");
        history.accept(output(6, "execution.completed", completion));
        var result = history.snapshot("session-1", "o6");
        assertTrue(result.pendingInteractions().isEmpty());
        assertEquals("cancelled", result.activities().getFirst().status());
    }

    @Test void highestMessageVersionKeepsItsFirstTimeAndAllPublicImagePartsAndReferences() {
        var history = new AgentRuntimeExecutionHistory(json, guard.capture());
        var first = base(); first.put("message_id", "message-1"); first.put("message_version", 1); first.put("content", "初版");
        first.put("content_parts", List.of(Map.of("type", "image", "media_id", "media-1")));
        first.put("attachment_ids", List.of("attachment-1"));
        first.put("references", List.of(Map.of("label", "原图", "source", Map.of("kind", "message", "source", Map.of("kind", "session"),
                "message_id", "image-message", "message_version", 1))));
        history.accept(output(1, "message.delta", first));
        var changed = new LinkedHashMap<>(first); changed.put("message_version", 2); changed.put("content", "最终回复");
        history.accept(output(2, "message.delta", changed));
        history.accept(output(3, "message.delta", first));
        var message = history.snapshot("session-1", "o3").messages().getFirst();
        assertEquals("最终回复", message.content()); assertEquals(2, message.messageVersion());
        assertEquals(Instant.parse("2026-10-22T01:00:01Z"), message.createdAt());
        assertEquals(first.get("content_parts"), message.metadata().get("content_parts"));
        assertEquals(List.of("attachment-1"), message.metadata().get("attachment_ids"));
        assertEquals("原图", message.references().getFirst().label());
    }

    @Test void activityKeepsRunStateAndMessageLinkWithoutDuplicatingTheReplyBody() {
        var history = new AgentRuntimeExecutionHistory(json, guard.capture());
        var card = Map.<String,Object>of("title", "原卡片");
        var accepted = base(); accepted.put("card", card);
        history.accept(output(1, "execution.accepted", accepted).toBuilder().inputId("input-1").rootInputId("input-1").build());
        var started = history.snapshot("session-1", "o1").activities().getFirst().startedAt();
        var message = base(); message.put("message_id", "reply"); message.put("message_version", 1);
        message.put("content", "正式消息正文"); message.put("updated_sequence", 2L);
        history.accept(output(2, "message.delta", message).toBuilder().inputId("input-1").rootInputId("input-1").build());
        var completed = base(); completed.put("status", "completed"); completed.put("card", card);
        completed.put("updated_sequence", 3L);
        history.accept(output(3, "execution.completed", completed).toBuilder().inputId("input-1").rootInputId("input-1").build());
        var updated = new LinkedHashMap<>(message); updated.put("message_version", 2); updated.put("content", "正式消息修订正文");
        history.accept(output(4, "message.delta", updated).toBuilder().inputId("input-1").rootInputId("input-1").build());
        var result = history.snapshot("session-1", "o4");
        assertEquals(new com.skillforge.core.chat.dto.response.ChatHistoryResponse.ActivityItem(
                "run-1", "agent_runtime", "completed", card,
                Map.of("root_run_id", "run-1", "root_input_id", "input-1", "updated_sequence", 3L),
                "reply", started), result.activities().getFirst());
        assertEquals("正式消息修订正文", result.messages().getFirst().content());
        assertEquals(2, result.messages().getFirst().messageVersion());
        assertEquals("o4", result.eventCursor());
        var serialized = json.copy().findAndRegisterModules().valueToTree(result);
        assertFalse(serialized.path("activities").get(0).has("output"));
        assertEquals("正式消息修订正文", serialized.path("messages").get(0).path("content").asText());
    }

    @Test void capturedCursorBoundsHistoryDespiteNewOutputInTheLastFetchedPage() {
        var store = mock(ExecutionStore.class); var mailbox = mock(SessionMailboxService.class);
        when(mailbox.projectionCheckpoint(eq("7"), eq("session-1"), anyString())).thenAnswer(call -> SessionOutboxProjectionCheckpoint.empty(call.getArgument(2)));
        when(store.events(new SessionRef(new TenantId("7"), "session-1"), 0, 100)).thenReturn(List.of());
        when(mailbox.committedCursor("7", "session-1")).thenReturn("o2");
        when(mailbox.resolveOutputSequence("7", "session-1", "o2")).thenReturn(2L);
        var first = base(); first.put("message_id", "first"); first.put("message_version", 1); first.put("content", "第一条");
        var second = base(); second.put("message_id", "second"); second.put("message_version", 1); second.put("content", "第二条");
        var newer = base(); newer.put("message_id", "newer"); newer.put("message_version", 1); newer.put("content", "水位之后");
        when(mailbox.listOutputsAfterSequence("7", "session-1", 0, 100)).thenReturn(List.of(output(1, "message.delta", first),
                output(2, "message.delta", second), output(3, "message.delta", newer)));
        var session = org.mockito.Mockito.mock(SessionView.class); org.mockito.Mockito.when(session.getTenantId()).thenReturn(7L); org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        var projection = new AgentRuntimeExecutionProjection(store, mailbox, guard, json, 100_000, 10_000, 32);
        var history = projection.read(session);
        assertEquals(List.of("first", "second"), history.messages().stream().map(com.skillforge.core.chat.message.ChatMessageRecord::messageId).toList());
        assertEquals("o2", history.eventCursor());
        verify(store).events(new SessionRef(new TenantId("7"), "session-1"), 0, 100); verifyNoMoreInteractions(store);
    }

    @Test void recentMessagesUsesOneBoundedQueryAndRetainsTheFirstMessagePosition() {
        var mailbox = mock(SessionMailboxService.class); var store = mock(ExecutionStore.class);
        var value = base(); value.put("message_id", "message"); value.put("message_version", 3); value.put("content", "最高版本");
        var row = output(7, "message.delta", value).toBuilder().firstMessageSequence(1L)
                .firstMessageCreatedAt(Instant.parse("2026-10-22T01:00:01Z")).build();
        when(mailbox.recentMessages("7", "session-1", 100)).thenReturn(List.of(row));
        var session = org.mockito.Mockito.mock(SessionView.class); org.mockito.Mockito.when(session.getTenantId()).thenReturn(7L); org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        var result = new AgentRuntimeExecutionProjection(store, mailbox, guard, json, 100_000, 10_000, 32).recentMessages(session, 100);
        assertEquals(3, result.getFirst().messageVersion()); assertEquals(1L, result.getFirst().metadata().get("source_sequence"));
        assertEquals(row.getFirstMessageCreatedAt(), result.getFirst().createdAt());
        verify(mailbox).recentMessages("7", "session-1", 100); verifyNoMoreInteractions(mailbox); verifyNoInteractions(store);
    }

    @Test void reconstructedUserMessageRetainsOriginalPublicIdentityPartsAndEventTime() throws Exception {
        var projection = new AgentRuntimeExecutionProjection(mock(ExecutionStore.class), mock(SessionMailboxService.class), guard, json, 100_000, 10_000, 32);
        var originalTime = Instant.parse("2026-10-20T00:00:00Z");
        var value = new LinkedHashMap<String, Object>(Map.of("inputId", "private-input", "messageId", "public-message",
                "rootInputId", "root", "traceId", "trace", "spanId", "span", "agentId", "7", "text", "确认"));
        value.put("contentParts", List.of(Map.of("type", "text", "text", "确认")));
        var event = new ExecutionStore.Event(new com.skillforge.runtime.api.EventId("user-source"),
                AgentRuntimeExecutionResponses.USER_MESSAGE_EVENT,
                new com.skillforge.runtime.api.TypedPayload(AgentRuntimeExecutionResponses.USER_MESSAGE_SCHEMA, json.writeValueAsString(value)));
        var saved = new ExecutionStore.SavedEvent(new com.skillforge.runtime.api.RunRef(new TenantId("7"), "run-1"), 1, 1, originalTime, event);
        var reference = new SessionRef(new TenantId("7"), "session-1");
        var outputs = new java.util.ArrayList<com.skillforge.core.chat.session.SessionOutboxOutput>();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(projection, "project", reference, saved, outputs);
        var again = new java.util.ArrayList<com.skillforge.core.chat.session.SessionOutboxOutput>();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(projection, "project", reference, saved, again);
        assertEquals(outputs, again);
        assertEquals(1, outputs.size());
        var output = outputs.getFirst();
        @SuppressWarnings("unchecked") var data = (Map<String,Object>) output.payload().get("data");
        // 正式Mailbox把source trace保存为Outbox列；EventProjector只信这些列，不信payload同名字段。
        var rebuilt = output(99, output.eventType(), data).toBuilder()
                .inputId(output.trace().inputId()).rootInputId(output.trace().rootInputId())
                .traceId(output.trace().traceId()).sourceEventId(output.trace().eventId())
                .causedByEventId(output.trace().causedByEventId()).build();
        var message = AgentRuntimeExecutionHistory.message(rebuilt, json, guard.capture());
        assertEquals("public-message", message.messageId());
        assertEquals("user", message.role()); assertEquals(originalTime, message.createdAt());
        assertEquals("private-input", message.metadata().get("input_id"));
        assertEquals(value.get("contentParts"), message.metadata().get("content_parts"));
        // 仅图片输入的正文可以为空，公开投影仍保留有序内容选择器。
        value.put("text", "");
        value.put("contentParts", List.of(Map.of("type", "image", "media_id", "media-selector")));
        var imageEvent = new ExecutionStore.Event(new com.skillforge.runtime.api.EventId("image-user-source"),
                AgentRuntimeExecutionResponses.USER_MESSAGE_EVENT,
                new com.skillforge.runtime.api.TypedPayload(AgentRuntimeExecutionResponses.USER_MESSAGE_SCHEMA, json.writeValueAsString(value)));
        var imageOutputs = new java.util.ArrayList<com.skillforge.core.chat.session.SessionOutboxOutput>();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(projection, "project", reference,
                new ExecutionStore.SavedEvent(saved.run(), 2, 1, originalTime, imageEvent), imageOutputs);
        @SuppressWarnings("unchecked") var imageData = (Map<String,Object>) imageOutputs.getFirst().payload().get("data");
        assertEquals("", imageData.get("content")); assertEquals(value.get("contentParts"), imageData.get("content_parts"));
    }

    /** 每轮公共因果基础字段；源序号来自已提交Outbox。 */
    private Map<String, Object> base() {
        return new LinkedHashMap<>(Map.of("run_id", "run-1", "root_run_id", "run-1", "input_id", "input-1", "root_input_id", "input-1", "updated_sequence", 1L));
    }

    /** 真实等待的最小公开身份，不使用旧checkpoint或pending表。 */
    private Map<String, Object> pending(String generation) {
        var value = base(); value.put("execution_id", "run-1"); value.put("run_revision", "7"); value.put("interaction_type", "user_input");
        value.put("tool_wait", Map.of("action_id", "action-1", "generation", generation)); value.put("question", "请补充信息");
        return value;
    }

    /** 已受理响应只闭合原等待，不公开响应材料。 */
    private Map<String, Object> resolved(String generation) {
        var value = base(); value.put("execution_id", "run-1"); value.put("tool_wait", Map.of("action_id", "action-1", "generation", generation));
        return value;
    }

    /** 固定合法Outbox事实，每个样本独立保留持久提交时间。 */
    private SessionOutboxEntry output(long sequence, String type, Map<String, Object> value) {
        var rowBuilder = SessionOutboxEntry.builder(); rowBuilder.tenantId(7L); rowBuilder.sessionId("session-1"); rowBuilder.runtimeRunId("run-1");
        rowBuilder.eventId("o" + sequence); rowBuilder.sessionSequence(sequence); rowBuilder.eventType(type); rowBuilder.payload(Map.of("event", type, "data", value));
        rowBuilder.createdAt(Instant.parse("2026-10-22T01:00:00Z").plusSeconds(sequence));
        var row = rowBuilder.build(); return row;
    }
}

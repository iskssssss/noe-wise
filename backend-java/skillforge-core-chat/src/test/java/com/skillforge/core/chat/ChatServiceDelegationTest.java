package com.skillforge.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.dto.request.CreateSessionRequest;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.contract.ChatEventSink;
import com.skillforge.core.chat.message.ChatMessageRecord;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** ChatService 薄门面的委派与防回流测试。 */
class ChatServiceDelegationTest {

    private final ChatConversationDirector director = mock(ChatConversationDirector.class);
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private final ChatService service = new ChatService(director, sessions);

    /** 验证门面不会重新持有底层基础设施依赖。 */
    @Test
    void facadeOnlyKeepsApplicationCollaborators() {
        // 反射检查是防回流门禁：后续新增底层字段会立即让本测试失败。
        Set<Class<?>> fieldTypes = Arrays.stream(ChatService.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ChatConversationDirector.class, ChatSessionService.class), fieldTypes);
    }

    /** 验证普通消息入口只委派给对话编排器。 */
    @Test
    void conversationEntrypointsDelegateToDirector() {
        // 使用真实 DTO 形状，确保门面委派测试不依赖 Mockito 对 record 的特殊行为。
        ChatRequest request = new ChatRequest(
                "你好", "s-1", "a-1", null, null, null, null, null,
                "input-1", null, Map.of("end_user_id", 200L));
        ChatEventSink sink = mock(ChatEventSink.class);
        ChatResult expected = new ChatResult(
                "completed", "s-1", null, null, null, null);
        when(director.runChat(request, sink)).thenReturn(expected);
        assertEquals(expected, service.runChat(request, sink));
    }

    /** 验证主动消息和会话查询分别委派给职责拥有者。 */
    @Test
    void recoveryAndSessionEntrypointsDelegateToOwners() {
        // 每个公开入口只验证一次对应拥有者调用，业务细节由职责组件测试覆盖。
        ChatEventSink sink = mock(ChatEventSink.class);
        ChatMessageRecord message = new ChatMessageRecord(
                "m-1", "assistant", "完成", Instant.EPOCH, Map.of());
        ChatHistoryResponse history = new ChatHistoryResponse(
                "s-1", List.of(), null, List.of(), List.of(),
                ChatHistoryResponse.Collaboration.empty());
        CreateSessionRequest create = new CreateSessionRequest("100", null, null, 200L);
        when(sessions.sendToSession("t-1", "s-1", "完成", null, null, null))
                .thenReturn(message);
        when(sessions.createSession(create, "t-1")).thenReturn(Map.of("session_id", "s-1"));
        when(sessions.history("t-1", "s-1")).thenReturn(history);

        assertEquals(message, service.sendToSession("t-1", "s-1", "完成", null, null, null));
        assertEquals("s-1", service.createSession(create, "t-1").get("session_id"));
        assertEquals(history, service.history("t-1", "s-1"));

        verify(sessions).history("t-1", "s-1");
    }
}

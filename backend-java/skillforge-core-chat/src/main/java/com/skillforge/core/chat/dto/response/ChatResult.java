package com.skillforge.core.chat.dto.response;
import java.util.Map;
/**
 * 对话执行结果 DTO。
 *
 * @param status     执行状态
 * @param sessionId  会话 ID
 * @param executionId 执行 ID
 * @param card       人工确认卡片
 * @param message    回退提示消息
 * @param collaboration Plan 模式与 Todo 会话投影（纯命令响应可用）
 */
public record ChatResult(
        String status,
        String sessionId,
        String executionId,
        Map<String, Object> card,
        String message,
        ChatHistoryResponse.Collaboration collaboration,
        String inputId,
        boolean replayed) {
    /** 无 Runtime 输入的纯命令结果不制造受理身份。 */
    public ChatResult(String status, String sessionId, String executionId,
            Map<String, Object> card, String message,
            ChatHistoryResponse.Collaboration collaboration) {
        this(status, sessionId, executionId, card, message, collaboration, null, false);
    }
}

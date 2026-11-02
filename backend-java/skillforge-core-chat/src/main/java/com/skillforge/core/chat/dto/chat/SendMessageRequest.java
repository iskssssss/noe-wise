package com.skillforge.core.chat.dto.chat;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 主动消息发送请求 DTO。
 *
 * <p>向指定会话推送助手消息（超管抄送 / 外部事件回推）：会话在线时经 SSE
 * 推送（卡/文本事件），离线时降级落库（历史拉取兜底）；mergeMessageId 非空
 * 时以同 id 覆盖（message.card.update，不追加）。
 *
 * @param sessionId       目标会话 ID，必填
 * @param content         消息内容，必填
 * @param card            卡数据，可选（非空推 message.card）
 * @param render          自定义渲染数据，可选（渲染契约透传）
 * @param mergeMessageId  同 id merge 目标消息 ID，可选
 */
public record SendMessageRequest(
        @NotBlank String sessionId,
        @NotBlank String content,
        Map<String, Object> card,
        Map<String, Object> render,
        String mergeMessageId) {
}

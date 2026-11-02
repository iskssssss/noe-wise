package com.skillforge.core.chat.dto.chat;


/**
 * 主动消息发送响应 DTO。
 *
 * @param status    受理状态；当前成功受理固定为 {@code queued}
 * @param messageId 消息 ID（merge 时为覆盖目标 ID；否则新生成）
 * @param delivered 是否已由本请求确认渠道收到；持久 Outbox 受理阶段固定为 {@code false}
 */
public record SendMessageResponse(
        String status,
        String messageId,
        boolean delivered) {
}

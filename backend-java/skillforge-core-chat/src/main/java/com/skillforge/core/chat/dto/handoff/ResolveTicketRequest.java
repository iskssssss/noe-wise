package com.skillforge.core.chat.dto.handoff;

/**
 * 解决转人工工单请求 DTO。
 *
 * @param resolutionNote 解决备注，可选
 */
public record ResolveTicketRequest(
        String resolutionNote) {
}

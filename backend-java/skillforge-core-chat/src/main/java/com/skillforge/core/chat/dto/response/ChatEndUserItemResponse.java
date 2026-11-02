package com.skillforge.core.chat.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 对话工作区可选终端用户。
 *
 * @param id          终端用户 ID，作为对话入口的 {@code end_user_id}
 * @param displayName 展示名称，可空
 * @param status      用户状态
 */
public record ChatEndUserItemResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,
        String displayName,
        String status) {
}

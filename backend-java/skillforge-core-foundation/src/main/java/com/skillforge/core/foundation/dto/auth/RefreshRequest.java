package com.skillforge.core.foundation.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求 DTO。
 *
 * @param refreshToken 刷新令牌，格式为「租户ID.随机串」，必填
 */
public record RefreshRequest(@NotBlank String refreshToken) {
}

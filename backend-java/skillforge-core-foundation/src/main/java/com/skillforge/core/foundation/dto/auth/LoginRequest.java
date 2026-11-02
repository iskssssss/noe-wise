package com.skillforge.core.foundation.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 用户登录请求 DTO。
 *
 * @param tenantSlug 租户唯一标识，必填
 * @param username   用户名，必填
 * @param password   密码，必填
 */
public record LoginRequest(
        @NotBlank String tenantSlug,
        @NotBlank String username,
        @NotBlank String password) {
}

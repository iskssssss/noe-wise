package com.skillforge.platform.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 平台运营后台登录请求 DTO。
 *
 * @param username 平台账号用户名，必填
 * @param password 密码，必填
 */
public record PlatformLoginRequest(
        @NotBlank String username,
        @NotBlank String password) {
}

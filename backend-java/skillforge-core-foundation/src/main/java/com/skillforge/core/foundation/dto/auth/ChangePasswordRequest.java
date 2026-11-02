package com.skillforge.core.foundation.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求。
 *
 * <p>POST /api/v1/auth/password 入参；校验当前密码后更新当前登录用户密码（BCrypt 存储）。
 *
 * @param oldPassword 当前密码，必填
 * @param newPassword 新密码：至少 12 位且同时包含字母与数字
 */
public record ChangePasswordRequest(
        @Schema(description = "当前密码", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "当前密码不能为空")
        String oldPassword,
        @Schema(description = "新密码：至少 12 位且同时包含字母与数字", example = "NewPassword123",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "新密码不能为空")
        @Size(min = 12, max = 72, message = "新密码需12至72位")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "新密码需同时包含字母与数字")
        String newPassword) {
}

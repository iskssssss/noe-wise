package com.skillforge.platform.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重置机构成员密码请求 DTO。
 *
 * @param newPassword 新密码，至少 12 位
 */
public record ResetPasswordRequest(
        @NotBlank @Size(min = 12, message = "newPassword 长度至少 12 位") String newPassword) {
}

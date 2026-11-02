package com.skillforge.platform.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 平台新增机构成员请求 DTO。
 *
 * @param username 用户名，必填
 * @param password 密码，至少 12 位
 * @param roles     角色：admin / developer / auditor
 * @param email    邮箱，可空
 */
public record CreateTenantMemberRequest(
        @NotBlank String username,
        @NotNull @Size(min = 12, message = "password 长度至少 12 位") String password,
        @NotNull @Size(min=1, max=32) java.util.List<@NotBlank String> roles,
        String email) {
}

package com.skillforge.platform.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 平台开通机构请求 DTO。
 *
 * @param tenantName    机构显示名称，必填
 * @param tenantSlug    机构唯一标识（URL 友好），必填
 * @param planCode      套餐编码（free / pro / enterprise），可空，默认 free
 * @param adminUsername 初始管理员用户名，必填
 * @param adminPassword 初始管理员密码，至少 12 个 Unicode 码点，包含 ASCII 字母和数字，UTF-8 最多 72 字节
 * @param adminEmail    初始管理员邮箱，可空
 */
public record CreateTenantRequest(
        @NotBlank(message = "请输入机构名称") @Size(max = 128, message = "机构名称最多 128 个 UTF-16 代码单元") String tenantName,
        @NotBlank(message = "请输入机构标识") @Size(max = 64, message = "机构标识最多 64 个 UTF-16 代码单元") String tenantSlug,
        String planCode,
        @NotBlank(message = "请输入管理员用户名") @Size(max = 128, message = "用户名最多 128 个 UTF-16 代码单元") String adminUsername,
        @NotBlank(message = "请输入管理员密码")
        @CodePointLength(min = 12, message = "密码至少 12 个字符（按 Unicode 码点计数）")
        @Pattern(regexp = "(?s)^(?=.*[A-Za-z])(?=.*[0-9]).+$", message = "密码必须包含英文字母和数字")
        @com.skillforge.core.shared.validation.Utf8Bytes(max = 72, message = "密码的 UTF-8 编码最多 72 字节") String adminPassword,
        @Size(max = 255, message = "邮箱最多 255 个 UTF-16 代码单元") String adminEmail) {
    /** 文本统一去除首尾空白；密码保持原样，禁止悄悄改变凭据。 */
    public CreateTenantRequest {
        tenantName = trim(tenantName);
        tenantSlug = trim(tenantSlug);
        adminUsername = trim(adminUsername);
        adminEmail = trim(adminEmail);
    }

    private static String trim(String value) { return value == null ? null : value.strip(); }
}

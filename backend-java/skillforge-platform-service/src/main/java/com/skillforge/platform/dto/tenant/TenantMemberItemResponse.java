package com.skillforge.platform.dto.tenant;

import java.time.Instant;

/**
 * 机构成员响应 DTO。
 *
 * @param id          成员 ID
 * @param username    用户名
 * @param roles        角色：admin / developer / auditor
 * @param email       邮箱，可空
 * @param status      状态：active / disabled
 * @param lastLoginAt 最近登录时间，可空
 * @param createdAt   创建时间
 */
public record TenantMemberItemResponse(
        Long id,
        String username,
        java.util.List<String> roles,
        String email,
        String status,
        Instant lastLoginAt,
        Instant createdAt) {
}

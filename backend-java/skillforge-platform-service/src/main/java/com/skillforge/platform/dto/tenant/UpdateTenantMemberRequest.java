package com.skillforge.platform.dto.tenant;

/**
 * 平台更新机构成员请求 DTO。
 *
 * @param status 状态：active / disabled，可空表示不修改
 * @param roles   角色：admin / developer / auditor，可空表示不修改
 * @param email  邮箱，可空表示不修改
 */
public record UpdateTenantMemberRequest(
        String status,
        java.util.List<String> roles,
        String email) {
}

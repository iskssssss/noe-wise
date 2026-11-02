package com.skillforge.platform.dto.tenant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 平台运营后台机构成员分页查询对象。
 *
 * <p>GET /api/v1/admin/tenants/members 的查询参数封装，租户标识通过查询参数传入
 * （tenantId 为路径变量，不入查询对象）；
 * 缺省值在控制器侧按 {@code ApiConstants.DEFAULT_PAGE / DEFAULT_PAGE_SIZE} 归一化。
 *
 * @param page   页码，缺省 1
 * @param limit  每页条数，缺省 20，最大 200
 * @param role   角色筛选，可空
 * @param status 状态筛选，可空
 */
public record TenantMemberQuery(
        @Schema(description = "页码，缺省 1") Integer page,
        @Schema(description = "每页条数，缺省 20，最大 200") Integer limit,
        @Schema(description = "角色筛选，可空") String role,
        @Schema(description = "状态筛选，可空") String status) {
}

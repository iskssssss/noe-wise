package com.skillforge.platform.dto.tenant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 平台运营后台机构分页查询对象。
 *
 * <p>GET /api/v1/admin/tenants 的查询参数封装；
 * 缺省值在控制器侧按 {@code ApiConstants.DEFAULT_PAGE / DEFAULT_PAGE_SIZE} 归一化。
 *
 * @param page   页码，缺省 1
 * @param limit  每页条数，缺省 20，最大 200
 * @param status 状态筛选，可空
 * @param q      名称或 slug 关键字，可空
 */
public record TenantQuery(
        @Schema(description = "页码，缺省 1") Integer page,
        @Schema(description = "每页条数，缺省 20，最大 200") Integer limit,
        @Schema(description = "状态筛选，可空") String status,
        @Schema(description = "名称或 slug 关键字，可空") String q) {
}

package com.skillforge.platform.dto.tenant;

import java.util.List;

/**
 * 机构分页列表响应 DTO。
 *
 * @param total 总数
 * @param items 机构列表
 */
public record TenantListResponse(
        long total,
        List<TenantItemResponse> items) {
}

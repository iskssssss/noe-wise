package com.skillforge.platform.dto.tenant;

import java.util.List;

/**
 * 机构成员分页列表响应 DTO。
 *
 * @param total 总数
 * @param items 成员列表
 */
public record TenantMemberListResponse(
        long total,
        List<TenantMemberItemResponse> items) {
}

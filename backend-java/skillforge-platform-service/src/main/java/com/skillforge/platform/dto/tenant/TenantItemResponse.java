package com.skillforge.platform.dto.tenant;

import java.time.Instant;

/**
 * 机构列表项响应 DTO。
 *
 * @param id        机构 ID
 * @param name      机构显示名称
 * @param slug      机构唯一标识
 * @param planCode  套餐编码
 * @param status    机构状态：active / warning / suspended / terminated
 * @param createdAt 创建时间
 */
public record TenantItemResponse(
        Long id,
        String name,
        String slug,
        String planCode,
        String status,
        Instant createdAt) {
}

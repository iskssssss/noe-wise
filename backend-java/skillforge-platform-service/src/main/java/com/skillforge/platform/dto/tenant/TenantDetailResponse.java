package com.skillforge.platform.dto.tenant;

import java.time.Instant;

/**
 * 机构详情响应 DTO。
 *
 * @param id                             机构 ID
 * @param name                           机构显示名称
 * @param slug                           机构唯一标识
 * @param planId                         套餐 ID
 * @param planCode                       套餐编码
 * @param status                         机构状态：active / warning / suspended / terminated
 * @param deploymentMode                 部署模式：cloud / hybrid / on_premise
 * @param memberCount                    成员数
 * @param overrideQuotaApiCallsPerMonth  月度 API 调用配额覆盖
 * @param overrideQuotaMaxWorkflows         工作流上限配额覆盖
 * @param overrideQuotaMaxKnowledgeBases 知识库上限配额覆盖
 * @param overrideQuotaMaxMembers        成员上限配额覆盖
 * @param overrideQuotaMaxChannels       渠道上限配额覆盖
 * @param overrideRateLimitPerMinute     每分钟限流覆盖
 * @param createdAt                      创建时间
 * @param updatedAt                      更新时间
 */
public record TenantDetailResponse(
        Long id,
        String name,
        String slug,
        Long planId,
        String planCode,
        String status,
        String deploymentMode,
        long memberCount,
        Integer overrideQuotaApiCallsPerMonth,
        Integer overrideQuotaMaxWorkflows,
        Integer overrideQuotaMaxKnowledgeBases,
        Integer overrideQuotaMaxMembers,
        Integer overrideQuotaMaxChannels,
        Integer overrideRateLimitPerMinute,
        Instant createdAt,
        Instant updatedAt) {
}

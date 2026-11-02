package com.skillforge.core.workflow.dto.response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
/**
 * 市场工作流详情响应 DTO。
 *
 * @param workflowId             工作流业务 ID
 * @param name                工作流名称
 * @param version             版本号
 * @param type                工作流类型
 * @param description         工作流描述
 * @param author              作者
 * @param authorType          作者类型
 * @param category            工作流分类
 * @param tags                工作流标签
 * @param priceType           价格类型
 * @param priceCents          价格（分）
 * @param installCount        安装次数
 * @param rating              评分
 * @param configSchema        配置 schema
 * @param requiredPermissions 所需权限
 * @param publishedAt         发布时间
 */
public record MarketplaceWorkflowDetailResponse(
        String workflowId,
        String name,
        String version,
        String type,
        String description,
        String author,
        String authorType,
        String category,
        List<String> tags,
        String priceType,
        Integer priceCents,
        Integer installCount,
        List<Map<String, Object>> configSchema,
        List<String> requiredPermissions,
        Instant publishedAt) {
}

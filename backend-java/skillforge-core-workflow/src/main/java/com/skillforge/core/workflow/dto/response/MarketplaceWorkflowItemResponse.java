package com.skillforge.core.workflow.dto.response;
import java.util.List;
/**
 * 市场搜索工作流项 DTO。
 *
 * @param workflowId     工作流业务 ID
 * @param name        工作流名称
 * @param version     版本号
 * @param type        工作流类型
 * @param description 工作流描述
 * @param author      作者
 * @param category    工作流分类
 * @param tags        工作流标签
 * @param installCount 安装次数
 * @param priceType   价格类型
 */
public record MarketplaceWorkflowItemResponse(
        String workflowId,
        String name,
        String version,
        String type,
        String description,
        String author,
        String category,
        List<String> tags,
        Integer installCount,
        String priceType) {
}

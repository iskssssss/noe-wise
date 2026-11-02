package com.skillforge.core.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.core.capability.constant.MarketplaceConstants;
import com.skillforge.core.workflow.constant.WorkflowConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.dto.response.MarketplaceSearchResponse;
import com.skillforge.core.workflow.dto.response.MarketplaceWorkflowDetailResponse;
import com.skillforge.core.workflow.dto.response.MarketplaceWorkflowItemResponse;
import com.skillforge.core.workflow.dto.query.MarketplaceSearchQuery;
import com.skillforge.core.workflow.mapper.WorkflowTemplateMapper;
import com.skillforge.core.workflow.model.WorkflowTemplateDO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 平台模板市场查询；发布条件和持久化映射在工作流域内收口。 */
@Service
public class WorkflowTemplateService {
    private final WorkflowTemplateMapper templates;

    public WorkflowTemplateService(WorkflowTemplateMapper templates) { this.templates = templates; }

    public MarketplaceSearchResponse search(MarketplaceSearchQuery query) {
        // 市场仅上架平台模板（tenant_id NULL，V54 起租户模板不进市场）
        LambdaQueryWrapper<WorkflowTemplateDO> wrapper = new LambdaQueryWrapper<WorkflowTemplateDO>()
                .select(WorkflowTemplateDO::getWorkflowId, WorkflowTemplateDO::getName,
                        WorkflowTemplateDO::getVersion, WorkflowTemplateDO::getType,
                        WorkflowTemplateDO::getDescription, WorkflowTemplateDO::getAuthor,
                        WorkflowTemplateDO::getCategory, WorkflowTemplateDO::getTags,
                        WorkflowTemplateDO::getInstallCount, WorkflowTemplateDO::getPriceType)
                .eq(WorkflowTemplateDO::getStatus, WorkflowConstants.STATUS_PUBLISHED)
                .isNull(WorkflowTemplateDO::getTenantId);
        applySearchFilters(wrapper, query.q(), query.workflowType(), query.category());
        String sortBy = query.sortBy() == null || query.sortBy().isBlank()
                ? MarketplaceConstants.DEFAULT_SORT : query.sortBy();
        if (MarketplaceConstants.SORT_PUBLISHED_AT.equals(sortBy)) {
            wrapper.orderByDesc(WorkflowTemplateDO::getPublishedAt);
        } else {
            wrapper.orderByDesc(WorkflowTemplateDO::getInstallCount);
        }
        int safePage = query.page() == null || query.page() < 1 ? MarketplaceConstants.DEFAULT_PAGE : query.page();
        int safeSize = query.pageSize() == null
                ? MarketplaceConstants.DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(MarketplaceConstants.MAX_PAGE_SIZE, query.pageSize()));
        Page<WorkflowTemplateDO> result = templates.selectPage(
                new Page<>(safePage, safeSize), wrapper);
        return new MarketplaceSearchResponse(
                result.getTotal(),
                safePage,
                safeSize,
                result.getRecords().stream().map(this::searchItem).toList());
    }
    private void applySearchFilters(
            LambdaQueryWrapper<WorkflowTemplateDO> wrapper,
            String q,
            String workflowType,
            String category) {
        if (q != null && !q.isBlank()) {
            wrapper.and(w -> w.like(WorkflowTemplateDO::getName, q)
                    .or()
                    .like(WorkflowTemplateDO::getDescription, q));
        }
        if (workflowType != null && !workflowType.isBlank()) {
            wrapper.eq(WorkflowTemplateDO::getType, workflowType);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(WorkflowTemplateDO::getCategory, category);
        }
    }
    public MarketplaceWorkflowDetailResponse detail(String workflowId) {
        return detailItem(findPublished(workflowId));
    }
    private WorkflowTemplateDO findPublished(String workflowId) {
        // 市场仅平台模板（tenant_id NULL，V54 起租户模板不进市场）
        WorkflowTemplateDO template = templates.selectOne(
                new LambdaQueryWrapper<WorkflowTemplateDO>()
                        .eq(WorkflowTemplateDO::getWorkflowId, workflowId)
                        .eq(WorkflowTemplateDO::getStatus, WorkflowConstants.STATUS_PUBLISHED)
                        .isNull(WorkflowTemplateDO::getTenantId));
        if (template == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "工作流不存在或未发布");
        }
        return template;
    }
    private MarketplaceWorkflowItemResponse searchItem(WorkflowTemplateDO template) {
        return new MarketplaceWorkflowItemResponse(
                template.getWorkflowId(),
                template.getName(),
                template.getVersion(),
                template.getType(),
                template.getDescription(),
                template.getAuthor(),
                template.getCategory(),
                template.getTags(),
                template.getInstallCount(),
                template.getPriceType());
    }
    /**
     * 构建详情响应项。
     *
     * @param template 模板
     * @return 详情 DTO
     */
    private MarketplaceWorkflowDetailResponse detailItem(WorkflowTemplateDO template) {
        return new MarketplaceWorkflowDetailResponse(
                template.getWorkflowId(),
                template.getName(),
                template.getVersion(),
                template.getType(),
                template.getDescription(),
                template.getAuthor(),
                template.getAuthorType(),
                template.getCategory(),
                template.getTags(),
                template.getPriceType(),
                template.getPriceCents(),
                template.getInstallCount(),
                template.getConfigSchema(),
                template.getRequiredPermissions(),
                template.getPublishedAt());
    }

}

package com.skillforge.core.workflow.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.util.List;
import com.skillforge.core.workflow.model.WorkflowTemplateDO;
import org.apache.ibatis.annotations.Param;

/** 工作流审核记录与队列查询；记录仅追加，决定和模板状态在同一事务落盘。 */
public interface WorkflowReviewMapper {
    String bindPlatform();
    Page<ReviewRow> list(Page<ReviewRow> page, @Param("status") String status, @Param("search") String search);
    TemplateReview lockTemplate(@Param("workflowId") String workflowId);
    WorkflowTemplateDO detailTemplate(@Param("workflowId") String workflowId);
    List<ReviewAsset> detailAssets(@Param("workflowId") String workflowId, @Param("tenantId") Long tenantId);
    int decide(@Param("templateId") Long templateId, @Param("status") String status, @Param("at") Instant at);
    int appendDecision(@Param("id") Long id, @Param("template") TemplateReview template,
                       @Param("decision") String decision, @Param("reason") String reason,
                       @Param("actorId") String actorId, @Param("at") Instant at);

    record TemplateReview(Long id, String workflowId, Long tenantId, String version,
                          String status, Instant updatedAt) {}
    record ReviewRow(String id, String workflowName, String author, String status, String version,
                     Instant revisionAt, Instant updatedAt, String reason, String reviewerId, Instant reviewedAt) {}
    record ReviewAsset(String name, byte[] content) {}
}

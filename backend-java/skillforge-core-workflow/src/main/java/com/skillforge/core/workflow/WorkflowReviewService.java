package com.skillforge.core.workflow;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.workflow.constant.WorkflowConstants;
import com.skillforge.core.workflow.enums.ReviewStatus;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.dto.response.MarketplaceReviewDecisionResponse;
import com.skillforge.core.workflow.dto.response.MarketplaceReviewItemResponse;
import com.skillforge.core.workflow.dto.response.MarketplaceReviewListResponse;
import com.skillforge.core.workflow.dto.response.MarketplaceReviewDetailResponse;
import com.skillforge.core.workflow.mapper.WorkflowReviewMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台工作流审核应用入口；队列状态来自待审核模板或真实决定，普通草稿不进入审核结果。 */
@Service
public class WorkflowReviewService {
    private final WorkflowReviewMapper mapper;
    private final WorkflowSecurityScanner scanner;

    public WorkflowReviewService(WorkflowReviewMapper mapper, WorkflowSecurityScanner scanner) {
        this.mapper = mapper;
        this.scanner = scanner;
    }

    @Transactional(readOnly = true)
    public MarketplaceReviewListResponse list(String status, String search, int page, int limit) {
        bindPlatform();
        String normalized = status == null || status.equals("all") ? "" : status.trim();
        if (!normalized.isEmpty() && ReviewStatus.fromCode(normalized) == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "审核状态无效");
        }
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(ApiConstants.MAX_PAGE_SIZE, limit));
        Page<WorkflowReviewMapper.ReviewRow> request = new Page<>(safePage, safeLimit);
        // 保留 lateral 子查询的计数语义，不使用自动删 JOIN 优化。
        request.setOptimizeCountSql(false);
        var result = mapper.list(request, normalized, search == null ? "" : search.trim());
        return new MarketplaceReviewListResponse(result.getTotal(), safePage, safeLimit,
                result.getRecords().stream().map(row -> new MarketplaceReviewItemResponse(
                        row.id(), row.workflowName(), row.author(), ReviewStatus.fromCode(row.status()),
                        row.revisionAt(), row.version(), row.updatedAt(), row.reason(), row.reviewerId(), row.reviewedAt())).toList());
    }

    /** 只有只读 SQL；共享行锁需要普通事务，避免 PostgreSQL 只读事务禁止锁查询。 */
    @Transactional
    public MarketplaceReviewDetailResponse detail(String workflowId, Instant expectedUpdatedAt) {
        bindPlatform();
        if (workflowId == null || workflowId.isBlank() || expectedUpdatedAt == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少审核对象或所见修订");
        }
        var template = mapper.detailTemplate(workflowId);
        if (template == null) throw new ApiException(HttpStatus.NOT_FOUND, "工作流不存在");
        if (!expectedUpdatedAt.equals(template.getUpdatedAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "审核内容已变化，请刷新列表后重新查看");
        }
        var files = mapper.detailAssets(workflowId, template.getTenantId()).stream().map(asset -> {
            byte[] bytes = asset.content();
            boolean text = asset.name().toLowerCase(Locale.ROOT).matches(".*\\.(md|yaml|yml|json|py|sh|js|ts|txt)$");
            return new MarketplaceReviewDetailResponse.PackageFile(asset.name(), bytes.length,
                    text ? new String(bytes, StandardCharsets.UTF_8) : null);
        }).toList();
        return new MarketplaceReviewDetailResponse(template.getWorkflowId(), template.getName(),
                template.getVersion(), template.getStatus(), template.getUpdatedAt(),
                template.getDescription(), template.getAuthor(), template.getConfigSchema(),
                template.getRequiredPermissions(), template.getTools(), template.getConnectorRequirements(), files);
    }

    /** 锁住审核对象后校验页面所见修订；竞争失败返回 409，绝不重复记录决定。 */
    @Transactional
    public MarketplaceReviewDecisionResponse decide(String workflowId, Instant expectedUpdatedAt,
                                                    boolean approve, String reason, String actorId) {
        bindPlatform();
        if (workflowId == null || workflowId.isBlank() || expectedUpdatedAt == null || actorId == null || actorId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少审核对象、修订或操作者");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (!approve && (normalizedReason.isEmpty() || normalizedReason.length() > 1000)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "驳回理由需为 1 至 1000 个字符");
        }
        var template = mapper.lockTemplate(workflowId);
        if (template == null) throw new ApiException(HttpStatus.NOT_FOUND, "工作流不存在");
        if (!WorkflowConstants.STATUS_REVIEW.equals(template.status())
                || !expectedUpdatedAt.equals(template.updatedAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "审核对象已变化，请刷新后重试");
        }
        if (approve) {
            var scan = scanner.scan(template.workflowId());
            if (!scan.passed()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Map.of(
                        "message", "工作流安全扫描未通过", "issues", scan.issues()));
            }
        }
        String nextStatus = approve ? WorkflowConstants.STATUS_PUBLISHED : WorkflowConstants.STATUS_DRAFT;
        Instant at = Instant.now();
        if (mapper.decide(template.id(), nextStatus, at) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "审核状态已变化，请刷新后重试");
        }
        mapper.appendDecision(SnowflakeIds.nextId(), template, approve ? "approved" : "rejected",
                approve ? null : normalizedReason, actorId, at);
        return new MarketplaceReviewDecisionResponse(workflowId, nextStatus);
    }

    private void bindPlatform() {
        String tenant = TenantContext.getTenantId();
        if (tenant != null && !tenant.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "平台审核不得携带机构上下文");
        }
        mapper.bindPlatform();
    }
}

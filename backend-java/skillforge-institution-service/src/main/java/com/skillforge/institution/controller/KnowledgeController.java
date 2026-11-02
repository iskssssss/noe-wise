package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.core.shared.exception.ApiException;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.skillforge.institution.common.TenantIds;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.knowledge.constant.KnowledgeConstants;
import com.skillforge.core.knowledge.dto.request.CreateKnowledgeBaseRequest;
import com.skillforge.core.knowledge.dto.request.MarkKnowledgeBaseRevisionReadyRequest;
import com.skillforge.core.knowledge.dto.response.CreateKnowledgeBaseResponse;
import com.skillforge.core.shared.dto.resource.DeleteResponse;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseDetailResponse;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseListResponse;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseRevisionListResponse;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseRevisionResponse;
import com.skillforge.core.knowledge.dto.response.KnowledgeSearchItemResponse;
import com.skillforge.core.knowledge.dto.request.KnowledgeSearchRequest;
import com.skillforge.core.knowledge.dto.response.KnowledgeSearchResponse;
import com.skillforge.core.knowledge.dto.request.UpdateKnowledgeBaseRequest;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.institution.service.KnowledgeManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库接口控制器（B 端机构）。
 *
 * <p>提供知识库创建/列表/详情/编辑/归档/删除与检索接口；
 * 写操作需 admin / developer 角色，读操作登录即可；数据按租户隔离。
 */
@Tag(name = "知识库管理", description = "机构端知识库 CRUD 与检索（X-Tenant-ID 租户隔离）")
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    /** 知识库服务。 */
    private final KnowledgeService knowledgeService;

    /** Knowledge Base revision 生命周期服务。 */
    private final KnowledgeBaseRevisionService revisionService;
    private final KnowledgeManagementService management;

    /**
     * 构造知识库控制器。
     *
     * @param knowledgeService 知识库服务
     */
    public KnowledgeController(KnowledgeService knowledgeService,
            KnowledgeBaseRevisionService revisionService, KnowledgeManagementService management) {
        this.knowledgeService = knowledgeService;
        this.revisionService = revisionService;
        this.management = management;
    }

    /**
     * 列出当前租户的知识库（含文档数/分块数统计）。
     *
     * @return 知识库列表响应
     */
    @Operation(summary = "知识库列表", description = "返回当前租户全部知识库，含文档数与分块数统计；归档库同样返回供前端区分展示")
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/bases")
    public ApiResponse<KnowledgeBaseListResponse> listBases() {
        return ApiResponses.wrap(management.list(TenantIds.requireTenantId()));
    }

    /**
     * 创建知识库。
     *
     * @param request 创建知识库请求
     * @return 创建结果
     */
    @Operation(summary = "创建知识库", description = "创建知识库，状态置 active；写操作需 admin / developer")
    @RequirePermission("foundation.knowledge.create")
    @PostMapping("/bases")
    public ApiResponse<CreateKnowledgeBaseResponse> createBase(
            @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        CreateKnowledgeBaseResponse created;
        try {
            var settings = KnowledgeManagementService.advancedSettings(request);
            created = management.create(
                    TenantIds.requireTenantId(), request.name(), request.description(),
                    request.indexingMode(), request.retrievalMode(), request.rerankEnabled(), settings);
        } catch (ApiException failure) {
            throw knowledgeWriteRejection(failure);
        }
        return ApiResponses.wrap(created);
    }

    /**
     * 查询知识库详情。
     *
     * @param kbId 知识库 ID
     * @return 知识库详情
     */
    @Operation(summary = "知识库详情", description = "返回知识库基本信息、分段参数 settings 与文档/分块统计")
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/bases/detail")
    public ApiResponse<KnowledgeBaseDetailResponse> getBase(@RequestParam("kbId") Long kbId) {
        management.requireBase(TenantIds.requireTenantId(), kbId, "read");
        return ApiResponses.wrap(knowledgeService.getBase(TenantIds.requireTenantId(), kbId));
    }

    /** 列出知识库不可变 revision。 */
    @Operation(summary = "知识库 revision 列表", description = "返回 draft/ready/published/archived revision，按 revision 倒序")
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/bases/revisions")
    public ApiResponse<KnowledgeBaseRevisionListResponse> listRevisions(@RequestParam("kbId") Long kbId) {
        management.requireBase(TenantIds.requireTenantId(), kbId, "read");
        return ApiResponses.wrap(revisionService.list(TenantIds.requireTenantId(), kbId));
    }

    /** 打开知识库工作草稿；已有草稿时返回原身份。 */
    @Operation(summary = "打开知识库修订草稿", description = "复用已有 draft；没有草稿时创建待确定 manifest 的工作修订，发布后才切换活动版本")
    @RequirePermission("foundation.knowledge.create")
    @PostMapping("/bases/revisions")
    public ApiResponse<KnowledgeBaseRevisionResponse> createRevisionEndpoint(
            @Valid @RequestBody CreateRevisionCommand command) {
        return ApiResponses.wrap(createRevision(command.kbId()));
    }

    public KnowledgeBaseRevisionResponse createRevision(Long kbId) {
        management.requireBase(TenantIds.requireTenantId(), kbId, "manage");
        return revisionService.create(TenantIds.requireTenantId(), kbId);
    }

    /** 固定 source-version manifest 并推进到 ready。 */
    @Operation(summary = "准备知识库 revision", description = "校验 source_version 均属于当前知识库后将 draft 推进为 ready")
    @RequirePermission("foundation.knowledge.update")
    @PostMapping("/bases/revisions/ready")
    public ApiResponse<KnowledgeBaseRevisionResponse> markRevisionReadyEndpoint(
            @Valid @RequestBody MarkRevisionReadyCommand command) {
        return ApiResponses.wrap(markRevisionReady(command.kbId(), command.revision(), command.payload()));
    }

    public KnowledgeBaseRevisionResponse markRevisionReady(Long kbId, Long revision, @Valid MarkKnowledgeBaseRevisionReadyRequest request) {
        management.requireBase(TenantIds.requireTenantId(), kbId, "manage");
        return revisionService.markReady(TenantIds.requireTenantId(), kbId, revision,
                request.sourceVersions(), request.policy());
    }

    /** 发布 ready revision 并原子切换 active 指针。 */
    @Operation(summary = "发布知识库 revision", description = "以 ready 状态 CAS 发布并归档旧 published revision")
    @RequirePermission("foundation.knowledge.publish")
    @PostMapping("/bases/revisions/publish")
    public ApiResponse<KnowledgeBaseRevisionResponse> publishRevisionEndpoint(
            @Valid @RequestBody PublishRevisionCommand command) {
        return ApiResponses.wrap(publishRevision(command.kbId(), command.revision()));
    }

    public KnowledgeBaseRevisionResponse publishRevision(Long kbId, Long revision) {
        management.requireBase(TenantIds.requireTenantId(), kbId, "manage");
        return revisionService.publish(TenantIds.requireTenantId(), kbId, revision);
    }

    /**
     * 更新知识库（名称/描述/状态/分段参数），空值字段不修改。
     *
     * @param kbId    知识库 ID
     * @param request 更新请求
     * @return 更新后的知识库详情
     */
    @Operation(summary = "更新知识库", description = "修改名称/描述/状态（active 恢复、archived 归档）或分段参数 settings（chunk_size/chunk_overlap）；空值字段不修改")
    @RequirePermission("foundation.knowledge.update")
    @PatchMapping("/bases/update")
    public ApiResponse<KnowledgeBaseDetailResponse> updateBaseEndpoint(
            @Valid @RequestBody UpdateBaseCommand command) {
        KnowledgeBaseDetailResponse updated;
        try {
            updated = updateBase(command.kbId(), command.payload());
        } catch (ApiException failure) {
            throw knowledgeWriteRejection(failure);
        }
        return ApiResponses.wrap(updated);
    }

    /** 仅在事务性写入已回滚的业务拒绝上提供可重试的确定回执。 */
    private ApiException knowledgeWriteRejection(ApiException failure) {
        if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408)
            return failure;
        return new ApiException(failure.getStatus(), "KNOWLEDGE_WRITE_REJECTED",
                String.valueOf(failure.getDetail()), false);
    }

    public KnowledgeBaseDetailResponse updateBase(Long kbId, UpdateKnowledgeBaseRequest request) {
        management.requireBase(TenantIds.requireTenantId(), kbId, "manage");
        return knowledgeService.updateBase(
                TenantIds.requireTenantId(), kbId, request.name(), request.description(),
                request.status(),
                new KnowledgeService.KnowledgeBaseSettingsUpdate(
                        request.chunkSize(), request.chunkOverlap(),
                        request.indexingMode(), request.retrievalMode(), request.topK(),
                        request.scoreThresholdEnabled(), request.scoreThreshold(),
                        request.vectorWeight(), request.rerankEnabled()));
    }

    /**
     * 删除知识库（级联删除文档与分块）。
     *
     * @param kbId 知识库 ID
     * @return 删除结果
     */
    @Operation(summary = "删除知识库", description = "仅空知识库可硬删除；已有不可变来源版本时应归档，避免销毁已发布引用")
    @RequirePermission("foundation.knowledge.delete")
    @DeleteMapping("/bases/delete")
    public ApiResponse<DeleteResponse> deleteBaseEndpoint(
            @Valid @RequestBody DeleteBaseCommand command) {
        return ApiResponses.wrap(deleteBase(command.kbId()));
    }

    public DeleteResponse deleteBase(Long kbId) {
        management.delete(TenantIds.requireTenantId(), kbId);
        return new DeleteResponse("deleted");
    }

    /**
     * 检索知识库。
     *
     * @param request 检索请求
     * @return 检索结果列表
     */
    @Operation(summary = "知识库检索", description = "三级回退检索：ES → pgvector 向量 → 关键词；归档知识库返回空结果")
    @RequirePermission("foundation.knowledge.run")
    @PostMapping("/search")
    public ApiResponse<KnowledgeSearchResponse> search(@Valid @RequestBody KnowledgeSearchRequest request) {
        management.requireBase(TenantIds.requireTenantId(), request.kbId(), "execute");
        int topK = request.topK() == null ? KnowledgeConstants.DEFAULT_TOP_K : request.topK();
        var hits = knowledgeService.search(TenantIds.requireTenantId(), request.kbId(), request.query(), topK, request);
        return ApiResponses.wrap(new KnowledgeSearchResponse(hits.stream().map(hit ->
                new KnowledgeSearchItemResponse(hit.content(), hit.score(), hit.vectorScore(), hit.chunkId(), hit.documentId())).toList()));
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record CreateRevisionCommand(
            @JsonProperty("kb_id") @NotNull Long kbId) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record MarkRevisionReadyCommand(
            @JsonProperty("kb_id") @NotNull Long kbId,
            @JsonProperty("revision") @NotNull Long revision,
            @JsonProperty("payload") @NotNull @Valid MarkKnowledgeBaseRevisionReadyRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record PublishRevisionCommand(
            @JsonProperty("kb_id") @NotNull Long kbId,
            @JsonProperty("revision") @NotNull Long revision) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record UpdateBaseCommand(
            @JsonProperty("kb_id") @NotNull Long kbId,
            @JsonProperty("payload") @NotNull @Valid UpdateKnowledgeBaseRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record DeleteBaseCommand(
            @JsonProperty("kb_id") @NotNull Long kbId) {}
}

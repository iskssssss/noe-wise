package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.knowledge.dto.response.ChunkItemResponse;
import com.skillforge.core.knowledge.dto.response.ChunkListResponse;
import com.skillforge.core.shared.dto.resource.DeleteResponse;
import com.skillforge.core.knowledge.dto.response.DocumentDetailResponse;
import com.skillforge.core.knowledge.dto.response.DocumentListResponse;
import com.skillforge.core.knowledge.dto.knowledge.ReindexResponse;
import com.skillforge.core.knowledge.dto.request.UpdateChunkRequest;
import com.skillforge.core.knowledge.dto.request.UpdateDocumentRequest;
import com.skillforge.core.knowledge.dto.query.KnowledgeDocumentQuery;
import com.skillforge.core.knowledge.KnowledgeService;
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
 * 知识文档与分段管理接口控制器（B 端机构）。
 *
 * <p>提供文档分页/详情/编辑（重新分块）/删除/重新索引与分段列表/编辑/删除；
 * 写操作需 admin / developer 角色；数据按租户隔离。
 */
@Tag(name = "知识文档管理", description = "机构端知识文档与分段管理（X-Tenant-ID 租户隔离）")
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeDocumentController {

    /** 知识库服务。 */
    private final KnowledgeService knowledgeService;
    private final KnowledgeManagementService management;

    /**
     * 构造知识文档控制器。
     *
     * @param knowledgeService 知识库服务
     */
    public KnowledgeDocumentController(KnowledgeService knowledgeService, KnowledgeManagementService management) {
        this.knowledgeService = knowledgeService;
        this.management = management;
    }

    /**
     * 分页查询知识库文档。
     *
     * @param kbId     知识库 ID
     * @param page     页码，默认 1
     * @param pageSize 每页条数，默认 20，最大 200
     * @param status   状态过滤（ready / processing / failed），可空
     * @return 文档分页列表
     */
    @Operation(summary = "文档分页列表", description = "按知识库分页返回文档摘要，支持状态及当前标题 titleQuery 字面包含筛选，筛选后计算总数")
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/bases/documents")
    public ApiResponse<DocumentListResponse> listDocuments(
            @RequestParam("kbId") Long kbId,
            @Valid KnowledgeDocumentQuery query) {
        management.requireBase(TenantIds.requireTenantId(), kbId, "read");
        return ApiResponses.wrap(knowledgeService.listDocuments(TenantIds.requireTenantId(), kbId, query));
    }

    @Operation(summary = "选择最新就绪来源", description = "按文档创建时间倒序返回最多 2000 个当前就绪来源版本")
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/bases/documents/latest-ready-sources")
    public ApiResponse<java.util.List<String>> latestReadySources(@RequestParam("kbId") Long kbId) {
        management.requireBase(TenantIds.requireTenantId(), kbId, "read");
        return ApiResponses.wrap(knowledgeService.latestReadySources(TenantIds.requireTenantId(), kbId));
    }

    /**
     * 查询文档详情。
     *
     * @param docId 文档 ID
     * @return 文档详情（含正文，供编辑回显）
     */
    @Operation(summary = "文档详情", description = "返回文档标题/正文/元数据（文件上传时含 original_filename 等），正文供编辑回显")
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/documents/detail")
    public ApiResponse<DocumentDetailResponse> getDocument(@RequestParam("docId") Long docId) {
        management.requireDocument(TenantIds.requireTenantId(), docId, "read");
        return ApiResponses.wrap(knowledgeService.getDocument(TenantIds.requireTenantId(), docId));
    }

    @Operation(summary = "读取精确来源原文", description = "只读历史正文；不存在时返回 404，不回退当前版本")
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/documents/version")
    public ApiResponse<com.skillforge.core.knowledge.dto.response.DocumentVersionResponse> getDocumentSource(
            @RequestParam("docId") Long docId,
            @RequestParam("sourceVersion") @jakarta.validation.constraints.NotBlank String sourceVersion) {
        management.requireDocument(TenantIds.requireTenantId(), docId, "read");
        var version = knowledgeService.getDocumentVersion(TenantIds.requireTenantId(), docId, sourceVersion);
        return ApiResponses.wrap(new com.skillforge.core.knowledge.dto.response.DocumentVersionResponse(
                version.id(), version.kbId(), version.sourceVersion(), version.title(), version.content()));
    }

    /**
     * 更新文档：仅标题变化复用原文与分块集合；省略或回送相同正文不重新分块。
     * 正文实际变化时按知识库参数重新分块，未变化分块复用向量。
     *
     * @param docId   文档 ID
     * @param request 更新请求
     * @return 更新后的文档详情
     */
    @Operation(summary = "更新文档", description = "expected_source_version 必填；标题或正文修改均创建完整不可变来源版本，未变化分段复用向量并以 CAS 切换当前指针")
    @RequirePermission("foundation.knowledge.update")
    @PatchMapping("/documents/update")
    public ApiResponse<DocumentDetailResponse> updateDocumentEndpoint(
            @Valid @RequestBody UpdateDocumentCommand command) {
        return ApiResponses.wrap(updateDocument(command.docId(), command.payload()));
    }

    public DocumentDetailResponse updateDocument(Long docId, UpdateDocumentRequest request) {
        management.requireDocument(TenantIds.requireTenantId(), docId, "manage");
        return knowledgeService.updateDocument(
                TenantIds.requireTenantId(), docId, request.title(), request.content(),
                request.expectedSourceVersion());
    }

    /**
     * 软删除文档身份，保留不可变来源版本与分段。
     *
     * @param docId 文档 ID
     * @return 删除结果
     */
    @Operation(summary = "删除文档", description = "expected_source_version 必填；软删除管理面文档，保留已发布来源版本、分段与 Runtime 冻结能力")
    @RequirePermission("foundation.knowledge.delete")
    @DeleteMapping("/documents/delete")
    public ApiResponse<DeleteResponse> deleteDocumentEndpoint(
            @Valid @RequestBody DeleteDocumentCommand command) {
        return ApiResponses.wrap(deleteDocument(command.docId(), command.expectedSourceVersion()));
    }

    public DeleteResponse deleteDocument(Long docId, String expectedSourceVersion) {
        management.requireDocument(TenantIds.requireTenantId(), docId, "manage");
        knowledgeService.deleteDocument(TenantIds.requireTenantId(), docId, expectedSourceVersion);
        return new DeleteResponse("deleted");
    }

    /**
     * 重新索引文档：复制完整分段、重算向量并创建新来源版本。
     *
     * @param docId 文档 ID
     * @return 重算的分块数
     */
    @Operation(summary = "重新索引文档", description = "expected_source_version 必填；复制完整分段并重算 embedding，生成新来源版本后 CAS 切换当前指针")
    @RequirePermission("foundation.knowledge.run")
    @PostMapping("/documents/reindex")
    public ApiResponse<ReindexResponse> reindexDocumentEndpoint(
            @Valid @RequestBody ReindexDocumentCommand command) {
        return ApiResponses.wrap(reindexDocument(command.docId(), command.expectedSourceVersion()));
    }

    public ReindexResponse reindexDocument(Long docId, String expectedSourceVersion) {
        management.requireDocument(TenantIds.requireTenantId(), docId, "manage");
        var result = knowledgeService.reindexDocument(TenantIds.requireTenantId(), docId, expectedSourceVersion);
        return new ReindexResponse(result.documentId(), result.chunks());
    }

    /**
     * 分页查询文档分段。
     *
     * @param docId    文档 ID
     * @param page     页码，默认 1
     * @param pageSize 每页条数，默认 20，最大 200
     * @return 分段分页列表
     */
    @Operation(summary = "文档分段列表", description = "按文档分页返回分段（序号/内容/是否有向量），不返回向量本身")
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/documents/chunks")
    public ApiResponse<ChunkListResponse> listChunks(
            @RequestParam("docId") Long docId,
            @RequestParam(defaultValue = "" + ApiConstants.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = "" + ApiConstants.DEFAULT_PAGE_SIZE) long pageSize) {
        management.requireDocument(TenantIds.requireTenantId(), docId, "read");
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(ApiConstants.MAX_PAGE_SIZE, pageSize));
        return ApiResponses.wrap(knowledgeService.listChunks(
                TenantIds.requireTenantId(), docId, safePage, safeSize));
    }

    /**
     * 更新分段内容并重算向量。
     *
     * @param chunkId 分段 ID
     * @param request 更新请求
     * @return 更新后的分段
     */
    @Operation(summary = "更新分段", description = "expected_source_version 必填；复制完整分段集合，仅修改目标分段并复用其他向量，随后 CAS 切换当前版本")
    @RequirePermission("foundation.knowledge.update")
    @PatchMapping("/chunks/update")
    public ApiResponse<ChunkItemResponse> updateChunkEndpoint(
            @Valid @RequestBody UpdateChunkCommand command) {
        return ApiResponses.wrap(updateChunk(command.chunkId(), command.payload()));
    }

    public ChunkItemResponse updateChunk(Long chunkId, @Valid UpdateChunkRequest request) {
        management.requireChunk(TenantIds.requireTenantId(), chunkId, "manage");
        return knowledgeService.updateChunk(
                TenantIds.requireTenantId(), chunkId, request.content(),
                request.expectedSourceVersion());
    }

    /**
     * 删除分段并生成完整的新来源版本。
     *
     * @param chunkId 分段 ID
     * @return 删除结果
     */
    @Operation(summary = "删除分段", description = "expected_source_version 必填；生成不含目标分段的完整新版本，历史版本保持不变")
    @RequirePermission("foundation.knowledge.delete")
    @DeleteMapping("/chunks/delete")
    public ApiResponse<DeleteResponse> deleteChunkEndpoint(
            @Valid @RequestBody DeleteChunkCommand command) {
        return ApiResponses.wrap(deleteChunk(command.chunkId(), command.expectedSourceVersion()));
    }

    public DeleteResponse deleteChunk(Long chunkId, String expectedSourceVersion) {
        management.requireChunk(TenantIds.requireTenantId(), chunkId, "manage");
        knowledgeService.deleteChunk(TenantIds.requireTenantId(), chunkId, expectedSourceVersion);
        return new DeleteResponse("deleted");
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record UpdateDocumentCommand(
            @JsonProperty("doc_id") @NotNull Long docId,
            @JsonProperty("payload") @NotNull @Valid UpdateDocumentRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record DeleteDocumentCommand(
            @JsonProperty("doc_id") @NotNull Long docId,
            @JsonProperty("expected_source_version") @NotBlank
            String expectedSourceVersion) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record ReindexDocumentCommand(
            @JsonProperty("doc_id") @NotNull Long docId,
            @JsonProperty("expected_source_version") @NotBlank
            String expectedSourceVersion) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record UpdateChunkCommand(
            @JsonProperty("chunk_id") @NotNull Long chunkId,
            @JsonProperty("payload") @NotNull @Valid UpdateChunkRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record DeleteChunkCommand(
            @JsonProperty("chunk_id") @NotNull Long chunkId,
            @JsonProperty("expected_source_version") @NotBlank
            String expectedSourceVersion) {}
}

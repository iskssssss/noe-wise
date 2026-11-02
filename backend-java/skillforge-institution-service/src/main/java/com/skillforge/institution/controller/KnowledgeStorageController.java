package com.skillforge.institution.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.skillforge.core.knowledge.KnowledgeStorageService;
import com.skillforge.core.knowledge.model.KnowledgeStorageCapacity;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.InstitutionResourceAccess;
import com.skillforge.institution.service.KnowledgeManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 机构知识存储治理入口；租户从已认证主体取得，预算和总容量只允许全机构治理范围。 */
@Tag(name="知识存储治理")
@RestController
@RequestMapping("/api/v1/knowledge/storage")
public class KnowledgeStorageController {
    private final KnowledgeStorageService storage;
    private final KnowledgeManagementService management;
    private final FoundationAccess access;
    private final InstitutionResourceAccess resources;

    /** 注入知识治理与真实授权入口，HTTP 请求体不携带租户或成员身份。 */
    public KnowledgeStorageController(KnowledgeStorageService storage, KnowledgeManagementService management,
            FoundationAccess access, InstitutionResourceAccess resources) {
        this.storage=storage;this.management=management;this.access=access;this.resources=resources;
    }

    /** 同机构全范围治理者查询真实正文/向量容量和当前预算。 */
    @Operation(summary="知识存储容量",description="正文按UTF-8字节、向量按浮点载荷统计；physical_bytes仅为实际列字节，不表示含索引的整库磁盘空间")
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/capacity")
    public ApiResponse<KnowledgeStorageCapacity> capacity() {
        long tenant=institution(true);
        return ApiResponses.wrap(storage.capacity(tenant));
    }

    /** 显式切换观测/额度模式，不触发历史清理；已有占用高于新额度时只拒绝后续增长。 */
    @Operation(summary="设置知识存储预算",description="observation_only=true时两个额度必须为空；false时至少给出一个正数上限。仅新增正文按事务锁计量，标题和引用复用不重复扣预算")
    @RequirePermission("foundation.knowledge.update")
    @PostMapping("/budget")
    public ApiResponse<KnowledgeStorageCapacity> budget(@Valid @RequestBody BudgetCommand command) {
        long tenant=institution(true);
        boolean hasQuota=command.maxContentBytes()!=null||command.maxChunkBodies()!=null;
        if(command.observationOnly()==hasQuota)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"knowledge_storage_budget_mode_invalid",
                    "knowledge_storage_budget_mode_invalid",false);
        storage.configureBudget(tenant,command.maxContentBytes(),command.maxChunkBodies());
        return ApiResponses.wrap(storage.capacity(tenant));
    }

    /** 先预览明确边界内的未引用历史；实际执行仍在锁内重查，终态或软删除不自动解除引用。 */
    @Operation(summary="预览或回收知识历史",description="dry_run=true返回当前可回收版本ID；false执行同范围有界回收。当前、历史发布、Runtime冻结、重建和UNKNOWN引用均受保护。必须提供明确before，无默认TTL")
    @RequirePermission("foundation.knowledge.delete")
    @PostMapping("/recycle")
    public ApiResponse<ReclamationResponse> recycle(@Valid @RequestBody RecycleCommand command) {
        long tenant=institution(false);
        management.requireBase(Long.toString(tenant),command.kbId(),"manage");
        if (command.dryRun()) return ApiResponses.wrap(new ReclamationResponse(true,
                storage.previewRecycling(tenant,command.kbId(),command.before(),command.limit()),null));
        return ApiResponses.wrap(new ReclamationResponse(false,List.of(),
                storage.recycle(tenant,command.kbId(),command.before(),command.limit())));
    }

    /** 拒绝平台/独立外部身份；总容量和总预算还要求当前机构数据范围为all。 */
    private long institution(boolean wholeInstitution) {
        var subject=access.currentSubject();
        String tenant=TenantIds.requireTenantId();
        if (resources.external() || subject.platform() || !tenant.equals(subject.tenantId())
                || wholeInstitution && !access.currentDataScope().all())
            throw new ApiException(HttpStatus.FORBIDDEN,"knowledge_storage_institution_scope_required");
        return Long.parseLong(tenant);
    }

    /** 完整预算替换请求；显式声明观测模式，NULL的额度维度不限制。 */
    @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record BudgetCommand(@JsonProperty("observation_only") @Schema(description="true只观测且两额度为空；false至少指定一个额度") @NotNull Boolean observationOnly,
            @JsonProperty("max_content_bytes") @Schema(description="逻辑正文及向量载荷字节上限，正数；NULL不限制该维度") @Positive Long maxContentBytes,
            @JsonProperty("max_chunk_bodies") @Schema(description="唯一分块正文数上限，正数；NULL不限制该维度") @Positive Long maxChunkBodies) {
        /** 固定运营请求拒绝未知字段，宿主Jackson的宽容默认值不能吞掉配置或身份拼写错误。 */
        @JsonAnySetter public void rejectUnknownField(String field,Object ignored) {
            throw new IllegalArgumentException("knowledge_storage_budget_unknown_field");
        }
    }
    /** 明确对象、截止时间、批次和预览意图；正文中没有可伪造的租户字段。 */
    @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record RecycleCommand(@JsonProperty("kb_id") @Schema(description="当前机构已授权manage的知识库ID") @NotNull @Positive Long kbId,
            @Schema(description="明确历史保留截止时间，必须早于当前时刻；无默认TTL") @NotNull @Past Instant before,
            @Schema(description="每类事实有界批次，1至200") @NotNull @Positive @Max(200) Integer limit,
            @JsonProperty("dry_run") @Schema(description="true只预览，false锁内重查并执行") @NotNull Boolean dryRun) {
        /** 删除请求不接受租户/成员身份或未声明的过滤条件；失败发生在对象授权及读取之前。 */
        @JsonAnySetter public void rejectUnknownField(String field,Object ignored) {
            throw new IllegalArgumentException("knowledge_storage_recycle_unknown_field");
        }
    }
    /** 预览只返回候选身份；执行返回每类事实实际删除的行数。 */
    public record ReclamationResponse(boolean dryRun,List<String> versionIds,KnowledgeStorageService.Recycled affectedRows) {}
}

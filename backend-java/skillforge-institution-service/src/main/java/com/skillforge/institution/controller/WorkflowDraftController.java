package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.WorkflowDraftLifecycleService;
import com.skillforge.core.workflow.WorkflowBuilderSnapshotService;
import com.skillforge.core.workflow.WorkflowBuilderInstallationService;
import com.skillforge.core.workflow.dto.request.CreateWorkflowDraftRequest;
import com.skillforge.core.workflow.dto.request.SaveWorkflowDraftRequest;
import com.skillforge.core.workflow.dto.request.SaveWorkflowBuilderSnapshotRequest;
import com.skillforge.core.workflow.dto.request.WorkflowInstallByWorkflowIdRequest;
import com.skillforge.core.workflow.dto.response.WorkflowBuilderSnapshotResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftDiffResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftListResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftResponse;
import com.skillforge.core.workflow.dto.response.WorkflowDraftValidationResponse;
import com.skillforge.core.workflow.dto.response.WorkflowPublishResponse;
import com.skillforge.core.workflow.dto.response.WorkflowPublicationHistoryResponse;
import com.skillforge.core.workflow.dto.response.WorkflowPublicationDetailResponse;
import com.skillforge.core.workflow.dto.response.WorkflowInstallResponse;
import com.skillforge.core.workflow.dto.response.BuilderWorkflowInstanceListResponse;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.WorkflowManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Builder 的版本化设计接口；不提供 Workflow run/resume 或 checkpoint 读取旁路。 */
@RestController
@RequestMapping("/api/v1/builder/workflows")
public class WorkflowDraftController {

    private final WorkflowDraftLifecycleService lifecycleService;
    private final WorkflowBuilderSnapshotService snapshotService;
    private final WorkflowBuilderInstallationService installationService;
    private final WorkflowManagementService management;

    public WorkflowDraftController(WorkflowDraftLifecycleService lifecycleService,
            WorkflowBuilderSnapshotService snapshotService,
            WorkflowBuilderInstallationService installationService,
            WorkflowManagementService management) {
        this.lifecycleService = lifecycleService;
        this.snapshotService = snapshotService;
        this.installationService = installationService;
        this.management = management;
    }

    @RequirePermission("foundation.workflows.create")
    @PostMapping
    public ApiResponse<WorkflowDraftResponse> create(@RequestBody CreateWorkflowDraftRequest request) {
        return ApiResponses.wrap(management.create(tenantId(), request.workflowId(), request.definition()));
    }

    /** 当前租户的版本化 Builder 工作流列表；不混入旧模板或执行实例。 */
    @RequirePermission("foundation.workflows.read")
    @GetMapping
    public ApiResponse<WorkflowDraftListResponse> list() {
        return ApiResponses.wrap(management.list(tenantId()));
    }

    /** 当前租户已安装的 Builder 工作流实例。 */
    @RequirePermission("foundation.workflows.read")
    @GetMapping("/instances")
    public ApiResponse<BuilderWorkflowInstanceListResponse> listInstances() {
        long tenantId = tenantId();
        var items = management.instances(tenantId);
        return ApiResponses.wrap(new BuilderWorkflowInstanceListResponse(items.size(), items));
    }

    @RequirePermission("foundation.workflows.read")
    @GetMapping("/draft")
    public ApiResponse<WorkflowDraftResponse> getCurrentDraft(@RequestParam("workflowId") String workflowId) {
        management.require(tenantId(), workflowId, "read");
        return ApiResponses.wrap(lifecycleService.getCurrentDraft(tenantId(), workflowId));
    }

    /** 每个稳定工作流只展示一份工作草稿，历史仅列出已发布定义。 */
    @RequirePermission("foundation.workflows.read")
    @GetMapping("/history")
    public ApiResponse<WorkflowPublicationHistoryResponse> history(@RequestParam("workflowId") String workflowId) {
        return ApiResponses.wrap(management.history(tenantId(), workflowId));
    }

    @RequirePermission("foundation.workflows.read")
    @GetMapping("/history/detail")
    public ApiResponse<WorkflowPublicationDetailResponse> publicationDetail(@RequestParam("workflowId") String workflowId,
            @RequestParam("definitionVersionId") long definitionVersionId) {
        return ApiResponses.wrap(management.publicationDetail(tenantId(), workflowId, definitionVersionId));
    }

    /** 历史定义恢复到工作草稿；发布后才影响下一次执行。 */
    @RequirePermission("foundation.workflows.update")
    @PostMapping("/draft/restore")
    public ApiResponse<WorkflowDraftResponse> restore(@Valid @RequestBody RestoreCommand command,
            @RequestHeader("If-Match") String ifMatch) {
        management.require(tenantId(), command.workflowId(), "manage");
        return ApiResponses.wrap(lifecycleService.restore(tenantId(), command.workflowId(), parseRevision(ifMatch),
                command.definitionVersionId()));
    }

    /** 只读比较当前草稿与 active DefinitionVersion，供发布前审阅。 */
    @RequirePermission("foundation.workflows.read")
    @GetMapping("/draft/diff")
    public ApiResponse<WorkflowDraftDiffResponse> diff(@RequestParam("workflowId") String workflowId) {
        management.require(tenantId(), workflowId, "read");
        return ApiResponses.wrap(lifecycleService.diff(tenantId(), workflowId));
    }

    /** 读取当前 draft revision 的布局与视口，发布态仅允许读取。 */
    @RequirePermission("foundation.workflows.read")
    @GetMapping("/draft/snapshot")
    public ApiResponse<WorkflowBuilderSnapshotResponse> getSnapshot(@RequestParam("workflowId") String workflowId) {
        management.require(tenantId(), workflowId, "read");
        return ApiResponses.wrap(snapshotService.getCurrent(tenantId(), workflowId));
    }

    /** 以 If-Match 绑定当前 draft revision 保存布局与视口，绝不写入 DefinitionVersion。 */
    @RequirePermission("foundation.workflows.update")
    @PutMapping("/draft/snapshot")
    public ApiResponse<WorkflowBuilderSnapshotResponse> saveSnapshotEndpoint(
            @Valid @RequestBody SaveSnapshotCommand command,
            @RequestHeader("If-Match") String ifMatch) {
        return ApiResponses.wrap(saveSnapshot(command.workflowId(), ifMatch, command.payload()));
    }

    public WorkflowBuilderSnapshotResponse saveSnapshot(String workflowId, @RequestHeader("If-Match") String ifMatch, SaveWorkflowBuilderSnapshotRequest request) {
        management.require(tenantId(), workflowId, "manage");
        return snapshotService.save(tenantId(), workflowId, parseRevision(ifMatch), request.layout(), request.viewport());
    }

    @RequirePermission("foundation.workflows.update")
    @PutMapping("/draft")
    public ApiResponse<WorkflowDraftResponse> saveEndpoint(
            @Valid @RequestBody SaveCommand command,
            @RequestHeader("If-Match") String ifMatch) {
        return ApiResponses.wrap(save(command.workflowId(), ifMatch, command.payload()));
    }

    public WorkflowDraftResponse save(String workflowId, @RequestHeader("If-Match") String ifMatch, SaveWorkflowDraftRequest request) {
        management.require(tenantId(), workflowId, "manage");
        return lifecycleService.save(tenantId(), workflowId, parseRevision(ifMatch), request.definition());
    }

    /** 从 active DefinitionVersion 派生下一份可编辑草稿；不能直接修改发布物。 */
    @RequirePermission("foundation.workflows.create")
    @PostMapping("/draft/fork")
    public ApiResponse<WorkflowDraftResponse> forkPublishedDraftEndpoint(
            @Valid @RequestBody ForkPublishedDraftCommand command,
            @RequestHeader("If-Match") String ifMatch) {
        return ApiResponses.wrap(forkPublishedDraft(command.workflowId(), ifMatch));
    }

    public WorkflowDraftResponse forkPublishedDraft(String workflowId, @RequestHeader("If-Match") String ifMatch) {
        management.require(tenantId(), workflowId, "manage");
        return lifecycleService.forkPublishedDraft(tenantId(), workflowId, parseRevision(ifMatch));
    }

    @RequirePermission("foundation.workflows.update")
    @PostMapping("/draft/validate")
    public ApiResponse<WorkflowDraftValidationResponse> validateEndpoint(
            @Valid @RequestBody ValidateCommand command) {
        return ApiResponses.wrap(validate(command.workflowId()));
    }

    public WorkflowDraftValidationResponse validate(String workflowId) {
        management.require(tenantId(), workflowId, "manage");
        return lifecycleService.validate(tenantId(), workflowId);
    }

    /** 新工作流仅验证输入，不创建对象。 */
    @RequirePermission("foundation.workflows.create")
    @PostMapping("/validate-content")
    public ApiResponse<WorkflowDraftValidationResponse> validateNewContent(@Valid @RequestBody ValidateContentCommand command) {
        return ApiResponses.wrap(lifecycleService.validateNewContent(command.definition()));
    }

    /** 已保存工作流仍要求管理权限和精确的草稿修订。 */
    @RequirePermission("foundation.workflows.update")
    @PostMapping("/draft/validate-content")
    public ApiResponse<WorkflowDraftValidationResponse> validateContent(@Valid @RequestBody ValidateDraftContentCommand command,
            @RequestHeader("If-Match") String ifMatch) {
        long tenant = tenantId();
        management.require(tenant, command.workflowId(), "manage");
        return ApiResponses.wrap(lifecycleService.validateContent(tenant, command.workflowId(), parseRevision(ifMatch), command.definition()));
    }

    @RequirePermission("foundation.workflows.publish")
    @PostMapping("/publish")
    public ApiResponse<WorkflowPublishResponse> publishEndpoint(
            @Valid @RequestBody PublishCommand command,
            @RequestHeader("If-Match") String ifMatch) {
        return ApiResponses.wrap(publish(command.workflowId(), ifMatch));
    }

    public WorkflowPublishResponse publish(String workflowId, @RequestHeader("If-Match") String ifMatch) {
        management.require(tenantId(), workflowId, "manage");
        return lifecycleService.publish(tenantId(), workflowId, parseRevision(ifMatch));
    }

    /**
     * 安装稳定工作流身份并保存业务配置和能力槽映射。
     *
     * <p>后续发布会在下一次执行解析时生效；安装不改变已经冻结的执行。
     */
    @RequirePermission("foundation.workflows.create")
    @PostMapping("/install")
    public ApiResponse<WorkflowInstallResponse> installOrUpgradeEndpoint(
            @Valid @RequestBody InstallOrUpgradeCommand command) {
        return ApiResponses.wrap(installOrUpgrade(command.workflowId(), command.payload()));
    }

    public WorkflowInstallResponse installOrUpgrade(String workflowId, WorkflowInstallByWorkflowIdRequest request) {
        management.require(tenantId(), workflowId, "manage");
        return installationService.installOrUpgrade(tenantId(), workflowId,
                request == null || request.config() == null ? java.util.Map.of() : request.config(),
                request == null ? "" : request.customPrompt(), request == null ? null : request.bindings());
    }

    private long tenantId() {
        return Long.parseLong(TenantIds.requireTenantId());
    }

    private long parseRevision(String ifMatch) {
        String normalized = ifMatch == null ? "" : ifMatch.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            long revision = Long.parseLong(normalized);
            if (revision > 0) {
                return revision;
            }
        } catch (NumberFormatException ignored) {
            // 统一为 400，避免底层解析错误泄露。
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "If-Match 必须为正整数草稿 revision");
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record SaveSnapshotCommand(
            @JsonProperty("workflow_id") @NotNull String workflowId,
            @JsonProperty("payload") @NotNull @Valid SaveWorkflowBuilderSnapshotRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record SaveCommand(
            @JsonProperty("workflow_id") @NotNull String workflowId,
            @JsonProperty("payload") @NotNull @Valid SaveWorkflowDraftRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record ForkPublishedDraftCommand(
            @JsonProperty("workflow_id") @NotNull String workflowId) {}

    public record RestoreCommand(
            @JsonProperty("workflow_id") @NotNull String workflowId,
            @JsonProperty("definition_version_id") @NotNull Long definitionVersionId) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record ValidateCommand(
            @JsonProperty("workflow_id") @NotNull String workflowId) {}

    public record ValidateContentCommand(@NotNull java.util.Map<String, Object> definition) {}
    public record ValidateDraftContentCommand(@JsonProperty("workflow_id") @NotNull String workflowId,
            @NotNull java.util.Map<String, Object> definition) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record PublishCommand(
            @JsonProperty("workflow_id") @NotNull String workflowId) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record InstallOrUpgradeCommand(
            @JsonProperty("workflow_id") @NotNull String workflowId,
            @JsonProperty("payload") @Valid WorkflowInstallByWorkflowIdRequest payload) {}
}

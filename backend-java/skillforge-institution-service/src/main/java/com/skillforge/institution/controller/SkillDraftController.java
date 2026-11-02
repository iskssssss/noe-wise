package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.SkillDependencySelection;
import com.skillforge.core.capability.SkillActivationMode;
import com.skillforge.core.capability.SkillDraftContent;
import com.skillforge.core.capability.SkillDraftFilesDeserializer;
import com.skillforge.core.capability.SkillDraftService;
import com.skillforge.core.capability.SkillDraftValidation;
import com.skillforge.core.capability.SkillDraftView;
import com.skillforge.institution.common.TenantIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 当前租户的 Skill 草稿控制面；制品安全校验与发布规则由生命周期服务统一执行。 */
@RestController
@RequestMapping("/api/v1/capabilities/skill-drafts")
@Validated
public class SkillDraftController {

    private final SkillDraftService drafts;

    private final FoundationAccess access;

    public SkillDraftController(SkillDraftService drafts, FoundationAccess access) {
        this.drafts = drafts;
        this.access = access;
    }

    /** 在同一事务内创建技能身份及其活动工作草稿。 */
    @RequirePermission("foundation.skills.create")
    @PostMapping("/new")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SkillDraftView> createSkill(@Valid @RequestBody NewSkillCommand command) {
        access.require("foundation.capabilities.create");
        return ApiResponses.wrap(drafts.createSkill(tenantScope(), command.capabilityKey(),
                command.name(), command.description()));
    }

    /** 返回指定 Definition 的活动草稿；尚未创建草稿时以 204 表达。 */
    @RequirePermission("foundation.skills.read")
    @GetMapping
    public ResponseEntity<ApiResponse<SkillDraftView>> active(@RequestParam("definitionId") @Positive Long definitionId) {
        Optional<SkillDraftView> draft = drafts.active(tenantScope(), definitionId);
        return ApiResponses.wrap(draft.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build()));
    }

    @RequirePermission("foundation.skills.create")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SkillDraftView> create(@Valid @RequestBody CreateCommand command) {
        return ApiResponses.wrap(drafts.create(tenantScope(), command.definitionId(), command.baseVersionId()));
    }

    @RequirePermission("foundation.skills.read")
    @GetMapping("/detail")
    public ApiResponse<SkillDraftView> get(@RequestParam("draftId") @Positive Long draftId) {
        return ApiResponses.wrap(drafts.get(tenantScope(), draftId));
    }

    @RequirePermission("foundation.skills.read")
    @GetMapping("/version")
    public ApiResponse<SkillDraftView> version(@RequestParam("versionId") @Positive Long versionId) {
        return ApiResponses.wrap(drafts.version(tenantScope(), versionId));
    }

    @RequirePermission("foundation.skills.update")
    @PutMapping
    public ApiResponse<SkillDraftView> save(@Valid @RequestBody SaveCommand command,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(drafts.saveSelected(tenantScope(), command.draftId(), parseIfMatch(ifMatch), command.content(), command.selections()));
    }

    @RequirePermission("foundation.skills.create")
    @PostMapping(value = "/import-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SkillDraftView> importZip(
            @RequestParam("draft_id") @Positive Long draftId,
            @RequestPart("archive") MultipartFile archive,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) throws IOException {
        return ApiResponses.wrap(drafts.importZip(tenantScope(), draftId, parseIfMatch(ifMatch), archive.getBytes()));
    }

    @RequirePermission("foundation.skills.update")
    @PostMapping("/validate")
    public ApiResponse<SkillDraftValidation> validate(@Valid @RequestBody ValidateCommand command) {
        return ApiResponses.wrap(drafts.validate(tenantScope(), command.draftId(), command.revision()));
    }

    @RequirePermission("foundation.skills.update")
    @PostMapping("/validate-content")
    public ApiResponse<SkillDraftValidation> validateContent(@Valid @RequestBody SaveCommand command,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(drafts.validateSelected(tenantScope(), command.draftId(),
                parseIfMatch(ifMatch), command.content(), command.selections()));
    }

    @RequirePermission("foundation.skills.publish")
    @PostMapping("/publish")
    public ApiResponse<PublishResponse> publish(@Valid @RequestBody PublishCommand command) {
        var result = drafts.publishChecked(tenantScope(), command.draftId(), command.revision(),
                command.definitionRevision());
        return ApiResponses.wrap(new PublishResponse(result.publication().versionId(), result.publication().definitionRevision(), result.validation()));
    }

    public record PublishResponse(@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long versionId,
            long definitionRevision, SkillDraftValidation validation) { }

    private CapabilityCatalogScope tenantScope() {
        String tenantId = TenantIds.requireTenantId();
        try {
            return CapabilityCatalogScope.tenant(Long.parseLong(tenantId));
        } catch (NumberFormatException exception) {
            throw CapabilityCatalogErrors.exception(
                    HttpStatus.UNAUTHORIZED, CapabilityCatalogErrors.TENANT_REQUIRED,
                    "租户上下文格式无效", exception);
        }
    }

    private long parseIfMatch(String value) {
        if (value == null || value.isBlank()) {
            throw CapabilityCatalogErrors.exception(
                    HttpStatus.BAD_REQUEST, CapabilityCatalogErrors.IF_MATCH_REQUIRED,
                    "If-Match revision is required");
        }
        String normalized = value.trim();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2).trim();
        }
        if (normalized.length() >= 2 && normalized.startsWith("\"")
                && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            long revision = Long.parseLong(normalized);
            if (revision <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return revision;
        } catch (NumberFormatException exception) {
            throw CapabilityCatalogErrors.exception(
                    HttpStatus.BAD_REQUEST, CapabilityCatalogErrors.IF_MATCH_INVALID,
                    "If-Match revision 格式无效", exception);
        }
    }

    /** 新技能元数据；归属仅从当前认证租户取得。 */
    public record NewSkillCommand(
            @JsonProperty("capability_key") @NotBlank @Size(max = 128) String capabilityKey,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 4000) String description) {}

    /** 创建草稿的 HTTP 请求体。 */
    public record CreateCommand(
            @JsonProperty("definition_id") @NotNull @Positive Long definitionId,
            @JsonProperty("base_version_id") @Positive Long baseVersionId) {}

    /** 保存草稿的 HTTP 请求体；内容安全校验统一在服务层完成。 */
    public record SaveCommand(
            @JsonProperty("draft_id") @NotNull @Positive Long draftId,
            @JsonProperty("version_key") String versionKey,
            @JsonProperty("files") @JsonDeserialize(using = SkillDraftFilesDeserializer.class)
            Map<String, String> files,
            @JsonProperty("model_invocable") boolean modelInvocable,
            @JsonProperty("user_invocable") boolean userInvocable,
            @JsonProperty("activation_mode") SkillActivationMode activationMode,
            @JsonProperty("conflict_group") String conflictGroup,
            @JsonProperty("required_capabilities") List<@NotNull @Valid SkillDependencySelection> requiredCapabilities) {

        SkillDraftContent content() {
            return new SkillDraftContent(versionKey, files, modelInvocable, userInvocable, activationMode, conflictGroup, List.of());
        }
        List<SkillDependencySelection> selections() { return requiredCapabilities == null ? List.of() : requiredCapabilities; }
    }

    /** 草稿校验请求必须绑定待校验 revision。 */
    public record ValidateCommand(
            @JsonProperty("draft_id") @NotNull @Positive Long draftId,
            @JsonProperty("revision") @Positive long revision) {}

    /** 发布请求同时绑定草稿与 Definition revision，避免发布陈旧输入。 */
    public record PublishCommand(
            @JsonProperty("draft_id") @NotNull @Positive Long draftId,
            @JsonProperty("revision") @Positive long revision,
            @JsonProperty("definition_revision") @Positive long definitionRevision) {}
}

package com.skillforge.platform.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillPackage;
import com.skillforge.core.capability.SkillPackageCatalogService;
import com.skillforge.core.capability.SkillPackageImportRequest;
import com.skillforge.platform.dto.capability.CapabilityDefinitionResponse;
import com.skillforge.platform.dto.capability.CapabilityVersionSummaryResponse;
import com.skillforge.platform.dto.capability.CapabilityRequirementOptionResponse;
import com.skillforge.platform.dto.capability.SkillCatalogSummaryResponse;
import com.skillforge.platform.dto.capability.CapabilityVersionResponse;
import com.skillforge.platform.dto.capability.CapabilityValidationResponse;
import com.skillforge.platform.dto.capability.CreateCapabilityDefinitionRequest;
import com.skillforge.platform.dto.capability.CreateCapabilityVersionRequest;
import com.skillforge.platform.dto.capability.UpdateCapabilityVersionRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

/** 平台公共 Capability Catalog API；所有写入显式使用 platform_admin 数据库上下文。 */
@RestController
@RequestMapping("/api/v1/admin/capabilities")
public class CapabilityCatalogController {

    private final CapabilityCatalogService catalog;
    private final SkillPackageCatalogService skillPackages;

    public CapabilityCatalogController(
            CapabilityCatalogService catalog,
            SkillPackageCatalogService skillPackages) {
        this.catalog = catalog;
        this.skillPackages = skillPackages;
    }

    @RequirePermission("foundation.capabilities.create")
    @PostMapping("/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CapabilityDefinitionResponse> createDefinition(
            @Valid @RequestBody CreateCapabilityDefinitionRequest request) {
        return ApiResponses.wrap(CapabilityDefinitionResponse.from(catalog.createDefinition(
                CapabilityCatalogScope.platform(), parseType(request.capabilityType()),
                request.capabilityKey(), request.name(), request.description())));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/definitions")
    public ApiResponse<List<CapabilityDefinitionResponse>> listDefinitions(
            @RequestParam(name = "capability_type", required = false) String capabilityType,
            @RequestParam(required = false) String status) {
        CapabilityType type = capabilityType == null || capabilityType.isBlank()
                ? null : parseType(capabilityType);
        return ApiResponses.wrap(catalog.listDefinitions(CapabilityCatalogScope.platform(), type, status).stream()
                .map(CapabilityDefinitionResponse::from).toList());
    }

    /** 技能目录只读摘要；工作台完整配置仍由现有详情入口按需读取。 */
    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/skills/catalog")
    public ApiResponse<List<SkillCatalogSummaryResponse>> listSkills() {
        return ApiResponses.wrap(catalog.listPlatformSkills().stream()
                .map(SkillCatalogSummaryResponse::from).toList());
    }

    /** 依赖选择仅读取当前可选发布版本的精确身份与名称。 */
    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/requirements/options")
    public ApiResponse<List<CapabilityRequirementOptionResponse>> requirementOptions(
            @RequestParam("capability_type") String capabilityType) {
        return ApiResponses.wrap(catalog.listRequirementOptions(CapabilityCatalogScope.platform(),
                parseType(capabilityType)).stream()
                .map(CapabilityRequirementOptionResponse::from).toList());
    }

    /** 能力版本目录只读元数据摘要；正文继续由精确版本详情按需返回。 */
    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/versions/summaries")
    public ApiResponse<List<CapabilityVersionSummaryResponse>> versionSummaries(
            @RequestParam("definition_id") Long definitionId) {
        return ApiResponses.wrap(catalog.listVersionSummaries(CapabilityCatalogScope.platform(), definitionId).stream()
                .map(CapabilityVersionSummaryResponse::from).toList());
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/definitions/detail")
    public ApiResponse<CapabilityDefinitionResponse> getDefinition(@RequestParam("definitionId") Long definitionId) {
        return ApiResponses.wrap(CapabilityDefinitionResponse.from(
                catalog.getDefinition(CapabilityCatalogScope.platform(), definitionId)));
    }

    @RequirePermission("foundation.capabilities.create")
    @PostMapping("/definitions/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CapabilityVersionResponse> createVersionEndpoint(@Valid @RequestBody CreateVersionCommand command) {
        return ApiResponses.wrap(createVersion(command.definitionId(), command.payload()));
    }

    public CapabilityVersionResponse createVersion(
            Long definitionId,
            CreateCapabilityVersionRequest request) {
        return CapabilityVersionResponse.from(catalog.createDraftVersion(
                CapabilityCatalogScope.platform(), definitionId, request.versionKey(), request.name(),
                request.description(), request.contentHash(), request.descriptor()));
    }

    @RequirePermission("foundation.capabilities.create")
    @PostMapping("/definitions/skill-versions/import-directory")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CapabilityVersionResponse> importSkillDirectoryEndpoint(
            @Valid @RequestBody ImportSkillDirectoryCommand command) {
        return ApiResponses.wrap(importSkillDirectory(command.definitionId(), command.payload()));
    }

    public CapabilityVersionResponse importSkillDirectory(
            Long definitionId,
            SkillPackageImportRequest request) {
        return CapabilityVersionResponse.from(skillPackages.importDirectory(
                CapabilityCatalogScope.platform(), definitionId, request));
    }

    @RequirePermission("foundation.capabilities.create")
    @PostMapping(value = "/definitions/skill-versions/import-zip",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CapabilityVersionResponse> importSkillZipEndpoint(
            @Valid @RequestPart("metadata") ImportSkillZipCommand command, @RequestPart("archive") MultipartFile archive) throws java.io.IOException {
        return ApiResponses.wrap(importSkillZip(command.definitionId(), command.payload(), archive));
    }

    public CapabilityVersionResponse importSkillZip(
            Long definitionId,
            SkillPackageImportRequest request,
            MultipartFile archive) throws java.io.IOException {
        return CapabilityVersionResponse.from(skillPackages.importZip(
                CapabilityCatalogScope.platform(), definitionId, request, archive.getBytes()));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/skill-versions/files")
    public ApiResponse<List<SkillPackageCatalogService.SkillFileView>> listSkillFiles(
            @RequestParam("versionId") Long versionId) {
        return ApiResponses.wrap(skillPackages.listFiles(CapabilityCatalogScope.platform(), versionId));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/skill-versions/file")
    public ResponseEntity<byte[]> getSkillFile(
            @RequestParam("versionId") Long versionId, @RequestParam String path) {
        SkillPackage.FileEntry file = skillPackages.getFile(
                CapabilityCatalogScope.platform(), versionId, path);
        byte[] content = file.encoding() == SkillPackage.Encoding.UTF8
                ? file.content().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                : java.util.Base64.getDecoder().decode(file.content());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.ETAG, "\"" + file.sha256() + "\"")
                .body(content);
    }

    @RequirePermission("foundation.capabilities.export")
    @GetMapping("/skill-versions/download")
    public ResponseEntity<byte[]> downloadSkill(@RequestParam("versionId") Long versionId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=skill-" + versionId + ".zip")
                .body(skillPackages.download(CapabilityCatalogScope.platform(), versionId));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/definitions/versions")
    public ApiResponse<List<CapabilityVersionResponse>> listVersions(
            @RequestParam("definitionId") Long definitionId,
            @RequestParam(required = false) String status) {
        return ApiResponses.wrap(catalog.listVersions(CapabilityCatalogScope.platform(), definitionId, status).stream()
                .map(CapabilityVersionResponse::from).toList());
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/versions/detail")
    public ApiResponse<CapabilityVersionResponse> getVersion(@RequestParam("versionId") Long versionId) {
        return ApiResponses.wrap(CapabilityVersionResponse.from(
                catalog.getVersion(CapabilityCatalogScope.platform(), versionId)));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/definitions/current-publication")
    public ApiResponse<CapabilityVersionResponse> currentPublication(@RequestParam("definitionId") Long definitionId) {
        return ApiResponses.wrap(CapabilityVersionResponse.from(catalog.getCurrentPublishedVersion(CapabilityCatalogScope.platform(), definitionId)));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/definitions/skill-default")
    public ApiResponse<CapabilityVersionResponse> getSkillDefault(@RequestParam("definitionId") Long definitionId) {
        return ApiResponses.wrap(CapabilityVersionResponse.from(catalog.getDefaultSkillVersion(
                CapabilityCatalogScope.platform(), definitionId)));
    }

    @RequirePermission("foundation.capabilities.run")
    @PostMapping("/definitions/skill-default/execute")
    public ApiResponse<CapabilityDefinitionResponse> setSkillDefaultEndpoint(
            @Valid @RequestBody SetSkillDefaultCommand command, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(setSkillDefault(command.definitionId(), command.versionId(), ifMatch));
    }

    public CapabilityDefinitionResponse setSkillDefault(
            Long definitionId,
            Long versionId,
            String ifMatch) {
        return CapabilityDefinitionResponse.from(catalog.setDefaultSkillVersion(
                CapabilityCatalogScope.platform(), definitionId, versionId, parseIfMatch(ifMatch)));
    }

    @RequirePermission("foundation.capabilities.update")
    @PostMapping("/definitions/versions/validate")
    public ApiResponse<CapabilityValidationResponse> validateVersionEndpoint(@Valid @RequestBody ValidateVersionCommand command) {
        return ApiResponses.wrap(validateVersion(command.definitionId(), command.versionId()));
    }

    public CapabilityValidationResponse validateVersion(
            Long definitionId, Long versionId) {
        return CapabilityValidationResponse.from(catalog.validateVersion(
                CapabilityCatalogScope.platform(), definitionId, versionId));
    }

    @RequirePermission("foundation.capabilities.update")
    @PutMapping("/definitions/versions/update")
    public ApiResponse<CapabilityVersionResponse> updateVersionEndpoint(
            @Valid @RequestBody UpdateVersionCommand command, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(updateVersion(command.definitionId(), command.versionId(), ifMatch, command.payload()));
    }

    public CapabilityVersionResponse updateVersion(
            Long definitionId,
            Long versionId,
            String ifMatch,
            UpdateCapabilityVersionRequest request) {
        return CapabilityVersionResponse.from(catalog.updateDraftVersion(
                CapabilityCatalogScope.platform(), definitionId, versionId, parseIfMatch(ifMatch),
                request.name(), request.description(), request.contentHash(), request.descriptor()));
    }

    @RequirePermission("foundation.capabilities.publish")
    @PostMapping("/definitions/versions/publish")
    public ApiResponse<CapabilityVersionResponse> publishVersionEndpoint(
            @Valid @RequestBody PublishVersionCommand command, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(publishVersion(command.definitionId(), command.versionId(), ifMatch));
    }

    public CapabilityVersionResponse publishVersion(
            Long definitionId,
            Long versionId,
            String ifMatch) {
        return CapabilityVersionResponse.from(catalog.publishVersion(
                CapabilityCatalogScope.platform(), definitionId, versionId, parseIfMatch(ifMatch)));
    }

    private CapabilityType parseType(String value) {
        try {
            return CapabilityType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw CapabilityCatalogErrors.exception(
                    HttpStatus.UNPROCESSABLE_ENTITY, CapabilityCatalogErrors.INVALID_TYPE,
                    "不支持的 capability_type: " + value, exception);
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

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record CreateVersionCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @Valid @NotNull CreateCapabilityVersionRequest payload) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record ImportSkillDirectoryCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @Valid @NotNull SkillPackageImportRequest payload) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record ImportSkillZipCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @Valid @NotNull SkillPackageImportRequest payload) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record SetSkillDefaultCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @JsonProperty("version_id") @NotNull Long versionId) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record ValidateVersionCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @JsonProperty("version_id") @NotNull Long versionId) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record UpdateVersionCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @JsonProperty("version_id") @NotNull Long versionId,
            @Valid @NotNull UpdateCapabilityVersionRequest payload) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record PublishVersionCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @JsonProperty("version_id") @NotNull Long versionId) {}
}

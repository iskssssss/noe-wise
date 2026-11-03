package com.skillforge.institution.controller;

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
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.dto.capability.CapabilityDefinitionResponse;
import com.skillforge.institution.dto.capability.CapabilityVersionSummaryResponse;
import com.skillforge.institution.dto.capability.SkillVersionSummaryResponse;
import com.skillforge.institution.dto.capability.CapabilityRequirementOptionResponse;
import com.skillforge.institution.dto.capability.CapabilityDeploymentResponse;
import com.skillforge.institution.dto.capability.CapabilityVersionResponse;
import com.skillforge.institution.dto.capability.CapabilityValidationResponse;
import com.skillforge.institution.dto.capability.CreateCapabilityDefinitionRequest;
import com.skillforge.institution.dto.capability.CreateCapabilityDeploymentRequest;
import com.skillforge.institution.dto.capability.CreateCapabilityVersionRequest;
import com.skillforge.institution.dto.capability.UpdateCapabilityVersionRequest;
import com.skillforge.institution.dto.capability.PlatformToolResponse;
import com.skillforge.institution.service.TenantToolDeploymentProviderValidator;
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

/**
 * 机构租户 Capability Catalog API。
 *
 * <p>平台公共已发布制品可被租户读取；租户 Draft 与 Version 只能由当前租户读取。
 * Definition/Version 写入和 Deployment 启停全部经统一生命周期服务。
 */
@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilityCatalogController {

    private final CapabilityCatalogService catalog;
    private final TenantToolDeploymentProviderValidator providerValidator;
    private final SkillPackageCatalogService skillPackages;
    private final PlatformToolCatalog platformTools;

    public CapabilityCatalogController(
            CapabilityCatalogService catalog,
            TenantToolDeploymentProviderValidator providerValidator,
            SkillPackageCatalogService skillPackages, PlatformToolCatalog platformTools) {
        this.catalog = catalog;
        this.providerValidator = providerValidator;
        this.skillPackages = skillPackages;
        this.platformTools = platformTools;
    }

    @RequirePermission("foundation.capabilities.create")
    @PostMapping("/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CapabilityDefinitionResponse> createDefinition(
            @Valid @RequestBody CreateCapabilityDefinitionRequest request) {
        return ApiResponses.wrap(CapabilityDefinitionResponse.from(catalog.createDefinition(
                tenantScope(), parseType(request.capabilityType()), request.capabilityKey(),
                request.name(), request.description())));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/definitions")
    public ApiResponse<List<CapabilityDefinitionResponse>> listDefinitions(
            @RequestParam(name = "capability_type", required = false) String capabilityType,
            @RequestParam(required = false) String status) {
        CapabilityType type = capabilityType == null || capabilityType.isBlank()
                ? null : parseType(capabilityType);
        return ApiResponses.wrap(catalog.listDefinitions(tenantScope(), type, status).stream()
                .map(CapabilityDefinitionResponse::from).toList());
    }

    /** 依赖选择仅读取当前可选发布版本的精确身份与名称。 */
    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/requirements/options")
    public ApiResponse<List<CapabilityRequirementOptionResponse>> requirementOptions(
            @RequestParam("capability_type") String capabilityType) {
        return ApiResponses.wrap(catalog.listRequirementOptions(tenantScope(),
                parseType(capabilityType)).stream()
                .map(CapabilityRequirementOptionResponse::from).toList());
    }

    /** 技能历史列表只读版本身份、版本号和真实状态。 */
    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/skills/versions/summaries")
    public ApiResponse<List<SkillVersionSummaryResponse>> skillVersionSummaries(
            @RequestParam("definition_id") Long definitionId) {
        return ApiResponses.wrap(catalog.listSkillVersionSummaries(tenantScope(), definitionId).stream()
                .map(SkillVersionSummaryResponse::from).toList());
    }

    /** 能力版本目录只读元数据摘要；正文继续由精确版本详情按需返回。 */
    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/versions/summaries")
    public ApiResponse<List<CapabilityVersionSummaryResponse>> versionSummaries(
            @RequestParam("definition_id") Long definitionId) {
        return ApiResponses.wrap(catalog.listVersionSummaries(tenantScope(), definitionId).stream()
                .map(CapabilityVersionSummaryResponse::from).toList());
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/definitions/detail")
    public ApiResponse<CapabilityDefinitionResponse> getDefinition(@RequestParam("definitionId") Long definitionId) {
        return ApiResponses.wrap(CapabilityDefinitionResponse.from(catalog.getDefinition(tenantScope(), definitionId)));
    }

    @RequirePermission("foundation.capabilities.create")
    @PostMapping("/definitions/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CapabilityVersionResponse> createVersionEndpoint(
            @Valid @RequestBody CreateVersionCommand command) {
        return ApiResponses.wrap(createVersion(command.definitionId(), command.payload()));
    }

    public CapabilityVersionResponse createVersion(Long definitionId, @Valid CreateCapabilityVersionRequest request) {
        return CapabilityVersionResponse.from(catalog.createDraftVersion(
                tenantScope(), definitionId, request.versionKey(), request.name(),
                request.description(), request.contentHash(), request.descriptor()));
    }

    /** 以 base64 文件映射导入标准 Skill 目录；服务端计算内容哈希。 */
    @RequirePermission("foundation.capabilities.create")
    @PostMapping("/definitions/skill-versions/import-directory")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CapabilityVersionResponse> importSkillDirectoryEndpoint(
            @Valid @RequestBody ImportSkillDirectoryCommand command) {
        return ApiResponses.wrap(importSkillDirectory(command.definitionId(), command.payload()));
    }

    public CapabilityVersionResponse importSkillDirectory(Long definitionId, @Valid SkillPackageImportRequest request) {
        return CapabilityVersionResponse.from(skillPackages.importDirectory(
                tenantScope(), definitionId, request));
    }

    /** 导入标准 Skill ZIP；ZIP 内文件永不在摄取阶段执行。 */
    @RequirePermission("foundation.capabilities.create")
    @PostMapping(value = "/definitions/skill-versions/import-zip",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CapabilityVersionResponse> importSkillZip(
            @RequestPart(value = "definition_id", required = true) Long definitionId,
            @Valid @RequestPart("metadata") SkillPackageImportRequest request,
            @RequestPart("archive") MultipartFile archive) throws java.io.IOException {
        return ApiResponses.wrap(CapabilityVersionResponse.from(skillPackages.importZip(
                tenantScope(), definitionId, request, archive.getBytes())));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/skill-versions/files")
    public ApiResponse<List<SkillPackageCatalogService.SkillFileView>> listSkillFiles(
            @RequestParam("versionId") Long versionId) {
        return ApiResponses.wrap(skillPackages.listFiles(tenantScope(), versionId));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/skill-versions/file")
    public ResponseEntity<byte[]> getSkillFile(
            @RequestParam("versionId") Long versionId, @RequestParam String path) {
        SkillPackage.FileEntry file = skillPackages.getFile(tenantScope(), versionId, path);
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
                .body(skillPackages.download(tenantScope(), versionId));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/definitions/versions")
    public ApiResponse<List<CapabilityVersionResponse>> listVersions(
            @RequestParam("definitionId") Long definitionId,
            @RequestParam(required = false) String status) {
        return ApiResponses.wrap(catalog.listVersions(tenantScope(), definitionId, status).stream()
                .map(CapabilityVersionResponse::from).toList());
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/versions/detail")
    public ApiResponse<CapabilityVersionResponse> getVersion(@RequestParam("versionId") Long versionId) {
        return ApiResponses.wrap(CapabilityVersionResponse.from(catalog.getVersion(tenantScope(), versionId)));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/definitions/current-publication")
    public ApiResponse<CapabilityVersionResponse> currentPublication(@RequestParam("definitionId") Long definitionId) {
        return ApiResponses.wrap(CapabilityVersionResponse.from(catalog.getCurrentPublishedVersion(tenantScope(), definitionId)));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/definitions/skill-default")
    public ApiResponse<CapabilityVersionResponse> getSkillDefault(@RequestParam("definitionId") Long definitionId) {
        return ApiResponses.wrap(CapabilityVersionResponse.from(catalog.getDefaultSkillVersion(
                tenantScope(), definitionId)));
    }

    @RequirePermission("foundation.capabilities.run")
    @PostMapping("/definitions/skill-default/execute")
    public ApiResponse<CapabilityDefinitionResponse> setSkillDefaultEndpoint(
            @Valid @RequestBody SetSkillDefaultCommand command,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(setSkillDefault(command.definitionId(), command.versionId(), ifMatch));
    }

    public CapabilityDefinitionResponse setSkillDefault(Long definitionId, Long versionId, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return CapabilityDefinitionResponse.from(catalog.setDefaultSkillVersion(
                tenantScope(), definitionId, versionId, parseIfMatch(ifMatch)));
    }

    /**
     * 返回平台默认工具的精确、只读 Provider 身份投影。
     *
     * <p>该接口不查询或修改租户 Connector，也不从显示名或 capabilityKey 推断身份；
     * 结果直接来自唯一事实源 {@link PlatformToolCatalog}。
     */
    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/platform-tools")
    public ApiResponse<List<PlatformToolResponse>> listPlatformTools() {
        tenantScope();
        return ApiResponses.wrap(platformTools.entries().stream()
                .map(PlatformToolResponse::from)
                .toList());
    }

    @RequirePermission("foundation.capabilities.update")
    @PostMapping("/definitions/versions/validate")
    public ApiResponse<CapabilityValidationResponse> validateVersionEndpoint(
            @Valid @RequestBody ValidateVersionCommand command) {
        return ApiResponses.wrap(validateVersion(command.definitionId(), command.versionId()));
    }

    public CapabilityValidationResponse validateVersion(Long definitionId, Long versionId) {
        return CapabilityValidationResponse.from(catalog.validateVersion(
                tenantScope(), definitionId, versionId));
    }

    @RequirePermission("foundation.capabilities.update")
    @PutMapping("/definitions/versions/update")
    public ApiResponse<CapabilityVersionResponse> updateVersionEndpoint(
            @Valid @RequestBody UpdateVersionCommand command,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(updateVersion(command.definitionId(), command.versionId(), ifMatch, command.payload()));
    }

    public CapabilityVersionResponse updateVersion(Long definitionId, Long versionId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid UpdateCapabilityVersionRequest request) {
        return CapabilityVersionResponse.from(catalog.updateDraftVersion(
                tenantScope(), definitionId, versionId, parseIfMatch(ifMatch), request.name(),
                request.description(), request.contentHash(), request.descriptor()));
    }

    @RequirePermission("foundation.capabilities.publish")
    @PostMapping("/definitions/versions/publish")
    public ApiResponse<CapabilityVersionResponse> publishVersionEndpoint(
            @Valid @RequestBody PublishVersionCommand command,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(publishVersion(command.definitionId(), command.versionId(), ifMatch));
    }

    public CapabilityVersionResponse publishVersion(Long definitionId, Long versionId, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return CapabilityVersionResponse.from(catalog.publishVersion(
                tenantScope(), definitionId, versionId, parseIfMatch(ifMatch)));
    }

    @RequirePermission("foundation.capabilities.create")
    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CapabilityDeploymentResponse> createDeployment(
            @Valid @RequestBody CreateCapabilityDeploymentRequest request) {
        CapabilityCatalogScope scope = tenantScope();
        ToolDeployment.ProviderType providerType = parseProvider(request.providerType());
        ToolDeployment.Environment environment = parseEnvironment(request.environment());
        providerValidator.validate(scope.tenantId(), providerType, request.providerRef(), environment);
        return ApiResponses.wrap(CapabilityDeploymentResponse.from(catalog.createDeployment(
                scope, request.deploymentKey() == null || request.deploymentKey().isBlank()
                        ? "tool-" + java.util.UUID.randomUUID() : request.deploymentKey(), request.toolVersionId(), providerType,
                request.providerRef(), environment, request.status())));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/deployments")
    public ApiResponse<List<CapabilityDeploymentResponse>> listDeployments(
            @RequestParam(required = false) String status) {
        return ApiResponses.wrap(catalog.listDeployments(tenantScope(), status).stream()
                .map(CapabilityDeploymentResponse::from).toList());
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/deployments/detail")
    public ApiResponse<CapabilityDeploymentResponse> getDeployment(@RequestParam("deploymentKey") String deploymentKey) {
        return ApiResponses.wrap(CapabilityDeploymentResponse.from(catalog.getDeployment(tenantScope(), deploymentKey)));
    }

    @RequirePermission("foundation.capabilities.run")
    @PostMapping("/deployments/start")
    public ApiResponse<CapabilityDeploymentResponse> startDeploymentEndpoint(
            @Valid @RequestBody StartDeploymentCommand command,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(startDeployment(command.deploymentKey(), ifMatch));
    }

    public CapabilityDeploymentResponse startDeployment(String deploymentKey, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return CapabilityDeploymentResponse.from(catalog.setDeploymentActive(
                tenantScope(), deploymentKey, parseIfMatch(ifMatch), true));
    }

    @RequirePermission("foundation.capabilities.cancel")
    @PostMapping("/deployments/stop")
    public ApiResponse<CapabilityDeploymentResponse> stopDeploymentEndpoint(
            @Valid @RequestBody StopDeploymentCommand command,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(stopDeployment(command.deploymentKey(), ifMatch));
    }

    public CapabilityDeploymentResponse stopDeployment(String deploymentKey, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return CapabilityDeploymentResponse.from(catalog.setDeploymentActive(
                tenantScope(), deploymentKey, parseIfMatch(ifMatch), false));
    }

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

    private CapabilityType parseType(String value) {
        try {
            return CapabilityType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw CapabilityCatalogErrors.exception(
                    HttpStatus.UNPROCESSABLE_ENTITY, CapabilityCatalogErrors.INVALID_TYPE,
                    "不支持的 capability_type: " + value, exception);
        }
    }

    private ToolDeployment.ProviderType parseProvider(String value) {
        try {
            return ToolDeployment.ProviderType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw CapabilityCatalogErrors.exception(
                    HttpStatus.UNPROCESSABLE_ENTITY, CapabilityCatalogErrors.DEPLOYMENT_INVALID,
                    "不支持的 provider_type: " + value, exception);
        }
    }

    private ToolDeployment.Environment parseEnvironment(String value) {
        try {
            return ToolDeployment.Environment.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw CapabilityCatalogErrors.exception(
                    HttpStatus.UNPROCESSABLE_ENTITY, CapabilityCatalogErrors.DEPLOYMENT_INVALID,
                    "不支持的 environment: " + value, exception);
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

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record CreateVersionCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @JsonProperty("payload") @NotNull @Valid CreateCapabilityVersionRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record ImportSkillDirectoryCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @JsonProperty("payload") @NotNull @Valid SkillPackageImportRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record SetSkillDefaultCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @JsonProperty("version_id") @NotNull Long versionId) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record ValidateVersionCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @JsonProperty("version_id") @NotNull Long versionId) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record UpdateVersionCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @JsonProperty("version_id") @NotNull Long versionId,
            @JsonProperty("payload") @NotNull @Valid UpdateCapabilityVersionRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record PublishVersionCommand(
            @JsonProperty("definition_id") @NotNull Long definitionId,
            @JsonProperty("version_id") @NotNull Long versionId) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record StartDeploymentCommand(
            @JsonProperty("deployment_key") @NotNull String deploymentKey) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record StopDeploymentCommand(
            @JsonProperty("deployment_key") @NotNull String deploymentKey) {}
}

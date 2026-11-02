package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.capability.contract.ResourceType;
import com.skillforge.core.channel.dto.openplatform.OpenPlatformChannelBindingRequest;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformDeploymentRequest;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformResourceContentRequest;
import com.skillforge.core.channel.dto.openplatform.OpenPlatformChannelBindingResponse;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformDeploymentResponse;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformResourceRevisionResponse;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformResourceCatalogItemResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.channel.ChannelBindingView;
import com.skillforge.core.channel.OpenPlatformChannelBindingService;
import com.skillforge.core.capability.persistence.OpenPlatformDeploymentDO;
import com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO;
import com.skillforge.core.capability.application.OpenPlatformResourceLifecycleService;
import com.skillforge.core.capability.application.ResourceRevisionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;

/** Open Platform 资源正文、Deployment 和 ChannelBinding 生命周期 API。 */
@RestController
@RequestMapping(value = "/api/v1/open-platform", produces = "application/json")
@Tag(name = "Open Platform Resource Control", description = "统一资源目录、版本和部署控制面")
public class OpenPlatformResourceLifecycleController {

    public static final String WRITE_SCOPE = "resources.write";
    public static final String READ_SCOPE = "resources.read";
    public static final String REVISION_HEADER = "X-Resource-Revision";
    public static final String DEPLOYMENT_REVISION_HEADER = "X-Deployment-Revision";
    public static final String BINDING_REVISION_HEADER = "X-Binding-Revision";

    private final OpenPlatformResourceLifecycleService lifecycle;
    private final OpenPlatformChannelBindingService channelBindings;

    public OpenPlatformResourceLifecycleController(OpenPlatformResourceLifecycleService lifecycle,
            OpenPlatformChannelBindingService channelBindings) {
        this.lifecycle = lifecycle;
        this.channelBindings = channelBindings;
    }

    @PostMapping("/resources/revisions")
    public ResponseEntity<ApiResponse<OpenPlatformResourceRevisionResponse>> createDraftEndpoint(
            @Valid @RequestBody CreateDraftCommand command) {
        return ApiResponses.wrap(createDraft(command.resourceType(), command.resourceId(), command.payload()));
    }

    public ResponseEntity<OpenPlatformResourceRevisionResponse> createDraft(String resourceType, String resourceId, OpenPlatformResourceContentRequest request) {
        ServicePrincipalContext.Identity principal = require(WRITE_SCOPE);
        requireContent(request);
        OpenPlatformResourceRevisionDO row = lifecycle.createDraft(principal.tenantId(), parseType(resourceType),
                resourceId, request.content());
        return revisionResponse(row);
    }

    @GetMapping("/resources/revisions/latest")
    @Operation(summary = "读取资源最新 revision")
    public ResponseEntity<ApiResponse<OpenPlatformResourceRevisionResponse>> latest(
            @RequestParam("resourceType") String resourceType, @RequestParam("resourceId") String resourceId) {
        ServicePrincipalContext.Identity principal = require(READ_SCOPE);
        OpenPlatformResourceRevisionDO row = lifecycle.latest(principal.tenantId(), parseType(resourceType), resourceId);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "资源 revision 不存在");
        }
        return ApiResponses.wrap(revisionResponse(row));
    }

    @GetMapping("/resources")
    @Operation(summary = "列出统一资源目录", description = "按租户返回每个资源的最新 revision 摘要，不返回资源正文")
    public ResponseEntity<ApiResponse<List<OpenPlatformResourceCatalogItemResponse>>> catalog(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        ServicePrincipalContext.Identity principal = require(READ_SCOPE);
        ResourceType parsedType = resourceType == null || resourceType.isBlank() ? null : parseType(resourceType);
        if (limit < 1 || limit > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "limit 必须在 1 到 200 之间");
        }
        return ApiResponses.wrap(ResponseEntity.ok(lifecycle.listLatest(principal.tenantId(), parsedType, status, limit).stream()
                .map(row -> new OpenPlatformResourceCatalogItemResponse(row.getResourceType(), row.getResourceId(),
                        row.getRevision(), row.getStatus(), row.getContentHash(), row.getEtag(), row.getCreatedAt(),
                        row.getUpdatedAt(), row.getPublishedAt()))
                .toList()));
    }

    @GetMapping("/resources/revisions/detail")
    @Operation(summary = "读取指定资源 revision")
    public ResponseEntity<ApiResponse<OpenPlatformResourceRevisionResponse>> revision(
            @RequestParam("resourceType") String resourceType, @RequestParam("resourceId") String resourceId, @RequestParam("revision") long revision) {
        ServicePrincipalContext.Identity principal = require(READ_SCOPE);
        OpenPlatformResourceRevisionDO row = lifecycle.revision(
                principal.tenantId(), parseType(resourceType), resourceId, revision);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "资源 revision 不存在");
        }
        return ApiResponses.wrap(revisionResponse(row));
    }

    @PutMapping("/resources/revisions/update")
    public ResponseEntity<ApiResponse<OpenPlatformResourceRevisionResponse>> updateDraftEndpoint(
            @Valid @RequestBody UpdateDraftCommand command,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(updateDraft(command.resourceType(), command.resourceId(), command.revision(), ifMatch, command.payload()));
    }

    public ResponseEntity<OpenPlatformResourceRevisionResponse> updateDraft(String resourceType, String resourceId, long revision, @RequestHeader(value = "If-Match", required = false) String ifMatch, OpenPlatformResourceContentRequest request) {
        ServicePrincipalContext.Identity principal = require(WRITE_SCOPE);
        requireContent(request);
        try {
            return revisionResponse(lifecycle.updateDraft(principal.tenantId(), parseType(resourceType), resourceId,
                    revision, ifMatch, request.content()));
        } catch (ResourceRevisionConflictException conflict) {
            throw conflict(conflict);
        }
    }

    @PostMapping("/resources/revisions/ready")
    public ResponseEntity<ApiResponse<OpenPlatformResourceRevisionResponse>> readyEndpoint(
            @Valid @RequestBody ReadyCommand command,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(ready(command.resourceType(), command.resourceId(), command.revision(), ifMatch));
    }

    public ResponseEntity<OpenPlatformResourceRevisionResponse> ready(String resourceType, String resourceId, long revision, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        ServicePrincipalContext.Identity principal = require(WRITE_SCOPE);
        try {
            return revisionResponse(lifecycle.markReady(principal.tenantId(), parseType(resourceType), resourceId,
                    revision, ifMatch));
        } catch (ResourceRevisionConflictException conflict) {
            throw conflict(conflict);
        }
    }

    @PostMapping("/resources/revisions/publish")
    public ResponseEntity<ApiResponse<OpenPlatformResourceRevisionResponse>> publishEndpoint(
            @Valid @RequestBody PublishCommand command,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(publish(command.resourceType(), command.resourceId(), command.revision(), ifMatch));
    }

    public ResponseEntity<OpenPlatformResourceRevisionResponse> publish(String resourceType, String resourceId, long revision, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        ServicePrincipalContext.Identity principal = require(WRITE_SCOPE);
        try {
            return revisionResponse(lifecycle.publish(principal.tenantId(), parseType(resourceType), resourceId,
                    revision, ifMatch));
        } catch (ResourceRevisionConflictException conflict) {
            throw conflict(conflict);
        }
    }

    @PutMapping("/deployments/update")
    public ResponseEntity<ApiResponse<OpenPlatformDeploymentResponse>> deployEndpoint(
            @Valid @RequestBody DeployCommand command,
            @RequestHeader(value = DEPLOYMENT_REVISION_HEADER, required = false) Long expectedRevision,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(deploy(command.deploymentId(), expectedRevision, ifMatch, command.payload()));
    }

    public ResponseEntity<OpenPlatformDeploymentResponse> deploy(String deploymentId, @RequestHeader(value = DEPLOYMENT_REVISION_HEADER, required = false) Long expectedRevision, @RequestHeader(value = "If-Match", required = false) String ifMatch, OpenPlatformDeploymentRequest request) {
        ServicePrincipalContext.Identity principal = require(WRITE_SCOPE);
        if (request == null || blank(request.resourceId()) || request.resourceRevision() == null
                || request.resourceRevision() <= 0 || blank(request.resourceType()) || blank(request.environment())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Deployment resourceType、resourceId、resourceRevision 和 environment 不能为空");
        }
        try {
            OpenPlatformDeploymentDO row = lifecycle.deploy(principal.tenantId(), deploymentId,
                    parseType(request.resourceType()), request.resourceId(), request.resourceRevision(),
                    request.environment(), expectedRevision, ifMatch);
            return ResponseEntity.ok().eTag(quote(row.getEtag()))
                    .header(DEPLOYMENT_REVISION_HEADER, String.valueOf(row.getDeploymentRevision()))
                    .body(deployment(row));
        } catch (ResourceRevisionConflictException conflict) {
            throw conflict(conflict);
        }
    }

    @PutMapping("/channels/binding")
    public ResponseEntity<ApiResponse<OpenPlatformChannelBindingResponse>> bindChannelEndpoint(
            @Valid @RequestBody BindChannelCommand command,
            @RequestHeader(value = BINDING_REVISION_HEADER, required = false) Long expectedRevision,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(bindChannel(command.channelId(), expectedRevision, ifMatch, command.payload()));
    }

    public ResponseEntity<OpenPlatformChannelBindingResponse> bindChannel(long channelId, @RequestHeader(value = BINDING_REVISION_HEADER, required = false) Long expectedRevision, @RequestHeader(value = "If-Match", required = false) String ifMatch, OpenPlatformChannelBindingRequest request) {
        ServicePrincipalContext.Identity principal = require(WRITE_SCOPE);
        if (request == null || blank(request.deploymentId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "deploymentId 不能为空");
        }
        try {
            ChannelBindingView row = channelBindings.bindChannel(principal.tenantId(), channelId,
                    request.deploymentId(), expectedRevision, ifMatch);
            return ResponseEntity.ok().eTag(quote(row.etag()))
                    .header(BINDING_REVISION_HEADER, String.valueOf(row.bindingRevision()))
                    .body(binding(row));
        } catch (ResourceRevisionConflictException conflict) {
            throw conflict(conflict);
        }
    }

    private ResponseEntity<OpenPlatformResourceRevisionResponse> revisionResponse(OpenPlatformResourceRevisionDO row) {
        return ResponseEntity.ok().eTag(quote(row.getEtag())).header(REVISION_HEADER, String.valueOf(row.getRevision()))
                .body(new OpenPlatformResourceRevisionResponse(row.getResourceType(), row.getResourceId(),
                        row.getRevision(), row.getStatus(), row.getContent(), row.getContentHash(), row.getEtag(),
                        row.getCreatedAt(), row.getUpdatedAt(), row.getPublishedAt()));
    }

    private OpenPlatformDeploymentResponse deployment(OpenPlatformDeploymentDO row) {
        return new OpenPlatformDeploymentResponse(row.getDeploymentId(), row.getResourceType(), row.getResourceId(),
                row.getResourceRevision(), row.getEnvironment(), row.getStatus(), row.getDeploymentRevision(),
                row.getEtag());
    }

    private OpenPlatformChannelBindingResponse binding(ChannelBindingView row) {
        return new OpenPlatformChannelBindingResponse(row.channelId(), row.deploymentId(), row.resourceId(),
                row.resourceRevision(), row.bindingRevision(), row.status(), row.etag());
    }

    private ResourceType parseType(String value) {
        for (ResourceType type : ResourceType.values()) {
            if (type.value().equals(value)) {
                return type;
            }
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "不支持的 resourceType");
    }

    private ServicePrincipalContext.Identity require(String scope) {
        ServicePrincipalContext.Identity principal = ServicePrincipalContext.get();
        if (principal == null || !principal.scopes().contains(scope)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ServicePrincipal 缺少 scope: " + scope);
        }
        return principal;
    }

    private void requireContent(OpenPlatformResourceContentRequest request) {
        if (request == null || request.content() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "resource content 不能为空");
        }
    }

    private ApiException conflict(ResourceRevisionConflictException conflict) {
        return new ApiException(HttpStatus.CONFLICT, "资源 revision 冲突，当前 revision=" + conflict.currentRevision());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String quote(String value) {
        return "\"" + value + "\"";
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    @Schema(name="OpenPlatformResourceCreateDraftCommand")
    public record CreateDraftCommand(
            @Schema(allowableValues={"agent","workflow","skill","persona","policy","tool","connector","channel","knowledge_base"}) @JsonProperty("resource_type") @NotNull String resourceType,
            @Schema(minLength=1) @JsonProperty("resource_id") @NotNull String resourceId,
            @JsonProperty("payload") @NotNull @Valid OpenPlatformResourceContentRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    @Schema(name="OpenPlatformResourceUpdateDraftCommand")
    public record UpdateDraftCommand(
            @Schema(allowableValues={"agent","workflow","skill","persona","policy","tool","connector","channel","knowledge_base"}) @JsonProperty("resource_type") @NotNull String resourceType,
            @Schema(minLength=1) @JsonProperty("resource_id") @NotNull String resourceId,
            @Schema(minimum="1") @JsonProperty("revision") @NotNull Long revision,
            @JsonProperty("payload") @NotNull @Valid OpenPlatformResourceContentRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    @Schema(name="OpenPlatformResourceReadyCommand")
    public record ReadyCommand(
            @Schema(allowableValues={"agent","workflow","skill","persona","policy","tool","connector","channel","knowledge_base"}) @JsonProperty("resource_type") @NotNull String resourceType,
            @Schema(minLength=1) @JsonProperty("resource_id") @NotNull String resourceId,
            @Schema(minimum="1") @JsonProperty("revision") @NotNull Long revision) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    @Schema(name="OpenPlatformResourcePublishCommand")
    public record PublishCommand(
            @Schema(allowableValues={"agent","workflow","skill","persona","policy","tool","connector","channel","knowledge_base"}) @JsonProperty("resource_type") @NotNull String resourceType,
            @Schema(minLength=1) @JsonProperty("resource_id") @NotNull String resourceId,
            @Schema(minimum="1") @JsonProperty("revision") @NotNull Long revision) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    @Schema(name="OpenPlatformResourceDeployCommand")
    public record DeployCommand(
            @Schema(minLength=1) @JsonProperty("deployment_id") @NotNull String deploymentId,
            @JsonProperty("payload") @NotNull @Valid OpenPlatformDeploymentRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    @Schema(name="OpenPlatformResourceBindChannelCommand")
    public record BindChannelCommand(
            @Schema(minimum="1") @JsonProperty("channel_id") @NotNull Long channelId,
            @JsonProperty("payload") @NotNull @Valid OpenPlatformChannelBindingRequest payload) {}
}

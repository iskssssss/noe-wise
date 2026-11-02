package com.skillforge.institution.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.capability.contract.ResourceType;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformResourceMappingRequest;
import com.skillforge.core.capability.dto.openplatform.OpenPlatformResourceMappingResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.capability.persistence.ResourceMappingDO;
import com.skillforge.core.capability.application.ResourceMappingService;
import com.skillforge.core.capability.application.ResourceRevisionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Open Platform externalRef 映射的 revision/ETag CAS API。 */
@RestController
@RequestMapping(value = "/api/v1/open-platform/resources", produces = "application/json")
public class OpenPlatformResourceMappingController {

    public static final String WRITE_SCOPE = "resources.write";
    public static final String READ_SCOPE = "resources.read";
    public static final String REVISION_HEADER = "X-Resource-Revision";

    private final ResourceMappingService mappings;

    public OpenPlatformResourceMappingController(ResourceMappingService mappings) {
        this.mappings = mappings;
    }

    @PutMapping("/update")
    public ResponseEntity<ApiResponse<OpenPlatformResourceMappingResponse>> upsertEndpoint(
            @Valid @RequestBody UpsertCommand command,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = REVISION_HEADER, required = false) Long expectedRevision) {
        return ApiResponses.wrap(upsert(command.resourceType(), command.externalRef(), ifMatch, expectedRevision, command.payload()));
    }

    public ResponseEntity<OpenPlatformResourceMappingResponse> upsert(String resourceType, String externalRef, @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestHeader(value = REVISION_HEADER, required = false) Long expectedRevision, OpenPlatformResourceMappingRequest request) {
        ServicePrincipalContext.Identity principal = require(WRITE_SCOPE);
        if (request == null || blank(request.resourceId()) || blank(request.contentHash()) || blank(externalRef)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "resourceId、contentHash 和 externalRef 不能为空");
        }
        ResourceType type = parseType(resourceType);
        try {
            ResourceMappingDO row = mappings.upsert(principal.tenantId(), principal.integrationId(), type,
                    externalRef, request.resourceId(), request.contentHash(), expectedRevision, ifMatch);
            return ResponseEntity.ok().eTag(quote(row.getEtag())).header(REVISION_HEADER,
                    String.valueOf(row.getRevision())).body(response(row));
        } catch (ResourceRevisionConflictException conflict) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "资源映射 revision 冲突，当前 revision=" + conflict.currentRevision());
        }
    }

    @GetMapping("/detail")
    public ResponseEntity<ApiResponse<OpenPlatformResourceMappingResponse>> get(
            @RequestParam("resourceType") String resourceType, @RequestParam("externalRef") String externalRef) {
        ServicePrincipalContext.Identity principal = require(READ_SCOPE);
        ResourceMappingDO row = mappings.find(principal.tenantId(), principal.integrationId(),
                parseType(resourceType), externalRef);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "资源映射不存在");
        }
        return ApiResponses.wrap(ResponseEntity.ok().eTag(quote(row.getEtag())).header(REVISION_HEADER,
                String.valueOf(row.getRevision())).body(response(row)));
    }

    private ResourceType parseType(String value) {
        if (blank(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "resourceType 不能为空");
        }
        for (ResourceType type : ResourceType.values()) {
            if (type.value().equals(value)) {
                return type;
            }
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "不支持的 resourceType");
    }

    private OpenPlatformResourceMappingResponse response(ResourceMappingDO row) {
        return new OpenPlatformResourceMappingResponse(row.getIntegrationId(), row.getResourceType(),
                row.getExternalRef(), row.getResourceId(), row.getRevision(), row.getEtag(), row.getContentHash());
    }

    private ServicePrincipalContext.Identity require(String scope) {
        ServicePrincipalContext.Identity principal = ServicePrincipalContext.get();
        if (principal == null || !principal.scopes().contains(scope)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ServicePrincipal 缺少 scope: " + scope);
        }
        return principal;
    }

    private String quote(String value) {
        return "\"" + value + "\"";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record UpsertCommand(
            @Schema(allowableValues={"agent","workflow","skill","persona","policy","tool","connector","channel","knowledge_base"}) @JsonProperty("resource_type") @NotNull String resourceType,
            @Schema(minLength=1) @JsonProperty("external_ref") @NotNull String externalRef,
            @JsonProperty("payload") @NotNull @Valid OpenPlatformResourceMappingRequest payload) {}
}

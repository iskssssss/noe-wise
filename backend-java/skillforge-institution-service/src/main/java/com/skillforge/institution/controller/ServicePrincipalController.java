package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.foundation.dto.identity.CreateServicePrincipalRequest;
import com.skillforge.core.foundation.dto.identity.IssuedServicePrincipalResponse;
import com.skillforge.core.foundation.dto.identity.ServicePrincipalResponse;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** B 端管理 Tenant Open Platform 的服务身份和一次性凭证。 */
@RestController
@RequestMapping("/api/v1/service-principals")
public class ServicePrincipalController {

    private final ServicePrincipalService principals;

    public ServicePrincipalController(ServicePrincipalService principals) {
        this.principals = principals;
    }

    @RequirePermission("foundation.service_principals.create")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IssuedServicePrincipalResponse> issue(@Valid @RequestBody CreateServicePrincipalRequest request) {
        return ApiResponses.wrap(issued(principals.issue(TenantIds.requireTenantId(), request.integrationId(),
                Set.copyOf(request.scopes()))));
    }

    @RequirePermission("foundation.service_principals.read")
    @GetMapping
    public ApiResponse<List<ServicePrincipalResponse>> list() {
        return ApiResponses.wrap(principals.list(TenantIds.requireTenantId()).stream().map(this::response).toList());
    }

    @RequirePermission("foundation.service_principals.update")
    @PostMapping("/rotate")
    public ApiResponse<IssuedServicePrincipalResponse> rotateEndpoint(
            @Valid @RequestBody RotateCommand command) {
        return ApiResponses.wrap(rotate(command.id()));
    }

    public IssuedServicePrincipalResponse rotate(long id) {
        return issued(principals.rotate(TenantIds.requireTenantId(), id));
    }

    @RequirePermission("foundation.service_principals.update")
    @PostMapping("/suspend")
    public ApiResponse<Map<String, Object>> suspendEndpoint(
            @Valid @RequestBody SuspendCommand command) {
        return ApiResponses.wrap(suspend(command.id()));
    }

    public Map<String, Object> suspend(long id) {
        return Map.of("id", id, "suspended", principals.suspend(TenantIds.requireTenantId(), id));
    }

    @RequirePermission("foundation.service_principals.update")
    @PostMapping("/revoke")
    public ApiResponse<Map<String, Object>> revokeEndpoint(
            @Valid @RequestBody RevokeCommand command) {
        return ApiResponses.wrap(revoke(command.id()));
    }

    public Map<String, Object> revoke(long id) {
        return Map.of("id", id, "revoked", principals.revoke(TenantIds.requireTenantId(), id));
    }

    private IssuedServicePrincipalResponse issued(ServicePrincipalService.IssuedPrincipal issued) {
        return new IssuedServicePrincipalResponse(response(issued.principal()), issued.apiKey(), issued.signingSecret());
    }

    private ServicePrincipalResponse response(ServicePrincipalDO principal) {
        return new ServicePrincipalResponse(principal.getId(), principal.getPrincipalId(),
                principal.getIntegrationId(), principal.getApiKeyPrefix(),
                principal.getScopes() == null ? List.of() : List.copyOf(principal.getScopes()),
                principal.getStatus(), principal.getCredentialVersion(), principal.getCreatedAt());
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record RotateCommand(
            @JsonProperty("id") @NotNull Long id) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record SuspendCommand(
            @JsonProperty("id") @NotNull Long id) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record RevokeCommand(
            @JsonProperty("id") @NotNull Long id) {}
}

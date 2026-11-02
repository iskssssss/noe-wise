package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import com.skillforge.core.foundation.dto.openplatform.OpenPlatformControlOperationItemRequest;
import com.skillforge.core.foundation.dto.openplatform.OpenPlatformControlOperationRequest;
import com.skillforge.core.foundation.dto.openplatform.OpenPlatformControlOperationResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.ControlOperationService;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Open Platform 非会话 ControlOperation 受理与查询边界。 */
@RestController
@RequestMapping(value = "/api/v1/open-platform/control-operations", produces = "application/json")
public class OpenPlatformControlOperationController {

    public static final String WRITE_SCOPE = "control.operations.write";
    public static final String READ_SCOPE = "control.operations.read";

    private final ControlOperationService operations;

    public OpenPlatformControlOperationController(ControlOperationService operations) {
        this.operations = operations;
    }

    @PostMapping
    public ApiResponse<OpenPlatformControlOperationResponse> accept(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OpenPlatformControlOperationRequest request) {
        ServicePrincipalContext.Identity principal = requirePrincipal(WRITE_SCOPE);
        if (blank(idempotencyKey) || request == null || blank(request.operationId())
                || blank(request.operationType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key、operationId 和 operationType 不能为空");
        }
        List<ControlOperationItem> items = items(request.items());
        String fingerprint = fingerprint(request, items);
        ControlOperationService.Acceptance accepted = operations.accept(
                principal.tenantId(), principal.integrationId(), request.operationId(), request.rootOperationId(),
                request.operationType(), idempotencyKey, fingerprint, items);
        return ApiResponses.wrap(response(accepted.operation(), accepted.created()));
    }

    @GetMapping("/detail")
    public ApiResponse<OpenPlatformControlOperationResponse> get(@RequestParam("operationId") String operationId) {
        ServicePrincipalContext.Identity principal = requirePrincipal(READ_SCOPE);
        ControlOperation operation = operations.find(principal.tenantId(), principal.integrationId(), operationId);
        if (operation == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ControlOperation 不存在");
        }
        return ApiResponses.wrap(response(operation, false));
    }

    private List<ControlOperationItem> items(List<OpenPlatformControlOperationItemRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        Set<String> keys = new HashSet<>();
        return requests.stream().map(item -> {
            if (item == null || blank(item.itemKey()) || blank(item.resourceRef())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "控制操作 itemKey/resourceRef 不能为空");
            }
            if (!keys.add(item.itemKey().trim())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "控制操作 itemKey 不能重复");
            }
            return new ControlOperationItem(item.itemKey(), item.resourceRef(),
                    ControlOperationStatus.ACCEPTED, null, null, false);
        }).toList();
    }

    private String fingerprint(OpenPlatformControlOperationRequest request, List<ControlOperationItem> items) {
        String material = request.operationId() + "|" + nullToEmpty(request.rootOperationId()) + "|"
                + request.operationType() + "|" + items.stream()
                .sorted(Comparator.comparing(ControlOperationItem::itemKey))
                .map(item -> item.itemKey() + "=" + item.resourceRef())
                .reduce((left, right) -> left + ";" + right).orElse("");
        return TokenHash.sha256Hex(material);
    }

    private OpenPlatformControlOperationResponse response(ControlOperation operation, boolean created) {
        return new OpenPlatformControlOperationResponse(operation.operationId(), operation.rootOperationId(),
                operation.integrationId(), operation.operationType(), operation.status().value(),
                operation.idempotencyKey(), created, operation.items(), operation.acceptedAt(), operation.completedAt());
    }

    private ServicePrincipalContext.Identity requirePrincipal(String scope) {
        ServicePrincipalContext.Identity principal = ServicePrincipalContext.get();
        if (principal == null || !principal.scopes().contains(scope)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ServicePrincipal 缺少 scope: " + scope);
        }
        return principal;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return Objects.requireNonNullElse(value, "");
    }
}

package com.skillforge.institution.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.ConnectorDeleteReceiptService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/connectors/instances/delete-with-receipt")
public class ConnectorDeleteReceiptController {
    private final ConnectorDeleteReceiptService service;
    public ConnectorDeleteReceiptController(ConnectorDeleteReceiptService service) { this.service = service; }

    public record Command(@JsonProperty("request_id") @NotNull UUID requestId,
                          @JsonProperty("instance_id") @NotBlank String instanceId) { }
    public record Receipt(@JsonProperty("request_id") UUID requestId,
                          @JsonProperty("instance_id") String instanceId) { }

    @RequirePermission("foundation.connectors.delete")
    @PostMapping
    public ApiResponse<Receipt> delete(@Valid @RequestBody Command command) {
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        String actor = actor();
        try {
            service.delete(tenant, command.requestId().toString(), actor, command.instanceId());
        } catch (ApiException failure) {
            // 请求标识冲突不能证明原请求失败；只有删除业务拒绝才解除待核对状态。
            if (failure.getStatus() != HttpStatus.NOT_FOUND && !"CONNECTOR_REFERENCED".equals(failure.getCode()))
                throw failure;
            throw new ApiException(failure.getStatus(), "CONNECTOR_DELETE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
        return ApiResponses.wrap(new Receipt(command.requestId(), command.instanceId()));
    }

    @RequirePermission("foundation.connectors.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<Receipt>> result(@RequestParam("request_id") UUID requestId) {
        var row = service.find(Long.parseLong(TenantIds.requireTenantId()), requestId.toString(), actor());
        return row == null ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(ApiResponses.wrap(new Receipt(requestId, row.instanceId())));
    }

    private static String actor() {
        String actor = RequestContext.getUserId();
        if (actor == null || actor.isBlank() || ChannelContext.getChannelId() != null || ServicePrincipalContext.get() != null)
            throw new ApiException(HttpStatus.FORBIDDEN, "仅机构成员可核对连接器删除请求");
        return actor;
    }
}

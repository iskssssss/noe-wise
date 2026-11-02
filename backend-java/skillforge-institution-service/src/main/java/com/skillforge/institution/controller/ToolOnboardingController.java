package com.skillforge.institution.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.tool.ToolOnboardingService;
import com.skillforge.institution.tool.ToolOnboardingViews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 工具默认接入：确认后原子登记、发布并建立机构默认服务绑定，不执行或挂载到 Agent。 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolOnboardingController {
    private final ToolOnboardingService service;

    public ToolOnboardingController(ToolOnboardingService service) { this.service = service; }

    @RequirePermission("foundation.capabilities.create")
    @PostMapping("/onboard")
    public ApiResponse<ToolOnboardingViews.Receipt> onboard(@Valid @RequestBody Onboard request) {
        return ApiResponses.wrap(service.onboard(tenant(), request.requestId(), request.connectorId(), request.toolNames(), request.allowAgentCalls()));
    }

    /** 未查到回执可能仍在途，不证明原请求失败。 */
    @RequirePermission("foundation.connectors.read")
    @GetMapping("/onboard/result")
    public ResponseEntity<ApiResponse<ToolOnboardingViews.Receipt>> result(@RequestParam UUID requestId) {
        var receipt = service.result(tenant(), requestId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(receipt));
    }

    /** 查询原选择的当前可用事实；MISSING 不能证明此前未知写未受理，不提供自动重发授权。 */
    @RequirePermission("foundation.connectors.read")
    @GetMapping("/onboarding")
    public ApiResponse<ToolOnboardingViews.Selection> onboarding(
            @RequestParam String connectorId, @RequestParam List<String> toolName) {
        return ApiResponses.wrap(service.onboarding(tenant(), connectorId, toolName));
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/available")
    public ApiResponse<ToolOnboardingViews.Available> available() {
        return ApiResponses.wrap(service.available(tenant()));
    }

    private Long tenant() { return Long.valueOf(TenantIds.requireTenantId()); }

    public record Onboard(
            @JsonProperty("request_id") @NotNull UUID requestId,
            @JsonProperty("connector_id") @NotBlank @Size(max = 64) String connectorId,
            @JsonProperty("tool_names") @NotEmpty @Size(max = 100) List<@NotBlank String> toolNames,
            @JsonProperty("allow_agent_calls") @AssertTrue(message = "必须明确授权 Agent 调用所选外部工具")
            boolean allowAgentCalls) {}
}

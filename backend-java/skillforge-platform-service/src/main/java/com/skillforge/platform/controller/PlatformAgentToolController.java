package com.skillforge.platform.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.agent.systemagent.PlatformAgentResources;
import com.skillforge.core.agent.systemagent.PlatformAgentResources.DeploymentView;
import com.skillforge.core.agent.systemagent.PlatformAgentResources.DeploymentWrite;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 平台管理员维护系统 Agent 的平台工具部署。 */
@RestController
@RequestMapping("/api/v1/admin/system-agent-tools")
public class PlatformAgentToolController {
    public static final String WRITE_REJECTED = "SYSTEM_AGENT_TOOL_WRITE_REJECTED";
    private final PlatformAgentResources resources;

    public PlatformAgentToolController(PlatformAgentResources resources) {
        this.resources = resources;
    }

    @RequirePermission("foundation.system_agent_tools.read")
    @GetMapping
    public ApiResponse<List<DeploymentView>> deployments() {
        return ApiResponses.wrap(resources.deployments());
    }

    @RequirePermission("foundation.system_agent_tools.create")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DeploymentView> create(@Valid @RequestBody DeploymentWrite request, @RequestHeader("Idempotency-Key") String key) {
        String actor = actor();
        try { return ApiResponses.wrap(resources.create(request, actor, key)); }
        catch (ApiException failure) { throw writeRejection(failure); }
    }

    @RequirePermission("foundation.system_agent_tools.update")
    @PutMapping("/update")
    public ApiResponse<DeploymentView> update(@Valid @RequestBody UpdateCommand command, @RequestHeader("Idempotency-Key") String key) {
        String actor = actor();
        try { return ApiResponses.wrap(resources.update(command.id(), command.expectedRevision(), command.payload(), actor, key)); }
        catch (ApiException failure) { throw writeRejection(failure); }
    }

    @RequirePermission("foundation.system_agent_tools.publish")
    @PostMapping("/publish")
    public ApiResponse<DeploymentView> publish(@Valid @RequestBody PublishCommand command, @RequestHeader("Idempotency-Key") String key) {
        String actor = actor();
        try { return ApiResponses.wrap(resources.publish(command.id(), command.expectedRevision(), actor, key)); }
        catch (ApiException failure) { throw writeRejection(failure); }
    }

    @RequirePermission("foundation.system_agent_tools.read")
    @GetMapping("/receipt")
    public ApiResponse<PlatformAgentResources.ReceiptView> receipt(@RequestParam("request_id") String requestId) {
        return ApiResponses.wrap(resources.receipt(actor(), requestId));
    }

    @RequirePermission("foundation.system_agent_tools.read")
    @GetMapping("/history")
    public ApiResponse<List<DeploymentView>> history(@RequestParam("id") String id) {
        return ApiResponses.wrap(resources.history(id));
    }

    public record PublishCommand(@JsonProperty("id") @NotBlank String id,
            @JsonProperty("expected_revision") @Min(1) long expectedRevision) {}

    /** 事务代理已完成回滚的业务拒绝可作为确定结果，技术失败仍保持未知。 */
    private static ApiException writeRejection(ApiException failure) {
        if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) return failure;
        return new ApiException(failure.getStatus(), WRITE_REJECTED, String.valueOf(failure.getDetail()), false);
    }

    private static String actor() {
        try {
            String value = StpUtil.getLoginIdAsString();
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("blank");
            }
            return value;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, WRITE_REJECTED, "平台操作者身份无效", false);
        }
    }

    /** 固定更新端点请求；标识、CAS revision 与完整部署载荷必须一并提交。 */
    public record UpdateCommand(
            @JsonProperty("id") @NotBlank String id,
            @JsonProperty("expected_revision") @Min(1) long expectedRevision,
            @JsonProperty("payload") @Valid @NotNull DeploymentWrite payload) {}
}

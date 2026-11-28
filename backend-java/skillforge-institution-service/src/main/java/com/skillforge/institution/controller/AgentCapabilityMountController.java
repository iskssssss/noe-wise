package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.agent.dto.response.CapabilityMountSetResponse;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.CapabilityMountManagementService;
import com.skillforge.institution.service.AgentMountCreationService;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Agent CapabilityMount 的唯一机构管理入口。 */
@RestController
@RequestMapping("/api/v1/agents/capability-mounts")
public class AgentCapabilityMountController {

    private final CapabilityMountManagementService service;
    private final AgentMountCreationService creation;

    public AgentCapabilityMountController(CapabilityMountManagementService service, AgentMountCreationService creation) {
        this.service = Objects.requireNonNull(service, "service");
        this.creation = Objects.requireNonNull(creation, "creation");
    }

    /** 读取 mount-set generation 与全部类型化 mount。 */
    @RequirePermission("foundation.agents.read")
    @GetMapping
    public ApiResponse<CapabilityMountSetResponse> list(@RequestParam("agentId") String agentId) {
        return ApiResponses.wrap(CapabilityMountSetResponse.from(service.read(tenantId(), parseAgentId(agentId))));
    }

    /** 创建一个类型化 CapabilityMount。 */
    @RequirePermission("foundation.agents.create")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentMountCreationService.Result> createEndpoint(
            @Valid @RequestBody CreateCommand command) {
        try {
            return ApiResponses.wrap(creation.create(command.requestId(), parseAgentId(command.agentId()), command.payload()));
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408
                    || "AGENT_MOUNT_CREATE_IDENTITY_CONFLICT".equals(failure.getCode())) throw failure;
            throw new ApiException(failure.getStatus(), "AGENT_MOUNT_CREATE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
    }

    @RequirePermission("foundation.agents.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<AgentMountCreationService.Result>> creationResult(
            @RequestParam("agent_id") String agentId, @RequestParam("request_id") UUID requestId) {
        var result = creation.result(requestId, parseAgentId(agentId));
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }

    /** 以 mount revision CAS 更新一个类型化 CapabilityMount。 */
    @RequirePermission("foundation.agents.update")
    @PutMapping("/update")
    public ApiResponse<CapabilityMountSetResponse> updateEndpoint(
            @Valid @RequestBody UpdateCommand command,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(update(command.agentId(), command.mountId(), ifMatch, command.payload()));
    }

    public CapabilityMountSetResponse update(String agentId, String mountId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid CapabilityMountRequest request) {
        return CapabilityMountSetResponse.from(service.update(
                tenantId(), parseAgentId(agentId), mountId, parseIfMatch(ifMatch), request));
    }

    /** 以 mount revision CAS 删除一个 CapabilityMount。 */
    @RequirePermission("foundation.agents.delete")
    @DeleteMapping("/delete")
    public ApiResponse<CapabilityMountSetResponse> deleteEndpoint(
            @Valid @RequestBody DeleteCommand command,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponses.wrap(delete(command.agentId(), command.mountId(), ifMatch));
    }

    public CapabilityMountSetResponse delete(String agentId, String mountId, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return CapabilityMountSetResponse.from(service.delete(
                tenantId(), parseAgentId(agentId), mountId, parseIfMatch(ifMatch)));
    }

    private Long tenantId() {
        String value = TenantIds.requireTenantId();
        try {
            long tenant = Long.parseLong(value);
            if (tenant <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return tenant;
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "租户上下文格式无效", exception);
        }
    }

    private Long parseAgentId(String value) {
        try {
            long agentId = Long.parseLong(value);
            if (agentId <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return agentId;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在", exception);
        }
    }

    private long parseIfMatch(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "If-Match revision is required");
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "If-Match revision 格式无效", exception);
        }
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record CreateCommand(
            @JsonProperty("request_id") @NotNull UUID requestId,
            @JsonProperty("agent_id") @NotNull String agentId,
            @JsonProperty("payload") @NotNull @Valid CapabilityMountRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record UpdateCommand(
            @JsonProperty("agent_id") @NotNull String agentId,
            @JsonProperty("mount_id") @NotNull String mountId,
            @JsonProperty("payload") @NotNull @Valid CapabilityMountRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record DeleteCommand(
            @JsonProperty("agent_id") @NotNull String agentId,
            @JsonProperty("mount_id") @NotNull String mountId) {}
}

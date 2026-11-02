package com.skillforge.institution.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.channel.ChannelService;
import com.skillforge.core.channel.dto.request.ChannelAgentBindingRequest;
import com.skillforge.core.channel.dto.request.CreateChannelRequest;
import com.skillforge.core.channel.dto.request.PatchChannelRequest;
import com.skillforge.core.channel.dto.response.*;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.channel.dto.channel.BindResultResponse;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 渠道 HTTP 边界；租户来自认证上下文，管理和凭据规则统一交给渠道应用服务。 */
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {
    private final ChannelService channels;

    public ChannelController(ChannelService channels) { this.channels = channels; }

    @RequirePermission("foundation.channels.read")
    @GetMapping("")
    public ApiResponse<ChannelListResponse> list() {
        return ApiResponses.wrap(channels.list(tenant()));
    }

    @RequirePermission("foundation.channels.create")
    @PostMapping("")
    public ApiResponse<CreateChannelResponse> create(@Valid @RequestBody CreateChannelRequest request) {
        return ApiResponses.wrap(channels.create(tenant(), request));
    }

    @RequirePermission("foundation.channels.read")
    @GetMapping("/detail")
    public ApiResponse<ChannelResponse> get(@RequestParam("channelId") long channelId) {
        return ApiResponses.wrap(channels.get(tenant(), channelId));
    }

    @RequirePermission("foundation.channels.update")
    @PatchMapping("/update")
    public ApiResponse<ChannelResponse> update(@Valid @RequestBody PatchCommand command) {
        return ApiResponses.wrap(channels.update(tenant(), command.channelId(), command.payload()));
    }

    @RequirePermission("foundation.channels.delete")
    @DeleteMapping("/delete")
    public ApiResponse<ChannelDeleteResponse> delete(@Valid @RequestBody ChannelCommand command) {
        return ApiResponses.wrap(channels.disable(tenant(), command.channelId()));
    }

    @RequirePermission("foundation.channels.update")
    @PostMapping("/rotate-token")
    public ApiResponse<ChannelTokenResponse> rotateToken(@Valid @RequestBody ChannelCommand command) {
        return ApiResponses.wrap(channels.rotateToken(tenant(), command.channelId()));
    }

    @RequirePermission("foundation.channels.update")
    @PutMapping("/agents")
    public ApiResponse<BindResultResponse> bindAgents(@Valid @RequestBody BindAgentsCommand command) {
        return ApiResponses.wrap(channels.bindAgents(tenant(), command.channelId(), command.payload()));
    }

    @RequirePermission("foundation.channels.delete")
    @DeleteMapping("/agents/delete")
    public ApiResponse<UnbindAgentResponse> unbindAgent(@Valid @RequestBody UnbindAgentCommand command) {
        return ApiResponses.wrap(channels.unbindAgent(tenant(), command.channelId(), command.agentId()));
    }

    @RequirePermission("foundation.channels.update")
    @PostMapping({"/api-keys", "/api-keys/rotate"})
    public ApiResponse<ApiKeyIssueResponse> issueApiKey(@Valid @RequestBody ChannelCommand command) {
        return ApiResponses.wrap(channels.issueApiKey(tenant(), command.channelId()));
    }

    @RequirePermission("foundation.channels.delete")
    @DeleteMapping("/api-keys")
    public ApiResponse<ApiKeyDeleteResponse> revokeApiKey(@Valid @RequestBody ChannelCommand command) {
        return ApiResponses.wrap(channels.revokeApiKey(tenant(), command.channelId()));
    }

    private static long tenant() { return Long.parseLong(TenantIds.requireTenantId()); }

    public record ChannelCommand(@JsonProperty("channel_id") @NotNull @Positive Long channelId) { }
    public record PatchCommand(@JsonProperty("channel_id") @NotNull @Positive Long channelId,
                               @NotNull @Valid PatchChannelRequest payload) { }
    public record BindAgentsCommand(@JsonProperty("channel_id") @NotNull @Positive Long channelId,
                                   @NotNull @Valid List<ChannelAgentBindingRequest> payload) { }
    public record UnbindAgentCommand(@JsonProperty("channel_id") @NotNull @Positive Long channelId,
                                    @JsonProperty("agent_id") @NotNull @Positive Long agentId) { }
}

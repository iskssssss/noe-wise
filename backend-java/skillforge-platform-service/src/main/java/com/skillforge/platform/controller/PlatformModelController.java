package com.skillforge.platform.controller;

import com.skillforge.core.model.ModelProviderCatalogView;
import com.skillforge.core.model.ModelView;
import com.skillforge.core.model.PlatformModelDirectorySummary;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.platform.models.PlatformModelProbeService;
import com.skillforge.platform.models.PlatformModelProbeService.Discovery;
import com.skillforge.platform.models.PlatformModelProbeService.DiscoveryView;
import com.skillforge.platform.models.PlatformModelProbeService.ProbeResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 平台独占模型配置与显式连接探测；探测结果不回显供应商正文。 */
@RestController
@RequestMapping("/api/v1/admin/models")
public class PlatformModelController {
    private final PlatformModelCatalogService models;
    private final PlatformModelProbeService probes;

    public PlatformModelController(PlatformModelCatalogService models, PlatformModelProbeService probes) {
        this.models = models;
        this.probes = probes;
    }
    @RequirePermission("foundation.models.read")
    @GetMapping public ApiResponse<List<ModelView>> list() { return ApiResponses.wrap(models.list()); }
    @RequirePermission("foundation.models.read")
    @GetMapping("/summaries")
    public ApiResponse<List<PlatformModelDirectorySummary>> summaries() { return ApiResponses.wrap(models.summaries()); }
    /** 详情页独立加载安全投影，不暴露凭据。 */
    @RequirePermission("foundation.models.read")
    @GetMapping("/detail")
    public ApiResponse<ModelView> detail(@RequestParam("id") String id) { return ApiResponses.wrap(models.detail(id)); }
    @RequirePermission("foundation.models.create")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ModelView> create(@RequestBody ModelWrite request) { return ApiResponses.wrap(models.create(request, StpUtil.getLoginIdAsString())); }
    @RequirePermission("foundation.models.update")
    @PutMapping("/update")
    public ApiResponse<ModelView> updateEndpoint(@Valid @RequestBody UpdateCommand command) {
        return ApiResponses.wrap(models.update(command.id(), command.payload(), StpUtil.getLoginIdAsString()));
    }
    @RequirePermission("foundation.models.read")
    @GetMapping("/history") public ApiResponse<List<ModelView>> history(@RequestParam("id") String id) { return ApiResponses.wrap(models.history(id)); }
    @RequirePermission("foundation.models.publish")
    @PostMapping("/publish")
    public ApiResponse<ModelView> publish(@Valid @RequestBody PublishCommand command) {
        return ApiResponses.wrap(models.publish(command.id(), command.expectedRevision(), StpUtil.getLoginIdAsString()));
    }
    @RequirePermission("foundation.models.update")
    @PostMapping("/rollback")
    public ApiResponse<ModelView> rollbackEndpoint(@Valid @RequestBody RollbackCommand command) {
        var request = command.payload();
        return ApiResponses.wrap(models.rollback(command.id(), request.revision(), request.expectedRevision(), StpUtil.getLoginIdAsString()));
    }
    public record Rollback(long revision, long expectedRevision) { }
    public record PublishCommand(@JsonProperty("id") @NotNull String id,
            @JsonProperty("expected_revision") @jakarta.validation.constraints.Min(1) long expectedRevision) { }
    @RequirePermission("foundation.models.read")
    @GetMapping("/providers") public ApiResponse<ModelProviderCatalogView> providers() { return ApiResponses.wrap(probes.providers()); }

    @RequirePermission("foundation.models.read")
    @PostMapping("/reasoning-options")
    public ApiResponse<PlatformModelProbeService.ReasoningOptionsView> reasoningOptions(
            @RequestBody PlatformModelProbeService.ReasoningSelection request) {
        return ApiResponses.wrap(probes.reasoningOptions(request));
    }

    @RequirePermission("foundation.models.create")
    @PostMapping("/discover")
    public ApiResponse<DiscoveryView> discover(@RequestBody Discovery request) {
        return ApiResponses.wrap(probes.discover(request));
    }

    @RequirePermission("foundation.models.run")
    @PostMapping("/test")
    public ApiResponse<ProbeResult> testEndpoint(@Valid @RequestBody TestCommand command) {
        return ApiResponses.wrap(probes.test(command.id(), command.payload()));
    }

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record UpdateCommand(
            @JsonProperty("id") @NotNull String id,
            @Valid @NotNull ModelWrite payload) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record RollbackCommand(
            @JsonProperty("id") @NotNull String id,
            @Valid @NotNull Rollback payload) {}

    /** payload 缺省时测试已保存草稿；存在时只测试当前输入，id 仅用于复用原连接凭据。 */
    public record TestCommand(
            @JsonProperty("id") String id,
            @Valid ModelWrite payload) {}
}

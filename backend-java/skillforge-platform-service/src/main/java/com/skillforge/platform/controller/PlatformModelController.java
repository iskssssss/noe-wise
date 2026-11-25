package com.skillforge.platform.controller;

import com.skillforge.core.model.ModelProviderCatalogView;
import com.skillforge.core.model.ModelView;
import com.skillforge.core.model.PlatformModelDirectorySummary;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelKind;

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
import java.util.Arrays;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 平台独占模型配置与显式连接探测；探测结果不回显供应商正文。 */
@RestController
@RequestMapping("/api/v1/admin/models")
public class PlatformModelController {
    private final PlatformModelCatalogService models;
    private final PlatformModelProbeService probes;
    private static final ModelListOptions LIST_OPTIONS = new ModelListOptions(
            Arrays.stream(ModelKind.values()).map(kind -> new FilterOption(kind.name(), switch (kind) {
                case LLM -> "对话模型（LLM）";
                case EMBEDDING -> "向量模型（Embedding）";
                case RERANK -> "重排模型（Rerank）";
                case IMAGE_GENERATION -> "图像生成与编辑";
                case VIDEO_GENERATION -> "视频生成";
                case SPEECH_RECOGNITION -> "语音识别";
                case SPEECH_SYNTHESIS -> "语音合成";
            })).toList(),
            List.of(new FilterOption("true", "启用"), new FilterOption("false", "停用")),
            List.of(new FilterOption("pending", "草稿待发布"), new FilterOption("published", "与发布一致")));

    public PlatformModelController(PlatformModelCatalogService models, PlatformModelProbeService probes) {
        this.models = models;
        this.probes = probes;
    }
    @RequirePermission("foundation.models.read")
    @GetMapping public ApiResponse<List<ModelView>> list() { return ApiResponses.wrap(models.list()); }
    @RequirePermission("foundation.models.read")
    @GetMapping("/summaries")
    public ApiResponse<List<PlatformModelDirectorySummary>> summaries(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String kind,
            @RequestParam(defaultValue = "") String enabled,
            @RequestParam(defaultValue = "") String publication) {
        validateFilter(kind, LIST_OPTIONS.kinds(), "模型类型");
        validateFilter(enabled, LIST_OPTIONS.statuses(), "模型状态");
        validateFilter(publication, LIST_OPTIONS.publications(), "发布进度");
        String term = search.trim().toLowerCase(Locale.ROOT);
        return ApiResponses.wrap(models.summaries().stream().filter(model ->
                (kind.isEmpty() || kind.equals(model.kind()))
                && (enabled.isEmpty() || enabled.equals(Boolean.toString(model.enabled())))
                && (publication.isEmpty() || (publication.equals("pending") == model.hasUnpublishedChanges()))
                && (model.name() + " " + model.modelId() + " " + model.providerId()).toLowerCase(Locale.ROOT).contains(term))
                .toList());
    }

    @RequirePermission("foundation.models.read")
    @GetMapping("/options")
    public ApiResponse<ModelListOptions> options() { return ApiResponses.wrap(LIST_OPTIONS); }

    private static void validateFilter(String value, List<FilterOption> options, String label) {
        if (!value.isEmpty() && options.stream().noneMatch(option -> option.value().equals(value))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "model_filter_invalid",
                    label + "无效，请重新选择或重置后查询", false);
        }
    }

    public record FilterOption(String value, String label) {}
    public record ModelListOptions(List<FilterOption> kinds, List<FilterOption> statuses,
            List<FilterOption> publications) {}
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

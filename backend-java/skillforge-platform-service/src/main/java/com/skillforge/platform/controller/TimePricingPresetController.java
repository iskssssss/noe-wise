package com.skillforge.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.model.TimePricingPresetService;
import com.skillforge.core.model.TimePricingPresetService.View;
import com.skillforge.core.model.TimePricingPresetService.Write;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 平台价格规则预设；每次模型采用后自行发布，不实时继承预设后续修改。 */
@RestController
@RequestMapping("/api/v1/admin/models/time-pricing-presets")
public class TimePricingPresetController {
    private final TimePricingPresetService presets;
    public TimePricingPresetController(TimePricingPresetService presets) { this.presets = presets; }

    @RequirePermission("foundation.models.read")
    @GetMapping public ApiResponse<List<View>> list() { return ApiResponses.wrap(presets.list()); }
    @RequirePermission("foundation.models.read")
    @GetMapping("/detail") public ApiResponse<View> detail(@RequestParam("id") String id) { return ApiResponses.wrap(presets.detail(id)); }
    @RequirePermission("foundation.models.read")
    @GetMapping("/history") public ApiResponse<List<View>> history(@RequestParam("id") String id) { return ApiResponses.wrap(presets.history(id)); }
    @RequirePermission("foundation.models.create")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<View> create(@RequestBody Write command) { return ApiResponses.wrap(presets.create(command, StpUtil.getLoginIdAsString())); }
    @RequirePermission("foundation.models.update")
    @PutMapping("/update") public ApiResponse<View> update(@Valid @RequestBody Update command) { return ApiResponses.wrap(presets.update(command.id(), command.payload(), StpUtil.getLoginIdAsString())); }
    @RequirePermission("foundation.models.publish")
    @PostMapping("/publish") public ApiResponse<View> publish(@Valid @RequestBody Publish command) { return ApiResponses.wrap(presets.publish(command.id(), command.expectedRevision(), StpUtil.getLoginIdAsString())); }

    public record Update(@NotNull String id, @NotNull Write payload) { }
    public record Publish(@NotNull String id, @JsonProperty("expected_revision") @Min(1) long expectedRevision) { }
}

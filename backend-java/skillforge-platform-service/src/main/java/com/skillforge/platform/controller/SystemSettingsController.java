package com.skillforge.platform.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.fasterxml.jackson.annotation.JsonProperty;
import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.GroupView;
import com.skillforge.core.foundation.settings.OperationalSettingsService.VersionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台管理员运营配置版本管理接口。 */
@RestController
@RequestMapping("/api/v1/admin/system-settings")
public class SystemSettingsController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SystemSettingsController.class);
    private final OperationalSettingsService settings;

    public SystemSettingsController(OperationalSettingsService settings) {
        this.settings = settings;
    }

    @RequirePermission("foundation.runtime_settings.read")
    @GetMapping
    public ApiResponse<List<GroupView>> list() {
        return ApiResponses.wrap(settings.list());
    }

    /** 代码管理的协议身份只读展示，变更运营字符串不能升级技术实现。 */
    @RequirePermission("foundation.runtime_settings.read")
    @GetMapping("/context-policy")
    public ApiResponse<ContextPolicyView> contextPolicy() {
        LOG.debug("读取上下文代码策略版本");
        var version = com.skillforge.runtime.session.spi.SessionContextPolicy.defaults().version();
        LOG.debug("读取上下文代码策略版本完成 version={}", version);
        return ApiResponses.wrap(new ContextPolicyView(version));
    }

    public record ContextPolicyView(String version) { }

    @RequirePermission("foundation.runtime_settings.update")
    @PutMapping("/update")
    public ApiResponse<GroupView> updateEndpoint(@Valid @RequestBody UpdateCommand command) {
        var request = command.payload();
        return ApiResponses.wrap(settings.update(command.group(), request.expectedRevision(), request.values(), actorId()));
    }

    @RequirePermission("foundation.runtime_settings.read")
    @GetMapping("/history")
    public ApiResponse<List<VersionView>> history(@RequestParam("group") String group) {
        return ApiResponses.wrap(settings.history(group));
    }

    @RequirePermission("foundation.runtime_settings.update")
    @PostMapping("/rollback")
    public ApiResponse<GroupView> rollbackEndpoint(@Valid @RequestBody RollbackCommand command) {
        var request = command.payload();
        return ApiResponses.wrap(settings.rollback(command.group(), request.expectedRevision(), request.targetRevision(), actorId()));
    }

    private static long actorId() {
        try {
            long actorId = Long.parseLong(StpUtil.getLoginIdAsString());
            if (actorId <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return actorId;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "平台操作者身份无效", exception);
        }
    }

    public record UpdateRequest(
            @Min(value = 1, message = "expected_revision 必须是正整数") long expectedRevision,
            @NotNull(message = "values 必须是对象") Map<String, Object> values) {
    }

    public record RollbackRequest(
            @Min(value = 1, message = "expected_revision 必须是正整数") long expectedRevision,
            @Min(value = 1, message = "target_revision 必须是正整数") long targetRevision) {
    }

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record UpdateCommand(
            @JsonProperty("group") @NotNull String group,
            @Valid @NotNull UpdateRequest payload) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record RollbackCommand(
            @JsonProperty("group") @NotNull String group,
            @Valid @NotNull RollbackRequest payload) {}
}

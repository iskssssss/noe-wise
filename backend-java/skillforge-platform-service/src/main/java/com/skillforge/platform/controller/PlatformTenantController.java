package com.skillforge.platform.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.platform.dto.tenant.CreateTenantMemberRequest;
import com.skillforge.platform.dto.tenant.CreateTenantRequest;
import com.skillforge.platform.dto.tenant.ResetPasswordRequest;
import com.skillforge.platform.dto.tenant.TenantDetailResponse;
import com.skillforge.platform.dto.tenant.TenantListResponse;
import com.skillforge.platform.dto.tenant.TenantMemberItemResponse;
import com.skillforge.platform.dto.tenant.TenantMemberQuery;
import com.skillforge.platform.dto.tenant.TenantQuery;
import com.skillforge.platform.dto.tenant.TenantMemberListResponse;
import com.skillforge.platform.dto.tenant.UpdateTenantMemberRequest;
import com.skillforge.platform.dto.tenant.UpdateTenantRequest;
import com.skillforge.platform.service.PlatformTenantAccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台运营后台机构账号管理接口控制器。
 *
 * <p>提供机构开通、机构停用/启用与机构成员全生命周期管理，
 * 仅允许 {@code platform_admin} 访问。
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class PlatformTenantController {

    /** 机构账号管理服务。 */
    private final PlatformTenantAccountService tenantAccountService;

    /**
     * 构造平台机构账号管理控制器。
     *
     * @param tenantAccountService 机构账号管理服务
     */
    public PlatformTenantController(PlatformTenantAccountService tenantAccountService) {
        this.tenantAccountService = tenantAccountService;
    }

    /**
     * 创建机构并初始化管理员。
     *
     * @param request 开通机构请求
     * @return 机构详情
     */
    @RequirePermission("foundation.tenants.create")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TenantDetailResponse> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        return ApiResponses.wrap(tenantAccountService.createTenant(request));
    }

    /**
     * 分页查询机构列表。
     *
     * @param page   页码，默认 1
     * @param limit  每页条数，默认 20，最大 200
     * @param status 状态过滤，可空
     * @param q      名称或 slug 关键字，可空
     * @return 机构分页列表
     */
    @RequirePermission("foundation.tenants.read")
    @GetMapping
    public ApiResponse<TenantListResponse> listTenants(TenantQuery query) {
        return ApiResponses.wrap(tenantAccountService.listTenants(query));
    }

    /** 查询当前启用的套餐目录。 */
    @RequirePermission("foundation.tenants.read")
    @GetMapping("/plans")
    public ApiResponse<java.util.List<com.skillforge.platform.dto.tenant.TenantPlanResponse>> plans() {
        return ApiResponses.wrap(tenantAccountService.listPlans());
    }

    /**
     * 查询机构详情。
     *
     * @param tenantId 机构 ID
     * @return 机构详情
     */
    @RequirePermission("foundation.tenants.read")
    @GetMapping("/detail")
    public ApiResponse<TenantDetailResponse> getTenant(@RequestParam("tenantId") Long tenantId) {
        return ApiResponses.wrap(tenantAccountService.getTenant(tenantId));
    }

    /** 未知创建结果的只读查证；不存在时返回 204。 */
    @RequirePermission("foundation.tenants.read")
    @GetMapping("/find-by-slug")
    public org.springframework.http.ResponseEntity<ApiResponse<com.skillforge.platform.dto.tenant.TenantItemResponse>> findBySlug(
            @RequestParam("slug") String slug) {
        var result = tenantAccountService.findBySlug(slug);
        return ApiResponses.wrap(result == null ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.ok(result));
    }

    /**
     * 更新机构信息（名称、套餐、状态、配额与自定义 LLM 配置等）。
     *
     * @param tenantId 机构 ID
     * @param request  更新请求，空值字段不修改
     * @return 更新后的机构详情
     */
    @RequirePermission("foundation.tenants.update")
    @PatchMapping("/update")
    public ApiResponse<TenantDetailResponse> updateTenantEndpoint(@Valid @RequestBody UpdateTenantCommand command) {
        return ApiResponses.wrap(updateTenant(command.tenantId(), command.payload()));
    }

    public TenantDetailResponse updateTenant(
            Long tenantId,
            UpdateTenantRequest request) {
        return tenantAccountService.updateTenant(tenantId, request);
    }

    /**
     * 停用机构。
     *
     * @param tenantId 机构 ID
     * @return 更新后的机构详情
     */
    @RequirePermission("foundation.tenants.update")
    @PostMapping("/suspend")
    public ApiResponse<TenantDetailResponse> suspendEndpoint(@Valid @RequestBody SuspendCommand command) {
        return ApiResponses.wrap(suspend(command.tenantId()));
    }

    public TenantDetailResponse suspend(Long tenantId) {
        return tenantAccountService.setTenantStatus(tenantId, "suspended");
    }

    /**
     * 启用机构。
     *
     * @param tenantId 机构 ID
     * @return 更新后的机构详情
     */
    @RequirePermission("foundation.tenants.update")
    @PostMapping("/activate")
    public ApiResponse<TenantDetailResponse> activateEndpoint(@Valid @RequestBody ActivateCommand command) {
        return ApiResponses.wrap(activate(command.tenantId()));
    }

    public TenantDetailResponse activate(Long tenantId) {
        return tenantAccountService.setTenantStatus(tenantId, "active");
    }

    /**
     * 分页查询机构成员。
     *
     * @param tenantId 机构 ID
     * @param page     页码，默认 1
     * @param limit    每页条数，默认 20，最大 200
     * @param role     角色过滤，可空
     * @param status   状态过滤，可空
     * @return 成员分页列表
     */
    @RequirePermission("foundation.tenants.read")
    @GetMapping("/members")
    public ApiResponse<TenantMemberListResponse> listMembers(
            @RequestParam("tenantId") Long tenantId,
            TenantMemberQuery query) {
        return ApiResponses.wrap(tenantAccountService.listMembers(tenantId, query));
    }

    /**
     * 新增机构成员。
     *
     * @param tenantId 机构 ID
     * @param request  新增成员请求
     * @return 新成员
     */
    @RequirePermission("foundation.tenants.create")
    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TenantMemberItemResponse> createMemberEndpoint(@Valid @RequestBody CreateMemberCommand command) {
        return ApiResponses.wrap(createMember(command.tenantId(), command.payload()));
    }

    public TenantMemberItemResponse createMember(
            Long tenantId,
            CreateTenantMemberRequest request) {
        return tenantAccountService.createMember(tenantId, request);
    }

    /**
     * 更新机构成员。
     *
     * @param tenantId 机构 ID
     * @param memberId 成员 ID
     * @param request  更新请求
     * @return 更新后的成员
     */
    @RequirePermission("foundation.tenants.update")
    @PatchMapping("/members/update")
    public ApiResponse<TenantMemberItemResponse> updateMemberEndpoint(@Valid @RequestBody UpdateMemberCommand command) {
        return ApiResponses.wrap(updateMember(command.tenantId(), command.memberId(), command.payload()));
    }

    public TenantMemberItemResponse updateMember(
            Long tenantId,
            Long memberId,
            UpdateTenantMemberRequest request) {
        return tenantAccountService.updateMember(tenantId, memberId, request);
    }

    /**
     * 重置机构成员密码。
     *
     * @param tenantId 机构 ID
     * @param memberId 成员 ID
     * @param request  重置密码请求
     * @return 更新后的成员
     */
    @RequirePermission("foundation.tenants.update")
    @PostMapping("/members/reset-password")
    public ApiResponse<TenantMemberItemResponse> resetPasswordEndpoint(@Valid @RequestBody ResetPasswordCommand command) {
        return ApiResponses.wrap(resetPassword(command.tenantId(), command.memberId(), command.payload()));
    }

    public TenantMemberItemResponse resetPassword(
            Long tenantId,
            Long memberId,
            ResetPasswordRequest request) {
        return tenantAccountService.resetPassword(tenantId, memberId, request);
    }

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record UpdateTenantCommand(
            @JsonProperty("tenant_id") @NotNull Long tenantId,
            @Valid @NotNull UpdateTenantRequest payload) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record SuspendCommand(
            @JsonProperty("tenant_id") @NotNull Long tenantId) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record ActivateCommand(
            @JsonProperty("tenant_id") @NotNull Long tenantId) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record CreateMemberCommand(
            @JsonProperty("tenant_id") @NotNull Long tenantId,
            @Valid @NotNull CreateTenantMemberRequest payload) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record UpdateMemberCommand(
            @JsonProperty("tenant_id") @NotNull Long tenantId,
            @JsonProperty("member_id") @NotNull Long memberId,
            @Valid @NotNull UpdateTenantMemberRequest payload) {}

    /** 固定写入端点的请求体，标识与业务参数一并校验。 */
    public record ResetPasswordCommand(
            @JsonProperty("tenant_id") @NotNull Long tenantId,
            @JsonProperty("member_id") @NotNull Long memberId,
            @Valid @NotNull ResetPasswordRequest payload) {}
}

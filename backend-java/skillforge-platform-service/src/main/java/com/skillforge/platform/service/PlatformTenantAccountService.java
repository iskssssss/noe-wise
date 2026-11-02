package com.skillforge.platform.service;
import com.skillforge.core.shared.config.SnowflakeIds;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.skillforge.platform.dto.tenant.TenantPlanResponse;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.constant.AuditConstants;
import com.skillforge.core.foundation.constant.AuthConstants;
import com.skillforge.core.billing.constant.BillingConstants;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.foundation.constant.RoleConstants;
import com.skillforge.platform.dto.tenant.CreateTenantMemberRequest;
import com.skillforge.platform.dto.tenant.TenantMemberQuery;
import com.skillforge.platform.dto.tenant.TenantQuery;
import com.skillforge.platform.dto.tenant.CreateTenantRequest;
import com.skillforge.platform.dto.tenant.ResetPasswordRequest;
import com.skillforge.platform.dto.tenant.TenantDetailResponse;
import com.skillforge.platform.dto.tenant.TenantItemResponse;
import com.skillforge.platform.dto.tenant.TenantListResponse;
import com.skillforge.platform.dto.tenant.TenantMemberItemResponse;
import com.skillforge.platform.dto.tenant.TenantMemberListResponse;
import com.skillforge.platform.dto.tenant.UpdateTenantMemberRequest;
import com.skillforge.platform.dto.tenant.UpdateTenantRequest;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.billing.persistence.PlanDO;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.application.AuditService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.postgresql.util.PSQLException;

/**
 * 平台机构账号管理服务。
 *
 * <p>平台管理员通过本服务完成机构与机构账号的全生命周期管理；所有租户表
 * 读写前先绑定目标租户上下文，复用 PostgreSQL RLS 策略。
 */
@Service
public class PlatformTenantAccountService {

    /** 免费套餐编码。 */
    private static final String DEFAULT_PLAN_CODE = "free";

    /** 机构成员状态取值。 */
    private static final Set<String> MEMBER_STATUSES =
            Set.of(AuthConstants.MEMBER_STATUS_ACTIVE, AuthConstants.MEMBER_STATUS_DISABLED);

    /** 租户表映射器。 */
    private final TenantMapper tenantMapper;

    /** 租户成员表映射器。 */
    private final TenantMemberMapper tenantMemberMapper;

    /** 套餐表映射器。 */
    private final PlanMapper planMapper;

    /** 密码编码器。 */
    private final PasswordEncoder passwordEncoder;

    /** 审计服务。 */
    private final AuditService auditService;
    private final com.skillforge.core.foundation.access.FoundationOrganizationService organization;
    private final com.skillforge.core.foundation.catalog.entitlements.ResourceQuota quota;

    /**
     * 构造平台机构账号管理服务。
     *
     * @param tenantMapper       租户表映射器
     * @param tenantMemberMapper 租户成员表映射器
     * @param planMapper         套餐表映射器
     * @param passwordEncoder    密码编码器
     * @param auditService       审计服务
     */
    public PlatformTenantAccountService(
            TenantMapper tenantMapper,
            TenantMemberMapper tenantMemberMapper,
            PlanMapper planMapper,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            com.skillforge.core.foundation.access.FoundationOrganizationService organization,
            com.skillforge.core.foundation.catalog.entitlements.ResourceQuota quota) {
        this.organization=organization; this.quota=quota;
        this.tenantMapper = tenantMapper;
        this.tenantMemberMapper = tenantMemberMapper;
        this.planMapper = planMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /**
     * 创建机构并初始化管理员成员。
     *
     * @param request 开通机构请求
     * @return 机构详情
     */
    @Transactional
    public TenantDetailResponse createTenant(CreateTenantRequest request) {
        requirePassword(request.adminPassword());
        Long exists = tenantMapper.selectCount(
                new LambdaQueryWrapper<TenantDO>().eq(TenantDO::getSlug, request.tenantSlug()));
        if (exists != null && exists > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "租户 slug 已存在");
        }
        PlanDO plan = resolvePlan(request.planCode());
        Long tenantId = SnowflakeIds.nextId();
        TenantDO tenant = new TenantDO();
        tenant.setId(tenantId);
        tenant.setName(request.tenantName());
        tenant.setSlug(request.tenantSlug());
        tenant.setPlanId(plan.getId());
        tenant.setStatus(BillingConstants.TENANT_STATUS_ACTIVE);
        tenant.setDeploymentMode("cloud");
        tenant.setSettings(Map.of());
        try {
            tenantMapper.insert(tenant);
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            // slug 的数据库唯一约束裁定并发创建；整个机构与初始成员事务回滚。
            throw new ApiException(HttpStatus.CONFLICT, "机构标识已存在");
        }

        return TenantContext.callWithTenant(tenantId.toString(), () -> {
            Long adminId = SnowflakeIds.nextId();
            TenantMemberDO admin = new TenantMemberDO();
            admin.setId(adminId);
            admin.setTenantId(tenantId);
            admin.setUsername(request.adminUsername());
            admin.setPasswordHash(passwordEncoder.encode(request.adminPassword()));
            admin.setEmail(request.adminEmail());
            admin.setRole(RoleConstants.ADMIN);
            admin.setStatus(AuthConstants.MEMBER_STATUS_ACTIVE);
            admin.setPermissions(List.of());
            quota.requireCapacity(tenantId.toString(), com.skillforge.core.foundation.catalog.entitlements.ResourceKind.MEMBER, 1);
            tenantMemberMapper.insert(admin);
            organization.bootstrapMember(subject(tenantId, adminId), "tenant_admin");

            auditService.record(
                    tenantId.toString(),
                    actorId(),
                    AuditConstants.ACTION_TENANT_PROVISION,
                    AuditConstants.RESOURCE_TENANT,
                    tenantId.toString(),
                    "平台开通机构",
                    Map.of("tenant_slug", tenant.getSlug(), "admin_username", admin.getUsername()));
            return detail(tenantId);
        });
    }

    /**
     * 分页查询机构列表。
     *
     * @param page   页码
     * @param limit  每页条数
     * @param status 机构状态过滤，可空
     * @param q      名称或 slug 关键字，可空
     * @return 机构分页列表
     */
    public TenantListResponse listTenants(TenantQuery query) {
        int page = query.page() == null || query.page() < 1 ? ApiConstants.DEFAULT_PAGE : query.page();
        int limit = query.limit() == null
                ? ApiConstants.DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(ApiConstants.MAX_PAGE_SIZE, query.limit()));
        LambdaQueryWrapper<TenantDO> wrapper = new LambdaQueryWrapper<TenantDO>()
                .ne(TenantDO::getStatus, "internal")
                .orderByDesc(TenantDO::getCreatedAt);
        if (query.status() != null && !query.status().isBlank()) {
            wrapper.eq(TenantDO::getStatus, query.status());
        }
        if (query.q() != null && !query.q().isBlank()) {
            wrapper.and(w -> w.like(TenantDO::getName, query.q().trim()).or().like(TenantDO::getSlug, query.q().trim()));
        }
        Page<TenantDO> result = tenantMapper.selectPage(new Page<>(page, limit), wrapper);
        Map<Long, PlanDO> plans = plansByIds(result.getRecords());
        List<TenantItemResponse> items = result.getRecords().stream()
                .map(tenant -> item(tenant, plans))
                .toList();
        return new TenantListResponse(result.getTotal(), items);
    }

    /**
     * 查询机构详情。
     *
     * @param tenantId 机构 ID
     * @return 机构详情
     */
    public TenantDetailResponse getTenant(Long tenantId) {
        return detail(tenantId);
    }

    /** 精确查证标识对应的机构；无结果不代表旧请求不会稍后提交。 */
    public TenantItemResponse findBySlug(String slug) {
        TenantDO tenant = tenantMapper.selectOne(new LambdaQueryWrapper<TenantDO>()
                .eq(TenantDO::getSlug, slug).ne(TenantDO::getStatus, "internal"));
        return tenant == null ? null : item(tenant, plansByIds(List.of(tenant)));
    }

    /**
     * 停用或启用机构。
     *
     * @param tenantId 机构 ID
     * @param status   目标状态：suspended / active
     * @return 更新后的机构详情
     */
    @Transactional
    public TenantDetailResponse setTenantStatus(Long tenantId, String status) {
        if (!"suspended".equals(status) && !BillingConstants.TENANT_STATUS_ACTIVE.equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "机构状态仅支持 suspended / active");
        }
        TenantDO tenant = requireTenant(tenantId);
        tenant.setStatus(status);
        tenantMapper.updateById(tenant);
        auditService.record(
                tenantId.toString(),
                actorId(),
                "suspended".equals(status)
                        ? AuditConstants.ACTION_TENANT_SUSPEND
                        : AuditConstants.ACTION_TENANT_ACTIVATE,
                AuditConstants.RESOURCE_TENANT,
                tenantId.toString(),
                "suspended".equals(status) ? "平台停用机构" : "平台启用机构",
                Map.of());
        return detail(tenantId);
    }

    /**
     * 更新机构信息（名称、套餐、状态、配额与自定义 LLM 配置等）。
     *
     * <p>请求中的空值表示不修改对应属性。
     *
     * @param tenantId 机构 ID
     * @param request  更新请求
     * @return 更新后的机构详情
     */
    @Transactional
    public TenantDetailResponse updateTenant(Long tenantId, UpdateTenantRequest request) {
        TenantDO tenant = requireTenant(tenantId);
        if (request.name() != null && !request.name().isBlank()) {
            tenant.setName(request.name());
        }
        if (request.planCode() != null && !request.planCode().isBlank()) {
            tenant.setPlanId(resolvePlan(request.planCode()).getId());
        }
        if (request.status() != null && !request.status().isBlank()) {
            if (!Set.of(BillingConstants.TENANT_STATUS_ACTIVE, "suspended", "warning")
                    .contains(request.status())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "机构状态仅支持 active / suspended / warning");
            }
            tenant.setStatus(request.status());
        }
        if (request.deploymentMode() != null && !request.deploymentMode().isBlank()) {
            if (!Set.of("cloud", "hybrid", "on_premise").contains(request.deploymentMode())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "部署模式仅支持 cloud / hybrid / on_premise");
            }
            tenant.setDeploymentMode(request.deploymentMode());
        }
        if (request.overrideQuotaApiCallsPerMonth() != null) {
            tenant.setOverrideQuotaApiCallsPerMonth(request.overrideQuotaApiCallsPerMonth());
        }
        if (request.overrideRateLimitPerMinute() != null) {
            tenant.setOverrideRateLimitPerMinute(request.overrideRateLimitPerMinute());
        }
        // 显式清除覆盖使用 wrapper，避免 null 被默认更新策略忽略。
        LambdaUpdateWrapper<TenantDO> resets = new LambdaUpdateWrapper<TenantDO>().eq(TenantDO::getId, tenantId);
        Set<String> resetFields = request.resetQuotaOverrides() == null ? Set.of() : request.resetQuotaOverrides();
        for (String field : resetFields) {
            switch (field) {
                case "override_quota_api_calls_per_month" -> {
                    if (request.overrideQuotaApiCallsPerMonth() != null) throw new ApiException(HttpStatus.BAD_REQUEST, "配额不能同时自定义和恢复默认");
                    resets.set(TenantDO::getOverrideQuotaApiCallsPerMonth, null);
                    tenant.setOverrideQuotaApiCallsPerMonth(null);
                }
                case "override_rate_limit_per_minute" -> {
                    if (request.overrideRateLimitPerMinute() != null) throw new ApiException(HttpStatus.BAD_REQUEST, "配额不能同时自定义和恢复默认");
                    resets.set(TenantDO::getOverrideRateLimitPerMinute, null);
                    tenant.setOverrideRateLimitPerMinute(null);
                }
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "未知配额覆盖字段: " + field);
            }
        }
        tenantMapper.updateById(tenant);
        if (!resetFields.isEmpty()) tenantMapper.update(null, resets);
        auditService.record(
                tenantId.toString(),
                actorId(),
                AuditConstants.ACTION_TENANT_UPDATE,
                AuditConstants.RESOURCE_TENANT,
                tenantId.toString(),
                "平台更新机构信息",
                Map.of());
        return detail(tenantId);
    }

    /**
     * 分页查询机构成员。
     *
     * @param tenantId 机构 ID
     * @param page     页码
     * @param limit    每页条数
     * @param role     角色过滤，可空
     * @param status   状态过滤，可空
     * @return 成员分页列表
     */
    public TenantMemberListResponse listMembers(Long tenantId, TenantMemberQuery query) {
        requireTenant(tenantId);
        TenantContext.setTenantId(tenantId.toString());
        int page = query.page() == null || query.page() < 1 ? ApiConstants.DEFAULT_PAGE : query.page();
        int limit = query.limit() == null
                ? ApiConstants.DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(ApiConstants.MAX_PAGE_SIZE, query.limit()));
        LambdaQueryWrapper<TenantMemberDO> wrapper = new LambdaQueryWrapper<TenantMemberDO>()
                .eq(TenantMemberDO::getTenantId, tenantId)
                .orderByDesc(TenantMemberDO::getCreatedAt);
        if (query.role() != null && !query.role().isBlank()) {
            wrapper.apply("EXISTS (SELECT 1 FROM nw_foundation_member_roles mr JOIN nw_foundation_roles r ON r.scope_key=mr.scope_key AND r.id=mr.role_id WHERE mr.scope_key=nw_tenant_members.tenant_id::text AND mr.member_id=nw_tenant_members.id AND r.code={0} AND r.enabled)", query.role());
        }
        if (query.status() != null && !query.status().isBlank()) {
            wrapper.eq(TenantMemberDO::getStatus, query.status());
        }
        Page<TenantMemberDO> result = tenantMemberMapper.selectPage(new Page<>(page, limit), wrapper);
        List<TenantMemberItemResponse> items = result.getRecords().stream()
                .map(this::memberItem)
                .toList();
        return new TenantMemberListResponse(result.getTotal(), items);
    }

    /**
     * 新增机构成员。
     *
     * @param tenantId 机构 ID
     * @param request  新增成员请求
     * @return 新成员
     */
    @Transactional
    public TenantMemberItemResponse createMember(Long tenantId, CreateTenantMemberRequest request) {
        requirePassword(request.password());
        requireTenant(tenantId);
        requireMemberRoles(request.roles());
        quota.requireCapacity(tenantId.toString(), com.skillforge.core.foundation.catalog.entitlements.ResourceKind.MEMBER, 1);
        TenantContext.setTenantId(tenantId.toString());
        Long exists = tenantMemberMapper.selectCount(
                new LambdaQueryWrapper<TenantMemberDO>()
                        .eq(TenantMemberDO::getTenantId, tenantId)
                        .eq(TenantMemberDO::getUsername, request.username()));
        if (exists != null && exists > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "用户名在该机构已存在");
        }
        Long memberId = SnowflakeIds.nextId();
        TenantMemberDO member = new TenantMemberDO();
        member.setId(memberId);
        member.setTenantId(tenantId);
        member.setUsername(request.username());
        member.setPasswordHash(passwordEncoder.encode(request.password()));
        member.setRole("developer");
        member.setEmail(request.email());
        member.setStatus(AuthConstants.MEMBER_STATUS_ACTIVE);
        member.setPermissions(List.of());
        try {
            tenantMemberMapper.insert(member);
        } catch (DuplicateKeyException conflict) {
            // 只将租户内用户名唯一约束竞争映射成既有 409，主键或其他约束保留技术异常。
            for (Throwable cause = conflict; cause != null; cause = cause.getCause()) {
                if (cause instanceof PSQLException sql && "23505".equals(sql.getSQLState())
                        && sql.getServerErrorMessage() != null
                        && "uq_tenant_member".equals(sql.getServerErrorMessage().getConstraint()))
                    throw new ApiException(HttpStatus.CONFLICT, "用户名在该机构已存在");
            }
            throw conflict;
        }
        for (String role : request.roles()) organization.bootstrapMember(subject(tenantId, memberId), role);
        auditService.record(
                tenantId.toString(),
                actorId(),
                AuditConstants.ACTION_TENANT_MEMBER_CREATE,
                AuditConstants.RESOURCE_TENANT_MEMBER,
                memberId.toString(),
                "平台新增机构成员",
                Map.of("username", member.getUsername(), "roles", request.roles()));
        return memberItem(member);
    }

    /**
     * 更新机构成员状态、角色或邮箱。
     *
     * @param tenantId 机构 ID
     * @param memberId 成员 ID
     * @param request  更新请求
     * @return 更新后的成员
     */
    @Transactional
    public TenantMemberItemResponse updateMember(
            Long tenantId, Long memberId, UpdateTenantMemberRequest request) {
        return organization.changeAccount(subject(tenantId, memberId), "disabled".equals(request.status()), () -> {
        TenantMemberDO member = requireMember(tenantId, memberId);
        if (request.status() != null && !request.status().isBlank()) {
            if (!MEMBER_STATUSES.contains(request.status())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "成员状态仅支持 active / disabled");
            }
            member.setStatus(request.status());
        }
        if (request.roles() != null) {
            requireMemberRoles(request.roles());
            organization.replaceTenantMemberRoles(subject(tenantId, memberId), request.roles());
        }
        if (request.email() != null && !request.email().isBlank()) {
            member.setEmail(request.email());
        }
        tenantMemberMapper.updateById(member);
        auditService.record(
                tenantId.toString(),
                actorId(),
                AuditConstants.ACTION_TENANT_MEMBER_UPDATE,
                AuditConstants.RESOURCE_TENANT_MEMBER,
                memberId.toString(),
                "平台更新机构成员",
                Map.of("username", member.getUsername()));
        return memberItem(member);
        });
    }

    /**
     * 重置机构成员密码。
     *
     * @param tenantId 机构 ID
     * @param memberId 成员 ID
     * @param request  重置密码请求
     * @return 更新后的成员
     */
    @Transactional
    public TenantMemberItemResponse resetPassword(
            Long tenantId, Long memberId, ResetPasswordRequest request) {
        requirePassword(request.newPassword());
        return organization.changeAccount(subject(tenantId, memberId), false, () -> {
        TenantMemberDO member = requireMember(tenantId, memberId);
        member.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        tenantMemberMapper.updateById(member);
        auditService.record(
                tenantId.toString(),
                actorId(),
                AuditConstants.ACTION_TENANT_MEMBER_RESET_PASSWORD,
                AuditConstants.RESOURCE_TENANT_MEMBER,
                memberId.toString(),
                "平台重置机构成员密码",
                Map.of("username", member.getUsername()));
        return memberItem(member);
        });
    }

    /**
     * 组装机构详情。
     *
     * @param tenantId 机构 ID
     * @return 机构详情
     */
    private TenantDetailResponse detail(Long tenantId) {
        TenantDO tenant = requireTenant(tenantId);
        String planCode = null;
        if (tenant.getPlanId() != null) {
            PlanDO plan = planMapper.selectById(tenant.getPlanId());
            planCode = plan == null ? null : plan.getPlanCode();
        }
        TenantContext.setTenantId(tenantId.toString());
        Long count = tenantMemberMapper.selectCount(
                new LambdaQueryWrapper<TenantMemberDO>()
                        .eq(TenantMemberDO::getTenantId, tenantId));
        return new TenantDetailResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getPlanId(),
                planCode,
                tenant.getStatus(),
                tenant.getDeploymentMode(),
                count == null ? 0 : count,
                tenant.getOverrideQuotaApiCallsPerMonth(),
                tenant.getOverrideQuotaMaxWorkflows(),
                tenant.getOverrideQuotaMaxKnowledgeBases(),
                tenant.getOverrideQuotaMaxMembers(),
                tenant.getOverrideQuotaMaxChannels(),
                tenant.getOverrideRateLimitPerMinute(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt());
    }

    /**
     * 组装机构列表项。
     *
     * @param tenant 机构
     * @param plans  机构 ID → 套餐
     * @return 列表项
     */
    private TenantItemResponse item(TenantDO tenant, Map<Long, PlanDO> plans) {
        PlanDO plan = tenant.getPlanId() == null ? null : plans.get(tenant.getPlanId());
        return new TenantItemResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                plan == null ? null : plan.getPlanCode(),
                tenant.getStatus(),
                tenant.getCreatedAt());
    }

    /**
     * 批量读取套餐并按 ID 建索引。
     *
     * @param tenants 机构列表
     * @return 套餐 ID → 套餐
     */
    private Map<Long, PlanDO> plansByIds(List<TenantDO> tenants) {
        List<Long> ids = tenants.stream()
                .map(TenantDO::getPlanId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return planMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(PlanDO::getId, Function.identity()));
    }

    /** 返回真实启用套餐目录。 */
    public List<TenantPlanResponse> listPlans() {
        return planMapper.selectList(new LambdaQueryWrapper<PlanDO>().eq(PlanDO::getIsActive, true)
                .orderByAsc(PlanDO::getPlanCode)).stream()
                .map(plan -> new TenantPlanResponse(plan.getPlanCode(), plan.getDisplayName())).toList();
    }

    /**
     * 解析套餐，未指定时使用免费套餐。
     *
     * @param planCode 套餐编码，可空
     * @return 套餐
     */
    private PlanDO resolvePlan(String planCode) {
        String code = planCode == null || planCode.isBlank() ? DEFAULT_PLAN_CODE : planCode.trim();
        PlanDO plan = planMapper.selectOne(
                new LambdaQueryWrapper<PlanDO>().eq(PlanDO::getPlanCode, code));
        if (plan == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "套餐不存在: " + code);
        }
        return plan;
    }

    /**
     * 校验成员角色是否允许平台创建。
     *
     * @param role 角色
     */
    /** 密码变更在编码及任何数据库写入前校验 BCrypt 的 UTF-8 字节边界。 */
    private static void requirePassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 12
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                || !password.matches("(?s)^(?=.*[A-Za-z])(?=.*[0-9]).+$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码至少 12 个字符且包含字母和数字，UTF-8 编码最多 72 字节");
        }
    }

    private void requireMemberRoles(List<String> roles) {
        if (roles == null || roles.isEmpty() || roles.size() > 32 || new java.util.HashSet<>(roles).size() != roles.size()
                || roles.stream().anyMatch(role -> role == null || !role.matches("[a-z][a-z0-9_.-]{0,63}")))
            throw new ApiException(HttpStatus.BAD_REQUEST, "请指定有效角色编码数组");
    }

    private com.skillforge.core.foundation.access.FoundationAccess.Subject subject(Long tenant, Long member) {
        return new com.skillforge.core.foundation.access.FoundationAccess.Subject(false, tenant.toString(), member.toString());
    }

    /**
     * 按 ID 加载机构，不存在时返回 404。
     *
     * @param tenantId 机构 ID
     * @return 机构
     */
    private TenantDO requireTenant(Long tenantId) {
        TenantDO tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || "internal".equals(tenant.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "机构不存在");
        }
        return tenant;
    }

    /**
     * 按机构与成员 ID 加载成员，不存在时返回 404。
     *
     * @param tenantId 机构 ID
     * @param memberId 成员 ID
     * @return 成员
     */
    private TenantMemberDO requireMember(Long tenantId, Long memberId) {
        TenantContext.setTenantId(tenantId.toString());
        TenantMemberDO member = tenantMemberMapper.selectById(memberId);
        if (member == null || !tenantId.equals(member.getTenantId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "成员不存在");
        }
        return member;
    }

    /**
     * 组装成员响应。
     *
     * @param member 成员
     * @return 成员响应
     */
    private TenantMemberItemResponse memberItem(TenantMemberDO member) {
        return new TenantMemberItemResponse(
                member.getId(),
                member.getUsername(),
                organization.memberRoleCodes(subject(member.getTenantId(), member.getId())),
                member.getEmail(),
                member.getStatus(),
                member.getLastLoginAt(),
                member.getCreatedAt());
    }

    /**
     * 获取当前操作者 ID（平台管理员 ID）。
     *
     * @return 操作者 ID
     */
    private static String actorId() {
        String userId = RequestContext.getUserId();
        return userId == null ? "platform" : userId;
    }
}

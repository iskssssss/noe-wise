package com.skillforge.platform.service;
import com.skillforge.core.shared.config.SnowflakeIds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.constant.AuthConstants;
import com.skillforge.core.billing.constant.BillingConstants;
import com.skillforge.core.foundation.constant.RoleConstants;
import com.skillforge.platform.dto.tenant.CreateTenantMemberRequest;
import com.skillforge.platform.dto.tenant.CreateTenantRequest;
import com.skillforge.platform.dto.tenant.ResetPasswordRequest;
import com.skillforge.platform.dto.tenant.TenantDetailResponse;
import com.skillforge.platform.dto.tenant.TenantMemberItemResponse;
import com.skillforge.platform.dto.tenant.TenantQuery;
import com.skillforge.platform.dto.tenant.TenantMemberQuery;
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
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 平台机构账号管理服务单元测试。
 */
class PlatformTenantAccountServiceTest {

    private TenantMapper tenantMapper;
    private TenantMemberMapper memberMapper;
    private PlanMapper planMapper;
    private PasswordEncoder passwordEncoder;
    private PlatformTenantAccountService service;
    private FoundationOrganizationService organization;
    private ResourceQuota quota;

    @BeforeEach
    void setUp() {
        tenantMapper = mock(TenantMapper.class);
        memberMapper = mock(TenantMemberMapper.class);
        planMapper = mock(PlanMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        AuditService auditService = mock(AuditService.class);
        organization = mock(FoundationOrganizationService.class);
        quota = mock(ResourceQuota.class);
        // 此单元测试仍执行账号服务自己的mutation；并发管理员保护由真实数据库契约测试验证。
        when(organization.changeAccount(any(), anyBoolean(), any())).thenAnswer(call ->
                call.<java.util.function.Supplier<?>>getArgument(2).get());
        service = new PlatformTenantAccountService(
                tenantMapper, memberMapper, planMapper, passwordEncoder, auditService, organization, quota);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private PlanDO freePlan() {
        PlanDO plan = new PlanDO();
        plan.setId(SnowflakeIds.nextId());
        plan.setPlanCode("free");
        return plan;
    }

    @Test
    void memberUsernameRaceUsesExistingConflictResponse() {
        long tenantId = 13L;
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant(tenantId));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(memberMapper.insert(any(TenantMemberDO.class))).thenThrow(duplicate("uq_tenant_member"));
        ApiException failure = assertThrows(ApiException.class, () -> service.createMember(tenantId,
                new CreateTenantMemberRequest("dev", "password12345", List.of(RoleConstants.DEVELOPER), null)));
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
    }

    @Test
    void unrelatedMemberConstraintIsNotReportedAsUsernameConflict() {
        long tenantId = 13L;
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant(tenantId));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        var duplicate = duplicate("skillforge_tenant_members_pkey");
        when(memberMapper.insert(any(TenantMemberDO.class))).thenThrow(duplicate);
        assertEquals(duplicate, assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> service.createMember(tenantId,
                        new CreateTenantMemberRequest("dev", "password12345", List.of(RoleConstants.DEVELOPER), null))));
    }

    private org.springframework.dao.DuplicateKeyException duplicate(String constraint) {
        return new org.springframework.dao.DuplicateKeyException("duplicate", new org.postgresql.util.PSQLException(
                new org.postgresql.util.ServerErrorMessage("SERROR\0C23505\0n" + constraint + "\0\0")));
    }

    private TenantDO tenant(Long id) {
        TenantDO tenant = new TenantDO();
        tenant.setId(id);
        tenant.setName("测试机构");
        tenant.setSlug("test-clinic");
        tenant.setPlanId(freePlan().getId());
        tenant.setStatus(BillingConstants.TENANT_STATUS_ACTIVE);
        tenant.setDeploymentMode("cloud");
        return tenant;
    }

    @Test
    void createTenantCreatesTenantAndAdmin() {
        PlanDO plan = freePlan();
        Long tenantId = SnowflakeIds.nextId();
        TenantDO tenant = tenant(tenantId);
        when(tenantMapper.selectCount(any())).thenReturn(0L);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(planMapper.selectById(any(Long.class))).thenReturn(plan);
        when(tenantMapper.selectById(any(Long.class))).thenReturn(tenant);
        when(memberMapper.selectCount(any())).thenReturn(1L);

        TenantDetailResponse response = service.createTenant(new CreateTenantRequest(
                "测试机构", "test-clinic", null, "admin", "password12345", null));

        assertEquals("free", response.planCode());
        assertEquals(1, response.memberCount());
        verify(tenantMapper).insert(any(TenantDO.class));
        verify(memberMapper).insert(any(TenantMemberDO.class));
    }

    @Test
    void createTenantRejectsDuplicateSlug() {
        when(tenantMapper.selectCount(any())).thenReturn(1L);

        ApiException ex = assertThrows(ApiException.class, () -> service.createTenant(
                new CreateTenantRequest("测试机构", "test-clinic", null, "admin", "password12345", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void createTenantRejectsUnknownPlan() {
        when(tenantMapper.selectCount(any())).thenReturn(0L);
        when(planMapper.selectOne(any())).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> service.createTenant(
                new CreateTenantRequest("测试机构", "test-clinic", "enterprise", "admin", "password12345", null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void setTenantStatusRejectsInvalidStatus() {
        when(tenantMapper.selectById(any(Long.class))).thenReturn(tenant(SnowflakeIds.nextId()));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.setTenantStatus(SnowflakeIds.nextId(), "deleted"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void createMemberRejectsMalformedOrRepeatedRolesBeforeWriting() {
        when(tenantMapper.selectById(any(Long.class))).thenReturn(tenant(SnowflakeIds.nextId()));
        for (List<String> roles : List.of(List.<String>of(), List.of("developer", "developer"), List.of("invalid role"))) {
            ApiException ex = assertThrows(ApiException.class, () -> service.createMember(
                    SnowflakeIds.nextId(), new CreateTenantMemberRequest("member", "password12345", roles, null)));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }
        verify(memberMapper, never()).insert(any(TenantMemberDO.class));
        org.mockito.Mockito.verifyNoInteractions(quota, organization, passwordEncoder);
    }

    @Test
    void updateMemberRejectsInvalidStatus() {
        Long tenantId = SnowflakeIds.nextId();
        TenantMemberDO member = new TenantMemberDO();
        member.setId(SnowflakeIds.nextId());
        member.setTenantId(tenantId);
        member.setUsername("member");
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant(tenantId));
        when(memberMapper.selectById(member.getId())).thenReturn(member);

        ApiException ex = assertThrows(ApiException.class, () -> service.updateMember(
                tenantId, member.getId(), new UpdateTenantMemberRequest("deleted", null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void resetPasswordUpdatesHash() {
        Long tenantId = SnowflakeIds.nextId();
        TenantMemberDO member = new TenantMemberDO();
        member.setId(SnowflakeIds.nextId());
        member.setTenantId(tenantId);
        member.setUsername("member");
        member.setRole(RoleConstants.DEVELOPER);
        member.setStatus(AuthConstants.MEMBER_STATUS_ACTIVE);
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant(tenantId));
        when(memberMapper.selectById(member.getId())).thenReturn(member);
        when(passwordEncoder.encode("new-password-123")).thenReturn("encoded");

        TenantMemberItemResponse response = service.resetPassword(
                tenantId, member.getId(), new ResetPasswordRequest("new-password-123"));

        assertEquals(member.getId(), response.id());
        verify(memberMapper).updateById(member);
        assertEquals("encoded", member.getPasswordHash());
    }

    @Test
    void listsTenantsWithPlansAndNormalizesPagination() {
        TenantDO value = tenant(11L);
        PlanDO plan = freePlan();
        value.setPlanId(plan.getId());
        Page<TenantDO> page = new Page<>(1, 200);
        page.setTotal(1);
        page.setRecords(java.util.List.of(value));
        when(tenantMapper.selectPage(any(), any())).thenReturn(page);
        when(planMapper.selectBatchIds(any())).thenReturn(java.util.List.of(plan));

        var result = service.listTenants(new TenantQuery(0, 500, "active", "test"));

        assertEquals(1, result.total());
        assertEquals("free", result.items().getFirst().planCode());
    }

    @Test
    void updatesTenantQuota() {
        Long tenantId = 12L;
        TenantDO value = tenant(tenantId);
        when(tenantMapper.selectById(tenantId)).thenReturn(value);
        when(planMapper.selectOne(any())).thenReturn(freePlan());
        when(planMapper.selectById(any())).thenReturn(freePlan());
        when(memberMapper.selectCount(any())).thenReturn(0L);
        TenantDetailResponse response = service.updateTenant(tenantId, new UpdateTenantRequest(
                "新名称", "free", "warning", "hybrid", 10, 60, null));

        assertEquals("新名称", response.name());
        assertEquals("warning", value.getStatus());
        assertEquals(10, value.getOverrideQuotaApiCallsPerMonth());
        assertEquals(60, value.getOverrideRateLimitPerMinute());
        verify(tenantMapper).updateById(value);
    }

    @Test
    void clearsQuotaOverrideWithExplicitNullUpdate() {
        TenantDO value = tenant(12L);
        value.setOverrideRateLimitPerMinute(42);
        when(tenantMapper.selectById(12L)).thenReturn(value);
        service.updateTenant(12L, new UpdateTenantRequest(null, null, null, null,
                null, null, java.util.Set.of("override_rate_limit_per_minute")));
        assertNull(value.getOverrideRateLimitPerMinute());
        verify(tenantMapper).update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }

    @Test
    void rejectsUnknownQuotaReset() {
        when(tenantMapper.selectById(12L)).thenReturn(tenant(12L));
        assertThrows(ApiException.class, () -> service.updateTenant(12L, new UpdateTenantRequest(
                null, null, null, null, null, null, java.util.Set.of("unknown"))));
        verify(tenantMapper, never()).updateById(any(TenantDO.class));
    }

    @Test
    void createsAndUpdatesMembersWithTenantIsolation() {
        Long tenantId = 13L;
        TenantDO value = tenant(tenantId);
        when(tenantMapper.selectById(tenantId)).thenReturn(value);
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("password12345")).thenReturn("hash");

        TenantMemberItemResponse created = service.createMember(tenantId,
                new CreateTenantMemberRequest("dev", "password12345", List.of(RoleConstants.DEVELOPER), "d@x"));
        assertEquals("dev", created.username());
        verify(memberMapper).insert(any(TenantMemberDO.class));

        TenantMemberDO member = new TenantMemberDO();
        member.setId(22L);
        member.setTenantId(tenantId);
        member.setUsername("dev");
        member.setRole(RoleConstants.DEVELOPER);
        member.setStatus(AuthConstants.MEMBER_STATUS_ACTIVE);
        when(memberMapper.selectById(22L)).thenReturn(member);
        var memberSubject = new com.skillforge.core.foundation.access.FoundationAccess.Subject(false, "13", "22");
        when(organization.memberRoleCodes(memberSubject)).thenReturn(List.of(RoleConstants.AUDITOR));
        var updated = service.updateMember(tenantId, 22L,
                new UpdateTenantMemberRequest(AuthConstants.MEMBER_STATUS_DISABLED,
                        List.of(RoleConstants.AUDITOR), "audit@x"));
        assertEquals(AuthConstants.MEMBER_STATUS_DISABLED, updated.status());
        assertEquals(List.of(RoleConstants.AUDITOR), updated.roles());
        verify(organization).replaceTenantMemberRoles(memberSubject, List.of(RoleConstants.AUDITOR));

        member.setTenantId(999L);
        assertThrows(ApiException.class, () -> service.updateMember(tenantId, 22L,
                new UpdateTenantMemberRequest(null, null, null)));
    }

    @Test
    void newMemberInitializesEveryRequestedRoleThroughOrganizationAuthority() {
        when(tenantMapper.selectById(13L)).thenReturn(tenant(13L));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        var roles = List.of(RoleConstants.DEVELOPER, RoleConstants.AUDITOR);
        var created = service.createMember(13L, new CreateTenantMemberRequest("multiple-roles", "password12345", roles, null));
        var memberSubject = new com.skillforge.core.foundation.access.FoundationAccess.Subject(false, "13", created.id().toString());
        verify(organization).bootstrapMember(memberSubject, RoleConstants.DEVELOPER);
        verify(organization).bootstrapMember(memberSubject, RoleConstants.AUDITOR);
        verify(quota).requireCapacity("13", com.skillforge.core.foundation.catalog.entitlements.ResourceKind.MEMBER, 1);
    }

    @Test
    void invalidPasswordsRejectBeforeAnyAccountOrTenantMutation() {
        for (String password : java.util.Arrays.asList(null, "Aa1" + "🙂".repeat(8), "letterswithoutnumber",
                "123456789012", "Aa1" + "界".repeat(24), "Aa1" + "🙂".repeat(18))) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> service.createTenant(
                    new CreateTenantRequest("机构", "password-test", null, "administrator", password, null))).getStatus());
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> service.createMember(13L,
                    new CreateTenantMemberRequest("member", password, List.of("developer"), null))).getStatus());
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> service.resetPassword(13L, 22L,
                    new ResetPasswordRequest(password))).getStatus());
        }
        org.mockito.Mockito.verifyNoInteractions(tenantMapper, memberMapper, planMapper, passwordEncoder, organization, quota);
    }

    /** 初始管理员受理与 DTO 同一边界；无效密码在任何机构、成员写入和编码之前拒绝。 */
    @Test
    void initialAdministratorPasswordBoundariesRejectBeforeTenantOrAccountMutation() {
        var passwords = new java.util.ArrayList<String>();
        for (int length = 8; length <= 11; length++) passwords.add("Aa1" + "x".repeat(length - 3));
        passwords.addAll(List.of("ABCDEFGHIJKL", "123456789012", "Aa1" + "🙂".repeat(8), "Aa1" + "界".repeat(23) + "x"));
        for (String password : passwords) {
            var rejected = assertThrows(ApiException.class, () -> service.createTenant(
                    new CreateTenantRequest("机构", "initial-password-test", null, "administrator", password, null)));
            assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
            org.junit.jupiter.api.Assertions.assertFalse(rejected.getMessage().contains(password));
        }
        org.mockito.Mockito.verifyNoInteractions(tenantMapper, memberMapper, planMapper, passwordEncoder, organization, quota);
    }

    /** 初始开通的 72 字节、12 码点及原始空白/组合字符/换行密码均原样进入编码器。 */
    @Test
    void initialAdministratorValidPasswordReachesEncoderWithoutTrimmingOrNormalization() {
        PlanDO plan = freePlan();
        when(tenantMapper.selectCount(any())).thenReturn(0L);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(planMapper.selectById(any(Long.class))).thenReturn(plan);
        when(tenantMapper.selectById(any(Long.class))).thenReturn(tenant(13L));
        when(memberMapper.selectCount(any())).thenReturn(1L);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        var passwords = List.of("Aa1" + "界".repeat(23), "Aa1" + "🙂".repeat(17) + "z", "Aa1" + "🙂".repeat(9),
                " Aa1" + "x".repeat(7) + " ", "Aa1" + "e\u0301".repeat(4) + "x", "Aa1\n" + "x".repeat(8));
        for (String password : passwords) {
            service.createTenant(new CreateTenantRequest("机构", "initial-password-test", null, "administrator", password, null));
            verify(passwordEncoder).encode(password);
        }
        var members = org.mockito.ArgumentCaptor.forClass(TenantMemberDO.class);
        verify(memberMapper, org.mockito.Mockito.times(passwords.size())).insert(members.capture());
        for (TenantMemberDO member : members.getAllValues()) assertEquals("encoded", member.getPasswordHash());
    }

    @Test
    void chineseAndEmojiPasswordsAtExactlySeventyTwoUtf8BytesReachEncoderIntact() {
        when(tenantMapper.selectById(13L)).thenReturn(tenant(13L));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        for (String password : List.of("Aa1" + "界".repeat(23), "Aa1" + "🙂".repeat(17) + "z")) {
            assertEquals(72, password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            service.createMember(13L, new CreateTenantMemberRequest("member", password, List.of("developer"), null));
            verify(passwordEncoder).encode(password);
        }
    }

    @Test
    void listsMembersAndRejectsDuplicateUser() {
        Long tenantId = 14L;
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant(tenantId));
        TenantMemberDO member = new TenantMemberDO();
        member.setId(24L);
        member.setTenantId(tenantId);
        member.setUsername("member");
        member.setRole(RoleConstants.ADMIN);
        member.setStatus(AuthConstants.MEMBER_STATUS_ACTIVE);
        Page<TenantMemberDO> page = new Page<>(1, 20);
        page.setTotal(1);
        page.setRecords(java.util.List.of(member));
        when(memberMapper.selectPage(any(), any())).thenReturn(page);
        assertEquals(1, service.listMembers(tenantId,
                new TenantMemberQuery(0, 500, RoleConstants.ADMIN, "active")).total());

        when(memberMapper.selectCount(any())).thenReturn(1L);
        assertThrows(ApiException.class, () -> service.createMember(tenantId,
                new CreateTenantMemberRequest("member", "password12345", List.of("tenant_admin"), null)));
    }

    @Test
    void suspendAndActivateRequireExistingTenant() {
        Long tenantId = 15L;
        TenantDO value = tenant(tenantId);
        when(tenantMapper.selectById(tenantId)).thenReturn(value);
        when(memberMapper.selectCount(any())).thenReturn(0L);
        assertEquals("suspended", service.setTenantStatus(tenantId, "suspended").status());
        assertEquals("active", service.setTenantStatus(tenantId, "active").status());
        when(tenantMapper.selectById(999L)).thenReturn(null);
        assertThrows(ApiException.class, () -> service.getTenant(999L));
    }
}

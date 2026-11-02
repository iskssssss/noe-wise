package com.skillforge.platform.service;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.platform.dto.tenant.CreateTenantMemberRequest;
import com.skillforge.platform.dto.tenant.ResetPasswordRequest;
import com.skillforge.platform.dto.tenant.UpdateTenantMemberRequest;
import com.skillforge.platform.dto.tenant.UpdateTenantRequest;
import com.skillforge.core.foundation.application.AuditService;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 独立 PostgreSQL 与正式 Spring 事务代理验证账号写入和审计失败共同回滚。 */
@Testcontainers
class AccountTransactionIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private PlatformTenantAccountService accounts;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        jdbc.execute("DROP TABLE IF EXISTS account_members, account_tenants");
        jdbc.execute("CREATE TABLE account_tenants(id bigint primary key,name text,status text)");
        jdbc.execute("CREATE TABLE account_members(id bigint primary key,tenant_id bigint,username text,password_hash text,role text,email text,status text)");
        jdbc.update("INSERT INTO account_tenants VALUES(7,'机构','active')");
        jdbc.update("INSERT INTO account_members VALUES(11,7,'member','old-hash','developer','old@x','active')");

        var tenants = mock(TenantMapper.class);
        when(tenants.selectById(7L)).thenAnswer(call -> {
            var value = new TenantDO(); value.setId(7L); value.setName("机构"); value.setStatus("active");
            return value;
        });
        when(tenants.updateById(any(TenantDO.class))).thenAnswer(call -> {
            TenantDO value = call.getArgument(0);
            return jdbc.update("UPDATE account_tenants SET name=?,status=? WHERE id=?", value.getName(), value.getStatus(), value.getId());
        });
        var members = mock(TenantMemberMapper.class);
        when(members.selectById(11L)).thenAnswer(call -> member());
        when(members.selectOne(any())).thenAnswer(call -> member());
        when(members.selectCount(any())).thenReturn(0L);
        when(members.insert(any(TenantMemberDO.class))).thenAnswer(call -> {
            TenantMemberDO value = call.getArgument(0);
            return jdbc.update("INSERT INTO account_members VALUES(?,?,?,?,?,?,?)", value.getId(), value.getTenantId(), value.getUsername(),
                    value.getPasswordHash(), value.getRole(), value.getEmail(), value.getStatus());
        });
        when(members.updateById(any(TenantMemberDO.class))).thenAnswer(call -> {
            TenantMemberDO value = call.getArgument(0);
            return jdbc.update("UPDATE account_members SET password_hash=?,role=?,email=?,status=? WHERE id=?",
                    value.getPasswordHash(), value.getRole(), value.getEmail(), value.getStatus(), value.getId());
        });
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("new-hash");
        when(encoder.matches("old-password", "old-hash")).thenReturn(true);
        var audit = mock(AuditService.class);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(), any(), any(), any(), any(), any(), any());
        var organization=mock(com.skillforge.core.foundation.access.FoundationOrganizationService.class);
        when(organization.changeAccount(any(),anyBoolean(),any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(2)).get());
        accounts = transactional(new PlatformTenantAccountService(tenants, members, mock(PlanMapper.class), encoder, audit,
                organization,mock(com.skillforge.core.foundation.catalog.entitlements.ResourceQuota.class)));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void platformWritesRollbackWhenAuditCannotPersist() {
        List<Runnable> writes = List.of(
                () -> accounts.setTenantStatus(7L, "suspended"),
                () -> accounts.updateTenant(7L, new UpdateTenantRequest("新机构", null, null, null, null, null, null)),
                () -> accounts.createMember(7L, new CreateTenantMemberRequest("new-member", "Password1234", List.of("developer"), null)),
                () -> accounts.updateMember(7L, 11L, new UpdateTenantMemberRequest("disabled", List.of("auditor"), "new@x")),
                () -> accounts.resetPassword(7L, 11L, new ResetPasswordRequest("Password1234")));
        for (var write : writes) {
            assertThrows(IllegalStateException.class, write::run);
            assertEquals("机构", jdbc.queryForObject("SELECT name FROM account_tenants WHERE id=7", String.class));
            assertEquals("active", jdbc.queryForObject("SELECT status FROM account_tenants WHERE id=7", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account_members", Integer.class));
            assertEquals("old-hash", jdbc.queryForObject("SELECT password_hash FROM account_members WHERE id=11", String.class));
            assertEquals("developer", jdbc.queryForObject("SELECT role FROM account_members WHERE id=11", String.class));
            assertEquals("old@x", jdbc.queryForObject("SELECT email FROM account_members WHERE id=11", String.class));
            assertEquals("active", jdbc.queryForObject("SELECT status FROM account_members WHERE id=11", String.class));
        }
    }

    private TenantMemberDO member() {
        var value = new TenantMemberDO(); value.setId(11L); value.setTenantId(7L); value.setUsername("member");
        value.setPasswordHash("old-hash"); value.setRole("developer"); value.setEmail("old@x"); value.setStatus("active");
        return value;
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}

package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.access.SessionAccessPolicy;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.institution.group.GroupAgentEligibility;
import com.skillforge.institution.group.GroupBindingMapper;
import com.skillforge.institution.group.GroupBindingService;
import com.skillforge.institution.group.GroupContracts.*;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 群领域的真实持久化验收：正式迁移、正式 Mapper 与事务、无 RLS 绕过权限的角色。
 * 凭据当前状态、EndUser 和 Session 都读取真实表；Agent 发布资格由独立资格测试负责。
 */
@Testcontainers(disabledWithoutDocker = false)
class GroupBindingPersistenceIntegrationTest {
    private static final Set<String> SCOPES = Set.of("groups.write", "groups.read",
            "groups.members.write", "groups.agents.write");

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("group_binding_persistence")
            .withUsername("skillforge").withPassword("isolated-group-owner-password");

    private static JdbcTemplate owner;
    private static JdbcTemplate restrictedSql;
    private static TransactionTemplate writer;
    private static SqlSessionTemplate sqlSessions;
    private GroupBindingService groups;
    private EndUserService users;
    private SessionService sessions;
    private Domain primary;
    private Domain sameTenant;
    private Domain foreignTenant;

    @BeforeAll
    static void database() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE group_domain LOGIN PASSWORD 'isolated-group-role-password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO group_domain");
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO group_domain");
        var restricted = new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                "group_domain", "isolated-group-role-password");
        restrictedSql = new JdbcTemplate(restricted);
        writer = new TransactionTemplate(new DataSourceTransactionManager(restricted));
        var configuration = new MybatisConfiguration(new Environment("group-domain",
                new SpringManagedTransactionFactory(), restricted));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.addInterceptor(new TenantRlsInterceptor());
        for (Class<?> mapper : List.of(TenantMapper.class, AuditLogMapper.class,
                EndUserMapper.class, ServicePrincipalMapper.class, SessionMapper.class)) {
            configuration.addMapper(mapper);
        }
        for (String path : List.of("mapper/GroupBindingMapper.xml", "mapper/InstitutionIntegrationMapper.xml",
                "mapper/EndUserMapper.xml", "mapper/SessionMapper.xml", "mapper/ServicePrincipalMapper.xml")) {
            try (var xml = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(xml, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        sqlSessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
    }

    @BeforeEach
    void fixture() throws Exception {
        primary = domain(tenant(), "primary");
        sameTenant = domain(primary.tenant(), "other-integration");
        foreignTenant = domain(tenant(), "foreign-tenant");
        var principals = new ServicePrincipalService(sqlSessions.getMapper(ServicePrincipalMapper.class), mock(CryptoService.class));
        var integrations = new InstitutionIntegrationService(sqlSessions.getMapper(InstitutionIntegrationMapper.class),
                sqlSessions.getMapper(TenantMapper.class), principals, mock(PlatformManagementService.class));
        users = new EndUserService(sqlSessions.getMapper(EndUserMapper.class),org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry.class));
        sessions = new SessionService(sqlSessions.getMapper(SessionMapper.class),
                mock(OperationalSettingsService.class), mock(SessionAccessPolicy.class));
        groups = new GroupBindingService(sqlSessions.getMapper(GroupBindingMapper.class), integrations,
                users, sessions, mock(GroupAgentEligibility.class), new AuditService(sqlSessions.getMapper(AuditLogMapper.class)),
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @AfterEach
    void clearContext() {
        ServicePrincipalContext.clear();
        TenantContext.clear();
    }

    @Test
    void restrictedRoleAndForceRlsHideOtherTenantsWithoutRelyingOnServiceFilters() {
        var own = ensure(primary, "rls-own", "life-1");
        var other = ensure(foreignTenant, "rls-other", "life-1");
        assertTrue(owner.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='group_domain'", Boolean.class));
        for (String table : List.of("nw_group_bindings", "nw_group_memberships", "nw_group_service_agents", "nw_group_binding_requests")) {
            assertTrue(owner.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid=?::regclass", Boolean.class, table));
        }
        assertEquals(0L, restrictedSql.queryForObject("SELECT count(*) FROM nw_group_bindings", Long.class));
        assertEquals(1L, direct(primary, () -> restrictedSql.queryForObject("SELECT count(*) FROM nw_group_bindings", Long.class)));
        assertNull(in(primary, () -> sqlSessions.getMapper(GroupBindingMapper.class).find(
                foreignTenant.tenant(), foreignTenant.integration(), Long.parseLong(other.groupBindingId()))));
        assertEquals(0, direct(primary, () -> restrictedSql.update("UPDATE nw_group_bindings SET status='closed',revision=revision+1 WHERE id=?",
                Long.parseLong(other.groupBindingId()))));
        assertEquals("active", in(foreignTenant, () -> groups.detail(other.groupBindingId())).status());
        assertForbidden(() -> in(sameTenant, () -> groups.detail(own.groupBindingId())));
        assertForbidden(() -> in(foreignTenant, () -> groups.detail(own.groupBindingId())));
        assertEquals(own, in(primary, () -> groups.detail(own.groupBindingId())));
    }

    @Test
    void concurrentEnsureCreatesOneBindingOneSessionAndNoSyntheticEndUser() throws Exception {
        var command = new Ensure("wecom", "concurrent", "life-1", primary.scope());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        GroupView first;
        GroupView second;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return in(primary, () -> groups.ensure(command, "ensure-left")); });
            var right = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return in(primary, () -> groups.ensure(command, "ensure-right")); });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first = left.get(30, TimeUnit.SECONDS);
            second = right.get(30, TimeUnit.SECONDS);
        }
        assertEquals(first, second);
        assertEquals(1L, count("nw_group_bindings", primary));
        assertEquals(1L, count("nw_chat_sessions", primary));
        assertEquals(0L, owner.queryForObject("SELECT count(*) FROM nw_end_users WHERE tenant_id=?", Long.class, primary.tenant()));
        var session = in(primary, () -> sessions.requireForSystem(primary.tenantId(), first.sessionId()));
        assertEquals(Long.valueOf(first.groupBindingId()), session.getGroupBindingId());
        assertNull(session.getEndUserId());
        assertNull(session.getAgentId());
        assertNull(session.getTeamId());
        assertNull(session.getChannelId());
        var storedSession = in(primary, () -> sqlSessions.getMapper(SessionMapper.class).selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.skillforge.core.chat.persistence.SessionDO>()
                        .eq(com.skillforge.core.chat.persistence.SessionDO::getTenantId, primary.tenant())
                        .eq(com.skillforge.core.chat.persistence.SessionDO::getSessionId, first.sessionId())));
        assertNotNull(storedSession);
        assertNull(storedSession.getExpiredAt());
        assertEquals("external", session.getOriginKind());
        assertEquals("service_principal:" + primary.principal(), session.getActorRef());
        assertEquals("tenant:" + primary.tenant(), session.getResponsibilityRef());
        assertEquals(new Subject("group", first.groupBindingId()), first.subject());
    }

    @Test
    void compositeForeignKeysRejectForeignMappedUsersAgentsAndOrphanSessions() {
        var group = ensure(primary, "composite-fk", "life-1");
        long otherIntegrationUser = user(sameTenant, "cross-integration");
        long otherTenantUser = user(foreignTenant, "cross-tenant");
        long otherAgent = agent(foreignTenant);
        for (long invalidUser : List.of(otherIntegrationUser, otherTenantUser)) {
            var failure = assertThrows(RuntimeException.class, () -> direct(primary, () -> restrictedSql.update("""
                    INSERT INTO nw_group_memberships(tenant_id,integration_id,group_binding_id,end_user_id,role,status,
                        revision,source_event_id,source_revision,actor_ref) VALUES(?,?,?,?,'member','active',1,?,1,?)
                    """, primary.tenant(), primary.integration(), Long.parseLong(group.groupBindingId()), invalidUser,
                    "forged-user-" + invalidUser, primary.actor())));
            assertSqlState(failure, "23503");
        }
        var agentFailure = assertThrows(RuntimeException.class, () -> direct(primary, () -> restrictedSql.update("""
                INSERT INTO nw_group_service_agents(tenant_id,integration_id,group_binding_id,agent_id,status,
                    revision,source_event_id,source_revision,actor_ref) VALUES(?,?,?,?,'active',1,'forged-agent',1,?)
                """, primary.tenant(), primary.integration(), Long.parseLong(group.groupBindingId()), otherAgent, primary.actor())));
        assertSqlState(agentFailure, "23503");
        var orphan = assertThrows(RuntimeException.class, () -> in(primary,
                () -> sessions.createGroup(primary.tenantId(), primary.integration(), primary.scope(), Long.toString(SnowflakeIds.nextId()))));
        assertSqlState(orphan, "23503");
        assertEquals(1L, count("nw_chat_sessions", primary));
        assertEquals(0L, count("nw_group_memberships", primary));
        assertEquals(0L, count("nw_group_service_agents", primary));
    }

    @Test
    void memberCasSourceReceiptsAndTombstonesNeverResurrectAnExitedMember() {
        var group = ensure(primary, "member-history", "life-1");
        long user = user(primary, "member-history-user");
        var originalCommand = new UpdateMember(group.groupBindingId(), user, "manager", "active", 0L, "member-joined", 1L);
        var original = in(primary, () -> groups.updateMember(originalCommand, "member-join"));
        assertEquals(original, in(primary, () -> groups.requireMember(primary.tenantId(), primary.integration(), group.groupBindingId(), user, true)));
        var left = in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                "member", "left", 1L, "member-left", 2L), "member-leave"));
        assertEquals(2, left.revision());
        assertEquals("left", left.status());
        assertConflict(() -> in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                "manager", "active", 0L, "member-after-stale-cas", 3L), "member-stale-cas")));
        assertConflict(() -> in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                "manager", "active", 2L, "different-event-old-version", 1L), "member-stale-source")));
        assertConflict(() -> in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                "member", "active", 0L, "member-joined", 1L), "member-changed-fingerprint")));
        assertConflict(() -> in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                "member", "active", 0L, "member-joined", 1L), "member-join")));
        assertEquals(original, in(primary, () -> groups.updateMember(originalCommand, "member-join")));
        assertEquals(original, in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                "manager", "active", 999L, "member-joined", 1L), "member-source-alias")));
        assertForbidden(() -> in(primary, () -> groups.requireMember(primary.tenantId(), primary.integration(), group.groupBindingId(), user, false)));
        assertEquals(left, currentMember(primary, group, user));
        var closed = in(primary, () -> groups.close(new Close(group.groupBindingId(), 1L), "member-group-close"));
        assertEquals("closed", closed.status());
        assertEquals(original, in(primary, () -> groups.updateMember(originalCommand, "member-join")));
        assertEquals(original, in(primary, () -> groups.updateMember(originalCommand, "member-source-alias-after-close")));
        assertEquals(left, currentMember(primary, group, user));
        assertThrows(RuntimeException.class, () -> owner.update(
                "UPDATE nw_group_binding_requests SET fingerprint=? WHERE tenant_id=? AND integration_id=? AND request_key='member-join'",
                "b".repeat(64), primary.tenant(), primary.integration()));
        assertThrows(RuntimeException.class, () -> owner.update("DELETE FROM nw_group_memberships WHERE tenant_id=? AND group_binding_id=?",
                primary.tenant(), Long.parseLong(group.groupBindingId())));
    }

    @Test
    void agentCasAndSourceDeduplicationAreScopedToTheExactProjectedObject() {
        var group = ensure(primary, "object-sources", "life-1");
        long firstUser = user(primary, "first-member");
        long secondUser = user(primary, "second-member");
        long firstAgent = agent(primary);
        long secondAgent = agent(primary);
        for (long user : List.of(firstUser, secondUser)) {
            in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                    "member", "active", 0L, "shared-source-event", 1L), "member-" + user));
        }
        assertForbidden(() -> in(primary, () -> groups.requireMember(primary.tenantId(), primary.integration(), group.groupBindingId(), secondUser, true)));
        var originalCommand = new UpdateAgent(group.groupBindingId(), firstAgent, "active", 0L, "shared-source-event", 1L);
        var original = in(primary, () -> groups.updateAgent(originalCommand, "first-agent"));
        var second = in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), secondAgent,
                "active", 0L, "shared-source-event", 1L), "second-agent"));
        assertEquals(1, second.revision());
        assertEquals(original, in(primary, () -> groups.requireServiceAgent(primary.tenantId(), primary.integration(), group.groupBindingId(), firstAgent)));
        var removed = in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), firstAgent,
                "removed", 1L, "agent-removed", 2L), "remove-agent"));
        assertEquals(2, removed.revision());
        assertConflict(() -> in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), firstAgent,
                "active", 1L, "agent-stale-cas", 3L), "agent-stale-cas")));
        assertConflict(() -> in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), firstAgent,
                "active", 2L, "agent-stale-source", 1L), "agent-stale-source")));
        assertConflict(() -> in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), firstAgent,
                "removed", 0L, "shared-source-event", 1L), "agent-changed-source")));
        assertEquals(original, in(primary, () -> groups.updateAgent(originalCommand, "first-agent")));
        assertEquals(original, in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), firstAgent,
                "active", 999L, "shared-source-event", 1L), "agent-source-alias")));
        assertForbidden(() -> in(primary, () -> groups.requireServiceAgent(primary.tenantId(), primary.integration(), group.groupBindingId(), firstAgent)));
        assertEquals(removed, currentAgent(primary, group, firstAgent));
        assertEquals(second, currentAgent(primary, group, secondAgent));
        assertEquals(2L, count("nw_group_memberships", primary));
        assertEquals(2L, count("nw_group_service_agents", primary));
    }

    @Test
    void simultaneousMemberUpdatesHaveExactlyOneCasWinner() throws Exception {
        var group = ensure(primary, "member-cas-race", "life-1");
        long user = user(primary, "race-user");
        in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                "member", "active", 0L, "initial", 1L), "initial-member"));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var promote = pool.submit(() -> compete(ready, start, () -> groups.updateMember(new UpdateMember(group.groupBindingId(),
                    user, "manager", "active", 1L, "promote", 2L), "promote")));
            var leave = pool.submit(() -> compete(ready, start, () -> groups.updateMember(new UpdateMember(group.groupBindingId(),
                    user, "member", "left", 1L, "leave", 3L), "leave")));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, List.of(promote.get(30, TimeUnit.SECONDS), leave.get(30, TimeUnit.SECONDS))
                    .stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(2, currentMember(primary, group, user).revision());
        assertEquals(2L, owner.queryForObject("""
                SELECT count(*) FROM nw_group_binding_requests WHERE tenant_id=? AND integration_id=?
                    AND group_binding_id=? AND operation='member.update'
                """, Long.class, primary.tenant(), primary.integration(), Long.parseLong(group.groupBindingId())));
    }

    @Test
    void closeSerializesWithMemberAndAgentUpdatesAndClosedStateIsTerminal() throws Exception {
        var group = ensure(primary, "close-race", "life-1");
        long user = user(primary, "close-race-user");
        long agent = agent(primary);
        in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                "member", "active", 0L, "initial-member", 1L), "initial-member"));
        in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), agent,
                "active", 0L, "initial-agent", 1L), "initial-agent"));
        var ready = new CountDownLatch(3);
        var start = new CountDownLatch(1);
        boolean memberUpdated;
        boolean agentUpdated;
        try (var pool = Executors.newFixedThreadPool(3)) {
            var close = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return in(primary, () -> groups.close(new Close(group.groupBindingId(), 1L), "close-race")); });
            var member = pool.submit(() -> compete(ready, start, () -> groups.updateMember(new UpdateMember(group.groupBindingId(),
                    user, "manager", "active", 1L, "race-member", 2L), "race-member")));
            var service = pool.submit(() -> compete(ready, start, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(),
                    agent, "removed", 1L, "race-agent", 2L), "race-agent")));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals("closed", close.get(30, TimeUnit.SECONDS).status());
            memberUpdated = member.get(30, TimeUnit.SECONDS);
            agentUpdated = service.get(30, TimeUnit.SECONDS);
        }
        assertEquals(memberUpdated ? 2 : 1, currentMember(primary, group, user).revision());
        assertEquals(agentUpdated ? 2 : 1, currentAgent(primary, group, agent).revision());
        assertConflict(() -> in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                "member", "active", memberUpdated ? 2L : 1L, "after-close-member", 3L), "after-close-member")));
        assertConflict(() -> in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), agent,
                "active", agentUpdated ? 2L : 1L, "after-close-agent", 3L), "after-close-agent")));
        assertTrue(in(primary, () -> sessions.requireForSystem(primary.tenantId(), group.sessionId()).getMetadata().containsKey("closed_at")));
        assertThrows(RuntimeException.class, () -> owner.update("UPDATE nw_group_bindings SET status='active',revision=revision+1 WHERE id=?", Long.parseLong(group.groupBindingId())));
        assertThrows(RuntimeException.class, () -> owner.update("UPDATE nw_chat_sessions SET metadata='{}'::jsonb WHERE tenant_id=? AND session_id=?", primary.tenant(), group.sessionId()));
    }

    @Test
    void closeRetainsOriginalReceiptsAndNewLifecycleHasIndependentSessionAndNoInheritedProjection() {
        var command = new Ensure("wecom", "reused-external-id", "life-1", primary.scope());
        var original = in(primary, () -> groups.ensure(command, "original-ensure"));
        long user = user(primary, "lifecycle-user");
        long agent = agent(primary);
        in(primary, () -> groups.updateMember(new UpdateMember(original.groupBindingId(), user,
                "manager", "active", 0L, "life-member", 1L), "life-member"));
        in(primary, () -> groups.updateAgent(new UpdateAgent(original.groupBindingId(), agent,
                "active", 0L, "life-agent", 1L), "life-agent"));
        assertConflict(() -> in(primary, () -> groups.ensure(new Ensure("wecom", "reused-external-id", "life-2", primary.scope()), "overlapping-life")));
        var close = new Close(original.groupBindingId(), 1L);
        var closed = in(primary, () -> groups.close(close, "close-old-life"));
        assertEquals(2, closed.revision());
        assertEquals(closed, in(primary, () -> groups.close(close, "close-old-life")));
        assertEquals(original, in(primary, () -> groups.ensure(command, "original-ensure")));
        assertEquals(closed, in(primary, () -> groups.ensure(command, "fresh-command-old-life")));
        var fresh = ensure(primary, "reused-external-id", "life-2");
        assertNotEquals(original.groupBindingId(), fresh.groupBindingId());
        assertNotEquals(original.sessionId(), fresh.sessionId());
        assertEquals(1, fresh.revision());
        assertEquals("active", fresh.status());
        assertNull(in(primary, () -> sqlSessions.getMapper(GroupBindingMapper.class).member(primary.tenant(), primary.integration(),
                Long.parseLong(fresh.groupBindingId()), user)));
        assertNull(in(primary, () -> sqlSessions.getMapper(GroupBindingMapper.class).agent(primary.tenant(), primary.integration(),
                Long.parseLong(fresh.groupBindingId()), agent)));
        assertForbidden(() -> in(primary, () -> groups.requireMember(primary.tenantId(), primary.integration(), fresh.groupBindingId(), user, false)));
        assertForbidden(() -> in(primary, () -> groups.requireServiceAgent(primary.tenantId(), primary.integration(), fresh.groupBindingId(), agent)));
        assertEquals("closed", in(primary, () -> groups.detail(original.groupBindingId())).status());
        assertEquals(2L, count("nw_chat_sessions", primary));
        assertEquals(1L, owner.queryForObject("SELECT count(*) FROM nw_end_users WHERE tenant_id=?", Long.class, primary.tenant()));
    }

    @Test
    void sameTenantOtherIntegrationCannotReadCloseProjectOrReuseBusinessScope() {
        var group = ensure(primary, "integration-guard", "life-1");
        long ownUser = user(primary, "own-user");
        long otherUser = user(sameTenant, "other-user");
        long ownAgent = agent(primary);
        assertForbidden(() -> in(sameTenant, () -> groups.detail(group.groupBindingId())));
        assertForbidden(() -> in(sameTenant, () -> groups.close(new Close(group.groupBindingId(), 1L), "cross-close")));
        assertForbidden(() -> in(sameTenant, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), otherUser,
                "member", "active", 0L, "cross-member", 1L), "cross-member")));
        assertForbidden(() -> in(sameTenant, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), ownAgent,
                "active", 0L, "cross-agent", 1L), "cross-agent")));
        assertThrows(ApiException.class, () -> in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), otherUser,
                "member", "active", 0L, "cross-user", 1L), "cross-user")));
        assertThrows(ApiException.class, () -> in(sameTenant, () -> groups.ensure(new Ensure("wecom", "invalid-scope", "life-1", primary.scope()), "cross-scope")));
        assertEquals(0L, count("nw_group_memberships", primary));
        assertEquals(0L, count("nw_group_service_agents", primary));
        assertEquals(group, in(primary, () -> groups.detail(group.groupBindingId())));
        assertEquals(ownUser, in(primary, () -> users.requireExternal(primary.tenantId(), primary.integration(), ownUser, true).getId()));
    }

    @Test
    void suspendedBusinessScopeRejectsJoiningAndRestoringButAllowsLeavingRemovalAndClose() {
        var group = ensure(primary, "suspended-scope", "life-1");
        long activeUser = user(primary, "active-before-suspend");
        long newUser = user(primary, "new-after-suspend");
        long activeAgent = agent(primary);
        long newAgent = agent(primary);
        var initialMember = in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), activeUser,
                "manager", "active", 0L, "before-suspend-member", 1L), "before-suspend-member"));
        var initialAgent = in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), activeAgent,
                "active", 0L, "before-suspend-agent", 1L), "before-suspend-agent"));
        assertEquals(1, owner.update("""
                UPDATE nw_integration_business_scopes SET status='suspended',revision=revision+1
                WHERE tenant_id=? AND integration_id=? AND scope_id=? AND status='active'
                """, primary.tenant(), primary.integration(), primary.scope()));
        assertForbidden(() -> in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), newUser,
                "member", "active", 0L, "blocked-new-member", 1L), "blocked-new-member")));
        assertForbidden(() -> in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), newAgent,
                "active", 0L, "blocked-new-agent", 1L), "blocked-new-agent")));
        assertEquals(1L, count("nw_group_memberships", primary));
        assertEquals(1L, count("nw_group_service_agents", primary));
        assertEquals(3L, count("nw_group_binding_requests", primary));
        assertEquals(initialMember, currentMember(primary, group, activeUser));
        assertEquals(initialAgent, currentAgent(primary, group, activeAgent));
        var left = in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), activeUser,
                "member", "left", 1L, "leave-after-suspend", 2L), "leave-after-suspend"));
        var removed = in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), activeAgent,
                "removed", 1L, "remove-after-suspend", 2L), "remove-after-suspend"));
        assertEquals("left", left.status());
        assertEquals("removed", removed.status());
        assertForbidden(() -> in(primary, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), activeUser,
                "manager", "active", 2L, "blocked-restore-member", 3L), "blocked-restore-member")));
        assertForbidden(() -> in(primary, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), activeAgent,
                "active", 2L, "blocked-restore-agent", 3L), "blocked-restore-agent")));
        assertEquals(left, currentMember(primary, group, activeUser));
        assertEquals(removed, currentAgent(primary, group, activeAgent));
        assertEquals(5L, count("nw_group_binding_requests", primary));
        var closed = in(primary, () -> groups.close(new Close(group.groupBindingId(), 1L), "close-after-suspend"));
        assertEquals("closed", closed.status());
        assertEquals(closed, in(primary, () -> groups.ensure(new Ensure("wecom", "suspended-scope", "life-1", primary.scope()),
                "ensure-closed-after-suspend")));
        assertForbidden(() -> in(primary, () -> groups.ensure(new Ensure("wecom", "suspended-scope", "life-2", primary.scope()),
                "new-life-after-suspend")));
        assertEquals(1L, count("nw_group_bindings", primary));
        assertEquals(1L, count("nw_chat_sessions", primary));
        assertTrue(in(primary, () -> sessions.requireForSystem(primary.tenantId(), group.sessionId()).getMetadata().containsKey("closed_at")));
    }

    /** 真实租户与计划只用于新建容器的隔离夹具，业务调用全部使用受限连接。 */
    private long tenant() {
        long tenant = SnowflakeIds.nextId();
        Long plan = owner.queryForObject("SELECT id FROM nw_billing_plans WHERE is_active ORDER BY id LIMIT 1", Long.class);
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status,deployment_mode,settings) VALUES(?,?,?,?,'active','cloud','{}')",
                tenant, "group institution " + tenant, "group-test-" + tenant, plan);
        return tenant;
    }

    /** 每个执行域拥有真实业务范围及持久凭据，跨 integration 不共享身份。 */
    private Domain domain(long tenant, String label) throws Exception {
        String integration = label + "-" + SnowflakeIds.nextId();
        String scope = Long.toString(SnowflakeIds.nextId());
        String principal = "sp_group_" + SnowflakeIds.nextId();
        owner.update("INSERT INTO nw_institution_integrations(tenant_id,integration_id) VALUES(?,?)", tenant, integration);
        owner.update("""
                INSERT INTO nw_integration_business_scopes(scope_id,tenant_id,integration_id,scope_type,external_ref,
                    display_name,status,revision,actor_ref) VALUES(?,?,?,'business',?,?,'active',1,?)
                """, scope, tenant, integration, "scope-" + scope, label, "service_principal:" + principal);
        owner.update("""
                INSERT INTO nw_service_principals(id,tenant_id,principal_id,integration_id,api_key_hash,api_key_prefix,
                    signing_secret_encrypted,scopes,status,credential_version)
                VALUES(?,?,?,?,?,?,?,?::jsonb,'active',1)
                """, SnowflakeIds.nextId(), tenant, principal, integration,
                String.format("%064d", SnowflakeIds.nextId()), "group_fixture", "unused-test-secret",
                new ObjectMapper().writeValueAsString(SCOPES));
        return new Domain(tenant, integration, scope, principal);
    }

    private GroupView ensure(Domain domain, String external, String lifecycle) {
        return in(domain, () -> groups.ensure(new Ensure("wecom", external, lifecycle, domain.scope()),
                "ensure-" + external + "-" + lifecycle));
    }

    private long user(Domain domain, String ref) {
        return in(domain, () -> users.ensureExternal(domain.tenantId(), domain.integration(), ref, ref).getId());
    }

    private long agent(Domain domain) {
        long agent = SnowflakeIds.nextId();
        owner.update("INSERT INTO nw_agents(id,agent_key,tenant_id,name,status) VALUES(?,?,?,?,'draft')",
                agent, "group-agent-" + agent, domain.tenant(), "group agent " + agent);
        return agent;
    }

    private MemberView currentMember(Domain domain, GroupView group, long user) {
        var row = in(domain, () -> sqlSessions.getMapper(GroupBindingMapper.class).member(domain.tenant(), domain.integration(),
                Long.parseLong(group.groupBindingId()), user));
        assertNotNull(row);
        return new MemberView(group.groupBindingId(), row.endUserId(), row.role(), row.status(), row.revision(), row.sourceEventId(), row.sourceRevision());
    }

    private AgentView currentAgent(Domain domain, GroupView group, long agent) {
        var row = in(domain, () -> sqlSessions.getMapper(GroupBindingMapper.class).agent(domain.tenant(), domain.integration(),
                Long.parseLong(group.groupBindingId()), agent));
        assertNotNull(row);
        return new AgentView(group.groupBindingId(), row.agentId(), row.status(), row.revision(), row.sourceEventId(), row.sourceRevision());
    }

    private boolean compete(CountDownLatch ready, CountDownLatch start, Supplier<?> action) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            in(primary, action);
            return true;
        } catch (ApiException rejected) {
            assertEquals(409, rejected.getStatus().value());
            return false;
        }
    }

    private long count(String table, Domain domain) {
        assertTrue(Set.of("nw_group_bindings", "nw_chat_sessions", "nw_group_memberships", "nw_group_service_agents", "nw_group_binding_requests").contains(table));
        return owner.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND integration_id=?",
                Long.class, domain.tenant(), domain.integration());
    }

    /** 每次领域调用都有独立真实事务，并在工作线程退出时清除可信上下文。 */
    private <T> T in(Domain domain, Supplier<T> action) {
        try {
            TenantContext.setTenantId(domain.tenantId());
            ServicePrincipalContext.set(new ServicePrincipalContext.Identity(domain.principal(), domain.tenantId(),
                    domain.integration(), 1, SCOPES));
            return writer.execute(status -> action.get());
        } finally {
            clearContext();
        }
    }

    /** 仅用于证明数据库约束，直接 SQL 同样使用 NOSUPERUSER/NOBYPASSRLS 角色。 */
    private <T> T direct(Domain domain, Supplier<T> action) {
        return in(domain, () -> {
            restrictedSql.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, domain.tenantId());
            return action.get();
        });
    }

    private void assertForbidden(Supplier<?> action) {
        var denied = assertThrows(ApiException.class, action::get);
        assertEquals(403, denied.getStatus().value());
    }

    private void assertConflict(Supplier<?> action) {
        var conflict = assertThrows(ApiException.class, action::get);
        assertEquals(409, conflict.getStatus().value());
    }

    private void assertSqlState(Throwable error, String expected) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && expected.equals(sql.getSQLState())) return;
        }
        fail("Expected PostgreSQL SQLSTATE " + expected, error);
    }

    private record Domain(long tenant, String integration, String scope, String principal) {
        String tenantId() { return Long.toString(tenant); }
        String actor() { return "service_principal:" + principal; }
    }
}

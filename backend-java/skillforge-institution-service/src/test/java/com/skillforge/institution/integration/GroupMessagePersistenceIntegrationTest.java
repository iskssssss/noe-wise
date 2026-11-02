package com.skillforge.institution.integration;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
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
import com.skillforge.institution.group.GroupExecutionAdmission;
import com.skillforge.institution.group.GroupMessageContracts.*;
import com.skillforge.institution.group.GroupMessageMapper;
import com.skillforge.institution.group.GroupHistoryMapper;
import com.skillforge.institution.group.GroupHistorySourcePublisher;
import com.skillforge.institution.group.GroupControlMapper;
import com.skillforge.institution.group.GroupControlService;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionOutboxSequenceMapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryMapper;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionWebhookDeliveryLedger;
import com.skillforge.institution.group.GroupMessageService;
import com.skillforge.institution.group.RuntimeGroupMessageSourceValidator;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.skillforge.institution.runtime.RuntimeWorkerRecoveryMapper;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.SourceMessageRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 群消息与前台触发的真实持久化验收：正式迁移、正式 Mapper 与事务、受限 RLS 角色。
 * 凭据当前状态、EndUser 和 Session 都读取真实表；Agent 发布资格由独立资格测试负责。
 */
@Testcontainers(disabledWithoutDocker = false)
class GroupMessagePersistenceIntegrationTest {
    private static final Set<String> SCOPES = Set.of("groups.write", "groups.read",
            "groups.members.write", "groups.agents.write", "groups.messages.write", "groups.triggers.write", "groups.triggers.read");

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("group_message_persistence")
            .withUsername("skillforge").withPassword("isolated-message-owner-password");

    private static JdbcTemplate owner;
    private static JdbcTemplate restrictedSql;
    private static DriverManagerDataSource runtimeSource;
    private static TransactionTemplate writer;
    private static PlatformTransactionManager transactions;
    private static SqlSessionTemplate sqlSessions;
    private GroupBindingService groups;
    private InstitutionIntegrationService integrations;
    private EndUserService users;
    private SessionService sessions;
    private GroupMessageService messages;
    private JdbcExecutionStore runtimeStore;
    private final AtomicBoolean failAdmissionAfterGuard = new AtomicBoolean();
    private final AtomicReference<GroupExecutionAdmission.Candidate> admittedCandidate = new AtomicReference<>();
    private Domain primary;
    private Domain sameTenant;
    private Domain foreignTenant;

    @BeforeAll
    static void database() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE group_message_domain LOGIN PASSWORD 'isolated-message-role-password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO group_message_domain");
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO group_message_domain");
        owner.execute("""
                CREATE FUNCTION group_message_test_fail_pending() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF current_setting('test.fail_group_pending',true)='true' THEN
                        RAISE EXCEPTION 'fixture_group_pending_failed' USING ERRCODE='P0001';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        owner.execute("CREATE TRIGGER group_message_test_fail_pending BEFORE INSERT ON nw_group_memory_extraction_pending FOR EACH ROW EXECUTE FUNCTION group_message_test_fail_pending()");
        owner.execute("""
                CREATE FUNCTION group_message_test_fail_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF current_setting('test.fail_group_outbox',true)='true' AND NEW.event_id LIKE 'output:group-source:%' THEN
                        RAISE EXCEPTION 'fixture_group_outbox_failed' USING ERRCODE='P0001';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        owner.execute("CREATE TRIGGER group_message_test_fail_outbox BEFORE INSERT ON nw_chat_session_outbox FOR EACH ROW EXECUTE FUNCTION group_message_test_fail_outbox()");
        var restricted = new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                "group_message_domain", "isolated-message-role-password");
        runtimeSource = restricted;
        restrictedSql = new JdbcTemplate(restricted);
        transactions = new DataSourceTransactionManager(restricted);
        writer = new TransactionTemplate(transactions);
        var configuration = new MybatisConfiguration(new Environment("group-domain",
                new SpringManagedTransactionFactory(), restricted));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.addInterceptor(new TenantRlsInterceptor());
        for (Class<?> mapper : List.of(TenantMapper.class, AuditLogMapper.class,
                EndUserMapper.class, ServicePrincipalMapper.class, SessionMapper.class, SessionOutboxMapper.class)) {
            configuration.addMapper(mapper);
        }
        for (String path : List.of("mapper/GroupBindingMapper.xml", "mapper/InstitutionIntegrationMapper.xml",
                "mapper/EndUserMapper.xml", "mapper/SessionMapper.xml", "mapper/ServicePrincipalMapper.xml",
                "mapper/GroupMessageMapper.xml", "mapper/GroupHistoryMapper.xml", "mapper/RuntimeWorkerRecoveryMapper.xml")) {
            try (var xml = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(xml, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        SessionMailboxService.configureBorrowed(configuration);
        GroupControlMapper.configure(configuration);
        sqlSessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
    }

    @BeforeEach
    void fixture() throws Exception {
        primary = domain(tenant(), "primary");
        sameTenant = domain(primary.tenant(), "other-integration");
        foreignTenant = domain(tenant(), "foreign-tenant");
        var principals = new ServicePrincipalService(sqlSessions.getMapper(ServicePrincipalMapper.class), mock(CryptoService.class));
        integrations = new InstitutionIntegrationService(sqlSessions.getMapper(InstitutionIntegrationMapper.class),
                sqlSessions.getMapper(TenantMapper.class), principals, mock(PlatformManagementService.class));
        users = new EndUserService(sqlSessions.getMapper(EndUserMapper.class),org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry.class));
        sessions = new SessionService(sqlSessions.getMapper(SessionMapper.class),
                mock(OperationalSettingsService.class), mock(SessionAccessPolicy.class));
        groups = new GroupBindingService(sqlSessions.getMapper(GroupBindingMapper.class), integrations,
                users, sessions, mock(GroupAgentEligibility.class), new AuditService(sqlSessions.getMapper(AuditLogMapper.class)),
                new ObjectMapper().registerModule(new JavaTimeModule()));
        runtimeStore = newRuntimeStore();
        messages = newMessages();
    }

    @AfterEach
    void clearContext() {
        ServicePrincipalContext.clear();
        TenantContext.clear();
    }

    @Test
    void immutableSourceRetainsOriginalReceiptsAndReliablePendingWithoutCreatingRun() {
        var group = activeGroup(primary, "immutable-source");
        var command = message(group, group.firstMember(), "external-event", 7L, "第一段", "第二段");
        var original = in(primary, () -> messages.record(command, "original-message"));
        assertEquals(1, original.sourceSequence());
        assertEquals(group.group().sessionId(), original.sessionId());
        assertEquals(original, in(primary, () -> messages.record(command, "original-message")));
        assertEquals(original, in(primary, () -> messages.record(command, "same-source-new-key")));
        assertConflict(() -> in(primary, () -> messages.record(message(group, group.firstMember(), "external-event", 8L, "第一段", "第二段"), "changed-source-revision")));
        assertConflict(() -> in(primary, () -> messages.record(message(group, group.firstMember(), "external-event", 7L, "第二段", "第一段"), "changed-content-order")));
        assertConflict(() -> in(primary, () -> messages.record(message(group, group.secondMember(), "different-event", 1L, "不同请求"), "original-message")));
        assertEquals(1L, count("nw_group_source_messages", primary));
        assertEquals(1L, count("nw_group_memory_extraction_pending", primary));
        assertEquals(1L,owner.queryForObject("SELECT count(*) FROM nw_chat_session_outbox WHERE tenant_id=? AND session_id=?",Long.class,primary.tenant(),group.group().sessionId()));
        assertEquals(0L, count("nw_group_triggers", primary));
        assertEquals(0L, runtimeRuns(primary));
        var stored = source(primary, group, original.sourceMessageId());
        assertEquals(group.firstMember(), stored.authorEndUserId());
        assertEquals("end_user", stored.authorKind());
        assertTrue(stored.contentJson().indexOf("第一段") < stored.contentJson().indexOf("第二段"));
        assertSqlState(assertThrows(RuntimeException.class, () -> owner.update(
                "UPDATE nw_group_source_messages SET source_revision=source_revision+1 WHERE tenant_id=? AND source_message_id=?",
                primary.tenant(), original.sourceMessageId())), "23514");
        assertSqlState(assertThrows(RuntimeException.class, () -> owner.update(
                "DELETE FROM nw_group_source_messages WHERE tenant_id=? AND source_message_id=?",
                primary.tenant(), original.sourceMessageId())), "23514");
        in(primary, () -> groups.close(new Close(group.group().groupBindingId(), 1L), "close-source-group"));
        assertEquals(original, in(primary, () -> messages.record(command, "source-retry-after-close")));
        assertConflict(() -> in(primary, () -> messages.record(message(group, group.firstMember(), "new-after-close", 1L, "新来源"), "new-after-close")));
    }

    @Test
    void simultaneousSourcesDeduplicateAndAllocateContiguousAuthoritativeSequences() throws Exception {
        var group = activeGroup(primary, "source-sequences");
        var shared = message(group, group.firstMember(), "same-event", 1L, "相同来源");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return in(primary, () -> messages.record(shared, "same-source-left")); });
            var right = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return in(primary, () -> messages.record(shared, "same-source-right")); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            assertEquals(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
        }
        var moreReady = new CountDownLatch(2);
        var moreStart = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { moreReady.countDown(); assertTrue(moreStart.await(10, TimeUnit.SECONDS));
                return record(primary, group, group.firstMember(), "different-a", "第一位成员"); });
            var second = pool.submit(() -> { moreReady.countDown(); assertTrue(moreStart.await(10, TimeUnit.SECONDS));
                return record(primary, group, group.secondMember(), "different-b", "第二位成员"); });
            assertTrue(moreReady.await(10, TimeUnit.SECONDS)); moreStart.countDown();
            assertEquals(Set.of(2L, 3L), Set.of(first.get(30, TimeUnit.SECONDS).sourceSequence(), second.get(30, TimeUnit.SECONDS).sourceSequence()));
        }
        var history = in(primary, () -> mapper().history(primary.tenant(), primary.integration(), id(group), 3));
        assertEquals(List.of(1L, 2L, 3L), history.stream().map(GroupMessageMapper.Message::sourceSequence).toList());
        assertEquals(3L, count("nw_group_memory_extraction_pending", primary));
        assertEquals(0L, runtimeRuns(primary));
    }

    @Test
    void restrictedRlsAndDomainChecksRejectCrossTenantIntegrationAndForgedAuthors() {
        var group = activeGroup(primary, "isolated-source");
        var foreign = activeGroup(foreignTenant, "foreign-source");
        var ownSource = record(primary, group, group.firstMember(), "own", "当前机构");
        var otherSource = record(foreignTenant, foreign, foreign.firstMember(), "other", "其他机构");
        assertTrue(owner.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='group_message_domain'", Boolean.class));
        for (String table : List.of("nw_group_front_slots", "nw_group_source_messages", "nw_group_triggers", "nw_group_message_requests", "nw_group_memory_extraction_pending")) {
            assertTrue(owner.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid=?::regclass", Boolean.class, table));
        }
        assertEquals(0L, restrictedSql.queryForObject("SELECT count(*) FROM nw_group_source_messages", Long.class));
        assertNull(in(primary, () -> mapper().message(foreignTenant.tenant(), foreignTenant.integration(), id(foreign), otherSource.sourceMessageId())));
        assertEquals(0, direct(primary, () -> restrictedSql.update("DELETE FROM nw_group_source_messages WHERE tenant_id=?", foreignTenant.tenant())));
        assertForbidden(() -> in(sameTenant, () -> messages.record(message(group, group.firstMember(), "cross-integration", 1L, "越界"), "cross-integration")));
        long outsider = user(primary, "not-a-member");
        assertForbidden(() -> in(primary, () -> messages.record(message(group, outsider, "not-a-member", 1L, "无成员资格"), "not-a-member")));
        for (Author author : List.of(new AgentAuthor(group.firstAgent(), "fabricated-run", "fabricated-message"),
                new ToolAuthor(group.firstAgent(), "fabricated-run", "fabricated-tool-result"), new SystemAuthor("fabricated-system"))) {
            assertForbidden(() -> in(primary, () -> messages.record(new MessageRecord(group.group().groupBindingId(),
                    "forged-" + author.kind(), 1L, author, ChatContent.text("冒充公开来源")), "forged-" + author.kind())));
        }
        assertEquals(1L, count("nw_group_source_messages", primary));
        assertEquals(ownSource.sourceMessageId(), source(primary, group, ownSource.sourceMessageId()).sourceMessageId());
        assertEquals(0L, runtimeRuns(primary));
    }

    @Test
    void compositeKeysAndAuthorUnionRejectForeignUsersAgentsSourceSequenceAndSession() throws Exception {
        var group = activeGroup(primary, "message-fks");
        var source = record(primary, group, group.firstMember(), "valid-source", "组合归属");
        var original = source(primary, group, source.sourceMessageId());
        long wrongUser = user(sameTenant, "other-integration-author");
        var foreignUser = new GroupMessageMapper.Message(primary.tenant(), primary.integration(), id(group), group.group().sessionId(),
                "forged-user", 99, "forged-user-event", 1, "end_user", wrongUser, null, null, null,
                new ObjectMapper().writeValueAsString(new EndUserAuthor(wrongUser)), original.contentJson(), original.fingerprint());
        assertSqlState(assertThrows(RuntimeException.class, () -> in(primary, () -> mapper().insertMessage(foreignUser))), "23503");
        var wrongSession = new GroupMessageMapper.Message(primary.tenant(), primary.integration(), id(group), "another-session",
                "forged-session", 99, "forged-session-event", 1, "end_user", group.firstMember(), null, null, null,
                original.authorJson(), original.contentJson(), original.fingerprint());
        assertSqlState(assertThrows(RuntimeException.class, () -> in(primary, () -> mapper().insertMessage(wrongSession))), "23514");
        var mixedAuthor = new GroupMessageMapper.Message(primary.tenant(), primary.integration(), id(group), group.group().sessionId(),
                "mixed-author", 99, "mixed-author-event", 1, "end_user", group.firstMember(), group.firstAgent(), null, null,
                original.authorJson(), original.contentJson(), original.fingerprint());
        assertSqlState(assertThrows(RuntimeException.class, () -> in(primary, () -> mapper().insertMessage(mixedAuthor))), "23514");
        long foreignAgent = agent(foreignTenant);
        assertSqlState(assertThrows(RuntimeException.class, () -> in(primary, () -> mapper().insertTrigger(triggerRow(group, source, group.firstMember(), foreignAgent, 1)))), "23503");
        assertSqlState(assertThrows(RuntimeException.class, () -> in(primary, () -> mapper().insertTrigger(triggerRow(group, source, group.secondMember(), group.firstAgent(), 1)))), "23514");
        assertSqlState(assertThrows(RuntimeException.class, () -> direct(primary, () -> restrictedSql.update("""
                INSERT INTO nw_group_memory_extraction_pending(tenant_id,integration_id,group_binding_id,source_message_id,source_sequence)
                VALUES(?,?,?,?,?)
                """, primary.tenant(), sameTenant.integration(), id(group), source.sourceMessageId(), source.sourceSequence()))), "23503");
        var wrongSequence = new GroupMessageMapper.TriggerRow(primary.tenant(), primary.integration(), id(group), group.group().sessionId(),
                Long.toString(SnowflakeIds.nextId()), 1, source.sourceMessageId(), source.sourceSequence() + 1,
                group.firstMember(), group.firstAgent(), primary.principal(), 1, "queued", null, null, null);
        assertSqlState(assertThrows(RuntimeException.class, () -> in(primary, () -> mapper().insertTrigger(wrongSequence))), "23503");
        assertEquals(0L, count("nw_group_triggers", primary));
        assertEquals(1L, count("nw_group_memory_extraction_pending", primary));
    }

    @Test
    void oneSourceCreatesOneLogicalTriggerAndOriginalQueuedReceiptSurvivesAdmission() {
        var group = activeGroup(primary, "one-trigger");
        var source = record(primary, group, group.firstMember(), "one-source", "只触发一次");
        assertForbidden(() -> in(primary, () -> messages.trigger(new Trigger(group.group().groupBindingId(), source.sourceMessageId(),
                group.secondMember(), group.firstAgent()), "forged-requester")));
        var command = new Trigger(group.group().groupBindingId(), source.sourceMessageId(), group.firstMember(), group.firstAgent());
        var original = in(primary, () -> messages.trigger(command, "original-trigger"));
        assertEquals("queued", original.status()); assertNull(original.inputId()); assertNull(original.runId());
        assertEquals(original, in(primary, () -> messages.trigger(command, "original-trigger")));
        assertEquals(original, in(primary, () -> messages.trigger(command, "same-source-trigger")));
        assertConflict(() -> in(primary, () -> messages.trigger(new Trigger(group.group().groupBindingId(), source.sourceMessageId(),
                group.firstMember(), group.secondAgent()), "different-agent")));
        assertEquals(0L, runtimeRuns(primary));
        pump(primary, group);
        var admitted = detail(primary, group, original.triggerId());
        assertEquals("accepted", admitted.status()); assertNotNull(admitted.inputId()); assertNotNull(admitted.runId());
        assertEquals(original, in(primary, () -> messages.trigger(command, "original-trigger")));
        assertEquals(original, in(primary, () -> messages.trigger(command, "source-retry-after-admission")));
        assertEquals(1L, count("nw_group_triggers", primary));
        assertEquals(1L, runtimeRuns(primary));
        var run = runtimeStore.load(new RunRef(new TenantId(primary.tenantId()), admitted.runId())).orElseThrow();
        assertInstanceOf(SessionSubject.Group.class, run.identity().owner().subject());
        assertEquals(new RunRequester.EndUser(new EndUserId(group.firstMember())), run.identity().requester());
        assertEquals(new AgentId(Long.toString(group.firstAgent())), run.identity().agentId());
        assertEquals(source.sourceMessageId(), run.identity().sourceMessageRef().orElseThrow().messageId());
        assertEquals(1, admittedCandidate.get().history().size());
        assertEquals(source.sourceSequence(), admittedCandidate.get().history().getFirst().sourceSequence());
    }

    @Test
    void concurrentPumpsAdmitOnlyTheHeadAndRestartRetainsOneSharedSession() throws Exception {
        var group = activeGroup(primary, "single-front-slot");
        var firstSource = record(primary, group, group.firstMember(), "front-first", "第一个触发");
        var secondSource = record(primary, group, group.secondMember(), "front-second", "第二个触发");
        var first = trigger(primary, group, firstSource, group.firstMember(), group.firstAgent(), "front-first");
        var second = trigger(primary, group, secondSource, group.secondMember(), group.secondAgent(), "front-second");
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); pump(primary, group); return true; });
            var right = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); pump(primary, group); return true; });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            assertTrue(left.get(30, TimeUnit.SECONDS)); assertTrue(right.get(30, TimeUnit.SECONDS));
        }
        var acceptedFirst = detail(primary, group, first.triggerId());
        assertEquals("accepted", acceptedFirst.status());
        assertEquals("queued", detail(primary, group, second.triggerId()).status());
        assertEquals(acceptedFirst.runId(), slot(primary, group).activeRunId());
        assertEquals(1L, runtimeRuns(primary));
        assertEquals(List.of(firstSource.sourceMessageId()), admittedCandidate.get().history().stream()
                .map(GroupExecutionAdmission.Source::sourceMessageId).toList());
        runtimeStore = newRuntimeStore(); messages = newMessages();
        pump(primary, group);
        assertEquals("queued", detail(primary, group, second.triggerId()).status());
        assertEquals(acceptedFirst.runId(), slot(primary, group).activeRunId());
        var firstRun = runtimeStore.load(new RunRef(new TenantId(primary.tenantId()), acceptedFirst.runId())).orElseThrow();
        finish(primary, acceptedFirst.runId()); pump(primary, group);
        var acceptedSecond = detail(primary, group, second.triggerId());
        assertEquals("accepted", acceptedSecond.status());
        assertEquals(acceptedSecond.runId(), slot(primary, group).activeRunId());
        var secondRun = runtimeStore.load(new RunRef(new TenantId(primary.tenantId()), acceptedSecond.runId())).orElseThrow();
        assertEquals(firstRun.identity().owner(), secondRun.identity().owner());
        assertNotEquals(firstRun.identity().requester(), secondRun.identity().requester());
        assertNotEquals(firstRun.identity().agentId(), secondRun.identity().agentId());
        assertEquals(List.of(firstSource.sourceMessageId(), secondSource.sourceMessageId()),
                admittedCandidate.get().history().stream().map(GroupExecutionAdmission.Source::sourceMessageId).toList());
        assertEquals(2L, runtimeRuns(primary));
    }

    @Test
    void queuedMemberOrServiceAgentRevocationPersistsRejectionWithoutAnotherRun() {
        for (String revoked : List.of("member", "agent")) {
            long previousRuns = runtimeRuns(primary);
            var group = activeGroup(primary, "queued-revoke-" + revoked);
            var firstSource = record(primary, group, group.firstMember(), "before-" + revoked, "当前运行");
            var queuedSource = record(primary, group, group.secondMember(), "queued-" + revoked, "排队运行");
            var first = trigger(primary, group, firstSource, group.firstMember(), group.firstAgent(), "before-" + revoked);
            var queued = trigger(primary, group, queuedSource, group.secondMember(), group.secondAgent(), "queued-" + revoked);
            pump(primary, group);
            if (revoked.equals("member")) in(primary, () -> groups.updateMember(new UpdateMember(group.group().groupBindingId(),
                    group.secondMember(), "member", "left", 1L, "leave-queued", 2L), "leave-queued"));
            else in(primary, () -> groups.updateAgent(new UpdateAgent(group.group().groupBindingId(), group.secondAgent(),
                    "removed", 1L, "remove-queued", 2L), "remove-queued"));
            finish(primary, detail(primary, group, first.triggerId()).runId());
            runtimeStore = newRuntimeStore(); messages = newMessages(); pump(primary, group);
            var rejected = detail(primary, group, queued.triggerId());
            assertEquals("rejected", rejected.status()); assertNull(rejected.inputId()); assertNull(rejected.runId());
            assertEquals("group_current_qualification_denied", rejected.rejectionCode());
            assertNull(slot(primary, group).activeRunId());
            assertEquals(previousRuns + 1, runtimeRuns(primary));
            assertEquals(queued, trigger(primary, group, queuedSource, group.secondMember(), group.secondAgent(), "queued-" + revoked));
            var followingSource = record(primary, group, group.firstMember(), "following-" + revoked, "有效后继");
            var following = trigger(primary, group, followingSource, group.firstMember(), group.firstAgent(), "following-" + revoked);
            pump(primary, group);
            assertEquals("accepted", detail(primary, group, following.triggerId()).status());
            assertEquals(previousRuns + 2, runtimeRuns(primary));
        }
    }

    @Test
    void admissionGuardFailureRollsBackSlotTriggerRunBudgetAndMaterialsAndRestartCanContinue() {
        var group = activeGroup(primary, "admission-rollback");
        var source = record(primary, group, group.firstMember(), "admission-source", "准入原子提交");
        var queued = trigger(primary, group, source, group.firstMember(), group.firstAgent(), "admission-trigger");
        failAdmissionAfterGuard.set(true);
        var failure = assertThrows(ExecutionStore.Rejection.class, () -> pump(primary, group));
        assertEquals("fixture_after_group_guard", failure.reason());
        assertEquals("queued", detail(primary, group, queued.triggerId()).status());
        assertNull(slot(primary, group).activeRunId()); assertNull(slot(primary, group).activeTriggerId());
        assertEquals(0L, runtimeRuns(primary));
        var run = new RunRef(new TenantId(primary.tenantId()), "gi03-run-" + queued.triggerId());
        assertTrue(new JdbcExecutionBudgets(runtimeSource).find(run).isEmpty());
        assertTrue(runtimeStore.material(run.tenantId(), new ResultRef("gi03-input-material-" + queued.triggerId())).isEmpty());
        assertEquals(1L, count("nw_group_source_messages", primary));
        assertEquals(1L, count("nw_group_memory_extraction_pending", primary));
        failAdmissionAfterGuard.set(false); runtimeStore = newRuntimeStore(); messages = newMessages(); pump(primary, group);
        var accepted = detail(primary, group, queued.triggerId());
        assertEquals("accepted", accepted.status()); assertEquals(run.runId(), accepted.runId());
        var saved = runtimeStore.load(run).orElseThrow();
        runtimeStore = newRuntimeStore(); messages = newMessages();
        assertEquals(saved, runtimeStore.load(run).orElseThrow());
        assertEquals(accepted, detail(primary, group, queued.triggerId()));
        assertEquals(run.runId(), slot(primary, group).activeRunId());
        pump(primary, group); assertEquals(1L, runtimeRuns(primary));
    }

    @Test
    void extractionPendingFailureRollsBackSourceReceiptAndSequenceInTheOriginalTransaction() {
        var group = activeGroup(primary, "source-pending-rollback");
        var command = message(group, group.firstMember(), "pending-source", 1L, "可靠后继不能丢");
        assertSqlState(assertThrows(RuntimeException.class, () -> direct(primary, () -> {
            restrictedSql.queryForObject("SELECT set_config('test.fail_group_pending','true',true)", String.class);
            return messages.record(command, "pending-source");
        })), "P0001");
        assertEquals(0L, count("nw_group_source_messages", primary));
        assertEquals(0L, count("nw_group_memory_extraction_pending", primary));
        assertEquals(0L, count("nw_group_message_requests", primary));
        assertEquals(0L,owner.queryForObject("SELECT count(*) FROM nw_chat_session_outbox WHERE tenant_id=? AND session_id=?",Long.class,primary.tenant(),group.group().sessionId()));
        assertNull(slot(primary, group));
        assertEquals(0L, runtimeRuns(primary));
        var accepted = in(primary, () -> messages.record(command, "pending-source"));
        assertEquals(1, accepted.sourceSequence());
        assertEquals(1L, count("nw_group_memory_extraction_pending", primary));
    }

    @Test
    void sourceOutboxFailureRollsBackSourcePendingReceiptAndSequenceTogether() {
        var group=activeGroup(primary,"source-outbox-rollback");
        var command=message(group,group.firstMember(),"outbox-source",1L,"同事务公开来源");
        assertSqlState(assertThrows(RuntimeException.class,()->direct(primary,()->{
            restrictedSql.queryForObject("SELECT set_config('test.fail_group_outbox','true',true)",String.class);
            return messages.record(command,"outbox-source");
        })),"P0001");
        assertEquals(0L,count("nw_group_source_messages",primary));assertEquals(0L,count("nw_group_memory_extraction_pending",primary));
        assertEquals(0L,count("nw_group_message_requests",primary));assertNull(slot(primary,group));
        assertEquals(0L,owner.queryForObject("SELECT count(*) FROM nw_chat_session_outbox WHERE tenant_id=? AND session_id=?",Long.class,primary.tenant(),group.group().sessionId()));
        var saved=in(primary,()->messages.record(command,"outbox-source"));
        assertEquals(saved,in(primary,()->messages.record(command,"outbox-source-alias")));
        assertEquals(1L,owner.queryForObject("SELECT count(*) FROM nw_chat_session_outbox WHERE tenant_id=? AND session_id=?",Long.class,primary.tenant(),group.group().sessionId()));
        assertEquals(0L,runtimeRuns(primary));
    }

    @Test
    void publicHistoryUsesTheSameOutboxAndKeepsCapturedThroughAcrossNewSources() {
        var group=activeGroup(primary,"public-snapshot");
        var first=record(primary,group,group.firstMember(),"snapshot-one","一");
        var second=record(primary,group,group.firstMember(),"snapshot-two","二");
        var third=record(primary,group,group.firstMember(),"snapshot-three","三");
        long through=owner.queryForObject("SELECT max(session_sequence) FROM nw_chat_session_outbox WHERE tenant_id=? AND session_id=?",Long.class,primary.tenant(),group.group().sessionId());
        var fourth=record(primary,group,group.firstMember(),"snapshot-four","四");
        var page=in(primary,()->sqlSessions.getMapper(GroupHistoryMapper.class).messagePage(primary.tenant(),group.group().sessionId(),through,Long.MAX_VALUE,2));
        assertEquals(2,page.size());assertTrue(page.get(0).payloadJson().contains(third.sourceMessageId()));assertTrue(page.get(1).payloadJson().contains(second.sourceMessageId()));
        assertTrue(page.stream().noneMatch(row->row.payloadJson().contains(fourth.sourceMessageId())));
        var older=in(primary,()->sqlSessions.getMapper(GroupHistoryMapper.class).messagePage(primary.tenant(),group.group().sessionId(),through,page.getLast().firstSequence(),2));
        assertEquals(1,older.size());assertTrue(older.getFirst().payloadJson().contains(first.sourceMessageId()));
        assertTrue(in(foreignTenant,()->sqlSessions.getMapper(GroupHistoryMapper.class).messagePage(primary.tenant(),group.group().sessionId(),through,Long.MAX_VALUE,2)).isEmpty());
        assertEquals(0L,runtimeRuns(primary));
    }

    @Test
    void databaseRejectsAcceptedOrOccupiedQueueFactsWithoutTheExactRealTypedRun() {
        var group = activeGroup(primary, "no-fictitious-run");
        var source = record(primary, group, group.firstMember(), "queue-source", "不接受虚构运行");
        var queued = trigger(primary, group, source, group.firstMember(), group.firstAgent(), "queue-source");
        assertSqlState(assertThrows(RuntimeException.class, () -> in(primary, () -> mapper().accept(primary.tenant(),
                primary.integration(), id(group), queued.triggerId(), "invented-input", "invented-run"))), "23514");
        assertSqlState(assertThrows(RuntimeException.class, () -> in(primary, () -> mapper().occupy(primary.tenant(),
                primary.integration(), id(group), queued.triggerId(), "invented-run"))), "23503");
        assertEquals("queued", detail(primary, group, queued.triggerId()).status());
        assertNull(slot(primary, group).activeRunId());
        assertEquals(0L, runtimeRuns(primary));
        pump(primary, group);
        var accepted = detail(primary, group, queued.triggerId());
        assertEquals("accepted", accepted.status());
        assertNotNull(runtimeStore.load(new RunRef(new TenantId(primary.tenantId()), accepted.runId())).orElseThrow());
    }

    @Test
    void warningTenantStillRecordsAndAdmitsAnAuthorizedGroupTrigger() {
        var group = activeGroup(primary, "warning-tenant");
        assertEquals(1, owner.update("UPDATE nw_tenants SET status='warning' WHERE id=?", primary.tenant()));
        var source = record(primary, group, group.firstMember(), "warning-source", "运营预警仍可合法执行");
        var queued = trigger(primary, group, source, group.firstMember(), group.firstAgent(), "warning-trigger");
        assertEquals("queued", queued.status());
        pump(primary, group);
        assertEquals("accepted", detail(primary, group, queued.triggerId()).status());
        assertEquals(1L, runtimeRuns(primary));
    }

    @Test
    void queuedCredentialRotationRetainsOriginalAuditVersionAndAdmitsWithCurrentCredential() throws Exception {
        var group = activeGroup(primary, "queued-credential-rotation");
        var source = record(primary, group, group.firstMember(), "rotation-source", "轮换不能丢弃原排队意图");
        var command = new Trigger(group.group().groupBindingId(), source.sourceMessageId(), group.firstMember(), group.firstAgent());
        var original = in(primary, () -> messages.trigger(command, "rotation-trigger"));
        assertEquals(1, in(primary, () -> mapper().trigger(primary.tenant(), primary.integration(), id(group), original.triggerId())).credentialVersion());
        assertEquals(1, owner.update("""
                UPDATE nw_service_principals SET credential_version=2
                WHERE tenant_id=? AND integration_id=? AND principal_id=? AND credential_version=1
                """, primary.tenant(), primary.integration(), primary.principal()));
        assertEquals(original, as(primary, 2, () -> writer.execute(status -> messages.trigger(command, "rotation-trigger"))));
        pump(primary, group);
        var accepted = in(primary, () -> mapper().trigger(primary.tenant(), primary.integration(), id(group), original.triggerId()));
        assertEquals("accepted", accepted.status());
        assertEquals(1, accepted.credentialVersion());
        assertEquals(1, admittedCandidate.get().credentialVersion());
        var run = new RunRef(new TenantId(primary.tenantId()), accepted.runId());
        var stored = runtimeStore.load(run).orElseThrow();
        assertEquals(source.sourceMessageId(), stored.identity().sourceMessageRef().orElseThrow().messageId());
        var input = runtimeStore.originalInput(run).orElseThrow().content();
        assertEquals(2, new ObjectMapper().readTree(input.canonicalJson()).path("credential_version").asLong());
        assertEquals(primary.principal(), new ObjectMapper().readTree(input.canonicalJson()).path("principal_id").asText());
        pump(primary, group);
        assertEquals(1L, count("nw_group_source_messages", primary));
        assertEquals(1L, count("nw_group_triggers", primary));
        assertEquals(1L, runtimeRuns(primary));
    }

    @Test
    void queuedPrincipalRevocationOrWriteScopeRemovalPersistsRejectionWithoutRun() throws Exception {
        for (String revoked : List.of("principal", "scope")) {
            var domain = domain(tenant(), "queued-revoke-" + revoked);
            var group = activeGroup(domain, "queued-authority-" + revoked);
            var source = record(domain, group, group.firstMember(), "authority-source", "当前授权必须仍有效");
            var command = new Trigger(group.group().groupBindingId(), source.sourceMessageId(), group.firstMember(), group.firstAgent());
            var queued = in(domain, () -> messages.trigger(command, "authority-trigger"));
            if (revoked.equals("principal")) {
                assertEquals(1, owner.update("UPDATE nw_service_principals SET status='revoked' WHERE tenant_id=? AND integration_id=? AND principal_id=?",
                        domain.tenant(), domain.integration(), domain.principal()));
            } else {
                assertEquals(1, owner.update("UPDATE nw_service_principals SET scopes=scopes - 'groups.triggers.write' WHERE tenant_id=? AND integration_id=? AND principal_id=?",
                        domain.tenant(), domain.integration(), domain.principal()));
            }
            pump(domain, group);
            var rejected = in(domain, () -> mapper().trigger(domain.tenant(), domain.integration(), id(group), queued.triggerId()));
            assertEquals("rejected", rejected.status());
            assertEquals("group_current_qualification_denied", rejected.rejectionCode());
            assertNull(rejected.inputId()); assertNull(rejected.runId());
            assertEquals(1, rejected.credentialVersion());
            assertNull(slot(domain, group).activeRunId());
            assertForbidden(() -> in(domain, () -> messages.trigger(command, "authority-trigger")));
            assertEquals(1L, count("nw_group_source_messages", domain));
            assertEquals(1L, count("nw_group_triggers", domain));
            assertEquals(0L, runtimeRuns(domain));
        }
    }

    /** 群、真实外部成员及本地 Agent 均先通过正式领域写入，再作为消息/触发测试前置。 */
    private TestGroup activeGroup(Domain domain, String external) {
        var group = ensure(domain, external, "life-1");
        long first = user(domain, external + "-first");
        long second = user(domain, external + "-second");
        long firstAgent = agent(domain);
        long secondAgent = agent(domain);
        for (long user : List.of(first, second)) {
            in(domain, () -> groups.updateMember(new UpdateMember(group.groupBindingId(), user,
                    "member", "active", 0L, "join-" + user, 1L), "join-" + user));
        }
        for (long agent : List.of(firstAgent, secondAgent)) {
            in(domain, () -> groups.updateAgent(new UpdateAgent(group.groupBindingId(), agent,
                    "active", 0L, "serve-" + agent, 1L), "serve-" + agent));
        }
        return new TestGroup(group, first, second, firstAgent, secondAgent);
    }

    private long runtimeRuns(Domain domain) {
        return owner.queryForObject("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=?",
                Long.class, domain.tenantId());
    }

    private GroupMessageMapper mapper() { return sqlSessions.getMapper(GroupMessageMapper.class); }
    private long id(TestGroup group) { return Long.parseLong(group.group().groupBindingId()); }

    private MessageRecord message(TestGroup group, long user, String event, long revision, String... text) {
        return new MessageRecord(group.group().groupBindingId(), event, revision, new EndUserAuthor(user),
                java.util.Arrays.stream(text).map(value -> (ChatContent) new ChatContent.Text(value)).toList());
    }

    private MessageReceipt record(Domain domain, TestGroup group, long user, String event, String text) {
        return in(domain, () -> messages.record(message(group, user, event, 1L, text), "message-" + event));
    }

    private GroupMessageMapper.Message source(Domain domain, TestGroup group, String sourceId) {
        return in(domain, () -> mapper().message(domain.tenant(), domain.integration(), id(group), sourceId));
    }

    private TriggerReceipt trigger(Domain domain, TestGroup group, MessageReceipt source, long user, long agent, String key) {
        return in(domain, () -> messages.trigger(new Trigger(group.group().groupBindingId(), source.sourceMessageId(), user, agent), "trigger-" + key));
    }

    private GroupMessageMapper.TriggerRow triggerRow(TestGroup group, MessageReceipt source, long user, long agent, long sequence) {
        return new GroupMessageMapper.TriggerRow(primary.tenant(), primary.integration(), id(group), group.group().sessionId(),
                Long.toString(SnowflakeIds.nextId()), sequence, source.sourceMessageId(), source.sourceSequence(), user, agent,
                primary.principal(), 1, "queued", null, null, null);
    }

    private TriggerReceipt detail(Domain domain, TestGroup group, String trigger) {
        return in(domain, () -> messages.detail(group.group().groupBindingId(), trigger));
    }

    private GroupMessageMapper.Slot slot(Domain domain, TestGroup group) {
        return in(domain, () -> mapper().slot(domain.tenant(), domain.integration(), id(group)));
    }

    private void pump(Domain domain, TestGroup group) {
        as(domain, () -> { messages.pump(domain.tenantId(), domain.integration(), group.group().groupBindingId()); return null; });
    }

    /** 通过正式 Store 领取及结束已受理 Run，未调用模型、工具或生产 worker。 */
    private void finish(Domain domain, String runId) {
        var run = new RunRef(new TenantId(domain.tenantId()), runId);
        var claimed = runtimeStore.claim(new ExecutionStore.Claim(run, "group-db-fixture", "finish-" + runId, Duration.ofSeconds(30))).orElseThrow();
        runtimeStore.finish(new ExecutionStore.Finish(run, claimed.revision(), claimed.lease().orElseThrow(), RunStatus.COMPLETED, List.of()));
    }

    /** 使用正式来源验证器；本夹具没有已公开 Agent 消息，因此伪造 Agent/Tool/System 回流必须拒绝。 */
    private GroupMessageService newMessages() {
        var json=new ObjectMapper().registerModule(new JavaTimeModule());
        var media=mock(SessionMediaService.class);
        when(media.resolveContent(any(SessionRef.class),anyList())).thenAnswer(call->{
            List<ChatContent> content=call.getArgument(1);
            return content.stream().map(part->(SessionMediaService.ResolvedContent)new SessionMediaService.TextContent("text",((ChatContent.Text)part).text())).toList();
        });
        var ledger=new SessionWebhookDeliveryLedger(sqlSessions.getMapper(SessionEventSubscriptionMapper.class),sqlSessions.getMapper(SessionOutboxWebhookDeliveryMapper.class));
        var mailbox=new SessionMailboxService(sqlSessions.getMapper(SessionOutboxMapper.class),sqlSessions.getMapper(SessionOutboxSequenceMapper.class),ledger);
        var publicSources=new GroupHistorySourcePublisher(sqlSessions.getMapper(GroupHistoryMapper.class),mailbox,media,json);
        var controls=new GroupControlService(sqlSessions.getMapper(GroupControlMapper.class),integrations,new AuditService(sqlSessions.getMapper(AuditLogMapper.class)),json);
        return new GroupMessageService(sqlSessions.getMapper(GroupMessageMapper.class),groups,integrations,
                media,new RuntimeGroupMessageSourceValidator(runtimeStore,mock(AgentRuntimeExecutionProjection.class),groups),this::admit,
                sqlSessions.getMapper(RuntimeWorkerRecoveryMapper.class),json,transactions,publicSources,controls);
    }

    /**
     * 真实 typed Store 保存 Run、预算、材料与领域 guard；此 Host 夹具只提供已批准准入值，
     * 不证明生产发布解析、模型配置或 Provider 执行。领域 Mapper 注册在 Store 的原 SqlSession。
     */
    private JdbcExecutionStore newRuntimeStore() {
        return new JdbcExecutionStore(runtimeSource, new JdbcIdentityPolicyPort(runtimeSource, Map.of()), new JdbcExecutionBudgets(runtimeSource),
                (session, run, actor, prepared) -> false, GroupMessageMapper::configure);
    }

    /** 使用正式准入端口和真实 Store；故障点只在原领域 guard 执行后抛出，检验共同回滚。 */
    private GroupExecutionAdmission.Admitted admit(GroupExecutionAdmission.Candidate candidate, JdbcExecutionStore.AdmissionGuard guard) {
        var command = admission(candidate);
        var result = runtimeStore.admit(command, (session, exact) -> {
            guard.require(session, exact);
            if (failAdmissionAfterGuard.get()) throw new ExecutionStore.Rejection("fixture_after_group_guard");
        });
        admittedCandidate.set(candidate);
        return new GroupExecutionAdmission.Admitted(result.run().identity().causationInputId().value(),
                result.run().identity().runRef().runId());
    }

    /** 每个 trigger 的来源、请求者与实际 Agent 原样进入正式 RunIdentity，不生成 owner EndUser。 */
    private ExecutionStore.Admission admission(GroupExecutionAdmission.Candidate candidate) {
        var tenant = new TenantId(candidate.tenantId());
        var session = new SessionRef(tenant, candidate.sessionId());
        var group = new GroupBindingRef(tenant, candidate.groupBindingId());
        var owner = new SessionOwner(session, SessionSubject.group(group), SessionTarget.group(group));
        var input = new InputId("gi03-input-" + candidate.triggerId());
        var run = new RunRef(tenant, "gi03-run-" + candidate.triggerId());
        var verification = new ResultRef("gi03-verified-" + candidate.triggerId());
        var current = writer.execute(status -> sqlSessions.getMapper(ServicePrincipalMapper.class).selectOne(
                new QueryWrapper<ServicePrincipalDO>().eq("tenant_id", Long.parseLong(candidate.tenantId()))
                        .eq("integration_id", candidate.integrationId()).eq("principal_id", candidate.principalId())
                        .eq("status", "active").apply("scopes @> jsonb_build_array(CAST({0} AS text))", "groups.triggers.write")));
        if (current == null) throw new ApiException(HttpStatus.FORBIDDEN, "fixture_current_principal_denied");
        var identity = new VerifiedIdentity(new PrincipalRef.ServicePrincipal(tenant, current.getPrincipalId(),
                current.getIntegrationId(), current.getCredentialVersion()), owner, Set.copyOf(current.getScopes()), 1, verification);
        var trace = new TraceEnvelope("gi03-trace-" + candidate.triggerId(), "gi03-span", input, Optional.empty());
        var runIdentity = new RunIdentity(owner, new AgentId(Long.toString(candidate.agentId())), input, run, trace, verification,
                new RunRequester.EndUser(new EndUserId(candidate.requesterEndUserId())),
                Optional.of(new SourceMessageRef(session, candidate.sourceMessageId())),
                new RunOriginRef(tenant, "group-trigger:" + candidate.triggerId()));
        var capacity = new ExecutionBudgetPort.Resources(2, 0, 0, 0, 2_000, 1_000, 3_000);
        var budget = new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, capacity, capacity, capacity,
                        60, 60, "gi03-persistence-fixture-v1")));
        try {
            var json = new ObjectMapper();
            var source = new ExecutionStore.Material(new ResultRef("gi03-input-material-" + candidate.triggerId()),
                    new TypedPayload("test.group-input.v1", json.writeValueAsString(Map.of("source_message_id", candidate.sourceMessageId(),
                            "source_sequence", candidate.sourceSequence(), "history", candidate.history(),
                            "principal_id", current.getPrincipalId(), "credential_version", current.getCredentialVersion()))));
            var context = new ExecutionStore.Material(new ResultRef("gi03-context-" + candidate.triggerId()),
                    new TypedPayload("test.group-context.v1", "{}"));
            return new ExecutionStore.Admission(identity, runIdentity, "gi03-admission-" + candidate.triggerId(),
                    new ResultRef("gi03-config-" + candidate.triggerId()), source, context, budget, List.of(), List.of());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("group_test_admission_encoding_failed", failure);
        }
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

    private long count(String table, Domain domain) {
        assertTrue(Set.of("nw_group_bindings", "nw_chat_sessions", "nw_group_memberships", "nw_group_service_agents", "nw_group_binding_requests",
                "nw_group_front_slots", "nw_group_source_messages", "nw_group_triggers", "nw_group_message_requests", "nw_group_memory_extraction_pending").contains(table));
        return owner.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND integration_id=?",
                Long.class, domain.tenant(), domain.integration());
    }

    /** 每次领域调用都有独立真实事务，并在工作线程退出时清除可信上下文。 */
    private <T> T in(Domain domain, Supplier<T> action) {
        return as(domain, () -> writer.execute(status -> action.get()));
    }

    /** pump 自持候选短事务并调用 Store 原事务；调用处只固定认证身份，不能再包外层写事务。 */
    private <T> T as(Domain domain, Supplier<T> action) {
        return as(domain, 1, action);
    }

    /** 已认证请求可使用轮换后的当前版本；原 trigger 中的审计版本独立保留。 */
    private <T> T as(Domain domain, long credentialVersion, Supplier<T> action) {
        try {
            TenantContext.setTenantId(domain.tenantId());
            ServicePrincipalContext.set(new ServicePrincipalContext.Identity(domain.principal(), domain.tenantId(),
                    domain.integration(), credentialVersion, SCOPES));
            return action.get();
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

    private record TestGroup(GroupView group, long firstMember, long secondMember, long firstAgent, long secondAgent) { }
}

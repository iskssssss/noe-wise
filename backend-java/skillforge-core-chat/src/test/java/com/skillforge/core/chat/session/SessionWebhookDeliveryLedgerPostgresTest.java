package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryMapper;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.ProxyTransactionManagementConfiguration;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 独立 PostgreSQL 执行正式账本 DDL 与 Mapper，验证重领 fencing、顺序和受限角色隔离。 */
@Testcontainers(disabledWithoutDocker = false)
class SessionWebhookDeliveryLedgerPostgresTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(1);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("webhook_delivery_ledger");
    private static SessionWebhookDeliveryLedger ledger;

    @BeforeAll
    static void initialize() throws Exception {
        // 只补本次正式迁移引用的 Outbox 主键；账本及 RLS 使用迁移自身的定义。
        execute("CREATE TABLE public.nw_chat_session_outbox(id bigint PRIMARY KEY)");
        try (var migration = SessionWebhookDeliveryLedgerPostgresTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V68__session_outbox_webhook_deliveries.sql")) {
            assertNotNull(migration);
            execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
        }
        execute("""
                CREATE ROLE webhook_worker LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS;
                GRANT USAGE ON SCHEMA public TO webhook_worker;
                GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO webhook_worker
                """);
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser("webhook_worker");
        source.setPassword("test-only");
        var configuration = new MybatisConfiguration(new Environment(
                "webhook-ledger", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor());
        String resource = "mapper/SessionOutboxWebhookDeliveryMapper.xml";
        try (var input = SessionWebhookDeliveryLedgerPostgresTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var deliveries = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                .getMapper(SessionOutboxWebhookDeliveryMapper.class);
        var target = new SessionWebhookDeliveryLedger(mock(SessionEventSubscriptionMapper.class), deliveries);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var attributes = new ProxyTransactionManagementConfiguration().transactionAttributeSource();
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), attributes));
        ledger = (SessionWebhookDeliveryLedger) proxy.getProxy();
    }

    @BeforeEach
    void seedDelivery() throws Exception {
        TenantContext.setTenantId("7");
        execute("""
                TRUNCATE nw_chat_session_outbox_webhook_deliveries, nw_chat_session_event_subscriptions, nw_chat_session_outbox;
                INSERT INTO nw_chat_session_outbox(id) VALUES (5), (6);
                INSERT INTO nw_chat_session_event_subscriptions(id, tenant_id, session_id, endpoint_url, signing_secret_encrypted)
                VALUES (12, 7, 'session-1', 'https://example.invalid/webhook', 'fixture-secret');
                INSERT INTO nw_chat_session_outbox_webhook_deliveries
                    (id, tenant_id, subscription_id, outbox_event_id, event_id, session_id, session_sequence, available_at)
                VALUES (99, 7, 12, 5, 'event-1', 'session-1', 1, '2026-10-24T00:00:00Z'),
                       (100, 7, 12, 6, 'event-2', 'session-1', 2, '2026-10-24T00:00:00Z')
                """);
    }

    @AfterEach
    void clearIdentity() {
        TenantContext.clear();
    }

    @Test
    void staleSuccessFailureAndRetryCannotChangeTheReclaimedDelivery() throws Exception {
        var first = ledger.claimNext("7", 12L, "session-1", NOW, LEASE);
        assertEquals(new SessionWebhookDeliveryLedger.Claim(99L, 5L, 1), first);
        assertNull(ledger.claimNext("7", 12L, "session-1", NOW.plusSeconds(59), LEASE),
                "未过期租约阻止跳过最早事件");
        var second = ledger.claimNext("7", 12L, "session-1", NOW.plus(LEASE), LEASE);
        assertEquals(new SessionWebhookDeliveryLedger.Claim(99L, 5L, 2), second);
        Snapshot reclaimed = snapshot();

        assertFalse(ledger.markDelivered("7", first, NOW.plusSeconds(61)));
        assertEquals(reclaimed, snapshot(), "旧成功不能确认新 claim");
        assertFalse(ledger.markUnresolved("7", first, "OLD_FAILURE"));
        assertEquals(reclaimed, snapshot(), "旧失败不能终结新 claim");
        assertFalse(ledger.retry("7", first, NOW.plusSeconds(300), "OLD_RETRY"));
        assertEquals(reclaimed, snapshot(), "旧重试不能清除新租约或改写退避");

        assertTrue(ledger.markDelivered("7", second, NOW.plusSeconds(62)));
        assertEquals(SessionWebhookDeliveryLedger.DELIVERED, snapshot().status());
        assertFalse(ledger.markUnresolved("7", first, "LATE_FAILURE"));
        assertEquals(SessionWebhookDeliveryLedger.DELIVERED, snapshot().status());
    }

    @Test
    void currentClaimCanRetryAndResolveWithoutSkippingTheEarlierSequence() {
        var first = ledger.claimNext("7", 12L, "session-1", NOW, LEASE);
        assertTrue(ledger.retry("7", first, NOW.plusSeconds(120), "WEBHOOK_HTTP_503"));
        assertNull(ledger.claimNext("7", 12L, "session-1", NOW.plusSeconds(119), LEASE),
                "最早事件退避期间不能投递后续 sequence");
        var retried = ledger.claimNext("7", 12L, "session-1", NOW.plusSeconds(120), LEASE);
        assertEquals(new SessionWebhookDeliveryLedger.Claim(99L, 5L, 2), retried);
        assertFalse(ledger.markUnresolved("7", first, "OLD_FAILURE"));
        assertTrue(ledger.markUnresolved("7", retried, "WEBHOOK_HTTP_401"));
        assertEquals(new SessionWebhookDeliveryLedger.Claim(100L, 6L, 1),
                ledger.claimNext("7", 12L, "session-1", NOW.plusSeconds(120), LEASE));
    }

    @Test
    void forgedTenantArgumentsCannotClaimOrWriteBackAnotherTenantsDelivery() throws Exception {
        var claim = ledger.claimNext("7", 12L, "session-1", NOW, LEASE);
        Snapshot owned = snapshot();
        TenantContext.setTenantId("8");

        assertNull(ledger.claimNext("7", 12L, "session-1", NOW.plus(LEASE), LEASE));
        assertFalse(ledger.markDelivered("7", claim, NOW));
        assertFalse(ledger.retry("7", claim, NOW, "FOREIGN_RETRY"));
        assertFalse(ledger.markUnresolved("7", claim, "FOREIGN_FAILURE"));
        assertEquals(owned, snapshot());
    }

    private static Snapshot snapshot() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var row = statement.executeQuery("""
                        SELECT status, attempts, available_at, claimed_at, delivered_at, error_code, updated_at
                        FROM nw_chat_session_outbox_webhook_deliveries WHERE id=99
                        """)) {
            assertTrue(row.next());
            return new Snapshot(row.getString("status"), row.getInt("attempts"),
                    row.getTimestamp("available_at").toInstant(), row.getTimestamp("claimed_at").toInstant(),
                    row.getTimestamp("delivered_at") == null ? null : row.getTimestamp("delivered_at").toInstant(),
                    row.getString("error_code"), row.getTimestamp("updated_at").toInstant());
        }
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record Snapshot(String status, int attempts, Instant availableAt, Instant claimedAt,
            Instant deliveredAt, String errorCode, Instant updatedAt) { }
}

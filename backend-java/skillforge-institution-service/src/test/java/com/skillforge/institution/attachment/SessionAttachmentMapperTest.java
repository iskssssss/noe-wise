package com.skillforge.institution.attachment;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实受限 PostgreSQL 验证单正文所有者、精确外键、RLS、事务回滚和有界到期回收。 */
@Testcontainers(disabledWithoutDocker = true)
class SessionAttachmentMapperTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private SessionAttachmentMapper mapper;
    private SessionAttachmentBodyMapper bodyMapper;
    private SessionAttachmentBodyStore bodies;
    private TransactionTemplate transactions;

    @BeforeEach void setup() throws Exception {
        source = new SingleConnectionDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS nw_chat_session_attachments,chat_attachment_bodies,agent_runtime_sessions,agent_runtime_runs,sessions,tenants CASCADE");
        jdbc.execute("DROP FUNCTION IF EXISTS chat_attachment_body_immutable()");
        jdbc.execute("DROP ROLE IF EXISTS chat_storage_writer");
        jdbc.execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY)");
        jdbc.execute("CREATE TABLE nw_chat_sessions(session_id varchar(64) NOT NULL UNIQUE,tenant_id bigint NOT NULL REFERENCES nw_tenants(id))");
        jdbc.execute("CREATE TABLE agent_runtime_sessions(tenant_id text,session_id text,status text)");
        jdbc.execute("CREATE TABLE agent_runtime_runs(tenant_id text,session_id text,status text)");
        jdbc.update("INSERT INTO nw_tenants VALUES(7),(17)");
        jdbc.update("INSERT INTO nw_chat_sessions VALUES('session-7',7),('session-7b',7),('session-17',17)");
        var migrations = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V241__chat_session_attachments.sql"),
                new ClassPathResource("db/migration/V255__session_image_media.sql"),
                new ClassPathResource("db/migration/V261__document_session_attachment_limits.sql"),
                new ClassPathResource("db/migration/V284__session_media_attachment_body_storage.sql"));
        // V284 的 PL/pgSQL 体含分号；每个迁移作为完整脚本交给 PostgreSQL。
        migrations.setSeparator(org.springframework.jdbc.datasource.init.ScriptUtils.EOF_STATEMENT_SEPARATOR);
        migrations.execute(source);
        jdbc.execute("CREATE ROLE chat_storage_writer NOLOGIN NOSUPERUSER");
        jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON nw_tenants,nw_chat_sessions,nw_chat_session_attachments,nw_chat_attachment_bodies,agent_runtime_sessions,agent_runtime_runs TO chat_storage_writer");
        jdbc.execute("SET ROLE chat_storage_writer");
        tenant("7");
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/SessionAttachmentMapper.xml"),
                new ClassPathResource("mapper/SessionAttachmentBodyMapper.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(SessionAttachmentMapper.class);
        bodyMapper = session.getMapper(SessionAttachmentBodyMapper.class);
        bodies = new SessionAttachmentBodyStore(bodyMapper, 0, 0);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @AfterEach void cleanup() { TenantContext.clear(); if (source != null) source.destroy(); }

    @Test void sameTenantSameBytesHaveOneBodyAndDistinctExactSessionReferences() throws Exception {
        byte[] bytes = "正文😀".getBytes(StandardCharsets.UTF_8);
        var first = row("7", "session-7", bytes, Instant.now().plus(7, ChronoUnit.DAYS));
        var second = row("7", "session-7b", bytes, first.expiresAt());
        assertEquals(1, insert(first)); assertEquals(1, insert(second)); assertEquals(0, insert(first));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM nw_chat_attachment_bodies", Integer.class));
        assertEquals((long) bytes.length, jdbc.queryForObject("SELECT sum(octet_length(content)) FROM nw_chat_attachment_bodies", Long.class));
        assertArrayEquals(bytes, mapper.find("7", "session-7", first.id()).content());
        assertNull(mapper.find("7", "session-7b", first.id()));
        assertNull(mapper.find("17", "session-7", first.id()));
        assertNull(mapper.list("7", "session-7").getFirst().content());
        assertEquals(List.of(first.id()), mapper.findMetadataBatch("7", "session-7", List.of(first.id(), second.id()))
                .stream().map(SessionAttachmentRow::id).toList());
        assertTrue(mapper.findMetadataBatch("7", "session-7", List.of()).isEmpty());
        var capacity = bodyMapper.capacity("7", "session-7", first.sha256(), first.size());
        assertEquals(bytes.length, capacity.sessionBytes()); assertEquals(bytes.length, capacity.tenantBytes());
        assertTrue(capacity.inSession());
    }
    @Test void actualBodyWriteIsRolledBackWhenReferenceRegistrationFails() throws Exception {
        var invalidOwner = row("7", "session-17", new byte[]{1, 2, 3}, Instant.now().plus(7, ChronoUnit.DAYS));
        assertThrows(RuntimeException.class, () -> insert(invalidOwner));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM nw_chat_attachment_bodies", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM nw_chat_session_attachments", Integer.class));
    }
    @Test void rlsRejectsCrossTenantBodyAndExactCompositeForeignKeyRejectsMissingIdentity() throws Exception {
        var first = row("7", "session-7", new byte[]{1, 2, 3}, Instant.now().plus(7, ChronoUnit.DAYS));
        insert(first);
        tenant("17");
        assertNull(bodyMapper.find("7", first.sha256(), first.size()));
        assertNull(mapper.find("7", "session-7", first.id()));
        assertThrows(RuntimeException.class, () -> bodyMapper.insert("7", first.sha256(), first.size(), first.content()));
        var own = row("17", "session-17", first.content(), first.expiresAt());
        insert(own);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM nw_chat_attachment_bodies", Integer.class));
        tenant("7");
        var missing = row("7", "session-7", new byte[]{4, 5, 6}, first.expiresAt());
        assertThrows(RuntimeException.class, () -> mapper.insert(missing));
        assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE nw_chat_attachment_bodies SET content=? WHERE tenant_id=7", new byte[]{3, 2, 1}));
        assertArrayEquals(first.content(), bodyMapper.find("7", first.sha256(), first.size()).content());
    }
    @Test void sevenDayExpiryDrainsMultipleBatchesAndKeepsMinimalUuidTombstones() throws Exception {
        Instant expired = Instant.now().minus(1, ChronoUnit.HOURS);
        for (int index = 0; index < 205; index++) insert(row("7", "session-7", new byte[]{1, 2, 3}, expired));
        var current = row("7", "session-7b", new byte[]{1, 2, 3}, Instant.now().plus(7, ChronoUnit.DAYS));
        insert(current);
        var processor = new SessionAttachmentReclamation(mapper, bodyMapper, bodies);
        var first = transactions.execute(status -> processor.expiredBatch("7"));
        assertEquals(100, first.released()); assertEquals(0, first.reclaimed());
        assertEquals(101, first.backlog().sampledRows()); assertTrue(first.backlog().saturated());
        assertEquals(100, transactions.execute(status -> processor.expiredBatch("7")).released());
        assertEquals(5, transactions.execute(status -> processor.expiredBatch("7")).released());
        assertEquals(0, transactions.execute(status -> processor.expiredBatch("7")).released());
        assertEquals(205, jdbc.queryForObject("SELECT count(*) FROM nw_chat_session_attachments WHERE reclaimed_at IS NOT NULL AND body_format IS NULL AND name IS NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM nw_chat_attachment_bodies", Integer.class));
        assertArrayEquals(current.content(), mapper.find("7", "session-7b", current.id()).content());
        String tombstone = jdbc.queryForObject("SELECT id::text FROM nw_chat_session_attachments WHERE reclaimed_at IS NOT NULL LIMIT 1", String.class);
        assertNull(mapper.find("7", "session-7", tombstone));
        assertEquals(0, transactions.execute(status -> { bodies.lockTenant("7"); return mapper.insert(new SessionAttachmentRow(tombstone,
                current.tenantId(), "session-7", current.name(), "attachments/"+tombstone+"/input.txt",
                current.size(), current.sha256(), current.content(), current.createdAt(), current.expiresAt())); }).intValue());
        transactions.executeWithoutResult(status -> { bodies.lockTenant("7"); jdbc.update("DELETE FROM nw_chat_sessions WHERE session_id='session-7b'");
            assertEquals(1, bodyMapper.reclaimUnreferenced("7", 100)); });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM nw_chat_attachment_bodies", Integer.class));
        assertEquals(205, jdbc.queryForObject("SELECT count(*) FROM nw_chat_session_attachments", Integer.class));
    }
    @Test void perSessionAndTenantBudgetRejectsBeforeAnyAdditionalBodyOrReferenceWrite() throws Exception {
        var first = row("7", "session-7", new byte[]{1, 2, 3}, Instant.now().plus(7, ChronoUnit.DAYS));
        var limited = new SessionAttachmentBodyStore(bodyMapper, 3, 3);
        transactions.executeWithoutResult(status -> { limited.register("7", "session-7", first.sha256(), first.content()); mapper.insert(first); });
        var second = row("7", "session-7", new byte[]{4}, first.expiresAt());
        assertEquals("attachment_session_capacity_exceeded", assertThrows(ApiException.class, () -> transactions.executeWithoutResult(status -> {
            limited.register("7", "session-7", second.sha256(), second.content()); mapper.insert(second);
        })).getCode());
        assertEquals("attachment_tenant_capacity_exceeded", assertThrows(ApiException.class, () -> transactions.executeWithoutResult(status -> {
            limited.register("7", "session-7b", second.sha256(), second.content());
        })).getCode());
        var reused = row("7", "session-7b", first.content(), first.expiresAt());
        transactions.executeWithoutResult(status -> { limited.register("7", "session-7b", reused.sha256(), reused.content()); mapper.insert(reused); });
        assertEquals(3, bodyMapper.sessionBytes("7", "session-7b"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM nw_chat_attachment_bodies", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM nw_chat_session_attachments", Integer.class));
    }
    @Test void bodyReclamationWaitsForConcurrentReferenceTransactionAndPreservesCommittedBody() throws Exception {
        var row = row("7", "session-7", new byte[]{9, 8, 7}, Instant.now().plus(7, ChronoUnit.DAYS));
        var attempted = new java.util.concurrent.CountDownLatch(1);
        var task = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<SessionAttachmentReclamation.BatchResult>>();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
             var observer = new SingleConnectionDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
            var observing = new JdbcTemplate(observer);
            transactions.executeWithoutResult(status -> {
                bodies.register("7", "session-7", row.sha256(), row.content());
                assertEquals(1, mapper.insert(row));
                task.set(executor.submit(() -> {
                    try (var concurrent = new SingleConnectionDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
                        var queries = new JdbcTemplate(concurrent);
                        queries.execute("SET ROLE chat_storage_writer");
                        queries.execute("SET application_name='chat_gc_blocked_test'");
                        queries.execute("SELECT set_config('app.tenant_id','7',false)");
                        TenantContext.setTenantId("7");
                        var factory = new SqlSessionFactoryBean(); factory.setDataSource(concurrent);
                        factory.setMapperLocations(new ClassPathResource("mapper/SessionAttachmentMapper.xml"),
                                new ClassPathResource("mapper/SessionAttachmentBodyMapper.xml"));
                        var session = new SqlSessionTemplate(factory.getObject());
                        var bodyQueries = session.getMapper(SessionAttachmentBodyMapper.class);
                        var processor = new SessionAttachmentReclamation(session.getMapper(SessionAttachmentMapper.class), bodyQueries,
                                new SessionAttachmentBodyStore(bodyQueries, 0, 0));
                        var transaction = new TransactionTemplate(new DataSourceTransactionManager(concurrent));
                        attempted.countDown();
                        return transaction.execute(state -> processor.expiredBatch("7"));
                    } finally { TenantContext.clear(); }
                }));
                try { assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                boolean blocked = false;
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
                do {
                    blocked = observing.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name='chat_gc_blocked_test' AND wait_event_type='Lock')", Boolean.class);
                    if (blocked) break;
                    Thread.yield();
                } while (System.nanoTime() - deadline < 0);
                assertTrue(blocked, "真实数据库必须观察到回收事务等待租户主行锁");
            });
            var result = task.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, result.released()); assertEquals(0, result.reclaimed());
            assertArrayEquals(row.content(), mapper.find("7", "session-7", row.id()).content());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM nw_chat_attachment_bodies", Integer.class));
        }
    }
    private int insert(SessionAttachmentRow row) {
        return transactions.execute(status -> { bodies.register(row.tenantId(), row.sessionId(), row.sha256(), row.content()); return mapper.insert(row); });
    }
    private void tenant(String tenant) {
        TenantContext.setTenantId(tenant);
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,false)", String.class, tenant);
    }
    private static SessionAttachmentRow row(String tenant, String session, byte[] content, Instant expiry) throws Exception {
        String id = UUID.randomUUID().toString();
        return new SessionAttachmentRow(id, tenant, session, "input.txt", "attachments/" + id + "/input.txt", content.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), content,
                expiry.minus(7, ChronoUnit.DAYS), expiry);
    }
}

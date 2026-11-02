package com.skillforge.institution.scheduler;

import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionOutboxOutput;
import com.skillforge.core.chat.session.SessionOutboxProjectionBatch;
import com.skillforge.core.chat.session.SessionOutboxProjectionCheckpoint;
import com.skillforge.core.chat.session.SessionOutboxProjectionRejectedException;
import com.skillforge.core.chat.session.SessionOutboxProjectionSource;
import com.skillforge.core.chat.session.SessionWebhookDeliveryLedger;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.ChatHistoryUnavailableException;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionOutboxSequenceMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryMapper;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.shared.tracing.TraceContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** 真实迁移、行锁、事务回滚与 RLS；永久拒绝属于 Outbox 投影进度，不触及 Runtime 终态。 */
@Testcontainers(disabledWithoutDocker = false)
class SessionOutboxProjectionRecoveryTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate admin, scopedJdbc;
    private static DriverManagerDataSource source;
    private static SqlSessionTemplate sessions;
    private static SessionOutboxSequenceMapper sequences;
    private static SessionMailboxService mailbox;
    private static org.apache.ibatis.session.SqlSessionFactory borrowedSessions;

    @BeforeAll static void schema() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        for (var migration : java.util.List.of("V56__session_inbox_outbox.sql", "V59__session_outbox_publication_lease.sql", "V62__session_outbox_trace_context.sql",
                "V68__session_outbox_webhook_deliveries.sql", "V249__session_outbox_projection_rejections.sql")) {
            try (var input = SessionOutboxProjectionRecoveryTest.class.getClassLoader().getResourceAsStream("db/migration/" + migration)) {
                assertNotNull(input); admin.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        try (var input = SessionOutboxProjectionRecoveryTest.class.getClassLoader().getResourceAsStream("db/migration/V250__incremental_chat_projection.sql")) {
            assertNotNull(input); admin.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8).split("CREATE INDEX")[0]);
        }
        try (var input = SessionOutboxProjectionRecoveryTest.class.getClassLoader().getResourceAsStream("db/migration/V291__session_outbox_committed_outputs.sql")) {
            assertNotNull(input); admin.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        admin.execute("CREATE ROLE projection_test LOGIN PASSWORD 'projection_test_password' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO projection_test");
        source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "projection_test", "projection_test_password");
        scopedJdbc = new JdbcTemplate(source);
        var configuration = new MybatisConfiguration();
        configuration.addMapper(SessionOutboxSequenceMapper.class);
        configuration.addMapper(SessionOutboxMapper.class);
        configuration.addMapper(SessionEventSubscriptionMapper.class);
        configuration.addMapper(SessionOutboxWebhookDeliveryMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/SessionOutboxMapper.xml"), new ClassPathResource("mapper/SessionOutboxSequenceMapper.xml"),
                new ClassPathResource("mapper/SessionOutboxWebhookDeliveryMapper.xml"), new ClassPathResource("mapper/SessionEventSubscriptionMapper.xml"));
        factory.setPlugins(new TenantRlsInterceptor());
        sessions = new SqlSessionTemplate(factory.getObject());
        sequences = sessions.getMapper(SessionOutboxSequenceMapper.class);
        mailbox = newMailbox();
        var borrowed = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                "borrowed-outbox-test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), source));
        borrowed.setMapUnderscoreToCamelCase(true);
        borrowed.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        SessionMailboxService.configureBorrowed(borrowed);
        borrowedSessions = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(borrowed);
    }

    private static SessionMailboxService newMailbox() {
        var target = new SessionMailboxService(sessions.getMapper(SessionOutboxMapper.class), sequences,
                new SessionWebhookDeliveryLedger(sessions.getMapper(SessionEventSubscriptionMapper.class),
                        sessions.getMapper(SessionOutboxWebhookDeliveryMapper.class)));
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        return (SessionMailboxService) proxy.getProxy();
    }

    @BeforeEach void clearRows() {
        admin.execute("TRUNCATE nw_chat_session_outbox_webhook_deliveries,nw_chat_session_event_subscriptions,nw_chat_session_outbox,nw_chat_session_outbox_sequences");
        TenantContext.setTenantId("7");
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    /** 裸 JDBC 事务拥有三类持久事实；提交后由真实所有者定向通知，借用入口不能抢先通知。 */
    @Test void borrowedOutputsCheckpointAndWebhookShareCommitWithoutEarlyNotification() throws Exception {
        subscribe();
        try (var observation = mailbox.observeOutputs("7", "session")) {
            var projection = version(1, "source-1", "policy");
            SessionOutboxEntry original;
            try (var sql = borrowedSession()) {
                original = mailbox.projectOutputsBorrowed(sql, "7", "session", projection,
                        checkpoint -> batch("{}", output("borrowed"))).getFirst();
                assertEquals(0, observation.version());
                assertEquals(0, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox", Integer.class));
                assertEquals(0, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_webhook_deliveries", Integer.class));
                assertEquals(1, sql.getMapper(SessionOutboxSequenceMapper.class).projectionCheckpoint(7L, "session").sequence());
                sql.commit(true);
            }
            assertEquals(0, observation.version());
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox", Integer.class));
            assertEquals(2, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_webhook_deliveries", Integer.class));
            mailbox.outputsCommitted("7", "session");
            assertTrue(observation.version() > 0);
            var committed = mailbox.listOutputsAfterSequence("7", "session", 0, 100).getFirst();
            assertEquals(original.getEventId(), committed.getEventId());
            assertEquals(original.getSessionSequence(), committed.getSessionSequence());
            try (var sql = borrowedSession()) {
                assertTrue(mailbox.projectOutputsBorrowed(sql, "7", "session", projection, ignored -> {
                    fail("已提交来源不能再次投影"); return batch("{}");
                }).isEmpty());
                sql.commit(true);
            }
            var replay = mailbox.listOutputsAfterSequence("7", "session", 0, 100).getFirst();
            assertEquals(original.getEventId(), replay.getEventId());
            assertEquals(original.getSessionSequence(), replay.getSessionSequence());
            assertEquals(committed.getCreatedAt(), replay.getCreatedAt());
            assertEquals(2, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_webhook_deliveries", Integer.class));
        }
    }

    /** 水位约束在输出与 Webhook 已写入后失败，必须一并回滚，不能泄漏半批结果或通知。 */
    @Test void borrowedCheckpointFailureRollsBackOutputsWebhookAndSequence() throws Exception {
        subscribe();
        try (var observation = mailbox.observeOutputs("7", "session"); var sql = borrowedSession()) {
            assertThrows(RuntimeException.class, () -> mailbox.projectOutputsBorrowed(sql, "7", "session",
                    version(1, "source-1", "policy"), checkpoint -> batch("[]", output("rolled-back"))));
            sql.rollback(true);
            assertEquals(0, observation.version());
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox", Integer.class));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_webhook_deliveries", Integer.class));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_sequences", Integer.class));
        }
    }

    /** 借用事务不重绑租户，伪造另一租户的投影范围仍由受限 PostgreSQL 角色拒绝。 */
    @Test void borrowedProjectionCannotChangeBoundTenant() throws Exception {
        try (var sql = borrowedSession()) {
            assertThrows(RuntimeException.class, () -> mailbox.projectOutputsBorrowed(sql, "8", "foreign",
                    version(1, "source-1", "policy"), ignored -> {
                        fail("RLS 应在投影前拒绝伪造范围"); return batch("{}");
                    }));
            sql.rollback(true);
        }
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_sequences", Integer.class));
    }

    /** 用与生产 Store 相同的裸工厂开启自有事务，测试拥有租户绑定及提交责任。 */
    private static org.apache.ibatis.session.SqlSession borrowedSession() throws Exception {
        var sql = borrowedSessions.openSession(false);
        try (var statement = sql.getConnection().prepareStatement("SELECT set_config('app.tenant_id','7',true)")) {
            statement.execute(); return sql;
        } catch (Exception failure) { sql.close(); throw failure; }
    }

    @Test void rejectionSurvivesServiceRecreationButNewSourceIdentityAndPolicyCanBeProjected() {
        var old = version(1, "event-1", "policy-1");
        assertTrue(mailbox.recordProjectionRejection("7", "session", old, "UNSUPPORTED_ACTION_FORMAT"));
        var count = new AtomicInteger();
        var restarted = newMailbox();
        assertThrows(SessionOutboxProjectionRejectedException.class,
                () -> restarted.projectOutputsIncrementally("7", "session", old, checkpoint -> { count.incrementAndGet(); return batch("{}"); }));
        assertEquals(0, count.get());
        assertFalse(restarted.recordProjectionRejection("7", "session", old, "UNSUPPORTED_ACTION_FORMAT"));
        for (var updated : java.util.List.of(version(1, "event-1", "policy-2"), version(1, "event-replaced", "policy-2"), version(2, "event-2", "policy-2")))
            restarted.projectOutputsIncrementally("7", "session", updated, checkpoint -> { count.incrementAndGet(); return batch("{}"); });
        assertEquals(3, count.get());
        assertNull(sequences.projectionRecord(7L, "session").errorCode());
        assertEquals(2L, sequences.projectionRecord(7L, "session").sourceSequence());
    }

    @Test void failedBatchRollsBackBeforeRecordingRejectionAndCommittedCursorIgnoresUncommittedRows() {
        var rejected = version(3, "source-3", "policy");
        var unavailable = new ChatHistoryUnavailableException(ChatHistoryUnavailableException.Reason.UNREADABLE_RUNTIME_EVENT, null);
        assertThrows(ChatHistoryUnavailableException.class, () -> mailbox.projectOutputsIncrementally("7", "session", rejected, checkpoint -> {
            sequences.reserve(7L, "session", 1);
            scopedJdbc.update("INSERT INTO nw_chat_session_outbox(id,tenant_id,session_id,event_id,session_sequence,event_type) VALUES(1,7,'session','not-committed',1,'process.started')");
            throw unavailable;
        }));
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox", Integer.class));
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_sequences", Integer.class));
        assertTrue(mailbox.recordProjectionRejection("7", "session", rejected, unavailable.reason().name()));
        assertEquals(0L, admin.queryForObject("SELECT next_sequence FROM nw_chat_session_outbox_sequences", Long.class));
        assertNull(mailbox.committedCursor("7","session"));
        admin.update("INSERT INTO nw_chat_session_outbox(id,tenant_id,session_id,event_id,session_sequence,event_type) VALUES"
                + "(10,7,'session','committed-1',1,'message.delta'),"
                + "(11,7,'session','committed-2',2,'message.delta'),"
                + "(12,7,'session','committed-3',3,'message.delta')");
        assertEquals("committed-3", mailbox.committedCursor("7", "session"));
    }

    @Test void lockedSuccessWinsAgainstLateRejectionAndOlderSuccessCannotRegressTheWatermark() throws Exception {
        var current = version(2, "source-2", "policy");
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var success = executor.submit(() -> {
                TenantContext.setTenantId("7");
                try { mailbox.projectOutputsIncrementally("7", "session", current, checkpoint -> {
                    entered.countDown();
                    try { assertTrue(finish.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    return batch("{}");
                }); } finally { TenantContext.clear(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var rejection = executor.submit(() -> {
                TenantContext.setTenantId("7");
                try { return mailbox.recordProjectionRejection("7", "session", current, "UNSUPPORTED_ACTION_FORMAT"); }
                finally { TenantContext.clear(); }
            });
            finish.countDown(); success.get(5, TimeUnit.SECONDS);
            assertFalse(rejection.get(5, TimeUnit.SECONDS));
        } finally { finish.countDown(); }
        var newer = version(3, "source-3", "policy");
        assertTrue(mailbox.recordProjectionRejection("7", "session", newer, "UNSUPPORTED_ACTION_FORMAT"));
        mailbox.projectOutputsIncrementally("7", "session", current, checkpoint -> batch("{}"));
        assertEquals(3L, sequences.projectionRecord(7L, "session").sourceSequence());
        assertEquals("UNSUPPORTED_ACTION_FORMAT", sequences.projectionRecord(7L, "session").errorCode());
    }

    @Test void tenantRlsPreventsForgedProjectionOwnerAndLeavesOtherReceiptsIntact() {
        assertTrue(mailbox.recordProjectionRejection("7", "session", version(1, "source", "policy"), "UNSUPPORTED_ACTION_FORMAT"));
        assertThrows(RuntimeException.class, () -> mailbox.recordProjectionRejection("8", "foreign", version(1, "source", "policy"), "UNSUPPORTED_ACTION_FORMAT"));
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_sequences", Integer.class));
        assertEquals("UNSUPPORTED_ACTION_FORMAT", sequences.projectionRecord(7L, "session").errorCode());
    }

    /** checkpoint与处理回执独立：旧政策可推进自身成功水位，但不能覆盖较新成功或永久拒绝。 */
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mergedCompletionAdvancesCheckpointWhilePreservingNewerReceipt(boolean rejected) {
        mailbox.projectOutputsIncrementally("7", "session", version(1, "source-1", "policy"),
                checkpoint -> batch("{\"step\":1}", output("first")));
        var newer = version(5, "source-5", "newer-policy");
        if (rejected) assertTrue(mailbox.recordProjectionRejection("7", "session", newer, "UNSUPPORTED_ACTION_FORMAT"));
        else mailbox.projectOutputsIncrementally("7", "session", newer, checkpoint -> batch("{\"step\":5}"));
        var receipt = sequences.projectionRecord(7L, "session");
        var current = version(2, "source-2", "policy");
        var rows = mailbox.projectOutputsIncrementally("7", "session", current, checkpoint -> {
            assertEquals(rejected ? 1 : 0, checkpoint.sequence());
            if (rejected) assertTrue(checkpoint.stateJson().contains("1"));
            else assertEquals("{}", checkpoint.stateJson());
            return batch("{\"step\":2}", output("second"));
        });
        assertEquals(2L, rows.getFirst().getSessionSequence());
        assertEquals(receipt, sequences.projectionRecord(7L, "session"));
        var checkpoint = sequences.projectionCheckpoint(7L, "session");
        assertEquals(2, checkpoint.sequence());
        assertEquals("source-2", checkpoint.eventId());
        assertEquals("policy", checkpoint.policyVersion());
        assertTrue(checkpoint.stateJson().contains("2"));
        newMailbox().projectOutputsIncrementally("7", "session", current, ignored -> {
            fail("same committed source must not project twice"); return batch("{}");
        });
        mailbox.projectOutputsIncrementally("7", "session", version(2, "source-2", "changed-policy"), previous -> {
            assertEquals(SessionOutboxProjectionCheckpoint.empty("changed-policy"), previous);
            return batch("{\"changed\":true}");
        });
        assertEquals(receipt, sequences.projectionRecord(7L, "session"));
        assertEquals("changed-policy", sequences.projectionCheckpoint(7L, "session").policyVersion());
        assertEquals(2L, admin.queryForObject("SELECT next_sequence FROM nw_chat_session_outbox_sequences", Long.class));
    }

    @Test void projectionNotificationFollowsMergedCheckpointCommitAndIgnoresRollback() {
        try (var observation = mailbox.observeOutputs("7", "session")) {
            mailbox.projectOutputsIncrementally("7", "session", version(1, "source-1", "policy"),
                    checkpoint -> batch("{\"accepted\":true}", output("first")));
            assertTrue(observation.version() > 0);
            long committed = observation.version();
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> mailbox.projectOutputsIncrementally("7", "session", version(2, "source-2", "policy"),
                            checkpoint -> batch("[]", output("rolled-back"))));
            assertEquals(committed, observation.version());
            assertEquals(1, sequences.projectionCheckpoint(7L, "session").sequence());
            assertEquals(1L, sequences.projectionRecord(7L, "session").sourceSequence());
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox", Integer.class));
        }
    }

    @Test void successfulCheckpointSurvivesRestartSkipsDuplicateAndRollsBackWithFailedOutput() {
        var first = version(1, "source-1", "policy");
        mailbox.projectOutputsIncrementally("7", "session", first, checkpoint -> {
            assertEquals(0, checkpoint.sequence()); return batch("{\"open\":\"process-1\"}");
        });
        newMailbox().projectOutputsIncrementally("7", "session", first, checkpoint -> {
            fail("same source must not execute projection again"); return batch("{}");
        });
        var second = version(2, "source-2", "policy");
        assertThrows(IllegalStateException.class, () -> mailbox.projectOutputsIncrementally("7", "session", second, checkpoint -> {
            assertEquals(1, checkpoint.sequence());
            assertTrue(checkpoint.stateJson().contains("process-1"));
            sequences.reserve(7L, "session", 1); throw new IllegalStateException("failed batch");
        }));
        assertEquals(1, sequences.projectionCheckpoint(7L, "session").sequence());
        assertEquals(0L, admin.queryForObject("SELECT next_sequence FROM nw_chat_session_outbox_sequences", Long.class));
        newMailbox().projectOutputsIncrementally("7", "session", second, checkpoint -> batch("{}"));
        assertEquals(2, sequences.projectionCheckpoint(7L, "session").sequence());
    }

    @Test void checkpointConstraintRejectsPartialOrNonObjectState() {
        mailbox.recordProjectionRejection("7", "session", version(1, "source", "policy"), "invalid");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> admin.update(
                "UPDATE nw_chat_session_outbox_sequences SET projection_checkpoint_state='{}'::jsonb"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> admin.update(
                "UPDATE nw_chat_session_outbox_sequences SET projection_checkpoint_sequence=1, projection_checkpoint_event_id='source', "
                + "projection_checkpoint_policy='policy', projection_checkpoint_state='[]'::jsonb"));
    }

    @Test void readOnlyReceiptSkipsCoveredSourceButKeepsIdentityPolicyAndLockedRecheck() {
        var first = version(1, "first", "policy");
        assertFalse(mailbox.projectionAlreadyHandled("7", "session", first));
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_sequences", Integer.class));
        mailbox.projectOutputsIncrementally("7", "session", first, checkpoint -> batch("{}"));
        var restarted = newMailbox();
        assertTrue(restarted.projectionAlreadyHandled("7", "session", first));
        assertTrue(restarted.projectionAlreadyHandled("7", "session", version(0, "", "policy")));
        assertFalse(restarted.projectionAlreadyHandled("7", "session", version(1, "other", "policy")));
        assertFalse(restarted.projectionAlreadyHandled("7", "session", version(1, "first", "changed-policy")));
        assertFalse(restarted.projectionAlreadyHandled("7", "session", version(2, "second", "policy")));

        var second = version(2, "second", "policy");
        // 两个读者可能均看到待处理；真正投影仍在锁内判断，不重复执行。
        assertFalse(mailbox.projectionAlreadyHandled("7", "session", second));
        restarted.projectOutputsIncrementally("7", "session", second, checkpoint -> batch("{}"));
        mailbox.projectOutputsIncrementally("7", "session", second, checkpoint -> {
            fail("locked recheck must reject duplicate projection"); return batch("{}");
        });
        var rejected = version(3, "rejected", "policy");
        assertTrue(mailbox.recordProjectionRejection("7", "session", rejected, "UNSUPPORTED_ACTION_FORMAT"));
        assertThrows(SessionOutboxProjectionRejectedException.class,
                () -> restarted.projectionAlreadyHandled("7", "session", rejected));
        assertFalse(restarted.projectionAlreadyHandled("7", "session", version(3, "rejected", "new-policy")));
        assertFalse(restarted.projectionAlreadyHandled("8", "session", first));
    }

    @Test void mixedNewAndReplayedBatchUsesOneContiguousRangeAndPreservesOriginalRows() {
        subscribe();
        var initial = mailbox.appendOutput("7", "session", "old", "message.delta", Map.of("value", "original"));
        var stored = sessions.getMapper(SessionOutboxMapper.class).findEvents(7L, new String[]{"old"}).getFirst();
        var rows = mailbox.projectOutputsIncrementally("7", "session", version(1, "source-1", "policy"),
                checkpoint -> batch("{}", output("new-1"), output("old"), output("new-1"), output("new-2")));
        assertEquals(List.of("new-1", "old", "new-1", "new-2"), rows.stream().map(SessionOutboxEntry::getEventId).toList());
        assertEquals(List.of(2L, 1L, 2L, 3L), rows.stream().map(SessionOutboxEntry::getSessionSequence).toList());
        assertEquals(initial.getEventId(), rows.get(1).getEventId());
        assertEquals(stored.getId(), sessions.getMapper(SessionOutboxMapper.class).findEvents(7L, new String[]{"old"}).getFirst().getId());
        assertEquals(stored.getCreatedAt(), rows.get(1).getCreatedAt());
        assertEquals(Map.of("value", "original"), rows.get(1).getPayload());
        assertEquals(rows.getFirst(), rows.get(2));
        assertEquals(3L, admin.queryForObject("SELECT next_sequence FROM nw_chat_session_outbox_sequences", Long.class));
        assertEquals(6, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_webhook_deliveries", Integer.class));
        var replay = mailbox.projectOutputsIncrementally("7", "session", version(2, "source-2", "policy"),
                checkpoint -> batch("{}", output("old"), output("new-1"), output("new-2")));
        assertEquals(List.of(1L, 2L, 3L), replay.stream().map(SessionOutboxEntry::getSessionSequence).toList());
        assertEquals(3L, admin.queryForObject("SELECT next_sequence FROM nw_chat_session_outbox_sequences", Long.class));
        assertEquals(6, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_webhook_deliveries", Integer.class));
        assertThrows(IllegalStateException.class, () -> mailbox.appendOutput("7", "foreign-session", "old", "message.delta", Map.of()));
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_sequences", Integer.class));
    }

    @Test void failedCheckpointRollsBackOutputRangeAndWebhookResponsibilitiesTogether() {
        subscribe();
        mailbox.projectOutputsIncrementally("7", "session", version(1, "source-1", "policy"),
                checkpoint -> batch("{\"accepted\":true}", output("first")));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> mailbox.projectOutputsIncrementally("7", "session", version(2, "source-2", "policy"),
                        checkpoint -> batch("[]", output("rolled-back-1"), output("rolled-back-2"))));
        assertEquals(1L, admin.queryForObject("SELECT next_sequence FROM nw_chat_session_outbox_sequences", Long.class));
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox", Integer.class));
        assertEquals(2, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_webhook_deliveries", Integer.class));
        assertEquals(1, sequences.projectionCheckpoint(7L, "session").sequence());
        assertEquals(1, sequences.projectionRecord(7L, "session").sourceSequence());
        var committed = newMailbox().projectOutputsIncrementally("7", "session", version(2, "source-2", "policy"),
                checkpoint -> batch("{}", output("rolled-back-1"), output("rolled-back-2")));
        assertEquals(List.of(2L, 3L), committed.stream().map(SessionOutboxEntry::getSessionSequence).toList());
        assertEquals(6, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_webhook_deliveries", Integer.class));
    }

    @Test void concurrentBatchesSerializeSourceOrderAndReuseCommittedOutputIdentities() throws Exception {
        subscribe();
        var entered = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                TenantContext.setTenantId("7");
                try {
                    return mailbox.projectOutputsIncrementally("7", "session", version(1, "source-1", "policy"), checkpoint -> {
                        entered.countDown();
                        try { assertTrue(finish.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                        return batch("{}", output("first"), output("shared"));
                    });
                } finally { TenantContext.clear(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                TenantContext.setTenantId("7");
                try {
                    secondStarted.countDown();
                    return newMailbox().projectOutputsIncrementally("7", "session", version(2, "source-2", "policy"), checkpoint -> {
                        assertEquals(1, checkpoint.sequence());
                        return batch("{}", output("shared"), output("last"));
                    });
                } finally { TenantContext.clear(); }
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            finish.countDown();
            assertEquals(List.of(1L, 2L), first.get(5, TimeUnit.SECONDS).stream().map(SessionOutboxEntry::getSessionSequence).toList());
            assertEquals(List.of(2L, 3L), second.get(5, TimeUnit.SECONDS).stream().map(SessionOutboxEntry::getSessionSequence).toList());
        } finally { finish.countDown(); }
        assertEquals(3, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox", Integer.class));
        assertEquals(6, admin.queryForObject("SELECT count(*) FROM nw_chat_session_outbox_webhook_deliveries", Integer.class));
        assertEquals(2, sequences.projectionCheckpoint(7L, "session").sequence());
    }

    private void subscribe() {
        admin.update("INSERT INTO nw_chat_session_event_subscriptions(id,tenant_id,session_id,endpoint_url,signing_secret_encrypted) VALUES "
                + "(100,7,NULL,'https://example.invalid/all','test'),(101,7,'session','https://example.invalid/session','test'),"
                + "(102,7,'other','https://example.invalid/other','test'),(103,8,NULL,'https://example.invalid/foreign','test')");
    }

    private static SessionOutboxOutput output(String eventId) {
        return new SessionOutboxOutput(eventId, "message.delta", Map.of("event", "message.delta", "data", Map.of("value", eventId)),
                new TraceContext("input", "root", "trace", "span", "runtime-source", null), "run");
    }

    private static SessionOutboxProjectionBatch batch(String state, SessionOutboxOutput... outputs) {
        return new SessionOutboxProjectionBatch(state, java.util.List.of(outputs));
    }

    private static SessionOutboxProjectionSource version(long sequence, String event, String policy) {
        return new SessionOutboxProjectionSource(sequence, event, policy);
    }
}

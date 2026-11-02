package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionStorePort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionStorePort.*;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 完整迁移链下验证首次页面并发初始化的锁顺序及归属隔离。 */
@Testcontainers(disabledWithoutDocker = false)
class SessionActivationConcurrencyTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("skillforge").withPassword("skillforge_dev");

    @BeforeAll
    static void migrate() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE activation_test LOGIN PASSWORD 'test' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO activation_test");
            statement.execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO activation_test");
        }
    }

    @Test
    void waitingActivationDoesNotHoldSessionLockNeededByConcurrentCreate() throws Exception {
        var owner = owner("lock-order", 1);
        var store = new JdbcSessionStorePort(dataSource("activation-cas"));
        var creation = create(owner);
        assertInstanceOf(SessionCreateResult.Created.class, store.create(creation));
        var executor = Executors.newSingleThreadExecutor();
        try (var blocker = POSTGRES.createConnection(""); var statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            try {
                // 模拟 create 已取得 owner root，尚未读取 Session 的确定性暂停点。
                statement.executeQuery("SELECT 1 FROM agent_runtime_session_owners WHERE session_id='lock-order' FOR UPDATE").close();
                Future<SessionCasResult> activation = executor.submit(() -> store.compareAndSet(activate(owner)));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean waiting = false;
                try (var observer = POSTGRES.createConnection(""); var probe = observer.createStatement()) {
                    while (!waiting && System.nanoTime() < deadline) {
                        try (var rows = probe.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='activation-cas' AND wait_event_type='Lock'")) {
                            rows.next();
                            waiting = rows.getInt(1) == 1;
                        }
                        if (!waiting) Thread.yield();
                    }
                }
                assertTrue(waiting, "CAS must be waiting on the held owner root");
                // 旧实现此时已占住 Session，NOWAIT 确定性失败；新实现可以继续 create。
                statement.executeQuery("SELECT 1 FROM agent_runtime_sessions WHERE session_id='lock-order' FOR UPDATE NOWAIT").close();
                blocker.commit();
                assertInstanceOf(SessionCasResult.Applied.class, activation.get(15, TimeUnit.SECONDS));
                assertInstanceOf(SessionCreateResult.Replayed.class, store.create(creation));
                assertInstanceOf(SessionCasResult.Replayed.class, store.compareAndSet(activate(owner)));
                assertEquals(2, store.find(new SessionLookup(owner)).orElseThrow().sessionRevision());
            } finally {
                blocker.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void wrongOwnerAndMissingSessionStayNotFound() {
        var store = new JdbcSessionStorePort(dataSource("activation-isolation"));
        var owner = owner("ownership", 1);
        store.create(create(owner));
        assertInstanceOf(SessionCasResult.NotFound.class, store.compareAndSet(activate(owner("ownership", 2))));
        assertInstanceOf(SessionCasResult.NotFound.class, store.compareAndSet(activate(owner("absent", 1))));
        assertEquals(SessionStatus.CREATED, store.find(new SessionLookup(owner)).orElseThrow().status());
    }

    private static PGSimpleDataSource dataSource(String name) {
        var source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser("activation_test");
        source.setPassword("test");
        source.setApplicationName(name);
        return source;
    }

    private static SessionOwner owner(String session, long user) {
        return SessionOwner.user(new SessionRef(new TenantId("activation-tenant"), session),
                new AgentId("activation-agent"), new EndUserId(user));
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope("activation-trace", "activation-span", new InputId("activation-root"), Optional.empty());
    }

    private static CreateSession create(SessionOwner owner) {
        return new CreateSession(owner, "chat", "open-platform:chat", "default", new InputId("create"), trace(), Instant.now());
    }

    private static ChangeSessionStatus activate(SessionOwner owner) {
        return new ChangeSessionStatus(owner, SessionStatus.CREATED, SessionStatus.ACTIVE, 1,
                new InputId("activate"), trace(), Instant.EPOCH);
    }
}

package com.skillforge.runtime.adapter.jdbc.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 自有 PostgreSQL 空库验证公开初始化、显式接管、结构拒绝与真实受限角色隔离。 */
@Testcontainers(disabledWithoutDocker = false)
class RuntimeJdbcSchemaTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("schema_admin").withUsername("schema_admin").withPassword("schema_test_password");
    private PGSimpleDataSource source;

    @BeforeEach void emptyDatabase() throws Exception {
        String database = "runtime_" + UUID.randomUUID().toString().replace("-", "");
        var admin = source(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute(admin, "CREATE DATABASE " + database);
        source = source(POSTGRES.getJdbcUrl().replace("/schema_admin", "/" + database),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test void installsOnlyRuntimeObjectsAndRepeatedInstallPreservesHistory() throws Exception {
        RuntimeJdbcSchema.install(source);
        RuntimeJdbcSchema.install(source);
        RuntimeJdbcSchema.validate(source);
        assertEquals(1, count("SELECT count(*) FROM agent_runtime_schema_history WHERE version='1' AND success"));
        assertEquals(0, count("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename IN ('nw_tenants','nw_agents','nw_external_invocations','flyway_schema_history')"));
        assertEquals(0, count("SELECT count(*) FROM pg_roles WHERE rolname='skillforge'"));
        // 自有 schema 的业务表和迁移历史都必须在 SDK 命名空间，不能混入平台或旧预算表。
        assertEquals(0, count("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND left(tablename,14)<>'agent_runtime_'"));
        assertEquals(121, count("SELECT count(*) FROM pg_tables WHERE schemaname='public'"));
    }

    @Test void explicitlyAdoptsExactUnmanagedStructureAndRejectsRepeatedAdoption() throws Exception {
        RuntimeJdbcSchema.install(source);
        execute(source, "DROP TABLE agent_runtime_schema_history");
        RuntimeJdbcSchema.adopt(source);
        assertEquals(1, count("SELECT count(*) FROM agent_runtime_schema_history WHERE type='BASELINE' AND version='1' AND success"));
        RuntimeJdbcSchema.install(source);
        assertThrows(IllegalStateException.class, () -> RuntimeJdbcSchema.adopt(source));
    }

    @Test void unmanagedNonemptySchemaRequiresExplicitAdoption() throws Exception {
        execute(source, "CREATE TABLE unrelated_host_data(id bigint PRIMARY KEY)");
        assertThrows(RuntimeException.class, () -> RuntimeJdbcSchema.install(source));
        assertEquals(0, count("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='agent_runtime_sessions'"));
    }

    @Test void rejectsUnsafeRlsAndDoesNotCreateAdoptionHistory() throws Exception {
        RuntimeJdbcSchema.install(source);
        execute(source, "DROP TABLE agent_runtime_schema_history");
        execute(source, "ALTER TABLE agent_runtime_session_owners NO FORCE ROW LEVEL SECURITY");
        assertThrows(IllegalStateException.class, () -> RuntimeJdbcSchema.adopt(source));
        assertEquals(0, count("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='agent_runtime_schema_history'"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "ALTER TABLE agent_runtime_session_owners ADD COLUMN unexpected text",
            "ALTER TABLE agent_runtime_session_owners ADD CONSTRAINT unexpected CHECK (length(tenant_id)<100)",
            "CREATE INDEX unexpected ON agent_runtime_session_owners(tenant_id)",
            "ALTER TABLE agent_runtime_session_events DISABLE TRIGGER USER",
            "CREATE OR REPLACE FUNCTION agent_runtime_session_event_host_referenced(t text,s text,eid text) RETURNS boolean LANGUAGE plpgsql AS $$ BEGIN RETURN true; END $$"
    })
    void detectsColumnConstraintIndexTriggerAndFunctionDrift(String mutation) throws Exception {
        RuntimeJdbcSchema.install(source);
        execute(source, "DROP TABLE agent_runtime_schema_history");
        execute(source, mutation);
        assertThrows(IllegalStateException.class, () -> RuntimeJdbcSchema.adopt(source));
        assertEquals(0, count("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='agent_runtime_schema_history'"));
    }

    @Test void restrictedRuntimeRoleCannotReadOrWriteAnotherTenant() throws Exception {
        RuntimeJdbcSchema.install(source);
        String role = "runtime_" + UUID.randomUUID().toString().replace("-", "");
        execute(source, "CREATE ROLE " + role + " LOGIN PASSWORD 'restricted_test_password' NOSUPERUSER NOBYPASSRLS");
        execute(source, "GRANT USAGE ON SCHEMA public TO " + role);
        execute(source, "GRANT SELECT, INSERT ON agent_runtime_session_owners TO " + role);
        var restricted = source(source.getURL(), role, "restricted_test_password");
        try (var connection = restricted.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id','tenant-a',false)");
            statement.execute("INSERT INTO agent_runtime_session_owners(tenant_id,session_id,agent_id,end_user_id,target_kind,target_id) VALUES('tenant-a','session-a','agent-a',101,'AGENT','agent-a')");
            statement.execute("SELECT set_config('app.tenant_id','tenant-b',false)");
            try (var rows = statement.executeQuery("SELECT count(*) FROM agent_runtime_session_owners")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO agent_runtime_session_owners(tenant_id,session_id,agent_id,end_user_id,target_kind,target_id) VALUES('tenant-a','session-forged','agent-a',101,'AGENT','agent-a')"));
        }
    }

    private int count(String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }
    private static void execute(PGSimpleDataSource source, String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static PGSimpleDataSource source(String url, String username, String password) {
        var source = new PGSimpleDataSource(); source.setURL(url); source.setUser(username); source.setPassword(password); return source;
    }
}

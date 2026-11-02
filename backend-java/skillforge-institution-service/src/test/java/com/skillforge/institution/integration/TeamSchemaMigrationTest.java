package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在 pgvector PostgreSQL 上执行完整迁移链，锁定 Team Runtime 的数据库契约。 */
@Testcontainers(disabledWithoutDocker = false)
class TeamSchemaMigrationTest {

    private static final List<String> TEAM_TABLES = List.of(
            "nw_agent_teams",
            "nw_chat_user_teams",
            "nw_agent_team_versions",
            "agent_runtime_dag_works",
            "agent_runtime_dag_admissions",
            "agent_runtime_dag_commands",
            "agent_runtime_dag_control_runs",
            "agent_runtime_dag_permits",
            "agent_runtime_dag_inputs",
            "agent_runtime_team_task_results",
            "agent_runtime_team_work_answers");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge")
            .withUsername("skillforge")
            .withPassword("skillforge_dev");

    @Test
    void completeMigrationChainInstallsTeamTablesConstraintsAndRls() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("172")
                .load().migrate();
        // 已有 Session 归属不可变；身份扩展需要保留数据，并在回填后恢复相同保护。
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent_runtime_session_owners (tenant_id, session_id, agent_id, end_user_id)
                    VALUES ('migration-tenant', 'migration-session', 'migration-agent', 1)
                    """);
            statement.execute("""
                    ALTER TABLE agent_runtime_session_owners
                    ENABLE ALWAYS TRIGGER agent_runtime_session_owner_immutable
                    """);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }

        var flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(6, scalar(statement, """
                    SELECT count(*) FROM flyway_schema_history
                    WHERE success AND version IN ('219', '220', '221', '222', '223', '224')
                    """));
            assertEquals(TEAM_TABLES.size(), scalar(statement, """
                    SELECT count(*) FROM pg_class
                    WHERE relnamespace = 'public'::regnamespace
                      AND relname IN (%s)
                    """.formatted(quoted(TEAM_TABLES))));
            assertEquals(TEAM_TABLES.size(), scalar(statement, """
                    SELECT count(*) FROM pg_class
                    WHERE relnamespace = 'public'::regnamespace
                      AND relname IN (%s)
                      AND relrowsecurity AND relforcerowsecurity
                    """.formatted(quoted(TEAM_TABLES))));
            assertEquals(TEAM_TABLES.size(), scalar(statement, """
                    SELECT count(*) FROM pg_policies
                    WHERE schemaname = 'public'
                      AND policyname = 'tenant_isolation'
                      AND tablename IN (%s)
                    """.formatted(quoted(TEAM_TABLES))));
            assertConstraint(statement, "nw_chat_sessions", "ck_skillforge_chat_sessions_team_id_nonblank");
            assertConstraint(statement, "nw_chat_sessions", "ck_skillforge_chat_sessions_target_exactly_one");
            assertConstraint(statement, "agent_runtime_dag_admissions",
                    "uq_agent_runtime_dag_admission_task_run");
            assertEquals(1, scalar(statement, """
                    SELECT count(*) FROM agent_runtime_session_owners
                    WHERE tenant_id = 'migration-tenant' AND session_id = 'migration-session'
                      AND agent_id = 'migration-agent' AND end_user_id = 1
                      AND target_kind = 'AGENT' AND target_id = 'migration-agent'
                    """));
            assertEquals(1, scalar(statement, """
                    SELECT count(*) FROM pg_trigger
                    WHERE tgrelid = 'agent_runtime_session_owners'::regclass
                      AND tgname = 'agent_runtime_session_owner_immutable' AND tgenabled = 'A'
                    """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    UPDATE agent_runtime_session_owners SET created_at = clock_timestamp()
                    WHERE tenant_id = 'migration-tenant' AND session_id = 'migration-session'
                    """));
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        assertTrue(flyway.validateWithResult().validationSuccessful);
    }

    private static void assertConstraint(Statement statement, String table, String name) throws SQLException {
        assertEquals(1, scalar(statement, """
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'public.%s'::regclass AND conname = '%s'
                """.formatted(table, name)));
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String quoted(List<String> values) {
        return values.stream().map(value -> "'%s'".formatted(value)).collect(java.util.stream.Collectors.joining(", "));
    }
}

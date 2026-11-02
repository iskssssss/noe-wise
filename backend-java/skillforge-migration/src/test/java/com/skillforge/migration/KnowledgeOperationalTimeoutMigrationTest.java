package com.skillforge.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在独立 PostgreSQL 使用真实运营配置表、约束和追加版本迁移验证升级。 */
@Testcontainers(disabledWithoutDocker = false)
class KnowledgeOperationalTimeoutMigrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("knowledge_settings_migration")
            .withUsername("skillforge")
            .withPassword("isolated-knowledge-migration");

    private static final String CURRENT_VALUES = """
            {"chunk_size":777,"chunk_overlap":13,"max_upload_mb":23,"max_chunks_per_document":1234,
             "default_indexing_mode":"economical","default_retrieval_mode":"full_text",
             "default_top_k":17,"default_score_threshold":0.67,"default_vector_weight":0.29}
            """;
    private static final String OLD_VALUES = """
            {"chunk_size":777,"chunk_overlap":13,"max_upload_mb":23,"max_chunks_per_document":1234,
             "default_indexing_mode":"economical","default_retrieval_mode":"full_text",
             "default_top_k":17,"default_score_threshold":0.67,"default_vector_weight":0.29,
             "embedding_timeout_seconds":47,"rerank_timeout_seconds":19}
            """;

    @BeforeAll
    static void installActualSettingsSchema() throws SQLException, IOException {
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            // 这两份真实迁移只需要平台成员主键外键，不依赖业务成员夹具。
            statement.execute("CREATE TABLE public.nw_platform_members (id bigint PRIMARY KEY)");
            statement.execute(resource("V154__platform_operational_settings.sql"));
            statement.execute(resource("V169__agent_context_policy_settings.sql"));
        }
    }

    @Test
    void appendsSystemRevisionAndPreservesHistoricalValuesAndAllOtherCurrentValues() throws Exception {
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                seed(connection, OLD_VALUES);
                statement.executeUpdate("""
                        INSERT INTO nw_platform_operational_setting_versions
                            (group_key,revision,values,actor_id,publication_source)
                        SELECT group_key,2,values,NULL,'system_release'
                        FROM nw_platform_operational_setting_versions WHERE group_key='knowledge' AND revision=1;
                        UPDATE nw_platform_operational_setting_groups SET current_revision=2 WHERE group_key='knowledge';
                        """);
                statement.execute(resource("V317__knowledge_operational_timeout_cleanup.sql"));
                assertEquals(3, scalar(statement, "SELECT current_revision FROM nw_platform_operational_setting_groups WHERE group_key='knowledge'"));
                assertEquals(3, scalar(statement, "SELECT count(*) FROM nw_platform_operational_setting_versions WHERE group_key='knowledge'"));
                assertValues(connection, 1, OLD_VALUES);
                assertValues(connection, 2, OLD_VALUES);
                assertValues(connection, 3, CURRENT_VALUES);
                assertEquals(1, scalar(statement, """
                        SELECT count(*) FROM nw_platform_operational_setting_versions
                        WHERE group_key='knowledge' AND revision=3
                          AND publication_source='system_release' AND actor_id IS NULL
                        """));
                assertEquals(1, scalar(statement, """
                        SELECT count(*) FROM nw_platform_operational_setting_groups
                        WHERE group_key='knowledge' AND current_revision=3 AND updated_by IS NULL
                        """));
                assertEquals(0, scalar(statement, "SELECT count(*) FROM nw_platform_operational_setting_audits"));
                // 再次读取/应用不会生成额外版本，也不能覆盖原历史。
                statement.execute(resource("V317__knowledge_operational_timeout_cleanup.sql"));
                assertEquals(3, scalar(statement, "SELECT count(*) FROM nw_platform_operational_setting_versions WHERE group_key='knowledge'"));
                var immutable = connection.setSavepoint();
                SQLException rejected = assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        UPDATE nw_platform_operational_setting_versions SET values='{}'::jsonb
                        WHERE group_key='knowledge' AND revision=1
                        """));
                assertEquals("55000", rejected.getSQLState());
                connection.rollback(immutable);
                connection.releaseSavepoint(immutable);
                assertValues(connection, 1, OLD_VALUES);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void currentKeysetAndAbsentGroupDoNotCreateAPublication() throws Exception {
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(resource("V317__knowledge_operational_timeout_cleanup.sql"));
                assertEquals(0, scalar(statement, "SELECT count(*) FROM nw_platform_operational_setting_versions"));
                seed(connection, CURRENT_VALUES);
                statement.execute(resource("V317__knowledge_operational_timeout_cleanup.sql"));
                assertEquals(1, scalar(statement, "SELECT current_revision FROM nw_platform_operational_setting_groups WHERE group_key='knowledge'"));
                assertEquals(1, scalar(statement, "SELECT count(*) FROM nw_platform_operational_setting_versions"));
                assertValues(connection, 1, CURRENT_VALUES);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void failedHeadSwitchRollsBackTheNewVersionAndRetainsTheOldCurrent() throws Exception {
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                seed(connection, OLD_VALUES);
                statement.execute("""
                        CREATE FUNCTION public.reject_test_knowledge_head_switch() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'injected head switch rejection' USING ERRCODE='23514'; END;
                        $$;
                        CREATE TRIGGER reject_test_knowledge_head_switch BEFORE UPDATE
                        ON public.nw_platform_operational_setting_groups FOR EACH ROW
                        EXECUTE FUNCTION public.reject_test_knowledge_head_switch();
                        """);
                var beforeMigration = connection.setSavepoint();
                SQLException rejected = assertThrows(SQLException.class,
                        () -> statement.execute(resource("V317__knowledge_operational_timeout_cleanup.sql")));
                assertEquals("23514", rejected.getSQLState());
                connection.rollback(beforeMigration);
                connection.releaseSavepoint(beforeMigration);
                assertEquals(1, scalar(statement, "SELECT current_revision FROM nw_platform_operational_setting_groups WHERE group_key='knowledge'"));
                assertEquals(1, scalar(statement, "SELECT count(*) FROM nw_platform_operational_setting_versions WHERE group_key='knowledge'"));
                assertValues(connection, 1, OLD_VALUES);
                assertEquals(0, scalar(statement, "SELECT count(*) FROM nw_platform_operational_setting_audits"));
            } finally {
                connection.rollback();
            }
        }
    }

    private static void seed(Connection connection, String values) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO nw_platform_operational_setting_versions(group_key,revision,values,actor_id)
                VALUES ('knowledge',1,CAST(? AS jsonb),NULL)
                """)) {
            insert.setString(1, values);
            assertEquals(1, insert.executeUpdate());
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO nw_platform_operational_setting_groups(group_key,current_revision) VALUES ('knowledge',1)");
        }
    }

    private static void assertValues(Connection connection, long revision, String expected) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT values=CAST(? AS jsonb) FROM nw_platform_operational_setting_versions
                WHERE group_key='knowledge' AND revision=?
                """)) {
            query.setString(1, expected);
            query.setLong(2, revision);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1));
            }
        }
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String resource(String file) throws IOException {
        try (var input = KnowledgeOperationalTimeoutMigrationTest.class.getClassLoader().getResourceAsStream("db/migration/" + file)) {
            assertNotNull(input, file);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

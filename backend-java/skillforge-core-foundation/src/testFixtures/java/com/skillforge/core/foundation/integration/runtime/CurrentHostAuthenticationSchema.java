package com.skillforge.core.foundation.integration.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/** 平台认证测试使用正式宿主迁移升级认证事实，不向 SDK 引入平台 schema。 */
public final class CurrentHostAuthenticationSchema {
    private CurrentHostAuthenticationSchema() { }

    public static void installAuthentication(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            installAuthentication(statement);
        }
    }

    public static void installAuthentication(Statement statement) throws Exception {
        if (hasTable(statement, "nw_runtime_authentication_facts")
                && !hasColumn(statement, "nw_runtime_authentication_facts", "target_kind")) {
            String sql = migration("V219__session_target_execution_identity.sql");
            executeSection(statement, sql,
                    "ALTER TABLE public.nw_runtime_authentication_facts",
                    "-- 先移除依赖旧 owner/stream Agent 键的外键");
            statement.execute(section(sql, "-- 旧 trigger function",
                    "CREATE OR REPLACE FUNCTION public.guard_agent_runtime_session_input_stream")
                    .replace("AND (columns.table_name LIKE 'agent_runtime_%' OR columns.table_name LIKE 'nw_runtime_%')",
                            "AND columns.table_name = 'nw_runtime_authentication_facts'"));
        }
    }

    private static boolean hasTable(Statement statement, String table) throws Exception {
        try (var rows = statement.getConnection().getMetaData().getTables(null, "public", table, new String[]{"TABLE"})) {
            return rows.next();
        }
    }

    private static boolean hasColumn(Statement statement, String table, String column) throws Exception {
        try (var rows = statement.getConnection().getMetaData().getColumns(null, "public", table, column)) {
            return rows.next();
        }
    }

    private static String section(String sql, String start, String end) {
        int from = sql.indexOf(start), to = sql.indexOf(end, from);
        assertTrue(from >= 0 && to > from, "migration section missing: " + start);
        return sql.substring(from, to);
    }

    private static String migration(String name) throws Exception {
        try (var resource = CurrentHostAuthenticationSchema.class.getClassLoader()
                .getResourceAsStream("db/migration/" + name)) {
            assertNotNull(resource);
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }
    }

    private static void executeSection(
            Statement statement, String sql, String start, String end) throws Exception {
        int from = sql.indexOf(start);
        int to = sql.indexOf(end, from);
        assertTrue(from >= 0 && to > from, "migration section missing: " + start);
        statement.execute(sql.substring(from, to));
    }
}

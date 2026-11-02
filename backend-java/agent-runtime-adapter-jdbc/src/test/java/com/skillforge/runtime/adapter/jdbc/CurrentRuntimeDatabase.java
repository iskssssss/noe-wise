package com.skillforge.runtime.adapter.jdbc;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** JDBC 契约测试在自有新库执行完整正式迁移，不维护另一套 Runtime schema。 */
public final class CurrentRuntimeDatabase {
    private static final List<String> SESSION_LOG_READS = List.of(
            "agent_runtime_session_events", "agent_runtime_session_history_retirements", "agent_runtime_session_event_tombstones",
            "agent_runtime_session_event_bodies", "agent_runtime_session_event_body_refs",
            "agent_runtime_session_event_payload_archives");
    private static final List<String> PERSISTENCE_READS = List.of(
            "agent_runtime_session_checkpoint_bodies", "agent_runtime_session_checkpoint_body_refs",
            "agent_runtime_input_ledger_archives", "agent_runtime_session_admission_prepare_archives",
            "agent_runtime_session_commit_journal_archives", "agent_runtime_run_record_archives",
            "agent_runtime_session_inbox_archives", "agent_runtime_run_mutation_archives",
            "agent_runtime_session_inbox_mutation_archives", "agent_runtime_run_publication_mutation_archives",
            "agent_runtime_session_checkpoint_body_archives", "agent_runtime_session_checkpoint_archives",
            "agent_runtime_session_checkpoint_archive_body_refs", "agent_runtime_session_owner_retirements");

    private CurrentRuntimeDatabase() { }

    /** 调用前数据库必须为空；Flyway 对非空未纳管 schema 或不完整迁移直接失败。 */
    public static void migrate(String jdbcUrl, String admin, String password) throws Exception {
        var source = new org.postgresql.ds.PGSimpleDataSource();
        source.setURL(jdbcUrl); source.setUser(admin); source.setPassword(password);
        com.skillforge.runtime.adapter.jdbc.schema.RuntimeJdbcSchema.install(source);
    }

    /** 当前 SessionLog 正文与退役元数据只授予读取，原领域写权限由调用夹具保留。 */
    public static void grantSessionLogReads(Statement statement, String role) throws SQLException {
        grantReads(statement, role, SESSION_LOG_READS);
        // PostgreSQL 的 FOR KEY SHARE 要求至少一个列的 UPDATE 权限；正式不可变触发器仍拒绝事件修改。
        statement.execute("GRANT UPDATE(event_id) ON public.agent_runtime_session_events TO \""
                + role.replace("\"", "\"\"") + "\"");
    }

    /** 当前私有账本与 checkpoint 的归档读取；不赋予归档写入或绕过 RLS 的权限。 */
    public static void grantPersistenceReads(Statement statement, String role) throws SQLException {
        grantSessionLogReads(statement, role);
        grantReads(statement, role, PERSISTENCE_READS);
    }

    private static void grantReads(Statement statement, String role, List<String> tables) throws SQLException {
        String names = tables.stream().map(table -> "public." + table).collect(java.util.stream.Collectors.joining(","));
        statement.execute("GRANT SELECT ON " + names + " TO \"" + role.replace("\"", "\"\"") + "\"");
    }
}

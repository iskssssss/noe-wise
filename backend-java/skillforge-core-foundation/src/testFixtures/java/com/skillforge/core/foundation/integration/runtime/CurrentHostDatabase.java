package com.skillforge.core.foundation.integration.runtime;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;

/** 平台适配集成测试在自有空库安装完整正式宿主 schema。 */
public final class CurrentHostDatabase {
    private CurrentHostDatabase() { }

    /** 调用前数据库必须为空；Flyway 对非空未纳管 schema 或不完整迁移直接失败。 */
    public static void migrate(String jdbcUrl, String admin, String password) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, admin, password);
                var statement = connection.createStatement()) {
            statement.execute("""
                    DO $$ BEGIN
                        IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge') THEN
                            CREATE ROLE skillforge NOLOGIN NOSUPERUSER NOBYPASSRLS;
                        END IF;
                    END $$
                    """);
        }
        Flyway.configure().dataSource(jdbcUrl, admin, password).locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(jdbcUrl, admin, password);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        SELECT EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='315' AND success)
                            AND to_regclass('public.agent_runtime_sessions') IS NOT NULL
                            AND to_regclass('public.agent_runtime_runs') IS NOT NULL
                            AND to_regclass('public.agent_runtime_input_ledger_archives') IS NOT NULL
                            AND to_regclass('public.agent_runtime_session_history_retirements') IS NOT NULL
                            AND EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public'
                                AND table_name='agent_runtime_execution_runs' AND column_name='requester_kind')
                            AND EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public'
                                AND table_name='agent_runtime_executions' AND column_name='subject_id')
                        """)) {
            if (!rows.next() || !rows.getBoolean(1)) throw new SQLException("current_host_fixture_migration_incomplete");
        }
    }

}

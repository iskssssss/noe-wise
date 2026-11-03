package com.skillforge.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在独立 pgvector PostgreSQL 库执行完整迁移链并验证当前工具部署引用约束。 */
@Testcontainers(disabledWithoutDocker = false)
class CompleteFlywayMigrationChainTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge_migration")
            .withUsername("skillforge")
            .withPassword("skillforge_dev");

    @Test
    void migratesFreshDatabaseThroughLatestResolvedVersion() throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        var latestResolvedVersion = Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .max(MigrationVersion::compareTo)
                .orElseThrow();

        flyway.migrate();

        assertNotNull(flyway.info().current());
        assertEquals(latestResolvedVersion, flyway.info().current().getVersion());
        assertEquals(0, flyway.info().pending().length);
        assertTrue(flyway.validateWithResult().validationSuccessful);
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement,
                    "SELECT count(*) FROM flyway_schema_history WHERE success = false"));
            // 非系统 Agent 模板由管理员写入数据库，新环境迁移不能注入业务角色正文。
            assertEquals(0, scalar(statement, "SELECT count(*) FROM public.nw_agent_templates"));
            assertEquals(1, scalar(statement,
                    "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '1'"));
            assertEquals(1, scalar(statement,
                    "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '%s'"
                            .formatted(latestResolvedVersion.getVersion())));
            assertEquals(0, scalar(statement,
                    "SELECT count(*) FROM pg_class WHERE oid = to_regclass('public.nw_workflow_tool_connector_bindings')"));
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM pg_class
                    WHERE oid IN (to_regclass('public.tool_definitions'),
                                  to_regclass('public.invoice_line_items'))
                    """));
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM flyway_schema_history
                    WHERE version IN ('12', '47', '85')
                    """));
            verifyMemoryFactOwnership(statement);
            verifyPlatformIntegrationBaseline(statement);
            verifyDatabaseNamespaces(connection, statement);
            assertEquals(1, scalar(statement, """
                    SELECT count(*) FROM pg_index
                    WHERE indexrelid=to_regclass('public.idx_session_events_input_first_source')
                      AND indrelid='public.agent_runtime_session_events'::regclass
                      AND indisvalid AND NOT indisunique
                      AND pg_get_indexdef(indexrelid) LIKE
                          '%(tenant_id, target_kind, target_id, session_id, end_user_id, run_id, input_id, session_sequence)%'
                      AND obj_description(indexrelid, 'pg_class') IS NOT NULL
                    """));
            verifyConnectorDeploymentReferences(connection);
            verifyWorkflowCreateReceiptIsolation(connection);
            verifyConnectorDeleteReceiptIsolation(connection);
        }
    }

    private static void verifyConnectorDeleteReceiptIsolation(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT count(*) FROM pg_constraint WHERE conrelid='public.nw_connector_delete_receipts'::regclass AND contype='f'"));
            statement.execute("CREATE ROLE connector_delete_probe NOLOGIN");
            statement.execute("GRANT SELECT, INSERT ON public.nw_connector_delete_receipts TO connector_delete_probe");
            statement.execute("SET LOCAL ROLE connector_delete_probe");
            statement.execute("SELECT set_config('app.tenant_id','7',true)");
            statement.execute("INSERT INTO public.nw_connector_delete_receipts(request_id,tenant_id,actor_ref,instance_id) VALUES ('00000000-0000-4000-8000-000000000001',7,'70','deleted-instance')");
            assertEquals(1, scalar(statement, "SELECT count(*) FROM public.nw_connector_delete_receipts"));
            statement.execute("SELECT set_config('app.tenant_id','8',true)");
            assertEquals(0, scalar(statement, "SELECT count(*) FROM public.nw_connector_delete_receipts"));
            Savepoint before = connection.setSavepoint();
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO public.nw_connector_delete_receipts(request_id,tenant_id,actor_ref,instance_id) VALUES ('00000000-0000-4000-8000-000000000002',7,'70','deleted-instance')"));
            connection.rollback(before);
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    /** 限权角色仅能读取和写入当前机构的创建回执。 */
    private static void verifyWorkflowCreateReceiptIsolation(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement=connection.createStatement()) {
            statement.execute("CREATE ROLE workflow_receipt_probe NOLOGIN");
            statement.execute("GRANT SELECT, INSERT ON public.nw_workflow_create_receipts TO workflow_receipt_probe");
            statement.execute("SET LOCAL ROLE workflow_receipt_probe");
            statement.execute("SELECT set_config('app.tenant_id','7',true)");
            statement.execute("INSERT INTO public.nw_workflow_create_receipts(request_id,tenant_id,actor_ref,request_fingerprint) VALUES ('00000000-0000-4000-8000-000000000001',7,'70',repeat('a',64))");
            assertEquals(1,scalar(statement,"SELECT count(*) FROM public.nw_workflow_create_receipts"));
            statement.execute("SELECT set_config('app.tenant_id','8',true)");
            assertEquals(0,scalar(statement,"SELECT count(*) FROM public.nw_workflow_create_receipts"));
            Savepoint before=connection.setSavepoint();
            assertThrows(SQLException.class,()->statement.execute("INSERT INTO public.nw_workflow_create_receipts(request_id,tenant_id,actor_ref,request_fingerprint) VALUES ('00000000-0000-4000-8000-000000000002',7,'70',repeat('a',64))"));
            connection.rollback(before);
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    /** 平台与 SDK 使用独立命名空间；负断言按 JDBC 参数保留被移除名称的原始事实。 */
    private static void verifyDatabaseNamespaces(Connection connection, Statement statement) throws SQLException {
        assertEquals(0, scalar(statement, """
                SELECT count(*) FROM pg_tables WHERE schemaname='public'
                  AND tablename<>'flyway_schema_history'
                  AND left(tablename,14)<>'agent_runtime_'
                  AND left(tablename,3)<>'nw_'
                """));
        try (var lookup = connection.prepareStatement("SELECT to_regclass(?)")) {
            for (String legacyName : List.of("agent_runtime_identity_verifications", "agent_runtime_authentication_facts",
                    "agent_runtime_host_owners", "agent_runtime_mcp_bindings", "agent_runtime_knowledge_bindings",
                    "agent_runtime_session_capabilities", "agent_runtime_workflow_bindings", "runtime_budget_roots")) {
                lookup.setString(1, "public." + legacyName);
                try (var result = lookup.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(null, result.getString(1), "旧业务表名仍存在: " + legacyName);
                }
            }
            for (String targetName : List.of("nw_agents", "nw_billing_plans",
                    "nw_chat_sessions", "nw_chat_session_outbox", "nw_runtime_identity_verifications",
                    "nw_runtime_mcp_bindings", "nw_runtime_session_capabilities", "agent_runtime_budget_roots")) {
                lookup.setString(1, "public." + targetName);
                try (var result = lookup.executeQuery()) {
                    assertTrue(result.next());
                    assertNotNull(result.getString(1), "目标业务表缺失: " + targetName);
                }
            }
            // V339 退役销售账单；模型用量账本不能使旧商业出账表重新成为必需结构。
            lookup.setString(1, "public.nw_billing_invoices");
            try (var result = lookup.executeQuery()) {
                assertTrue(result.next());
                assertEquals(null, result.getString(1), "已退役销售账单表不应存在");
            }
            for (String previousName : List.of("skillforge_agents", "skillforge_billing_plans", "skillforge_billing_invoices",
                    "skillforge_chat_sessions", "skillforge_chat_session_outbox", "skillforge_runtime_identity_verifications",
                    "skillforge_runtime_mcp_bindings", "skillforge_runtime_session_capabilities")) {
                lookup.setString(1, "public." + previousName);
                try (var result = lookup.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(null, result.getString(1), "中间表名仍存在: " + previousName);
                }
            }
        }
    }

    /** 全量迁移必须同时切换权威身份、来源和发布流，不留下可调用的 Application 存储旁路。 */
    private static void verifyPlatformIntegrationBaseline(Statement statement) throws SQLException {
        assertEquals(0, scalar(statement, """
                SELECT count(*) FROM information_schema.tables WHERE table_schema='public'
                  AND table_name IN ('nw_integration_applications','nw_integration_application_tenants',
                    'nw_integration_application_credentials','nw_integration_application_audit','nw_integration_provision_requests',
                    'nw_legacy_integration_business_scopes','nw_application_agent_template_grants',
                    'nw_application_agent_deployments','nw_application_usage_streams','nw_application_usage_exports')
                """));
        assertEquals(0, scalar(statement, """
                SELECT count(*) FROM information_schema.columns WHERE table_schema='public'
                  AND column_name IN ('application_id','application_managed')
                """));
        assertEquals(0, scalar(statement, """
                SELECT count(*) FROM nw_foundation_navigation_nodes WHERE code IN ('applications','application_usage')
                """));
        assertEquals(1, scalar(statement, "SELECT count(*) FROM nw_usage_publication_state WHERE stream_id=1 AND published_cursor=0"));
        assertEquals(3, scalar(statement, """
                SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='nw_chat_sessions'
                  AND column_name IN ('origin_kind','actor_ref','responsibility_ref') AND is_nullable='NO'
                """));
        assertEquals(1, scalar(statement, """
                SELECT count(*) FROM pg_trigger WHERE tgrelid='public.nw_knowledge_rebuild_jobs'::regclass
                  AND tgfoid='public.guard_knowledge_rebuild_metering_origin()'::regprocedure AND tgenabled='O'
                """));
    }

    /** 完整迁移后事实归属不能被来源 Session target 替代，来源外键与租户隔离继续生效。 */
    private static void verifyMemoryFactOwnership(Statement statement) throws SQLException {
        assertEquals(0, scalar(statement, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public'
                  AND table_name IN ('agent_runtime_memory_fact_revisions','agent_runtime_memory_fact_heads')
                  AND column_name IN ('target_kind','target_id')
                """));
        assertEquals(6, scalar(statement, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public'
                  AND table_name IN ('agent_runtime_memory_fact_revisions','agent_runtime_memory_fact_heads')
                  AND column_name IN ('scope','agent_id','session_id')
                """));
        assertEquals(0, scalar(statement, """
                SELECT count(*) FROM pg_trigger
                WHERE tgrelid IN ('public.agent_runtime_memory_fact_revisions'::regclass,
                                  'public.agent_runtime_memory_fact_heads'::regclass)
                  AND tgname='agent_runtime_session_target_immutable'
                """));
        assertEquals(3, scalar(statement, """
                SELECT count(*) FROM pg_trigger
                WHERE tgenabled='O' AND (
                    (tgrelid='public.agent_runtime_memory_fact_revisions'::regclass
                     AND tgname IN ('trg_agent_runtime_memory_revisions_immutable',
                                    'trg_agent_runtime_memory_sources_required'))
                    OR (tgrelid='public.agent_runtime_memory_fact_heads'::regclass
                        AND tgname='trg_agent_runtime_memory_head_guard'))
                """));
        assertEquals(1, scalar(statement, """
                SELECT count(*) FROM pg_trigger
                WHERE tgrelid='public.agent_runtime_session_owners'::regclass
                  AND tgname='agent_runtime_session_target_immutable' AND tgenabled='O'
                """));
        assertEquals(2, scalar(statement, """
                SELECT count(*) FROM pg_class
                WHERE oid IN ('public.agent_runtime_memory_fact_revisions'::regclass,
                              'public.agent_runtime_memory_fact_heads'::regclass)
                  AND relrowsecurity AND relforcerowsecurity
                """));
        assertEquals(2, scalar(statement, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='agent_runtime_memory_fact_sources'
                  AND column_name IN ('source_target_kind','source_target_id') AND is_nullable='NO'
                """));
        assertEquals(1, scalar(statement, """
                SELECT count(*) FROM pg_constraint
                WHERE conrelid='public.agent_runtime_memory_fact_sources'::regclass
                  AND conname='fk_agent_runtime_memory_source_session_event' AND contype='f'
                """));
    }

    /** 负向命令只回滚各自 savepoint，最终回滚整个夹具，独立验证每条约束。 */
    private static void verifyConnectorDeploymentReferences(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO nw_billing_plans(id, plan_code, display_name, slo_availability_target)
                    VALUES (700000001, 'deployment-schema-test', '部署引用验收', 0.999);
                    INSERT INTO nw_tenants(id, name, slug, plan_id)
                    VALUES (700000002, '租户 A', 'deployment-schema-a', 700000001),
                           (700000003, '租户 B', 'deployment-schema-b', 700000001);
                    INSERT INTO nw_connector_instances(id, instance_id, tenant_id, name, base_url, executor_type)
                    VALUES (700000006, 'connector-a', 700000002, '连接器 A', 'https://connector-a.example', 'mcp'),
                           (700000007, 'connector-b', 700000003, '连接器 B', 'https://connector-b.example', 'http');
                    INSERT INTO nw_capability_definitions(id, capability_type, capability_key, name,
                            trust_level, status, created_at, updated_at)
                    VALUES (700000004, 'TOOL', 'tool:deployment-schema', '部署引用工具',
                            'PLATFORM', 'PUBLISHED', now(), now());
                    INSERT INTO nw_capability_versions(id, definition_id, capability_type, version_key,
                            name, trust_level, descriptor, content_hash, status, created_at, published_at)
                    VALUES (700000005, 700000004, 'TOOL', 'v1', '部署引用工具', 'PLATFORM',
                            '{}', 'deployment-schema-content', 'PUBLISHED', now(), now());
                    """);

            insertDeployment(statement, 700000010, 700000002, "MCP", "connector-a");
            insertDeployment(statement, 700000011, 700000002, "CONNECTOR", "connector-a");
            insertDeployment(statement, 700000012, 700000002, "PLATFORM", "platform-handler");
            insertDeployment(statement, 700000013, 700000002, "TEST_MOCK", "mock-handler");
            assertEquals(2, scalar(statement, """
                    SELECT count(*) FROM nw_tool_deployments
                    WHERE tenant_id = 700000002 AND connector_instance_ref = 'connector-a'
                    """));
            assertEquals(2, scalar(statement, """
                    SELECT count(*) FROM nw_tool_deployments
                    WHERE tenant_id = 700000002 AND connector_instance_ref IS NULL
                    """));

            // 相同 provider_ref 在其他租户存在仍不能授权本租户部署；两种连接器 Provider 都受限。
            for (String provider : List.of("MCP", "CONNECTOR")) {
                assertForeignKeyRejected(connection, statement,
                        deploymentSql(700000014, 700000002, provider, "connector-b"));
                assertForeignKeyRejected(connection, statement,
                        deploymentSql(700000014, 700000002, provider, "missing-connector"));
            }
            insertDeployment(statement, 700000014, 700000003, "CONNECTOR", "connector-b");
            assertForeignKeyRejected(connection, statement, """
                    UPDATE nw_tool_deployments SET provider_ref = 'connector-b'
                    WHERE id = 700000010
                    """);
            assertForeignKeyRejected(connection, statement, """
                    UPDATE nw_tool_deployments SET tenant_id = 700000003
                    WHERE id = 700000010
                    """);
            assertForeignKeyRejected(connection, statement, """
                    UPDATE nw_tool_deployments SET provider_type = 'MCP'
                    WHERE id = 700000012
                    """);

            // 停用部署仍拥有持久连接器引用，只有显式删除全部引用后才允许删除实例。
            statement.executeUpdate("UPDATE nw_tool_deployments SET status = 'DISABLED' WHERE id = 700000010");
            assertForeignKeyRejected(connection, statement,
                    "DELETE FROM nw_connector_instances WHERE id = 700000006");
            assertForeignKeyRejected(connection, statement,
                    "UPDATE nw_connector_instances SET instance_id = 'renamed-a' WHERE id = 700000006");
            statement.executeUpdate("DELETE FROM nw_tool_deployments WHERE id = 700000011");
            assertForeignKeyRejected(connection, statement,
                    "DELETE FROM nw_connector_instances WHERE id = 700000006");
            statement.executeUpdate("DELETE FROM nw_tool_deployments WHERE id = 700000010");
            assertEquals(1, statement.executeUpdate("DELETE FROM nw_connector_instances WHERE id = 700000006"));

            // 切换到非连接器 Provider 时生成列自动清空，不留下与 provider_ref 分离的第二份引用。
            statement.executeUpdate("""
                    UPDATE nw_tool_deployments SET provider_type = 'PLATFORM', provider_ref = 'platform-handler'
                    WHERE id = 700000014
                    """);
            assertEquals(1, scalar(statement, """
                    SELECT count(*) FROM nw_tool_deployments
                    WHERE id = 700000014 AND connector_instance_ref IS NULL
                    """));
            assertEquals(1, statement.executeUpdate("DELETE FROM nw_connector_instances WHERE id = 700000007"));
        } finally {
            connection.rollback();
        }
    }

    private static void insertDeployment(Statement statement, long id, long tenant, String provider,
                                         String providerRef) throws SQLException {
        assertEquals(1, statement.executeUpdate(deploymentSql(id, tenant, provider, providerRef)));
    }

    private static String deploymentSql(long id, long tenant, String provider, String providerRef) {
        return """
                INSERT INTO nw_tool_deployments(id, tenant_id, deployment_key, tool_version_id,
                        provider_type, provider_ref, environment, status, created_at, updated_at)
                VALUES (%d, %d, 'deployment-%d', 700000005, '%s', '%s', 'TEST', 'ACTIVE', now(), now())
                """.formatted(id, tenant, id, provider, providerRef);
    }

    private static void assertForeignKeyRejected(Connection connection, Statement statement, String sql)
            throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            SQLException rejected = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
            assertEquals("23503", rejected.getSQLState());
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}

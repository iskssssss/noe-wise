package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实目录 Mapper、生产 Catalog 迁移和受限角色；仅使用本测试独占 PG。 */
@Testcontainers(disabledWithoutDocker = false)
class AvailableToolDirectoryPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tool_directory_test");
    private static SqlSessionFactory factory;

    @BeforeAll static void initialize() throws Exception {
        try (var connection = owner(); var sql = connection.createStatement()) {
            // V86 的宿主外键与 V264 的宿主字段只需最小表；Catalog 本身使用完整生产迁移。
            sql.execute("CREATE TABLE nw_connector_instances(tenant_id bigint,instance_id text,UNIQUE(tenant_id,instance_id));"
                    + "CREATE TABLE nw_agent_capability_mounts(id bigint)");
            for (var name : List.of("V86__capability_registry.sql", "V143__skill_default_version.sql", "V264__capability_current_publications.sql")) {
                try (var source = AvailableToolDirectoryPostgresTest.class.getClassLoader().getResourceAsStream("db/migration/" + name)) {
                    assertNotNull(source); sql.execute(new String(source.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            sql.execute("CREATE ROLE directory_reader LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS;"
                    + "GRANT USAGE ON SCHEMA public TO directory_reader; GRANT SELECT ON ALL TABLES IN SCHEMA public TO directory_reader");
            seed(connection, 1, 1L, "true", "NEVER", "PUBLISHED");
            seed(connection, 2, null, "true", "NEVER", "PUBLISHED");
            seed(connection, 3, 2L, "true", "NEVER", "PUBLISHED");
            seed(connection, 4, 1L, "\"true\"", "NEVER", "PUBLISHED");
            seed(connection, 5, 1L, "true", "ALWAYS", "PUBLISHED");
            seed(connection, 6, 1L, "false", "NEVER", "PUBLISHED");
            seed(connection, 7, null, "true", "NEVER", "DRAFT");
            sql.execute("INSERT INTO nw_tool_deployments(id,tenant_id,deployment_key,tool_version_id,provider_type,provider_ref,environment,status,created_at,updated_at)"
                    + " VALUES(101,1,'second-source',11,'PLATFORM','registry-second','TEST','ACTIVE',now(),now()),"
                    + "(102,1,'disabled',11,'PLATFORM','registry','TEST','DISABLED',now(),now()),"
                    + "(103,1,'test-mock',11,'TEST_MOCK','mock','TEST','ACTIVE',now(),now());"
                    + "UPDATE nw_capability_versions SET status='ARCHIVED' WHERE id=11");
        }
        var data = new PGSimpleDataSource(); data.setURL(PG.getJdbcUrl());
        data.setUser("directory_reader"); data.setPassword("test-only");
        var config = new Configuration(new Environment("directory", new JdbcTransactionFactory(), data));
        config.setMapUnderscoreToCamelCase(true); config.addInterceptor(new TenantRlsInterceptor());
        try (var source = AvailableToolDirectoryPostgresTest.class.getClassLoader().getResourceAsStream("mapper/ToolDeploymentRecordMapper.xml")) {
            assertNotNull(source); new XMLMapperBuilder(source, config, "mapper/ToolDeploymentRecordMapper.xml", config.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(config);
    }

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static void seed(Connection connection, long id, Long tenant, String invocable, String policy, String definitionStatus) throws Exception {
        try (var insert = connection.prepareStatement("INSERT INTO nw_capability_definitions(id,tenant_id,capability_type,capability_key,name,trust_level,status,created_at,updated_at) VALUES(?,?,'TOOL',?,?,?, ?,now(),now())")) {
            insert.setLong(1, id); insert.setObject(2, tenant); insert.setString(3, "tool-" + id);
            insert.setString(4, "tool-" + id); insert.setString(5, tenant == null ? "PLATFORM" : "TENANT");
            insert.setString(6, definitionStatus); insert.executeUpdate();
        }
        for (long version : List.of(id * 10 + 1, id * 10 + 2)) {
            try (var insert = connection.prepareStatement("INSERT INTO nw_capability_versions(id,definition_id,tenant_id,capability_type,version_key,name,description,trust_level,descriptor,content_hash,status,created_at,published_at) VALUES(?,?,?,'TOOL',?,?,?, ?,?::jsonb,?,'PUBLISHED',now(),now())")) {
                insert.setLong(1, version); insert.setLong(2, id); insert.setObject(3, tenant);
                insert.setString(4, "v" + version); insert.setString(5, "current-" + version); insert.setString(6, "description");
                insert.setString(7, tenant == null ? "PLATFORM" : "TENANT");
                insert.setString(8, "{\"tool_name\":\"lookup\",\"model_invocable\":" + invocable + ",\"confirmation_policy\":\"" + policy
                        + "\",\"input_schema\":{\"description\":\"" + "x".repeat(131072) + "\"}}");
                insert.setString(9, "hash-" + version); insert.executeUpdate();
            }
        }
        try (var sql = connection.createStatement()) {
            sql.execute("UPDATE nw_capability_definitions SET default_version_id=" + (id * 10 + 2) + " WHERE id=" + id);
            sql.execute("INSERT INTO nw_tool_deployments(id,tenant_id,deployment_key,tool_version_id,provider_type,provider_ref,environment,status,created_at,updated_at) VALUES("
                    + id + "," + (tenant == null ? 1 : tenant) + ",'deployment-" + id + "'," + (id * 10 + 1)
                    + ",'PLATFORM','registry','TEST','ACTIVE',now(),now())");
        }
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void oldAnchorResolvesCurrentPublicationWithoutLoadingSchemasAndPreservesPolicies() throws Exception {
        TenantContext.setTenantId("1");
        try (var session = factory.openSession()) {
            var rows = session.getMapper(ToolDeploymentRecordMapper.class).listAvailableTools(1L);
            assertEquals(3, rows.size());
            assertEquals(List.of(1L, 2L), rows.stream().map(AvailableToolDeployment::definitionId).distinct().sorted().toList());
            assertEquals(2, rows.stream().filter(row -> row.definitionId() == 1L).count());
            assertTrue(rows.stream().allMatch(row -> row.versionId() == row.definitionId() * 10 + 2));
            assertTrue(rows.stream().allMatch(row -> row.toolName().equals("lookup")));
            var bound = factory.getConfiguration().getMappedStatement(
                    ToolDeploymentRecordMapper.class.getName() + ".listAvailableTools")
                    .getBoundSql(java.util.Map.of("tenant", 1L));
            try (var query = session.getConnection().prepareStatement(bound.getSql())) {
                query.setLong(1, 1L); query.setLong(2, 1L); query.setLong(3, 1L);
                try (var result = query.executeQuery()) {
                    var columns = result.getMetaData();
                    assertEquals(9, columns.getColumnCount());
                    for (int column = 1; column <= columns.getColumnCount(); column++) {
                        assertNotEquals("descriptor", columns.getColumnLabel(column));
                        assertNotEquals("jsonb", columns.getColumnTypeName(column));
                    }
                }
            }
            try (var sql = session.getConnection().createStatement(); var role = sql.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(role.next()); assertTrue(role.getBoolean(1));
            }
        }
        try (var connection = owner(); var sql = connection.createStatement(); var size = sql.executeQuery("SELECT octet_length(descriptor::text) FROM nw_capability_versions WHERE id=12")) {
            assertTrue(size.next()); assertTrue(size.getLong(1) > 131072);
        }
        assertFalse(java.util.Arrays.stream(AvailableToolDeployment.class.getRecordComponents())
                .anyMatch(field -> field.getName().contains("descriptor") || field.getName().contains("schema")));
    }

    @Test void forgedTenantArgumentCannotBypassActualRls() {
        TenantContext.setTenantId("2");
        try (var session = factory.openSession()) {
            assertTrue(session.getMapper(ToolDeploymentRecordMapper.class).listAvailableTools(1L).isEmpty());
            var rows = session.getMapper(ToolDeploymentRecordMapper.class).listAvailableTools(2L);
            assertEquals(List.of(3L), rows.stream().map(AvailableToolDeployment::definitionId).toList());
        }
    }
}

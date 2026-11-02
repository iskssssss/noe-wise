package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V87 Catalog 作用域、平台上下文与发布生命周期不变量的 PostgreSQL 验收。 */
class CapabilityCatalogLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private PlanMapper planMapper;

    @Test
    void platformWritesRequireExplicitAdminContextAndPublishedContentIsImmutable() throws Exception {
        long tenantId = Long.parseLong(createTenant(
                tenantMapper, planMapper, "catalog-v87-" + SnowflakeIds.nextId()));
        long definitionId = SnowflakeIds.nextId();
        long versionId = SnowflakeIds.nextId();

        try (Connection connection = restrictedConnection()) {
            setContext(connection, "", false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO nw_capability_definitions
                        (id, tenant_id, capability_type, capability_key, name, description,
                         trust_level, status, revision, created_at, updated_at)
                    VALUES (?, NULL, 'TOOL', ?, 'Platform Tool', '', 'PLATFORM', 'DRAFT', 1, ?, ?)
                    """)) {
                statement.setLong(1, definitionId);
                statement.setString(2, "platform-no-context-" + definitionId);
                statement.setTimestamp(3, Timestamp.from(Instant.now()));
                statement.setTimestamp(4, Timestamp.from(Instant.now()));
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
        }
        try (Connection connection = restrictedConnection()) {
            setContext(connection, "", true);
            insertPlatformDefinition(connection, definitionId, "platform-" + definitionId);
            insertDraftVersion(connection, versionId, definitionId);
            updateDraftVersion(connection, versionId);
            publishPlatformVersion(connection, definitionId, versionId);
            connection.commit();
        }

        try (Connection connection = tenantConnection(tenantId);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM nw_capability_versions WHERE id = ?")) {
            statement.setLong(1, versionId);
            var result = statement.executeQuery();
            result.next();
            // Platform Draft is private to the platform; a published public version is visible.
            assertEquals(1, result.getInt(1));
        }

        try (Connection connection = restrictedConnection()) {
            setContext(connection, "", true);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nw_capability_versions SET descriptor = '{\"changed\":true}'::jsonb WHERE id = ?")) {
                statement.setLong(1, versionId);
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
        }
    }

    @Test
    void releasedVersionContentAndDefinitionIdentityRemainImmutableAndCannotReturnToDraft() throws Exception {
        long tenantId = Long.parseLong(createTenant(
                tenantMapper, planMapper, "catalog-release-" + SnowflakeIds.nextId()));
        long definitionId = SnowflakeIds.nextId();
        long versionId = SnowflakeIds.nextId();

        try (Connection connection = tenantConnection(tenantId)) {
            insertDefinition(connection, definitionId, tenantId, "TOOL", "release-guard");
            insertVersion(connection, versionId, definitionId, tenantId,
                    "TOOL", "v1", "PUBLISHED");
            connection.commit();
        }

        // A published version may be retired, but its released content and identity stay frozen.
        try (Connection connection = tenantConnection(tenantId)) {
            updateStatus(connection, "nw_capability_versions", versionId, "DEPRECATED");
            connection.commit();
        }
        try (Connection connection = tenantConnection(tenantId)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nw_capability_versions SET descriptor = '{\"changed\":true}'::jsonb "
                            + "WHERE id = ?")) {
                statement.setLong(1, versionId);
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
        }
        try (Connection connection = tenantConnection(tenantId)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nw_capability_versions SET version_key = 'v2' WHERE id = ?")) {
                statement.setLong(1, versionId);
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
        }
        try (Connection connection = tenantConnection(tenantId)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nw_capability_versions SET status = 'DRAFT' WHERE id = ?")) {
                statement.setLong(1, versionId);
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
        }
        try (Connection connection = tenantConnection(tenantId)) {
            updateStatus(connection, "nw_capability_versions", versionId, "ARCHIVED");
            connection.commit();
        }
        try (Connection connection = tenantConnection(tenantId)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nw_capability_versions SET status = 'DRAFT' WHERE id = ?")) {
                statement.setLong(1, versionId);
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
        }

        // Definition 的生命周期与身份保持单向冻结；名称和描述是当前发布指针的可更新展示。
        try (Connection connection = tenantConnection(tenantId)) {
            updateStatus(connection, "nw_capability_definitions", definitionId, "DEPRECATED");
            connection.commit();
        }
        try (Connection connection = tenantConnection(tenantId)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nw_capability_definitions SET name = 'changed', description = 'updated display' WHERE id = ?")) {
                statement.setLong(1, definitionId);
                assertEquals(1, statement.executeUpdate());
                connection.commit();
            }
        }
        try (Connection connection = tenantConnection(tenantId);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT name, description, status FROM nw_capability_definitions WHERE id = ?")) {
            statement.setLong(1, definitionId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("changed", result.getString("name"));
                assertEquals("updated display", result.getString("description"));
                assertEquals("DEPRECATED", result.getString("status"));
            }
            connection.rollback();
        }
        try (Connection connection = tenantConnection(tenantId)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nw_capability_definitions SET capability_key = 'changed' WHERE id = ?")) {
                statement.setLong(1, definitionId);
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
        }
        try (Connection connection = tenantConnection(tenantId)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nw_capability_definitions SET status = 'DRAFT' WHERE id = ?")) {
                statement.setLong(1, definitionId);
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
        }
        try (Connection connection = tenantConnection(tenantId)) {
            updateStatus(connection, "nw_capability_definitions", definitionId, "ARCHIVED");
            connection.commit();
        }
        try (Connection connection = tenantConnection(tenantId)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nw_capability_definitions SET status = 'DRAFT' WHERE id = ?")) {
                statement.setLong(1, definitionId);
                assertThrows(SQLException.class, statement::executeUpdate);
                connection.rollback();
            }
        }
    }

    private void updateStatus(Connection connection, String table, long id, String status)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table + " SET status = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setLong(2, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private Connection restrictedConnection() throws SQLException {
        ensureRestrictedRole();
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("SET LOCAL ROLE sf_capability_test")) {
            statement.execute();
        }
        return connection;
    }

    private void tenantContext(Connection connection, long tenantId) throws SQLException {
        setContext(connection, Long.toString(tenantId), false);
    }

    private Connection tenantConnection(long tenantId) throws SQLException {
        Connection connection = restrictedConnection();
        tenantContext(connection, tenantId);
        return connection;
    }

    private void setContext(Connection connection, String tenantId, boolean platformAdmin)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true), set_config('app.platform_admin', ?, true)")) {
            statement.setString(1, tenantId);
            statement.setString(2, Boolean.toString(platformAdmin));
            statement.execute();
        }
    }

    private void ensureRestrictedRole() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DO $$
                     BEGIN
                         IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sf_capability_test') THEN
                             CREATE ROLE sf_capability_test NOLOGIN NOBYPASSRLS;
                         END IF;
                     END $$;
                     GRANT USAGE ON SCHEMA public TO sf_capability_test;
                     GRANT SELECT, INSERT, UPDATE, DELETE
                         ON nw_capability_definitions, nw_capability_versions, nw_tool_deployments
                         TO sf_capability_test
                     """)) {
            statement.execute();
        }
    }

    private void insertDefinition(
            Connection connection, long id, long tenantId, String type, String key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nw_capability_definitions
                    (id, tenant_id, capability_type, capability_key, name, description,
                     trust_level, status, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '', 'TENANT', 'PUBLISHED', 1, ?, ?)
                """)) {
            statement.setLong(1, id);
            statement.setLong(2, tenantId);
            statement.setString(3, type);
            statement.setString(4, key);
            statement.setString(5, key);
            statement.setTimestamp(6, Timestamp.from(Instant.now()));
            statement.setTimestamp(7, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private void insertVersion(
            Connection connection, long id, long definitionId, long tenantId,
            String type, String version, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nw_capability_versions
                    (id, definition_id, tenant_id, capability_type, version_key,
                     name, description, trust_level, descriptor, content_hash,
                     status, created_at, published_at)
                VALUES (?, ?, ?, ?, ?, 'Version name', '', 'TENANT', '{}'::jsonb, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, id);
            statement.setLong(2, definitionId);
            statement.setLong(3, tenantId);
            statement.setString(4, type);
            statement.setString(5, version);
            statement.setString(6, "sha256:" + id);
            statement.setString(7, status);
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.setTimestamp(9, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private void insertPlatformDefinition(Connection connection, long id, String key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nw_capability_definitions
                    (id, tenant_id, capability_type, capability_key, name, description,
                     trust_level, status, revision, created_at, updated_at)
                VALUES (?, NULL, 'TOOL', ?, 'Platform Tool', '', 'PLATFORM', 'DRAFT', 1, ?, ?)
                """)) {
            statement.setLong(1, id);
            statement.setString(2, key);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private void insertDraftVersion(Connection connection, long id, long definitionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO nw_capability_versions
                    (id, definition_id, tenant_id, capability_type, version_key,
                     name, description, trust_level, descriptor, content_hash, status,
                     created_at, published_at)
                VALUES (?, ?, NULL, 'TOOL', 'v1', 'Platform Tool v1', '', 'PLATFORM',
                        '{\"modelInvocable\":true,\"userInvocable\":false,\"inputSchema\":{},
                         \"outputSchema\":{},\"effect\":\"READ_ONLY\",\"idempotent\":true,
                         \"confirmationPolicy\":\"NEVER\",\"timeoutMs\":1000,
                         \"sensitiveFields\":[],\"providerRequirements\":[]}'::jsonb,
                        ?, 'DRAFT', ?, NULL)
                """)) {
            statement.setLong(1, id);
            statement.setLong(2, definitionId);
            statement.setString(3, "sha256:catalog-" + id);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private void updateDraftVersion(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE nw_capability_versions
                   SET descriptor = '{"modelInvocable":true,"userInvocable":false,
                                     "inputSchema":{"type":"object"},"outputSchema":{},
                                     "effect":"READ_ONLY","idempotent":true,
                                     "confirmationPolicy":"NEVER","timeoutMs":1000,
                                     "sensitiveFields":[],"providerRequirements":[]}'::jsonb,
                       content_hash = ?
                 WHERE id = ? AND status = 'DRAFT'
                """)) {
            statement.setString(1, "sha256:catalog-draft-edit-" + id);
            statement.setLong(2, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void publishPlatformVersion(Connection connection, long definitionId, long versionId)
            throws SQLException {
        try (PreparedStatement definition = connection.prepareStatement("""
                UPDATE nw_capability_definitions
                   SET status = 'PUBLISHED', revision = 2, updated_at = ?
                 WHERE id = ? AND status = 'DRAFT' AND revision = 1
                """)) {
            definition.setTimestamp(1, Timestamp.from(Instant.now()));
            definition.setLong(2, definitionId);
            assertEquals(1, definition.executeUpdate());
        }
        try (PreparedStatement version = connection.prepareStatement("""
                UPDATE nw_capability_versions
                   SET status = 'PUBLISHED', published_at = ?
                 WHERE id = ? AND status = 'DRAFT'
                """)) {
            version.setTimestamp(1, Timestamp.from(Instant.now()));
            version.setLong(2, versionId);
            assertEquals(1, version.executeUpdate());
        }
    }
}

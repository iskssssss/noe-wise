package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.CapabilityDescriptorCodec;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.ToolVersion;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.capability.connector.PlatformToolDefinitionInitializer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** PostgreSQL 验收平台默认工具通过 Producer bridge 发布，旧工具定义表不再存在。 */
class PlatformToolCatalogPublicationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapabilityDescriptorCodec descriptorCodec;

    @Autowired
    private PlatformToolDefinitionInitializer initializer;

    @Test
    void publishesStrictPlatformToolVersionsIdempotentlyWithoutLegacyTable() throws Exception {
        TenantContext.clear();
        try {
            List<PublishedToolRow> first = readPlatformTools();
            assertEquals(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().size(), first.size());
            assertEquals(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).names(), first.stream()
                    .map(PublishedToolRow::name).collect(Collectors.toUnmodifiableSet()));
            assertLegacyToolTableAbsent();

            for (PublishedToolRow row : first) {
                PlatformToolCatalog.Entry entry = entry(row.name());
                assertNull(row.definitionTenantId());
                assertEquals("TOOL", row.definitionType());
                assertEquals("tool:" + row.name(), row.capabilityKey());
                assertEquals(entry.name(), row.name());
                assertEquals(entry.description(), row.description());
                assertEquals("PUBLISHED", row.definitionStatus());
                assertNull(row.versionTenantId());
                assertEquals("TOOL", row.versionType());
                assertEquals(entry.publishedVersionKey(), row.versionKey());
                assertEquals(entry.name(), row.versionName());
                assertEquals(entry.description(), row.versionDescription());
                assertEquals("PLATFORM", row.trustLevel());
                assertEquals("PUBLISHED", row.versionStatus());
                assertEquals(entry.contentHash(), row.contentHash());
                assertEquals(entry.descriptor().keySet(), row.descriptor().keySet());
                assertEquals(entry.parametersSchema(), row.descriptor().get("input_schema"));
                assertEquals(entry.outputSchema(), row.descriptor().get("output_schema"));
                assertEquals(Set.of("platform_handler:" + row.name()),
                        Set.copyOf(stringList(row.descriptor().get("provider_requirements"))));
                assertEquals(entry.sensitiveFields(),
                        Set.copyOf(stringList(row.descriptor().get("sensitive_fields"))));

                ToolVersion decoded = assertInstanceOf(ToolVersion.class, descriptorCodec.decode(
                        new CapabilityRef(CapabilityType.TOOL,
                                Long.toString(row.definitionId()), Long.toString(row.versionId())),
                        null, row.versionName(), row.versionDescription(), row.trustLevel(),
                        row.versionStatus(), row.contentHash(), row.descriptor()));
                assertEquals(entry.modelInvocable(), decoded.metadata().invocationPolicy().modelInvocable());
                assertEquals(entry.userInvocable(), decoded.metadata().invocationPolicy().userInvocable());
                assertEquals(entry.name(), decoded.definition().name());
                assertEquals(entry.description(), decoded.definition().description());
                assertEquals(entry.parametersSchema(), decoded.definition().inputSchema());
                assertEquals(entry.outputSchema(), decoded.definition().outputSchema().orElseThrow());
                assertEquals(entry.effect().name(), decoded.policy().effect().name());
                assertEquals(entry.idempotent(), decoded.policy().idempotent());
                assertEquals(entry.confirmationPolicy().name(), decoded.policy().confirmation().name());
                assertEquals(entry.timeoutMs(), decoded.policy().timeoutMs());
                assertEquals(entry.parallelSafe(), decoded.policy().parallelSafe());
                assertEquals(entry.sensitiveFields(), decoded.policy().sensitiveFields());
            }

            initializer.run(null);
            List<PublishedToolRow> second = readPlatformTools();
            assertEquals(first, second);
            assertEquals(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().size(), second.size());
            assertLegacyToolTableAbsent();
        } finally {
            TenantContext.clear();
        }
    }

    private PlatformToolCatalog.Entry entry(String name) {
        return new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private List<PublishedToolRow> readPlatformTools() throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT d.id AS definition_id, d.tenant_id AS definition_tenant_id,
                               d.capability_type AS definition_type, d.capability_key,
                               d.name AS definition_name, d.description AS definition_description,
                               d.status AS definition_status, v.id AS version_id,
                               v.tenant_id AS version_tenant_id, v.capability_type AS version_type,
                               v.version_key, v.name AS version_name,
                               v.description AS version_description, v.trust_level,
                               v.status AS version_status, v.content_hash, v.descriptor::text
                          FROM nw_capability_definitions d
                          JOIN nw_capability_versions v ON v.definition_id = d.id
                         WHERE d.tenant_id IS NULL
                           AND d.capability_type = 'TOOL'
                           AND d.capability_key LIKE 'tool:%'
                         ORDER BY d.capability_key
                        """)) {
            try (ResultSet result = statement.executeQuery()) {
                List<PublishedToolRow> rows = new java.util.ArrayList<>();
                while (result.next()) {
                    rows.add(new PublishedToolRow(
                            result.getLong("definition_id"), nullableLong(result, "definition_tenant_id"),
                            result.getString("definition_type"), result.getString("capability_key"),
                            result.getString("definition_name"), result.getString("definition_description"),
                            result.getString("definition_status"), result.getLong("version_id"),
                            nullableLong(result, "version_tenant_id"), result.getString("version_type"),
                            result.getString("version_key"), result.getString("version_name"),
                            result.getString("version_description"), result.getString("trust_level"),
                            result.getString("version_status"), result.getString("content_hash"),
                            readJson(result.getString("descriptor"))));
                }
                return List.copyOf(rows);
            }
        }
    }

    private void assertLegacyToolTableAbsent() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT to_regclass('public.tool_definitions')")) {
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertNull(result.getString(1));
            }
        }
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception exception) {
            throw new AssertionError("PostgreSQL returned invalid descriptor JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        return value instanceof List<?> list
                ? (List<String>) list
                : List.copyOf((Set<String>) value);
    }

    private record PublishedToolRow(
            long definitionId,
            Long definitionTenantId,
            String definitionType,
            String capabilityKey,
            String name,
            String description,
            String definitionStatus,
            long versionId,
            Long versionTenantId,
            String versionType,
            String versionKey,
            String versionName,
            String versionDescription,
            String trustLevel,
            String versionStatus,
            String contentHash,
            Map<String, Object> descriptor) {
    }
}

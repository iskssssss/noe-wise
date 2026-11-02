package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.agent.systemagent.SystemAgentTypes;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** 来源库只读重复读快照；完整配置与密文只在内存，不写入证据或日志。 */
final class LiveCapacitySourceSnapshot {
    static final String SOURCE_DATABASE = required("LIVE_CAPACITY_SOURCE_DATABASE");
    private static final int EXPECTED_MODEL_WINDOW = 327_680;
    private static final Set<String> REQUIRED_GROUPS = Set.of("task_budget", "prompts",
            "calls", "sandbox", "artifacts", "knowledge");
    private static final Set<String> PURPOSES = Set.of("context-summary", "memory-extraction", "task-context");
    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private LiveCapacitySourceSnapshot() { }

    static Snapshot read() {
        String tenant = required("LIVE_CAPACITY_SOURCE_TENANT_ID");
        String id = required("LIVE_CAPACITY_SOURCE_MODEL_ID");
        long revision;
        try { revision = Long.parseLong(required("LIVE_CAPACITY_SOURCE_MODEL_REVISION")); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("SOURCE_REVISION_INVALID"); }
        String url = environment("LIVE_CAPACITY_SOURCE_DB_URL", "SPRING_DATASOURCE_URL");
        try (Connection connection = DriverManager.getConnection(url,
                environment("LIVE_CAPACITY_SOURCE_DB_USERNAME", "SPRING_DATASOURCE_USERNAME"),
                environment("LIVE_CAPACITY_SOURCE_DB_PASSWORD", "SPRING_DATASOURCE_PASSWORD"))) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION READ ONLY");
                try (var rows = statement.executeQuery("SELECT current_database()")) {
                    if (!rows.next() || !SOURCE_DATABASE.equals(rows.getString(1)))
                        throw new IllegalStateException("SOURCE_DATABASE_MISMATCH");
                }
            }
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                bind.setString(1, tenant); bind.execute();
            }
            String encryptedConfig;
            PlatformModelConfig model;
            try (var query = connection.prepareStatement("""
                    SELECT v.config::text FROM nw_tenant_model_selections s
                    JOIN nw_platform_models m ON m.id=s.llm_model_config_id
                    JOIN nw_platform_model_versions v ON v.model_id=m.id AND v.revision=m.current_revision
                    WHERE s.tenant_id=CAST(? AS bigint) AND m.id=CAST(? AS bigint) AND m.current_revision=?
                    """)) {
                query.setString(1, tenant); query.setString(2, id); query.setLong(3, revision);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("SOURCE_DEFAULT_CHANGED");
                    encryptedConfig = rows.getString(1);
                    model = JSON.readValue(encryptedConfig, PlatformModelConfig.class);
                    if (rows.next()) throw new IllegalStateException("SOURCE_DEFAULT_AMBIGUOUS");
                }
            }
            if (!id.equals(model.id()) || revision != model.revision() || !model.enabled()
                    || !"LLM".equals(model.kind()) || model.maxOutputTokens() == null
                    || model.maxOutputTokens() != ChatBudgetSettings.CLOSING_OUTPUT_TOKENS || model.contextWindowTokens() == null
                    || model.contextWindowTokens() != EXPECTED_MODEL_WINDOW || model.timeoutSeconds() == null
                    || model.timeoutSeconds() <= 0 || model.apiKeyEncrypted() == null
                    || model.apiKeyEncrypted().isBlank()) throw new IllegalStateException("SOURCE_DEFAULT_INVALID");
            var groups = new ArrayList<Group>();
            try (var query = connection.prepareStatement("""
                    SELECT g.group_key,g.current_revision,v.values::text
                    FROM nw_platform_operational_setting_groups g JOIN nw_platform_operational_setting_versions v
                    ON v.group_key=g.group_key AND v.revision=g.current_revision ORDER BY g.group_key
                    """); var rows = query.executeQuery()) {
                while (rows.next()) groups.add(new Group(rows.getString(1), rows.getLong(2), rows.getString(3)));
            }
            if (!groups.stream().map(Group::key).collect(java.util.stream.Collectors.toSet()).containsAll(REQUIRED_GROUPS))
                throw new IllegalStateException("SOURCE_OPERATIONAL_GROUPS_MISSING");
            var systems = new ArrayList<SystemPurpose>();
            try (var query = connection.prepareStatement("""
                    SELECT a.id::text,a.agent_key,a.purpose,a.execution_mode,a.revision,a.published_version,v.config::text
                    FROM nw_platform_system_agents a JOIN nw_platform_system_agent_versions v
                    ON v.agent_id=a.id AND v.version=a.published_version
                    WHERE a.status='published' AND a.purpose IN ('context-summary','memory-extraction','task-context')
                    ORDER BY a.purpose
                    """); var rows = query.executeQuery()) {
                while (rows.next()) {
                    String config = rows.getString(7);
                    var parsed = JSON.readValue(config, SystemAgentTypes.Config.class);
                    if (!id.equals(parsed.modelConfigId()) || !"MODEL_TASK".equals(rows.getString(4))
                            || !parsed.mounts().isEmpty() || parsed.maxBusinessModelCalls() != 1)
                        throw new IllegalStateException("SOURCE_SYSTEM_PURPOSE_INVALID");
                    systems.add(new SystemPurpose(rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getLong(5), rows.getLong(6), config));
                }
            }
            if (!systems.stream().map(SystemPurpose::purpose).collect(java.util.stream.Collectors.toSet()).equals(PURPOSES)
                    || systems.size() != PURPOSES.size()) throw new IllegalStateException("SOURCE_SYSTEM_PURPOSES_MISSING");
            connection.rollback();
            return new Snapshot(model, encryptedConfig, List.copyOf(groups), List.copyOf(systems));
        } catch (IllegalStateException identified) { throw identified; }
        catch (Exception unavailable) {
            // JDBC/JSON 异常可能带连接或完整配置，不能把 cause 交给 Gradle 报告。
            throw new IllegalStateException("SOURCE_READ_FAILED_" + unavailable.getClass().getSimpleName());
        }
    }
    static String secretKey() { return environment("LIVE_CAPACITY_SOURCE_SECRET_KEY", "SECRET_KEY"); }
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("MISSING_ENV_" + name);
        return value;
    }
    private static String environment(String override, String existing) {
        String value = System.getenv(override);
        return value == null || value.isBlank() ? required(existing) : value;
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception unavailable) { throw new IllegalStateException("EVIDENCE_HASH_UNAVAILABLE"); }
    }
    record Snapshot(PlatformModelConfig model, String encryptedConfig, List<Group> groups, List<SystemPurpose> systems) {
        @Override public String toString() { return "Snapshot[frozen]"; }
    }
    record Group(String key, long revision, String values) {
        @Override public String toString() { return "Group[" + key + ":" + revision + "]"; }
    }
    record SystemPurpose(String id, String key, String purpose, String mode, long revision, long version, String config) {
        @Override public String toString() { return "SystemPurpose[" + purpose + ":" + version + "]"; }
    }
}

package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.model.PlatformModelConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/** 只读来源事务冻结默认模型；连接、完整配置和密文仅存在本次进程内存。 */
final class LivePublicSourceSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private LivePublicSourceSnapshot() { }

    static Snapshot read() {
        String tenant = required("LIVE_PUBLIC_SOURCE_TENANT_ID");
        String modelId = required("LIVE_PUBLIC_SOURCE_MODEL_ID");
        long revision;
        try { revision = Long.parseLong(required("LIVE_PUBLIC_SOURCE_MODEL_REVISION")); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("SOURCE_REVISION_INVALID"); }
        try (Connection connection = DriverManager.getConnection(
                environment("LIVE_PUBLIC_SOURCE_DB_URL", "SPRING_DATASOURCE_URL"),
                environment("LIVE_PUBLIC_SOURCE_DB_USERNAME", "SPRING_DATASOURCE_USERNAME"),
                environment("LIVE_PUBLIC_SOURCE_DB_PASSWORD", "SPRING_DATASOURCE_PASSWORD"))) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (var transaction = connection.createStatement()) { transaction.execute("SET TRANSACTION READ ONLY"); }
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                bind.setString(1, tenant); bind.execute();
            }
            PlatformModelConfig model;
            String rawConfig;
            try (var query = connection.prepareStatement("""
                    SELECT v.config::text
                    FROM nw_tenant_model_selections s
                    JOIN nw_platform_models m ON m.id=s.llm_model_config_id
                    JOIN nw_platform_model_versions v ON v.model_id=m.id AND v.revision=m.current_revision
                    WHERE s.tenant_id=CAST(? AS bigint) AND m.id=CAST(? AS bigint) AND m.current_revision=?
                    """)) {
                query.setString(1, tenant); query.setString(2, modelId); query.setLong(3, revision);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("SOURCE_DEFAULT_NOT_CONFIGURED_OR_CHANGED");
                    rawConfig = rows.getString(1);
                    var sourceJson = JSON.readTree(rawConfig);
                    if (!sourceJson.path("image_policy").isObject())
                        throw new IllegalStateException("SOURCE_MODEL_IMAGE_POLICY_REQUIRES_EXPLICIT_PUBLICATION");
                    model = JSON.readValue(rawConfig, PlatformModelConfig.class);
                    if (rows.next()) throw new IllegalStateException("SOURCE_DEFAULT_AMBIGUOUS");
                }
            }
            if (!modelId.equals(model.id()) || revision != model.revision() || !model.enabled()
                    || !"LLM".equals(model.kind()) || model.maxOutputTokens() == null
                    || model.maxOutputTokens() <= 0 || model.contextWindowTokens() == null
                    || model.contextWindowTokens() <= model.maxOutputTokens()
                    || model.apiKeyEncrypted() == null || model.apiKeyEncrypted().isBlank()) {
                throw new IllegalStateException("SOURCE_DEFAULT_INVALID");
            }
            var groups = new ArrayList<Group>();
            try (var query = connection.prepareStatement("""
                    SELECT g.group_key,g.current_revision,v.values::text
                    FROM nw_platform_operational_setting_groups g
                    JOIN nw_platform_operational_setting_versions v
                      ON v.group_key=g.group_key AND v.revision=g.current_revision
                    WHERE g.group_key IN ('task_budget','prompts','calls','sandbox','artifacts') ORDER BY g.group_key
                    """ ); var rows = query.executeQuery()) {
                while (rows.next()) groups.add(new Group(rows.getString(1), rows.getLong(2), rows.getString(3)));
            }
            if (groups.size() != 5) throw new IllegalStateException("SOURCE_OPERATIONAL_GROUPS_MISSING");
            var budget = groups.stream().filter(group -> "task_budget".equals(group.key())).findFirst().orElseThrow();
            if (budget.sourceRevision() != Long.parseLong(required("LIVE_PUBLIC_SOURCE_BUDGET_REVISION")))
                throw new IllegalStateException("SOURCE_BUDGET_REVISION_CHANGED");
            // 与宿主准备预算一致：输出预留及 5% 安全空间只扣除一次。
            long window = model.contextWindowTokens();
            long maxInput = window - model.maxOutputTokens() - Math.floorDiv(window * 5 + 99, 100);
            if (maxInput <= 1) throw new IllegalStateException("SOURCE_INPUT_BUDGET_INVALID");
            connection.rollback();
            return new Snapshot(tenant, model, rawConfig, List.copyOf(groups), maxInput);
        } catch (IllegalStateException identified) { throw identified; }
        catch (Exception unavailable) {
            // 驱动与 JSON 解析的异常可能包含连接或配置正文，禁止向测试报告传递 cause。
            throw new IllegalStateException("SOURCE_READ_FAILED_" + unavailable.getClass().getSimpleName());
        }
    }

    static String secretKey() { return environment("LIVE_PUBLIC_SOURCE_SECRET_KEY", "SECRET_KEY"); }
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("MISSING_ENV_" + name);
        return value;
    }
    private static String environment(String override, String existing) {
        String value = System.getenv(override);
        return value == null || value.isBlank() ? required(existing) : value;
    }
    record Group(String key, long sourceRevision, String values) {
        @Override public String toString() { return "Group[" + key + ":" + sourceRevision + "]"; }
    }
    record Snapshot(String sourceTenant, PlatformModelConfig model, String encryptedConfig,
                    List<Group> groups, long maxInputTokens) {
        long reservation() { return Math.addExact(maxInputTokens, model.maxOutputTokens()); }
        @Override public String toString() { return "Snapshot[model=" + model.id() + ":" + model.revision() + "]"; }
    }
}

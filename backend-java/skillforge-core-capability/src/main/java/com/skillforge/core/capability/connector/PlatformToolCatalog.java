package com.skillforge.core.capability.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 平台默认工具目录。
 *
 * <p>目录是平台 Handler 保留名、不可变 Tool descriptor 和发布元数据的唯一来源。
 * 每项 descriptor 都是完整的 snake_case wire contract；初始化器只负责把它发布到
 * platform-scope Capability Catalog。
 */
@org.springframework.stereotype.Component
public final class PlatformToolCatalog {

    /** 正式成果 kind 的封闭 wire 取值；公开工具 Schema 与宿主计划校验共用。 */
    public static final List<String> DELIVERABLE_KINDS = List.of(
            "document", "spreadsheet", "presentation", "website", "image", "code", "configuration", "media", "data");

    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final List<Entry> entries;

    public PlatformToolCatalog(ToolSchemaContributor schemas) {
        Objects.requireNonNull(schemas, "schemas");
        entries = List.of(
                BrowserToolDefinitions.browserStart(),
                BrowserToolDefinitions.browserCall(),
                BrowserToolDefinitions.browserStop(),
                BrowserToolDefinitions.browserTasks(),
                ArtifactToolDefinitions.readImage(),
                ArtifactToolDefinitions.finalizeTaskOutputs(),
                BasicToolDefinitions.getCurrentTime(),
                BasicToolDefinitions.calculateExpression(),
                BasicToolDefinitions.convertDatetime(),
                BasicToolDefinitions.jsonQuery(),
                BasicToolDefinitions.jsonSchemaValidate(),
                BasicToolDefinitions.templateRender(),
                BasicToolDefinitions.httpRequest(),
                ExecutionToolDefinitions.codeExecute(schemas),
                ExecutionToolDefinitions.workspaceFiles(),
                ExecutionToolDefinitions.workspaceExec(),
                ExecutionToolDefinitions.workspaceCheckpoint(),
                ArtifactToolDefinitions.renderArtifact(schemas),
                ArtifactToolDefinitions.listArtifacts(),
                MediaToolDefinitions.generateImage(schemas),
                MediaToolDefinitions.generateVideo(schemas),
                MediaToolDefinitions.transcribeAudio(schemas),
                MediaToolDefinitions.synthesizeSpeech(schemas),
                MediaToolDefinitions.queryJob(schemas),
                TaskToolDefinitions.searchMemory(),
                TaskToolDefinitions.searchTasks(),
                TaskToolDefinitions.readTask(),
                TaskToolDefinitions.updateTask(),
                TaskToolDefinitions.exitPlanMode(),
                ExecutionToolDefinitions.runCode(schemas),
                TaskToolDefinitions.getGoal(),
                TaskToolDefinitions.updateGoal(),
                TaskToolDefinitions.searchTools(),
                schemas.contextReferenceEntry());
    }

    public List<Entry> entries() { return entries; }

    public Set<String> names() {
        return entries.stream().map(Entry::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 完整发布条目；名称、版本和执行约束随工具声明，descriptor 变更须使用新的不可变版本。 */
    @lombok.Builder
    public record Entry(
            String name,
            String title,
            String publishedVersionKey,
            String description,
            Map<String, Object> parametersSchema,
            Map<String, Object> outputSchema,
            ToolEffect effect,
            boolean idempotent,
            ToolConfirmationPolicy confirmationPolicy,
            long timeoutMs,
            boolean modelInvocable,
            boolean userInvocable,
            boolean parallelSafe,
            Set<String> sensitiveFields,
            ToolCallIdentityPolicy callIdentityPolicy) {

        public Entry {
            Objects.requireNonNull(callIdentityPolicy, "callIdentityPolicy");
            name = requireText(name, "name");
            title = requireText(title, "title");
            publishedVersionKey = requireText(publishedVersionKey, "publishedVersionKey");
            description = requireText(description, "description");
            parametersSchema = Map.copyOf(parametersSchema == null ? Map.of() : parametersSchema);
            outputSchema = Map.copyOf(outputSchema == null ? Map.of() : outputSchema);
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(confirmationPolicy, "confirmationPolicy");
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("timeoutMs must be positive");
            }
            if (parallelSafe && (effect != ToolEffect.READ_ONLY || confirmationPolicy != ToolConfirmationPolicy.NEVER)) {
                throw new IllegalArgumentException("parallelSafe requires ordinary read-only tool without confirmation");
            }
            sensitiveFields = Set.copyOf(sensitiveFields == null ? Set.of() : sensitiveFields);
        }

        /** 返回包含标准可调用名称与宿主执行策略的 TOOL 版本描述。 */
        public Map<String, Object> descriptor() {
            return Map.ofEntries(
                    Map.entry("model_invocable", modelInvocable),
                    Map.entry("user_invocable", userInvocable),
                    Map.entry("tool_name", name),
                    Map.entry("input_schema", parametersSchema),
                    Map.entry("output_schema", outputSchema),
                    Map.entry("effect", effect.name()),
                    Map.entry("idempotent", idempotent),
                    Map.entry("confirmation_policy", confirmationPolicy.name()),
                    Map.entry("timeout_ms", timeoutMs),
                    Map.entry("parallel_safe", parallelSafe),
                    Map.entry(com.skillforge.core.capability.CapabilityDescriptorCodec.CALL_IDENTITY_POLICY_FIELD, callIdentityPolicy.name()),
                    Map.entry("sensitive_fields", sensitiveFields),
                    Map.entry("provider_requirements", Set.of("platform_handler:" + name)));
        }

        /** 由 name、description 和完整 descriptor 共同确定的服务端 SHA-256。 */
        public String contentHash() {
            return PlatformToolCatalog.contentHash(this);
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }

    private static String contentHash(Entry entry) {
        Map<String, Object> content = Map.of(
                "name", entry.name(),
                "description", entry.description(),
                "descriptor", entry.descriptor());
        try {
            return TokenHash.sha256Hex(CANONICAL_JSON.writeValueAsString(normalize(content)));
        } catch (Exception exception) {
            throw new IllegalStateException("platform tool content hash failed", exception);
        }
    }

    /** 递归规范化 Map/Set，避免构造顺序影响 contentHash。 */
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Set<?> set) {
            List<Object> normalized = new ArrayList<>(set.size());
            for (Object item : set) {
                normalized.add(normalize(item));
            }
            normalized.sort(Comparator.comparing(PlatformToolCatalog::canonicalValue));
            return normalized;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(PlatformToolCatalog::normalize).toList();
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> normalized = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                normalized.add(normalize(Array.get(value, index)));
            }
            return normalized;
        }
        return value;
    }

    private static String canonicalValue(Object value) {
        try {
            return CANONICAL_JSON.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("platform tool canonicalization failed", exception);
        }
    }
}

package com.skillforge.core.capability;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityMetadata.Status;
import com.skillforge.core.capability.CapabilityMetadata.Trust;
import java.math.BigInteger;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 数据库 Descriptor JSON 到四类封闭契约的严格解码器。 */
public final class CapabilityDescriptorCodec {
    public static final String CALL_IDENTITY_POLICY_FIELD = com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy.DESCRIPTOR_FIELD;

    private static final Set<String> SKILL_FIELDS = Set.of(
            "model_invocable", "user_invocable", "activation_mode", "package",
            "conflict_group", "required_capabilities", "estimated_tokens");
    private static final Set<String> SKILL_PACKAGE_FIELDS = Set.of(
            "entrypoint", "metadata", "files");
    private static final Set<String> SKILL_METADATA_FIELDS = Set.of(
            "name", "description", "license", "compatibility", "extensions", "allowed_tools");
    private static final Set<String> SKILL_FILE_FIELDS = Set.of(
            "path", "media_type", "encoding", "content", "sha256", "size");
    private static final Set<String> CAPABILITY_REF_FIELDS = Set.of(
            "type", "capability_id", "version_id");
    private static final Set<String> TOOL_ICON_FIELDS = Set.of(
            "src", "mimeType", "sizes", "theme");
    private static final Set<String> KNOWLEDGE_FIELDS = Set.of(
            "model_invocable", "user_invocable", "source_id", "source_versions",
            "allowed_operators", "freshness_policy", "citation_required");

    private final ObjectMapper objectMapper;

    public CapabilityDescriptorCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public CapabilityDescriptor decode(
            CapabilityRef ref,
            Long tenantId,
            String name,
            String description,
            String trust,
            String status,
            String contentHash,
            Map<String, Object> descriptor) {
        Objects.requireNonNull(ref, "ref");
        Map<String, Object> value = descriptor == null ? Map.of() : descriptor;
        return switch (ref.type()) {
            case SKILL -> {
                SkillWire wire = decodeSkillWire(ref, value);
                yield skill(metadata(ref, tenantId, name, description, trust, status,
                        contentHash, wire), wire);
            }
            case TOOL -> {
                validateOptionalToolTextSet(value, "sensitive_fields");
                validateOptionalToolTextSet(value, "provider_requirements");
                ToolWire wire = convert(value, ToolWire.class);
                yield tool(metadata(ref, tenantId, name, description, trust, status,
                        contentHash, wire), wire);
            }
            case WORKFLOW -> {
                WorkflowWire wire = convert(value, WorkflowWire.class);
                yield workflow(metadata(ref, tenantId, name, description, trust, status,
                        contentHash, wire), wire);
            }
            case KNOWLEDGE_SOURCE -> {
                KnowledgeWire wire = decodeKnowledgeWire(value);
                yield knowledge(metadata(ref, tenantId, name, description, trust, status,
                        contentHash, wire), wire);
            }
        };
    }

    private CapabilityMetadata metadata(
            CapabilityRef ref, Long tenantId, String name, String description,
            String trust, String status, String hash, CommonWire wire) {
        require(wire.modelInvocable(), "modelInvocable");
        require(wire.userInvocable(), "userInvocable");
        return new CapabilityMetadata(ref, tenantId, name, description,
                enumValue(Trust.class, trust, "trustLevel"),
                enumValue(Status.class, status, "status"), hash,
                new InvocationPolicy(wire.modelInvocable(), wire.userInvocable()));
    }

    private SkillDescriptor skill(CapabilityMetadata metadata, SkillWire wire) {
        require(wire.estimatedTokens(), "estimatedTokens");
        return new SkillDescriptor(metadata, wire.activationMode(), wire.skillPackage(),
                wire.conflictGroup(), list(wire.requiredCapabilities()), wire.estimatedTokens());
    }

    private SkillWire decodeSkillWire(CapabilityRef ref, Map<String, Object> descriptor) {
        Map<String, Object> value = object(descriptor, "skill descriptor");
        requireExactKeys(value, SKILL_FIELDS, "skill descriptor");

        Boolean modelInvocable = booleanValue(value, "model_invocable");
        Boolean userInvocable = booleanValue(value, "user_invocable");
        SkillActivationMode activationMode = enumValue(
                SkillActivationMode.class,
                textValue(value, "activation_mode"),
                "activation_mode");

        SkillPackage skillPackage = skillPackage(value.get("package"));

        String conflictGroup = optionalTextValue(value, "conflict_group");
        List<?> rawRequiredCapabilities = listValue(value, "required_capabilities");
        List<CapabilityRef> requiredCapabilities = new ArrayList<>(rawRequiredCapabilities.size());
        Set<String> seenCapabilities = new LinkedHashSet<>();
        for (Object rawRequired : rawRequiredCapabilities) {
            CapabilityRef required = capabilityRef(rawRequired, "required_capabilities");
            if (required.type() == ref.type() && required.capabilityId().equals(ref.capabilityId())) {
                throw new IllegalArgumentException("skill must not require itself");
            }
            if (!seenCapabilities.add(required.type() + ":" + required.capabilityId())) {
                throw new IllegalArgumentException("required capabilities must be unique");
            }
            requiredCapabilities.add(required);
        }

        long estimatedTokens = positiveLongValue(value, "estimated_tokens");
        return new SkillWire(
                modelInvocable,
                userInvocable,
                activationMode,
                skillPackage,
                conflictGroup,
                List.copyOf(requiredCapabilities),
                estimatedTokens);
    }

    private SkillPackage skillPackage(Object raw) {
        Map<String, Object> value = object(raw, "skill package");
        requireExactKeys(value, SKILL_PACKAGE_FIELDS, "skill package");
        String entrypoint = textValue(value, "entrypoint");
        Map<String, Object> metadataValue = object(value.get("metadata"), "skill package metadata");
        requireExactKeys(metadataValue, SKILL_METADATA_FIELDS, "skill package metadata");
        Map<String, String> extensions = stringMap(metadataValue.get("extensions"), "extensions");
        List<String> allowedTools = stringList(metadataValue.get("allowed_tools"), "allowed_tools");
        SkillPackage.Metadata metadata = new SkillPackage.Metadata(
                textValue(metadataValue, "name"),
                textValue(metadataValue, "description"),
                Optional.ofNullable(optionalTextValue(metadataValue, "license")),
                Optional.ofNullable(optionalTextValue(metadataValue, "compatibility")),
                extensions, allowedTools);
        List<?> rawFiles = listValue(value, "files");
        if (rawFiles.isEmpty()) {
            throw new IllegalArgumentException("skill package files must not be empty");
        }
        List<SkillPackage.FileEntry> files = new ArrayList<>(rawFiles.size());
        long totalBytes = 0;
        for (Object rawFile : rawFiles) {
            Map<String, Object> file = object(rawFile, "skill package file");
            requireExactKeys(file, SKILL_FILE_FIELDS, "skill package file");
            SkillPackage.Encoding encoding = enumValue(SkillPackage.Encoding.class,
                    textValue(file, "encoding"), "encoding");
            String content = stringValue(file, "content");
            long size = nonNegativeLongTextValue(file, "size");
            byte[] bytes;
            try {
                bytes = encoding == SkillPackage.Encoding.UTF8
                        ? content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : Base64.getDecoder().decode(content);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("skill package file content encoding is invalid", exception);
            }
            if (bytes.length != size || size > SkillPackageValidator.MAX_FILE_BYTES) {
                throw new IllegalArgumentException("skill package file size does not match content");
            }
            totalBytes = Math.addExact(totalBytes, size);
            if (totalBytes > SkillPackageValidator.MAX_PACKAGE_BYTES) {
                throw new IllegalArgumentException("skill package exceeds total size limit");
            }
            files.add(new SkillPackage.FileEntry(textValue(file, "path"),
                    textValue(file, "media_type"), encoding, content,
                    textValue(file, "sha256"), size));
        }
        if (files.size() > SkillPackageValidator.MAX_FILES) {
            throw new IllegalArgumentException("skill package contains too many files");
        }
        SkillPackage result = new SkillPackage(entrypoint, metadata, files);
        Map<String, byte[]> source = new LinkedHashMap<>();
        for (SkillPackage.FileEntry file : result.files()) {
            source.put(file.path(), file.encoding() == SkillPackage.Encoding.UTF8
                    ? file.content().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : Base64.getDecoder().decode(file.content()));
        }
        SkillPackage validated = new SkillPackageValidator().fromDirectory(source);
        Map<String, String> validatedDigests = new LinkedHashMap<>();
        validated.files().forEach(file -> validatedDigests.put(file.path(), file.sha256()));
        Map<String, String> suppliedDigests = new LinkedHashMap<>();
        result.files().forEach(file -> suppliedDigests.put(file.path(), file.sha256()));
        if (!validated.metadata().equals(result.metadata())
                || !validatedDigests.equals(suppliedDigests)) {
            throw new IllegalArgumentException("skill package metadata or digest does not match content");
        }
        return result;
    }

    /** 集合缺省或 null 表示空集；转换前拒绝 Jackson 将数值、布尔等元素强制转成字符串。 */
    private static void validateOptionalToolTextSet(Map<String, Object> descriptor, String field) {
        Object raw = descriptor.get(field);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Collection<?> values)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank() || !text.equals(text.trim())) {
                throw new IllegalArgumentException(field + " must contain normalized non-blank text");
            }
        }
    }

    private ToolVersion tool(CapabilityMetadata metadata, ToolWire wire) {
        require(wire.idempotent(), "idempotent");
        require(wire.timeoutMs(), "timeoutMs");
        ToolDefinition definition = new ToolDefinition(
                require(wire.toolName(), "toolName"),
                Optional.ofNullable(wire.title()),
                metadata.description(),
                require(wire.inputSchema(), "inputSchema"),
                Optional.ofNullable(wire.outputSchema()),
                toolIcons(wire.icons()),
                Optional.ofNullable(wire.annotations())
                        .map(ToolDefinition.UntrustedAnnotations::new));
        ToolExecutionPolicy policy = new ToolExecutionPolicy(
                enumValue(ToolExecutionPolicy.Effect.class, wire.effect(), "effect"),
                wire.idempotent(),
                enumValue(ToolExecutionPolicy.ConfirmationPolicy.class,
                        wire.confirmationPolicy(), "confirmationPolicy"),
                wire.timeoutMs(), set(wire.sensitiveFields()),
                set(wire.providerRequirements()), Boolean.TRUE.equals(wire.parallelSafe()),
                wire.callIdentityPolicy() == null
                        ? com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy.forEffect(
                            com.skillforge.runtime.api.ToolEffect.valueOf(wire.effect()))
                        : enumValue(com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy.class,
                            wire.callIdentityPolicy(), "callIdentityPolicy"));
        return new ToolVersion(metadata, definition, policy);
    }

    private WorkflowDescriptor workflow(CapabilityMetadata metadata, WorkflowWire wire) {
        require(wire.supportsHitl(), "supportsHitl");
        require(wire.timeoutMs(), "timeoutMs");
        require(wire.concurrencySafe(), "concurrencySafe");
        return new WorkflowDescriptor(metadata, map(wire.inputSchema()), map(wire.outputSchema()),
                wire.supportsHitl(), set(wire.exportFields()), wire.timeoutMs(),
                wire.concurrencySafe());
    }

    private KnowledgeSourceDescriptor knowledge(
            CapabilityMetadata metadata, KnowledgeWire wire) {
        require(wire.citationRequired(), "citationRequired");
        return new KnowledgeSourceDescriptor(metadata, wire.sourceId(),
                new LinkedHashSet<>(wire.sourceVersions()), set(wire.allowedOperators()),
                wire.freshnessPolicy(), wire.citationRequired());
    }

    private KnowledgeWire decodeKnowledgeWire(Map<String, Object> descriptor) {
        Map<String, Object> value = object(descriptor, "knowledge descriptor");
        requireExactKeys(value, KNOWLEDGE_FIELDS, "knowledge descriptor");
        String sourceId = textValue(value, "source_id");
        if (!sourceId.equals(sourceId.trim())) {
            throw new IllegalArgumentException("source_id must be normalized non-blank text");
        }
        List<String> sourceVersions = normalizedSourceVersions(value);
        KnowledgeWire wire = convert(value, KnowledgeWire.class);
        return new KnowledgeWire(wire.modelInvocable(), wire.userInvocable(), sourceId,
                sourceVersions, wire.allowedOperators(), wire.freshnessPolicy(), wire.citationRequired());
    }

    private static List<ToolDefinition.Icon> toolIcons(
            List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition.Icon> icons = new ArrayList<>(values.size());
        for (Map<String, Object> raw : values) {
            Map<String, Object> icon = object(raw, "tool icon");
            if (!TOOL_ICON_FIELDS.containsAll(icon.keySet()) || !icon.containsKey("src")) {
                throw new IllegalArgumentException(
                        "tool icon requires src and only supports " + TOOL_ICON_FIELDS);
            }
            List<String> sizes = new ArrayList<>();
            Object rawSizes = icon.get("sizes");
            if (rawSizes != null) {
                if (!(rawSizes instanceof List<?> list)) {
                    throw new IllegalArgumentException("tool icon sizes must be an array");
                }
                for (Object size : list) {
                    sizes.add(textValue(size, "tool icon size"));
                }
            }
            icons.add(new ToolDefinition.Icon(
                    textValue(icon, "src"),
                    Optional.ofNullable(optionalTextValue(icon, "mimeType")),
                    sizes,
                    Optional.ofNullable(optionalTextValue(icon, "theme"))));
        }
        return List.copyOf(icons);
    }

    private static List<String> normalizedSourceVersions(Map<String, Object> value) {
        List<?> rawVersions = listValue(value, "source_versions");
        if (rawVersions.isEmpty()) {
            throw new IllegalArgumentException("source_versions must not be empty");
        }
        List<String> versions = new ArrayList<>(rawVersions.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Object rawVersion : rawVersions) {
            if (!(rawVersion instanceof String version)
                    || version.isBlank() || !version.equals(version.trim())
                    || version.length() > 128
                    || !version.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
                throw new IllegalArgumentException(
                        "source_versions must contain normalized versions");
            }
            if (!seen.add(version)) {
                throw new IllegalArgumentException("source_versions must be unique");
            }
            versions.add(version);
        }
        return List.copyOf(versions);
    }

    private <T> T convert(Map<String, Object> value, Class<T> type) {
        try {
            return objectMapper.convertValue(value, type);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid " + type.getSimpleName() + " descriptor", exception);
        }
    }

    private static CapabilityRef capabilityRef(Object raw, String field) {
        Map<String, Object> value = object(raw, field);
        requireExactKeys(value, CAPABILITY_REF_FIELDS, field);
        return new CapabilityRef(
                enumValue(CapabilityType.class, textValue(value, "type"), field + ".type"),
                textValue(value, "capability_id"),
                textValue(value, "version_id"));
    }

    private static Map<String, Object> object(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " object keys must be text");
            }
            value.put(key, entry.getValue());
        }
        return value;
    }

    private static void requireExactKeys(
            Map<String, Object> value, Set<String> expected, String field) {
        if (!value.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                    field + " fields must be exactly " + expected + ", got " + value.keySet());
        }
    }

    private static Boolean booleanValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return booleanValue;
    }

    private static String textValue(Map<String, Object> value, String field) {
        return textValue(value.get(field), field);
    }

    private static String textValue(Object raw, String field) {
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return text;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return text;
    }

    private static List<String> stringList(Object raw, String field) {
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) result.add(textValue(value, field));
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(field + " must contain unique values");
        }
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(Object raw, String field) {
        Map<String, Object> value = object(raw, field);
        Map<String, String> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(textValue(key, field), textValue(item, field)));
        return Map.copyOf(result);
    }

    private static String optionalTextValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            return null;
        }
        return textValue(raw, field);
    }

    private static List<?> listValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return list;
    }

    private static long positiveLongValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer
                || raw instanceof Long || raw instanceof BigInteger)) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        try {
            long result = raw instanceof BigInteger bigInteger
                    ? bigInteger.longValueExact()
                    : ((Number) raw).longValue();
            if (result <= 0) {
                throw new ArithmeticException("not positive");
            }
            return result;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be a positive integer", exception);
        }
    }

    private static long nonNegativeLongValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer
                || raw instanceof Long || raw instanceof BigInteger)) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        try {
            long result = raw instanceof BigInteger bigInteger
                    ? bigInteger.longValueExact() : ((Number) raw).longValue();
            if (result < 0) throw new ArithmeticException("negative");
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be a non-negative integer", exception);
        }
    }

    private static long nonNegativeLongTextValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw instanceof String text && text.matches("0|[1-9][0-9]*")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(field + " exceeds range", exception);
            }
        }
        return nonNegativeLongValue(value, field);
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " has unsupported value: " + value, exception);
        }
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static <T> Set<T> set(Set<T> value) {
        return value == null ? Set.of() : value;
    }

    private static Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private sealed interface CommonWire permits SkillWire, ToolWire, WorkflowWire, KnowledgeWire {
        Boolean modelInvocable();
        Boolean userInvocable();
    }

    private record SkillWire(
            Boolean modelInvocable,
            Boolean userInvocable,
            SkillActivationMode activationMode,
            SkillPackage skillPackage,
                String conflictGroup,
            List<CapabilityRef> requiredCapabilities,
            Long estimatedTokens) implements CommonWire {
    }

    private record ToolWire(
            Boolean modelInvocable,
            Boolean userInvocable,
            String toolName,
            String title,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            List<Map<String, Object>> icons,
            Map<String, Object> annotations,
            String effect,
            Boolean idempotent,
            String confirmationPolicy,
            Long timeoutMs,
            Set<String> sensitiveFields,
            Set<String> providerRequirements, Boolean parallelSafe,
            @com.fasterxml.jackson.annotation.JsonProperty(CALL_IDENTITY_POLICY_FIELD) String callIdentityPolicy) implements CommonWire {
    }

    private record WorkflowWire(
            Boolean modelInvocable,
            Boolean userInvocable,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            Boolean supportsHitl,
            Set<String> exportFields,
            Long timeoutMs,
            Boolean concurrencySafe) implements CommonWire {
    }

    private record KnowledgeWire(
            Boolean modelInvocable,
            Boolean userInvocable,
            String sourceId,
            List<String> sourceVersions,
            Set<String> allowedOperators,
            String freshnessPolicy,
            Boolean citationRequired) implements CommonWire {
    }
}

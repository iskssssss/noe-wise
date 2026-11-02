package com.skillforge.core.capability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/** 把标准目录包与 灵知 激活治理字段组合为唯一持久 descriptor。 */
public final class SkillPackageDescriptorMapper {
    private SkillPackageDescriptorMapper() {
    }

    public static Map<String, Object> descriptor(
            SkillPackage skillPackage,
            boolean modelInvocable,
            boolean userInvocable,
            SkillActivationMode activationMode,
            String conflictGroup,
            List<CapabilityRef> requiredCapabilities) {
        return descriptor(skillPackage, modelInvocable, userInvocable, activationMode,
                conflictGroup, requiredCapabilities, estimatedTokens(skillPackage));
    }

    /**
     * SKILL.md 注入准入估算，与 Runtime 资源读取同一公式：{@code max(1, (字符数 + 3) / 4)}。
     * 这不是消息 tokenizer，也不是供应商 usage。
     */
    public static long estimatedTokens(SkillPackage skillPackage) {
        Objects.requireNonNull(skillPackage, "skillPackage");
        SkillPackage.FileEntry root = skillPackage.requireFile(skillPackage.entrypoint());
        if (root.encoding() != SkillPackage.Encoding.UTF8) {
            throw new IllegalArgumentException("SKILL.md must be UTF8");
        }
        return Math.max(1L, (root.content().length() + 3L) / 4L);
    }

    public static Map<String, Object> descriptor(
            SkillPackage skillPackage,
            boolean modelInvocable,
            boolean userInvocable,
            SkillActivationMode activationMode,
            String conflictGroup,
            List<CapabilityRef> requiredCapabilities,
            long estimatedTokens) {
        Objects.requireNonNull(skillPackage, "skillPackage");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", skillPackage.metadata().name());
        metadata.put("description", skillPackage.metadata().description());
        metadata.put("license", skillPackage.metadata().license().orElse(null));
        metadata.put("compatibility", skillPackage.metadata().compatibility().orElse(null));
        metadata.put("extensions", skillPackage.metadata().extensions());
        metadata.put("allowed_tools", skillPackage.metadata().allowedTools());
        List<Map<String, Object>> files = skillPackage.files().stream().map(file -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", file.path());
            value.put("media_type", file.mediaType());
            value.put("encoding", file.encoding().name());
            value.put("content", file.content());
            value.put("sha256", file.sha256());
            value.put("size", Long.toString(file.size()));
            return Map.copyOf(value);
        }).toList();
        Map<String, Object> packageValue = new LinkedHashMap<>();
        packageValue.put("entrypoint", skillPackage.entrypoint());
        packageValue.put("metadata", metadata);
        packageValue.put("files", files);
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("model_invocable", modelInvocable);
        descriptor.put("user_invocable", userInvocable);
        descriptor.put("activation_mode", Objects.requireNonNull(activationMode).name());
        descriptor.put("package", packageValue);
        descriptor.put("conflict_group", conflictGroup);
        descriptor.put("required_capabilities", requiredCapabilities.stream().map(ref -> Map.of(
                "type", ref.type().name(), "capability_id", ref.capabilityId(),
                "version_id", ref.versionId())).toList());
        descriptor.put("estimated_tokens", estimatedTokens);
        return Collections.unmodifiableMap(descriptor);
    }
}

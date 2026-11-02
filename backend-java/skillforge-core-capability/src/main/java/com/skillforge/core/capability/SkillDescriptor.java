package com.skillforge.core.capability;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Agent Skills 标准包的不可变版本描述；包资源本身不授予执行权限。
 * {@code estimatedTokens} 是发布时按 SKILL.md 冻结的体积，激活时对照挂载 tokenBudget，不是 tokenizer 或供应商 usage。
 */
public record SkillDescriptor(
        CapabilityMetadata metadata,
        SkillActivationMode activationMode,
        SkillPackage skillPackage,
        String conflictGroup,
        List<CapabilityRef> requiredCapabilities,
        long estimatedTokens) implements CapabilityDescriptor {

    /** 要求与不可变 SkillVersion 的包元数据绑定，恢复时不查询当前草稿。 */
    public boolean requiresPlan() {
        return skillPackage.metadata().requiresPlan();
    }

    public SkillDescriptor {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(activationMode, "activationMode");
        Objects.requireNonNull(skillPackage, "skillPackage");
        if (metadata.ref().type() != CapabilityType.SKILL) {
            throw new IllegalArgumentException("SkillDescriptor requires SKILL ref");
        }
        if (conflictGroup != null && conflictGroup.isBlank()) {
            throw new IllegalArgumentException("conflictGroup must be null or non-blank");
        }
        if (requiredCapabilities == null || requiredCapabilities.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("required capabilities must not be null");
        }
        Set<String> uniqueRequired = new HashSet<>();
        for (CapabilityRef required : requiredCapabilities) {
            if (required.type() == metadata.ref().type()
                    && required.capabilityId().equals(metadata.ref().capabilityId())) {
                throw new IllegalArgumentException("skill must not require itself");
            }
            if (!uniqueRequired.add(required.type() + ":" + required.capabilityId())) {
                throw new IllegalArgumentException("required capabilities must be unique");
            }
        }
        requiredCapabilities = List.copyOf(requiredCapabilities);
        if (estimatedTokens <= 0) {
            throw new IllegalArgumentException("estimatedTokens must be positive");
        }
    }
}

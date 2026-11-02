package com.skillforge.core.capability;

import java.util.Objects;

/** 不可变 Tool 版本：组合可移植调用定义与宿主执行策略。 */
public record ToolVersion(
        CapabilityMetadata metadata,
        ToolDefinition definition,
        ToolExecutionPolicy policy) implements CapabilityDescriptor {

    public ToolVersion {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(policy, "policy");
        if (metadata.ref().type() != CapabilityType.TOOL) {
            throw new IllegalArgumentException("ToolVersion requires TOOL ref");
        }
    }
}

package com.skillforge.core.capability;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 持久 Workflow 执行单元的不可变版本描述。 */
public record WorkflowDescriptor(
        CapabilityMetadata metadata,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        boolean supportsHitl,
        Set<String> exportFields,
        long timeoutMs,
        boolean concurrencySafe) implements CapabilityDescriptor {

    public WorkflowDescriptor {
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.ref().type() != CapabilityType.WORKFLOW) {
            throw new IllegalArgumentException("WorkflowDescriptor requires WORKFLOW ref");
        }
        inputSchema = Map.copyOf(inputSchema == null ? Map.of() : inputSchema);
        outputSchema = Map.copyOf(outputSchema == null ? Map.of() : outputSchema);
        exportFields = Set.copyOf(exportFields == null ? Set.of() : exportFields);
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
    }
}

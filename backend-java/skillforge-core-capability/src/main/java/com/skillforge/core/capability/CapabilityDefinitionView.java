package com.skillforge.core.capability;

import java.time.Instant;

/** Controller 可安全使用的 Definition 只读投影。 */
public record CapabilityDefinitionView(
        Long id,
        Long tenantId,
        CapabilityType capabilityType,
        String capabilityKey,
        String name,
        String description,
        String trustLevel,
        String status,
        Long defaultVersionId,
        Long revision,
        Instant createdAt,
        Instant updatedAt) {
}

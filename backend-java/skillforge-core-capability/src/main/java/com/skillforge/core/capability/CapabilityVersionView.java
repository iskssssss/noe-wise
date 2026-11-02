package com.skillforge.core.capability;

import java.time.Instant;
import java.util.Map;

/** Controller 可安全使用的 Capability Version 只读投影。 */
public record CapabilityVersionView(
        Long id,
        Long definitionId,
        Long tenantId,
        CapabilityType capabilityType,
        String versionKey,
        String name,
        String description,
        String trustLevel,
        Map<String, Object> descriptor,
        String contentHash,
        String status,
        Instant createdAt,
        Instant publishedAt) {
}

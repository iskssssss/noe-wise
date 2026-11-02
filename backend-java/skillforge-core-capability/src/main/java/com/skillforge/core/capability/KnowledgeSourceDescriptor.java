package com.skillforge.core.capability;

import java.util.Objects;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Knowledge Runtime 可使用的受治理来源版本描述。 */
public record KnowledgeSourceDescriptor(
        CapabilityMetadata metadata,
        String sourceId,
        Set<String> sourceVersions,
        Set<String> allowedOperators,
        String freshnessPolicy,
        boolean citationRequired) implements CapabilityDescriptor {

    public KnowledgeSourceDescriptor {
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.ref().type() != CapabilityType.KNOWLEDGE_SOURCE) {
            throw new IllegalArgumentException("KnowledgeSourceDescriptor requires KNOWLEDGE_SOURCE ref");
        }
        sourceId = normalizedSourceId(sourceId);
        sourceVersions = immutableManifest(sourceVersions);
        allowedOperators = immutableSet(allowedOperators);
        freshnessPolicy = freshnessPolicy == null ? "" : freshnessPolicy;
    }

    private static String normalizedSourceId(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("sourceId must be normalized non-blank text");
        }
        return value;
    }

    private static Set<String> immutableManifest(Set<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("sourceVersions must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || !value.equals(value.trim())
                    || value.length() > 128
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
                throw new IllegalArgumentException("sourceVersions must contain normalized versions");
            }
            if (!normalized.add(value)) {
                throw new IllegalArgumentException("sourceVersions must be unique");
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static Set<String> immutableSet(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values == null ? Set.of() : values));
    }
}

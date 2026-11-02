package com.skillforge.core.capability;

import java.util.Objects;

/** 所有能力类型共享的最小元数据，不包含类型专属字段。 */
public record CapabilityMetadata(
        CapabilityRef ref,
        Long tenantId,
        String name,
        String description,
        Trust trust,
        Status status,
        String contentHash,
        InvocationPolicy invocationPolicy) {

    public enum Trust { PLATFORM, TENANT }

    public enum Status { DRAFT, PUBLISHED, DEPRECATED, ARCHIVED }

    public CapabilityMetadata {
        Objects.requireNonNull(ref, "ref");
        name = requireText(name, "name");
        description = description == null ? "" : description;
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(status, "status");
        contentHash = requireText(contentHash, "contentHash");
        Objects.requireNonNull(invocationPolicy, "invocationPolicy");
        if (trust == Trust.TENANT && tenantId == null) {
            throw new IllegalArgumentException("tenant capability requires tenantId");
        }
        if (trust == Trust.PLATFORM && tenantId != null) {
            throw new IllegalArgumentException("platform capability must not have tenantId");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

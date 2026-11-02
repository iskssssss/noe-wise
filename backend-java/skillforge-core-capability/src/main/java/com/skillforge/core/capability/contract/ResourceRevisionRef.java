package com.skillforge.core.capability.contract;

import java.util.Objects;

/** 不可变资源 revision 的身份引用。 */
public record ResourceRevisionRef(
        String resourceId,
        ResourceType resourceType,
        String revisionId,
        long revision,
        ResourceRevisionStatus status,
        String contentHash) {

    public ResourceRevisionRef {
        resourceId = required(resourceId, "resourceId");
        Objects.requireNonNull(resourceType, "resourceType");
        revisionId = required(revisionId, "revisionId");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(status, "status");
        contentHash = required(contentHash, "contentHash");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

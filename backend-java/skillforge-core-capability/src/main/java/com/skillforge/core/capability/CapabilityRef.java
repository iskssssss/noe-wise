package com.skillforge.core.capability;

import java.util.Objects;

/** 不可变能力版本引用。 */
public record CapabilityRef(CapabilityType type, String capabilityId, String versionId) {

    public CapabilityRef {
        Objects.requireNonNull(type, "type");
        capabilityId = requireText(capabilityId, "capabilityId");
        versionId = requireText(versionId, "versionId");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

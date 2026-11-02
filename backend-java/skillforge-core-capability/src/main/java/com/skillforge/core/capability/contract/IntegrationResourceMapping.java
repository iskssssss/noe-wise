package com.skillforge.core.capability.contract;

import java.util.Objects;

/** 租户 integration 到内部资源 revision 的稳定外部映射。 */
public record IntegrationResourceMapping(
        String tenantId,
        String integrationId,
        ResourceType resourceType,
        String externalRef,
        String resourceId,
        long revision) {

    public IntegrationResourceMapping {
        tenantId = required(tenantId, "tenantId");
        integrationId = required(integrationId, "integrationId");
        Objects.requireNonNull(resourceType, "resourceType");
        externalRef = required(externalRef, "externalRef");
        resourceId = required(resourceId, "resourceId");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    public String stableKey() {
        return tenantId + ":" + integrationId + ":" + resourceType.value() + ":" + externalRef;
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

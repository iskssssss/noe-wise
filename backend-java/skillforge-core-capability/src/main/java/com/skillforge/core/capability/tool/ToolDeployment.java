package com.skillforge.core.capability.tool;

import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import java.util.Objects;

/** ToolVersion 到具体 Provider/Connector 的租户部署。 */
public record ToolDeployment(
        String deploymentId,
        Long tenantId,
        CapabilityRef toolVersion,
        ProviderType providerType,
        String providerRef,
        long revision,
        Environment environment,
        boolean active) {

    public enum ProviderType { PLATFORM, CONNECTOR, MCP, TEST_MOCK }

    public enum Environment { DEVELOPMENT, TEST, PRODUCTION }

    public ToolDeployment {
        if (deploymentId == null || deploymentId.isBlank()
                || providerRef == null || providerRef.isBlank()) {
            throw new IllegalArgumentException("deploymentId/providerRef must not be blank");
        }
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(toolVersion, "toolVersion");
        if (toolVersion.type() != CapabilityType.TOOL) {
            throw new IllegalArgumentException("ToolDeployment requires TOOL ref");
        }
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(environment, "environment");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (environment == Environment.PRODUCTION && providerType == ProviderType.TEST_MOCK) {
            throw new IllegalArgumentException("TEST_MOCK deployment is forbidden in production");
        }
    }
}

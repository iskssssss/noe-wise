package com.skillforge.core.capability.tool;

import com.skillforge.core.capability.PersistentCapabilityRegistry;
import java.util.Objects;
import java.util.Optional;

/** 持久 ToolDeployment 读取适配器。 */
public final class PersistentToolDeploymentRegistry implements ToolDeploymentRegistry {

    private final PersistentCapabilityRegistry capabilities;

    public PersistentToolDeploymentRegistry(PersistentCapabilityRegistry capabilities) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    @Override
    public Optional<ToolDeployment> findDeployment(Long tenantId, String deploymentId) {
        if (tenantId == null || deploymentId == null || deploymentId.isBlank()) {
            return Optional.empty();
        }
        Optional<ToolDeployment> deployment = capabilities.findToolDeployment(
                tenantId, deploymentId);
        return deployment == null ? Optional.empty() : deployment;
    }

}

package com.skillforge.core.agent.capabilitymount;

import com.skillforge.core.capability.CapabilityMount.MountConfig;
import com.skillforge.core.capability.CapabilityRef;
import java.util.Objects;

/** 以 mount revision CAS 更新 CapabilityMount 的强类型命令。 */
public record UpdateCapabilityMountCommand(
        Long tenantId,
        Long agentId,
        String mountId,
        long expectedRevision,
        CapabilityRef capability,
        MountConfig config,
        int priority,
        CapabilityMountStatus status) {

    public UpdateCapabilityMountCommand(
            Long tenantId,
            Long agentId,
            String mountId,
            long expectedRevision,
            CapabilityRef capability,
            MountConfig config,
            int priority) {
        this(tenantId, agentId, mountId, expectedRevision, capability, config, priority,
                CapabilityMountStatus.ACTIVE);
    }

    public UpdateCapabilityMountCommand {
        requirePositive(tenantId, "tenantId");
        requirePositive(agentId, "agentId");
        if (mountId == null || mountId.isBlank()) {
            throw new IllegalArgumentException("mountId must not be blank");
        }
        if (expectedRevision <= 0) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(status, "status");
        if (capability.type() != config.type()) {
            throw new IllegalArgumentException("mount config type does not match capability");
        }
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}

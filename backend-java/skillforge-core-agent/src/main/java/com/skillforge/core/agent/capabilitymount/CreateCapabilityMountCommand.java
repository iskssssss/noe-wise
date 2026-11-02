package com.skillforge.core.agent.capabilitymount;

import com.skillforge.core.capability.CapabilityMount.MountConfig;
import com.skillforge.core.capability.CapabilityRef;
import java.util.Objects;

/** 创建 CapabilityMount 的强类型命令。 */
public record CreateCapabilityMountCommand(
        Long tenantId,
        Long agentId,
        CapabilityRef capability,
        MountConfig config,
        int priority,
        CapabilityMountStatus status) {

    public CreateCapabilityMountCommand(
            Long tenantId,
            Long agentId,
            CapabilityRef capability,
            MountConfig config,
            int priority) {
        this(tenantId, agentId, capability, config, priority, CapabilityMountStatus.ACTIVE);
    }

    public CreateCapabilityMountCommand {
        requirePositive(tenantId, "tenantId");
        requirePositive(agentId, "agentId");
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

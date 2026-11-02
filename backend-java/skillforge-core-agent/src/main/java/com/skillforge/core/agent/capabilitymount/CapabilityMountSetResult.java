package com.skillforge.core.agent.capabilitymount;

import com.skillforge.core.capability.CapabilityMount;
import java.util.List;
import java.util.Objects;

/** 当前 Agent CapabilityMount 装配及其 generation。 */
public record CapabilityMountSetResult(
        Long tenantId,
        Long agentId,
        long generation,
        List<CapabilityMount> mounts) {

    public CapabilityMountSetResult {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        mounts = List.copyOf(mounts == null ? List.of() : mounts);
    }
}

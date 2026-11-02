package com.skillforge.core.capability.tool;

import com.skillforge.core.capability.CapabilityDescriptor;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.PersistentCapabilityRegistry;
import com.skillforge.core.capability.ToolVersion;
import java.util.Objects;
import java.util.Optional;

/** 基于持久 Capability Registry 的精确不可变 ToolVersion 读取适配器。 */
public final class PersistentToolRegistry implements ToolRegistry {

    private final PersistentCapabilityRegistry capabilities;

    public PersistentToolRegistry(PersistentCapabilityRegistry capabilities) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    @Override
    public Optional<ToolVersion> findVersion(Long tenantId, CapabilityRef version) {
        if (tenantId == null || version == null || version.type() != CapabilityType.TOOL) {
            return Optional.empty();
        }
        Optional<CapabilityDescriptor> descriptor = capabilities.find(tenantId, version);
        if (descriptor == null || descriptor.isEmpty()
                || !(descriptor.orElseThrow() instanceof ToolVersion toolVersion)) {
            return Optional.empty();
        }
        return Optional.of(toolVersion);
    }
}

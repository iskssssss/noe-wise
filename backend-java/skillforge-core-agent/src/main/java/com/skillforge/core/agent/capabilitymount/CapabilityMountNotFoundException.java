package com.skillforge.core.agent.capabilitymount;

/** CapabilityMount 或其 Agent 装配不存在。 */
public final class CapabilityMountNotFoundException extends CapabilityMountPersistenceException {

    public CapabilityMountNotFoundException(String message) {
        super(message);
    }
}

package com.skillforge.core.agent.capabilitymount;

/** CapabilityMount 的持久状态。 */
public enum CapabilityMountStatus {
    ACTIVE,
    DISABLED;

    public boolean active() {
        return this == ACTIVE;
    }
}

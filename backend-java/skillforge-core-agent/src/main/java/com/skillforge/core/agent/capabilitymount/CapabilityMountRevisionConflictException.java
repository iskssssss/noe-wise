package com.skillforge.core.agent.capabilitymount;

/** CapabilityMount expected revision 不匹配。 */
public final class CapabilityMountRevisionConflictException
        extends CapabilityMountPersistenceException {

    public CapabilityMountRevisionConflictException(String message) {
        super(message);
    }
}

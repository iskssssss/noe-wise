package com.skillforge.core.agent.capabilitymount;

/** CapabilityMount 持久化边界异常。 */
public class CapabilityMountPersistenceException extends RuntimeException {

    public CapabilityMountPersistenceException(String message) {
        super(message);
    }

    public CapabilityMountPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}

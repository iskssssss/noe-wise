package com.skillforge.core.agent.capabilitymount;

/** 以 mount revision CAS 删除 CapabilityMount 的强类型命令。 */
public record DeleteCapabilityMountCommand(
        Long tenantId,
        Long agentId,
        String mountId,
        long expectedRevision) {

    public DeleteCapabilityMountCommand {
        requirePositive(tenantId, "tenantId");
        requirePositive(agentId, "agentId");
        if (mountId == null || mountId.isBlank()) {
            throw new IllegalArgumentException("mountId must not be blank");
        }
        if (expectedRevision <= 0) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}

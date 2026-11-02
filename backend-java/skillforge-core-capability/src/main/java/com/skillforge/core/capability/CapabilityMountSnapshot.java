package com.skillforge.core.capability;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 单个 input/Loop 冻结的能力挂载快照。 */
public record CapabilityMountSnapshot(
        String snapshotId,
        String inputId,
        Long tenantId,
        Long agentId,
        long generation,
        List<CapabilityMount> mounts,
        Instant frozenAt) {

    /** Stable identity suffix shared by the snapshot and its SessionLog message. */
    public static final String IDENTITY_SUFFIX = ":capability-mount-snapshot";

    /** Metadata key under which the typed snapshot is stored in SessionLog. */
    public static final String METADATA_KEY = "capability_mount_snapshot";

    public CapabilityMountSnapshot {
        if (snapshotId == null || snapshotId.isBlank() || inputId == null || inputId.isBlank()) {
            throw new IllegalArgumentException("snapshotId/inputId must not be blank");
        }
        if (!snapshotId.equals(snapshotIdForInput(inputId))) {
            throw new IllegalArgumentException(
                    "snapshotId must equal inputId + ':capability-mount-snapshot'");
        }
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        if (tenantId <= 0 || agentId <= 0) {
            throw new IllegalArgumentException("tenantId and agentId must be positive");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        mounts = List.copyOf(mounts == null ? List.of() : mounts);
        Objects.requireNonNull(frozenAt, "frozenAt");
        Map<String, CapabilityMount> handles = new LinkedHashMap<>();
        Map<String, CapabilityRef> identities = new LinkedHashMap<>();
        for (CapabilityMount mount : mounts) {
            if (!tenantId.equals(mount.tenantId()) || !agentId.equals(mount.agentId())) {
                throw new IllegalArgumentException("mount ownership does not match snapshot");
            }
            if (!mount.active()) {
                throw new IllegalArgumentException("snapshot must contain only ACTIVE mounts");
            }
            if (mount.config() instanceof CapabilityMount.KnowledgeMountConfig knowledge
                    && knowledge.mode()
                            == CapabilityMount.KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE) {
                throw new IllegalArgumentException(
                        "snapshot knowledge mounts must pin their resolved revision");
            }
            if (handles.putIfAbsent(mount.handle(), mount) != null) {
                throw new IllegalArgumentException("duplicate capability handle: " + mount.handle());
            }
            String identity = mount.capability().type() + ":" + mount.capability().capabilityId();
            CapabilityRef previous = identities.putIfAbsent(identity, mount.capability());
            if (previous != null && !previous.versionId().equals(mount.capability().versionId())) {
                throw new IllegalArgumentException("conflicting capability versions: " + identity);
            }
        }
    }

    /** Returns the stable snapshot/message identity for an input. */
    public static String snapshotIdForInput(String inputId) {
        if (inputId == null || inputId.isBlank()) {
            throw new IllegalArgumentException("inputId must not be blank");
        }
        return inputId + IDENTITY_SUFFIX;
    }

    /** Alias making the SessionLog message identity explicit at call sites. */
    public static String messageIdForInput(String inputId) {
        return snapshotIdForInput(inputId);
    }

    public Optional<CapabilityMount> findActive(String handle) {
        return mounts.stream().filter(CapabilityMount::active)
                .filter(mount -> mount.handle().equals(handle)).findFirst();
    }

    public boolean contains(CapabilityRef ref) {
        return mounts.stream().filter(CapabilityMount::active)
                .anyMatch(mount -> mount.capability().equals(ref));
    }
}

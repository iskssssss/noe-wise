package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.core.capability.CapabilityMount.ToolMountConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CapabilityMountSnapshotTest {

    @Test
    void rejectsTwoVersionsOfTheSameCapabilityInOneInput() {
        CapabilityMount first = mount("mount-1", "version-1", "deployment-1");
        CapabilityMount second = mount("mount-2", "version-2", "deployment-2");

        assertThrows(IllegalArgumentException.class, () -> new CapabilityMountSnapshot(
                "input-1:capability-mount-snapshot", "input-1", 10L, 20L, 1,
                List.of(first, second), Instant.now()));
    }

    private CapabilityMount mount(String mountId, String versionId, String deploymentId) {
        return new CapabilityMount(
                mountId,
                10L,
                20L,
                new CapabilityRef(CapabilityType.TOOL, "calendar", versionId),
                new ToolMountConfig(deploymentId, 1),
                0,
                1,
                true);
    }
}

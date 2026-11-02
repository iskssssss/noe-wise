package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.capability.CapabilityMetadata.Status;
import com.skillforge.core.capability.CapabilityMetadata.Trust;
import com.skillforge.core.capability.CapabilityMount.ToolMountConfig;
import com.skillforge.core.capability.tool.ToolDeployment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CapabilityMountTenantIsolationTest {

    @Test
    void rejectsDescriptorOwnedByAnotherTenant() {
        CapabilityRef ref = new CapabilityRef(CapabilityType.TOOL, "calendar", "v1");
        CapabilityDescriptor descriptor = CapabilityContractsTest.metadataDescriptor(
                ref, 99L, Trust.TENANT, Status.PUBLISHED);
        CapabilityMountResolver resolver = resolver(descriptor, null);

        assertThrows(IllegalArgumentException.class, () -> resolver.freeze(
                "snapshot", "input", 10L, 20L, 1,
                List.of(mount(ref, "deployment"))));
    }

    @Test
    void rejectsDeploymentOwnedByAnotherTenant() {
        CapabilityRef ref = new CapabilityRef(CapabilityType.TOOL, "calendar", "v1");
        CapabilityDescriptor descriptor = CapabilityContractsTest.metadataDescriptor(
                ref, 10L, Trust.TENANT, Status.PUBLISHED);
        ToolDeployment deployment = new ToolDeployment(
                "deployment", 99L, ref, ToolDeployment.ProviderType.CONNECTOR,
                "connector", 1, ToolDeployment.Environment.TEST, true);

        assertThrows(IllegalArgumentException.class, () -> resolver(descriptor, deployment).freeze(
                "snapshot", "input", 10L, 20L, 1,
                List.of(mount(ref, "deployment"))));
    }

    @Test
    void disabledMountIsNotPartOfTheFrozenInput() {
        CapabilityMount disabled = new CapabilityMount(
                "disabled", 10L, 20L,
                new CapabilityRef(CapabilityType.TOOL, "missing", "v1"),
                new ToolMountConfig("missing", 1), 0, 1, false);

        CapabilityMountSnapshot snapshot = resolver(null, null).freeze(
                CapabilityMountSnapshot.snapshotIdForInput("input"), "input", 10L, 20L, 1,
                List.of(disabled));

        assertTrue(snapshot.mounts().isEmpty());
    }

    private CapabilityMountResolver resolver(
            CapabilityDescriptor descriptor, ToolDeployment deployment) {
        return new CapabilityMountResolver(new StubRegistry(descriptor, deployment),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private CapabilityMount mount(CapabilityRef ref, String deploymentId) {
        return new CapabilityMount("mount", 10L, 20L, ref,
                new ToolMountConfig(deploymentId, 1), 0, 1, true);
    }

    private record StubRegistry(
            CapabilityDescriptor descriptor,
            ToolDeployment deployment) implements CapabilityMountResolver.Registry {
        @Override public Optional<CapabilityMount> resolveCurrentMount(Long tenantId, CapabilityMount mount) {
            return Optional.of(mount);
        }
        @Override public Optional<CapabilityDescriptor> find(Long tenantId, CapabilityRef ref) {
            return Optional.ofNullable(descriptor);
        }
        @Override public Optional<ToolDeployment> findToolDeployment(
                Long tenantId, String deploymentId) {
            return Optional.ofNullable(deployment);
        }
        @Override public boolean workflowInstanceMatches(
                Long tenantId, String workflowInstanceId, long workflowInstanceRevision,
                CapabilityRef workflowVersion) {
            return false;
        }
        @Override public Optional<CapabilityMount> resolveKnowledgeMount(
                Long tenantId, CapabilityMount mount) {
            return Optional.empty();
        }
    }
}

package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.skillforge.core.capability.CapabilityMetadata.Status;
import com.skillforge.core.capability.CapabilityMetadata.Trust;
import com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig;
import com.skillforge.core.capability.KnowledgeSourceDescriptor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CapabilityMountResolverTest {

    private static final long TENANT_ID = 10L;
    private static final long AGENT_ID = 20L;

    @Test
    void exactInactiveToolDeploymentHasATypedRefusalAndActiveRestorationStillWorks() {
        var registry = toolRegistry();
        var snapshot = toolSnapshot();
        var resolver = new CapabilityMountResolver(registry, Clock.systemUTC());
        assertSame(snapshot, resolver.restore(snapshot));
        assertEquals(1, registry.deploymentReads);
        registry.deployment = Optional.of(toolDeployment("deployment", TENANT_ID, toolRef(), 8, false));
        assertThrows(CapabilityMountResolver.InactiveToolDeploymentException.class,
                () -> resolver.restore(snapshot));
        assertEquals(2, registry.deploymentReads); // 每次仍是原有的一次部署读取。
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "deployment", "version", "capability", "revision", "missing", "storage", "same_text"})
    void invalidOrUnavailableDeploymentCannotMasqueradeAsConfirmedInactivity(String variant) {
        var registry = toolRegistry();
        var ref = variant.equals("version") ? new CapabilityRef(CapabilityType.TOOL, "tool", "v2")
                : variant.equals("capability") ? new CapabilityRef(CapabilityType.TOOL, "other", "v1") : toolRef();
        registry.deployment = Optional.of(toolDeployment(variant.equals("deployment") ? "other" : "deployment",
                variant.equals("tenant") ? TENANT_ID + 1 : TENANT_ID, ref,
                variant.equals("revision") ? 6 : 8, false));
        if (variant.equals("missing")) registry.deployment = Optional.empty();
        if (variant.equals("storage")) registry.deploymentFailure = new IllegalStateException("storage_unavailable");
        if (variant.equals("same_text")) registry.deploymentFailure = new IllegalArgumentException("tool_deployment_inactive");

        var failure = assertThrows(RuntimeException.class,
                () -> new CapabilityMountResolver(registry, Clock.systemUTC()).restore(toolSnapshot()));
        assertFalse(failure instanceof CapabilityMountResolver.InactiveToolDeploymentException);
        if (registry.deploymentFailure != null) assertSame(registry.deploymentFailure, failure);
        assertEquals(1, registry.deploymentReads);
    }

    private static CapabilityRef toolRef() { return new CapabilityRef(CapabilityType.TOOL, "tool", "v1"); }

    private static CapabilityMountSnapshot toolSnapshot() {
        var mount = new CapabilityMount("tool-mount", TENANT_ID, AGENT_ID, toolRef(),
                new CapabilityMount.ToolMountConfig("deployment", 7), 0, 1, true);
        return new CapabilityMountSnapshot(CapabilityMountSnapshot.snapshotIdForInput("tool-input"),
                "tool-input", TENANT_ID, AGENT_ID, 1, List.of(mount), Instant.EPOCH);
    }

    private static com.skillforge.core.capability.tool.ToolDeployment toolDeployment(
            String id, Long tenant, CapabilityRef ref, long revision, boolean active) {
        return new com.skillforge.core.capability.tool.ToolDeployment(id, tenant, ref,
                com.skillforge.core.capability.tool.ToolDeployment.ProviderType.PLATFORM,
                "read", revision, com.skillforge.core.capability.tool.ToolDeployment.Environment.TEST, active);
    }

    private static StubRegistry toolRegistry() {
        var registry = new StubRegistry();
        registry.descriptors.put(toolRef(), new ToolVersion(new CapabilityMetadata(toolRef(), TENANT_ID,
                "read", "read", Trust.TENANT, Status.PUBLISHED, "hash", new InvocationPolicy(true, false)),
                new ToolDefinition("read", Optional.empty(), "read", Map.of("type", "object"), Optional.empty(),
                        List.of(), Optional.empty()),
                new ToolExecutionPolicy(ToolExecutionPolicy.Effect.READ_ONLY, true,
                        ToolExecutionPolicy.ConfirmationPolicy.NEVER, 5000, Set.of(), Set.of())));
        registry.deployment = Optional.of(toolDeployment("deployment", TENANT_ID, toolRef(), 7, true));
        return registry;
    }

    @Test
    void followActiveFreezesTheAdvancedPublishedVersionAndRevision() {
        CapabilityRef configured = knowledgeRef("501");
        CapabilityRef active = knowledgeRef("502");
        CapabilityMount requested = knowledgeMount(
                configured,
                new KnowledgeMountConfig(KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE, null));
        CapabilityMount resolved = knowledgeMount(
                active,
                new KnowledgeMountConfig(KnowledgeMountConfig.BindingMode.PINNED_REVISION, "700"));

        StubRegistry registry = new StubRegistry();
        registry.descriptors.put(configured, knowledgeDescriptor(configured));
        registry.descriptors.put(active, knowledgeDescriptor(active));
        registry.knowledgeResolution = Optional.of(resolved);

        CapabilityMountSnapshot snapshot = resolver(registry).freeze(
                CapabilityMountSnapshot.snapshotIdForInput("input-1"), "input-1",
                TENANT_ID, AGENT_ID, 8L, List.of(requested));

        CapabilityMount frozen = snapshot.mounts().get(0);
        assertEquals(active, frozen.capability());
        assertEquals(
                new KnowledgeMountConfig(
                        KnowledgeMountConfig.BindingMode.PINNED_REVISION, "700"),
                frozen.config());
    }

    @Test
    void pinnedKnowledgePreservesThePublishedVersionAndRevision() {
        CapabilityRef pinnedRef = knowledgeRef("501");
        CapabilityMount requested = knowledgeMount(
                pinnedRef,
                new KnowledgeMountConfig(
                        KnowledgeMountConfig.BindingMode.PINNED_REVISION, "600"));
        CapabilityMount returned = knowledgeMount(
                pinnedRef,
                new KnowledgeMountConfig(
                        KnowledgeMountConfig.BindingMode.PINNED_REVISION, "600"));

        StubRegistry registry = new StubRegistry();
        registry.descriptors.put(pinnedRef, knowledgeDescriptor(pinnedRef));
        registry.knowledgeResolution = Optional.of(returned);

        CapabilityMount frozen = resolver(registry).freeze(
                CapabilityMountSnapshot.snapshotIdForInput("input-2"), "input-2",
                TENANT_ID, AGENT_ID, 9L, List.of(requested)).mounts().get(0);

        assertEquals(pinnedRef, frozen.capability());
        assertEquals(requested.config(), frozen.config());
        assertEquals(requested.mountId(), frozen.mountId());
    }

    @Test
    void malformedResolvedKnowledgeOutputFailsClosed() {
        CapabilityRef configured = knowledgeRef("501");
        CapabilityMount requested = knowledgeMount(
                configured,
                new KnowledgeMountConfig(KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE, null));
        CapabilityMount wrongOwner = new CapabilityMount(
                requested.mountId(), 99L, AGENT_ID, knowledgeRef("502"),
                new KnowledgeMountConfig(
                        KnowledgeMountConfig.BindingMode.PINNED_REVISION, "700"),
                requested.priority(), requested.revision(), true);

        StubRegistry registry = new StubRegistry();
        registry.descriptors.put(configured, knowledgeDescriptor(configured));
        registry.descriptors.put(knowledgeRef("502"), knowledgeDescriptor(knowledgeRef("502")));
        registry.knowledgeResolution = Optional.of(wrongOwner);

        assertThrows(IllegalArgumentException.class, () -> resolver(registry).freeze(
                CapabilityMountSnapshot.snapshotIdForInput("input-3"), "input-3",
                TENANT_ID, AGENT_ID, 10L, List.of(requested)));
    }

    @Test
    void restoredPinnedResolutionCannotSilentlyChangeVersionOrRevision() {
        CapabilityRef configured = knowledgeRef("501");
        CapabilityMount requested = knowledgeMount(
                configured,
                new KnowledgeMountConfig(
                        KnowledgeMountConfig.BindingMode.PINNED_REVISION, "600"));
        CapabilityMount changed = knowledgeMount(
                knowledgeRef("502"),
                new KnowledgeMountConfig(
                        KnowledgeMountConfig.BindingMode.PINNED_REVISION, "700"));

        StubRegistry registry = new StubRegistry();
        registry.descriptors.put(configured, knowledgeDescriptor(configured));
        registry.descriptors.put(changed.capability(), knowledgeDescriptor(changed.capability()));
        registry.knowledgeResolution = Optional.of(changed);

        assertThrows(IllegalArgumentException.class, () -> resolver(registry).restore(
                new CapabilityMountSnapshot(CapabilityMountSnapshot.snapshotIdForInput("input-4"), "input-4",
                        TENANT_ID, AGENT_ID, 11L, List.of(requested), Instant.EPOCH)));
    }

    private CapabilityMountResolver resolver(StubRegistry registry) {
        return new CapabilityMountResolver(
                registry, Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC));
    }

    private CapabilityRef knowledgeRef(String versionId) {
        return new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "500", versionId);
    }

    private CapabilityMount knowledgeMount(CapabilityRef ref, KnowledgeMountConfig config) {
        return new CapabilityMount("mount-knowledge", TENANT_ID, AGENT_ID, ref, config, 4, 3, true);
    }

    private KnowledgeSourceDescriptor knowledgeDescriptor(CapabilityRef ref) {
        return new KnowledgeSourceDescriptor(
                new CapabilityMetadata(ref, TENANT_ID, "knowledge", "", Trust.TENANT,
                        Status.PUBLISHED, "hash-" + ref.versionId(),
                        new InvocationPolicy(true, false)),
                "knowledge-source-" + ref.capabilityId(),
                Set.of("source-v1", "source-v2"),
                Set.of("KEYWORD"), "active", true);
    }

    private static final class StubRegistry implements CapabilityMountResolver.Registry {
        private final Map<CapabilityRef, CapabilityDescriptor> descriptors = new HashMap<>();
        private Optional<CapabilityMount> knowledgeResolution = Optional.empty();
        private Optional<com.skillforge.core.capability.tool.ToolDeployment> deployment = Optional.empty();
        private RuntimeException deploymentFailure;
        private int deploymentReads;

        @Override
        public Optional<CapabilityMount> resolveCurrentMount(Long tenantId, CapabilityMount mount) {
            return mount.capability().type() == CapabilityType.KNOWLEDGE_SOURCE
                    ? knowledgeResolution : Optional.of(mount);
        }

        @Override
        public Optional<CapabilityDescriptor> find(Long tenantId, CapabilityRef ref) {
            return Optional.ofNullable(descriptors.get(ref));
        }

        @Override
        public Optional<com.skillforge.core.capability.tool.ToolDeployment> findToolDeployment(
                Long tenantId, String deploymentId) {
            deploymentReads++;
            if (deploymentFailure != null) throw deploymentFailure;
            return deployment;
        }

        @Override
        public boolean workflowInstanceMatches(
                Long tenantId, String workflowInstanceId, long workflowInstanceRevision,
                CapabilityRef workflowVersion) {
            return false;
        }

        @Override
        public Optional<CapabilityMount> resolveKnowledgeMount(
                Long tenantId, CapabilityMount mount) {
            return knowledgeResolution;
        }
    }
}

package com.skillforge.core.capability.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.capability.CapabilityMetadata;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.InvocationPolicy;
import com.skillforge.core.capability.ToolDefinition;
import com.skillforge.core.capability.ToolExecutionPolicy;
import com.skillforge.core.capability.ToolVersion;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultToolCatalogTest {

    private static final long TENANT_ID = 7L;
    private static final long AGENT_ID = 9L;

    @Test
    void listsOnlyExactFrozenVersionsAndDeploymentsWithStableServerDisambiguation() {
        CapabilityMount serverBMount = mount("b", "version-b", "deployment-b", 2L);
        CapabilityMount unavailableMount = mount(
                "disabled", "version-disabled", "deployment-disabled", 3L);
        CapabilityMount serverAMount = mount("a", "version-a", "deployment-a", 4L);

        ToolVersion serverAVersion = version(serverAMount.capability(), "Shared calendar");
        ToolVersion serverBVersion = version(serverBMount.capability(), "Shared calendar");
        ToolVersion unavailableVersion = version(
                unavailableMount.capability(), "Unavailable calendar");
        Map<CapabilityRef, ToolVersion> versions = Map.of(
                serverAMount.capability(), serverAVersion,
                serverBMount.capability(), serverBVersion,
                unavailableMount.capability(), unavailableVersion);
        Map<String, ToolDeployment> deployments = new HashMap<>();
        ToolDeployment serverADeployment = deployment(
                serverAMount, "server-a", 4L, true);
        ToolDeployment serverBDeployment = deployment(
                serverBMount, "server-b", 2L, true);
        deployments.put(serverADeployment.deploymentId(), serverADeployment);
        deployments.put(serverBDeployment.deploymentId(), serverBDeployment);
        deployments.put("deployment-disabled", deployment(
                unavailableMount, "server-a", 3L, false));

        ToolRegistry registry = (tenantId, ref) -> Optional.ofNullable(versions.get(ref));
        ToolDeploymentRegistry deploymentRegistry = new ToolDeploymentRegistry() {
            @Override
            public Optional<ToolDeployment> findDeployment(
                    Long tenantId, String deploymentId) {
                return Optional.ofNullable(deployments.get(deploymentId));
            }
        };
        ToolCatalog catalog = new DefaultToolCatalog(
                new DefaultToolResolver(registry, deploymentRegistry));
        CapabilityMountSnapshot snapshot = snapshot(
                serverBMount, unavailableMount, serverAMount);

        List<ToolCatalog.Entry> entries = catalog.list(snapshot);

        assertEquals(List.of("mcp:server-a", "mcp:server-b"), entries.stream()
                .map(ToolCatalog.Entry::serverIdentity).toList());
        assertEquals(List.of(
                "mcp:server-a/shared_calendar",
                "mcp:server-b/shared_calendar"), entries.stream()
                .map(ToolCatalog.Entry::qualifiedName).toList());
        assertEquals(List.of("tool:a", "tool:b"), entries.stream()
                .map(ToolCatalog.Entry::handle).toList());
        assertSame(serverAVersion, entries.getFirst().target().version());
        assertEquals(serverADeployment, entries.getFirst().target().deployment());
        assertEquals(Map.of("readOnlyHint", true), entries.getFirst().definition()
                .annotations().orElseThrow().value());

        assertTrue(catalog.resolve(snapshot, "shared_calendar").isEmpty());
        assertTrue(catalog.resolve(snapshot, "mcp:server-a/shared_calendar").isEmpty());
        assertTrue(catalog.resolve(snapshot, "tool:disabled").isEmpty());
        ToolCatalog.Entry exact = catalog.resolve(snapshot, "tool:a").orElseThrow();
        assertSame(serverAVersion, exact.target().version());
        assertEquals(serverADeployment, exact.target().deployment());
    }

    @Test
    void emptyFrozenMountSetProducesAnEmptyCatalog() {
        ToolCatalog catalog = new DefaultToolCatalog((handle, snapshot) ->
                new ToolResolver.Rejected(ToolResolver.Reason.CAPABILITY_NOT_MOUNTED));

        assertTrue(catalog.list(snapshot()).isEmpty());
    }

    private CapabilityMount mount(
            String mountId, String versionId, String deploymentId,
            long deploymentRevision) {
        return new CapabilityMount(
                mountId, TENANT_ID, AGENT_ID,
                new CapabilityRef(CapabilityType.TOOL, "tool-" + mountId, versionId),
                new CapabilityMount.ToolMountConfig(deploymentId, deploymentRevision),
                1, 1L, true);
    }

    private ToolVersion version(CapabilityRef ref, String description) {
        CapabilityMetadata metadata = new CapabilityMetadata(
                ref, TENANT_ID, "Calendar", description,
                CapabilityMetadata.Trust.TENANT,
                CapabilityMetadata.Status.PUBLISHED,
                "hash-" + ref.versionId(), new InvocationPolicy(true, true));
        ToolDefinition definition = new ToolDefinition(
                "shared_calendar", Optional.of("Shared calendar"), description,
                Map.of("type", "object"),
                Optional.of(Map.of("type", "object")),
                List.of(new ToolDefinition.Icon(
                        "https://example.test/calendar.svg",
                        Optional.of("image/svg+xml"), List.of("32x32"),
                        Optional.of("light"))),
                Optional.of(new ToolDefinition.UntrustedAnnotations(
                        Map.of("readOnlyHint", true))));
        return new ToolVersion(metadata, definition, new ToolExecutionPolicy(
                ToolExecutionPolicy.Effect.READ_ONLY, false,
                ToolExecutionPolicy.ConfirmationPolicy.NEVER,
                1_000L, Set.of(), Set.of()));
    }

    private ToolDeployment deployment(
            CapabilityMount mount, String serverIdentity,
            long revision, boolean active) {
        return new ToolDeployment(
                ((CapabilityMount.ToolMountConfig) mount.config()).toolDeploymentId(),
                TENANT_ID, mount.capability(), ToolDeployment.ProviderType.MCP,
                serverIdentity, revision, ToolDeployment.Environment.PRODUCTION, active);
    }

    private CapabilityMountSnapshot snapshot(CapabilityMount... mounts) {
        String inputId = "input-catalog";
        return new CapabilityMountSnapshot(
                CapabilityMountSnapshot.snapshotIdForInput(inputId), inputId,
                TENANT_ID, AGENT_ID, 1L, List.of(mounts),
                Instant.parse("2026-09-14T00:00:00Z"));
    }
}

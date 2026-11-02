package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.core.capability.CapabilityMetadata.Status;
import com.skillforge.core.capability.CapabilityMetadata.Trust;
import com.skillforge.core.capability.CapabilityMount.SkillMountConfig;
import com.skillforge.core.capability.CapabilityMount.ToolMountConfig;
import com.skillforge.core.capability.tool.ToolDeployment;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CapabilityContractsTest {

    @Test
    void mcpToolNamesAcceptProtocolCharactersInEveryPosition() {
        for (String name : List.of("_search", "-search", ".search", "search", "x".repeat(128))) {
            assertEquals(name, new ToolDefinition(name, java.util.Optional.empty(), "description",
                    java.util.Map.of(), java.util.Optional.empty(), List.of(), java.util.Optional.empty()).name());
        }
        for (String name : List.of("", "a b", "x".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> new ToolDefinition(name, java.util.Optional.empty(),
                    "description", java.util.Map.of(), java.util.Optional.empty(), List.of(), java.util.Optional.empty()));
        }
    }

    @Test
    void mountConfigMustMatchCapabilityType() {
        CapabilityRef skill = new CapabilityRef(CapabilityType.SKILL, "writing", "1.0.0");
        assertThrows(IllegalArgumentException.class, () -> new CapabilityMount(
                "m1", 1L, 2L, skill, new ToolMountConfig("deployment-1", 1), 0, 1, true));
    }

    @Test
    void snapshotRejectsCrossTenantMount() {
        CapabilityRef skill = new CapabilityRef(CapabilityType.SKILL, "writing", "1.0.0");
        CapabilityMount mount = new CapabilityMount(
                "m1", 9L, 2L, skill,
                new SkillMountConfig(SkillActivationMode.ALWAYS, 100), 0, 1, true);
        assertThrows(IllegalArgumentException.class, () -> new CapabilityMountSnapshot(
                "input:capability-mount-snapshot", "input", 1L, 2L, 1,
                List.of(mount), Instant.now()));
    }

    @Test
    void testMockDeploymentIsForbiddenInProduction() {
        CapabilityRef tool = new CapabilityRef(CapabilityType.TOOL, "query", "1.0.0");
        assertThrows(IllegalArgumentException.class, () -> new ToolDeployment(
                "d1", 1L, tool, ToolDeployment.ProviderType.TEST_MOCK,
                "mock", 1, ToolDeployment.Environment.PRODUCTION, true));
    }

    @Test
    void handleComesFromMountIdentityNotDisplayName() {
        CapabilityRef skill = new CapabilityRef(CapabilityType.SKILL, "writing", "1.0.0");
        CapabilityMount mount = new CapabilityMount(
                "mount-42", 1L, 2L, skill,
                new SkillMountConfig(SkillActivationMode.ON_DEMAND, 100), 0, 1, true);
        assertEquals("skill:mount-42", mount.handle());
    }

    @Test
    void skillDescriptorRejectsInvalidPackageAndSelfDependency() {
        CapabilityRef skill = new CapabilityRef(CapabilityType.SKILL, "writing", "1.0.0");
        SkillPackage skillPackage = new SkillPackageValidator().fromDirectory(java.util.Map.of(
                "SKILL.md", "---\nname: writing\ndescription: Writing policy\n---\nFollow policy"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThrows(IllegalArgumentException.class, () -> new SkillDescriptor(
                metadata(skill), SkillActivationMode.ALWAYS, skillPackage, null, List.of(skill), 20));
    }

    static CapabilityMetadata metadata(CapabilityRef ref) {
        return new CapabilityMetadata(ref, 1L, ref.capabilityId(), "description", Trust.TENANT,
                Status.PUBLISHED, "hash", new InvocationPolicy(true, false));
    }

    static CapabilityDescriptor metadataDescriptor(
            CapabilityRef ref, Long tenantId, CapabilityMetadata.Trust trust,
            CapabilityMetadata.Status status) {
        CapabilityMetadata metadata = new CapabilityMetadata(
                ref, tenantId, ref.capabilityId(), "description", trust, status,
                "hash", new InvocationPolicy(true, false));
        return switch (ref.type()) {
            case TOOL -> new ToolVersion(
                    metadata,
                    new ToolDefinition(
                            "query", java.util.Optional.empty(), "description",
                            java.util.Map.of(),
                            java.util.Optional.of(java.util.Map.of()),
                            java.util.List.of(), java.util.Optional.empty()),
                    new ToolExecutionPolicy(
                            ToolExecutionPolicy.Effect.READ_ONLY,
                            true,
                            ToolExecutionPolicy.ConfirmationPolicy.NEVER,
                            1_000,
                            java.util.Set.of(),
                            java.util.Set.of()));
            default -> throw new IllegalArgumentException("test helper only supports TOOL");
        };
    }
}

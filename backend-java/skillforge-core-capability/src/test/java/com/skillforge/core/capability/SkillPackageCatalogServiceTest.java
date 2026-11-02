package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class SkillPackageCatalogServiceTest {
    private final CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
    private final CapabilityDescriptorCodec codec = new CapabilityDescriptorCodec(new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    private final SkillPackageCatalogService service = new SkillPackageCatalogService(
            catalog, new SkillPackageValidator(), codec);

    @Test
    void planRequirementSurvivesDescriptorRoundTripAndRejectsFrozenMetadataTampering() throws Exception {
        var source = new java.util.LinkedHashMap<String, byte[]>();
        source.put("SKILL.md", "---\nname: frozen\ndescription: Frozen\nmetadata:\n  skillforge-plan: required\n---\nBody"
                .getBytes(StandardCharsets.UTF_8));
        var frozen = new SkillPackageValidator().fromDirectory(source);
        var descriptor = SkillPackageDescriptorMapper.descriptor(frozen, true, false,
                SkillActivationMode.ON_DEMAND, null, List.of());
        var mapper = new ObjectMapper();
        Map<String, Object> restored = mapper.readValue(mapper.writeValueAsBytes(descriptor),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        source.put("SKILL.md", "---\nname: frozen\ndescription: Frozen\n---\nChanged draft"
                .getBytes(StandardCharsets.UTF_8));
        var decoded = (SkillDescriptor) codec.decode(new CapabilityRef(CapabilityType.SKILL, "9", "4"),
                7L, "frozen", "Frozen", "TENANT", "PUBLISHED", "hash", restored);
        assertEquals(true, decoded.requiresPlan());
        assertEquals("required", decoded.skillPackage().metadata().planRequirement());
        @SuppressWarnings("unchecked")
        var packageValue = (Map<String, Object>) restored.get("package");
        @SuppressWarnings("unchecked")
        var metadata = (Map<String, Object>) packageValue.get("metadata");
        metadata.put("extensions", Map.of("skillforge-plan", "optional"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new CapabilityRef(CapabilityType.SKILL, "9", "4"), 7L, "frozen", "Frozen", "TENANT",
                "PUBLISHED", "hash", restored));
    }

    @Test
    void importsDirectoryWithServerComputedIdentityAndNeverExecutesScript() {
        when(catalog.createDraftVersion(any(), eq(9L), eq("1.0.0"), eq("safe-skill"),
                eq("Safe skill"), any(), any())).thenAnswer(invocation -> new CapabilityVersionView(
                        4L, 9L, 7L, CapabilityType.SKILL, "1.0.0", "safe-skill", "Safe skill",
                        "TENANT", invocation.getArgument(6), invocation.getArgument(5), "DRAFT",
                        Instant.EPOCH, null));
        String script = "throw new Error('must never execute')";
        SkillPackageImportRequest request = request(Map.of(
                "SKILL.md", b64("---\nname: safe-skill\ndescription: Safe skill\n---\nDo work"),
                "scripts/run.js", b64(script)));

        CapabilityVersionView imported = service.importDirectory(
                CapabilityCatalogScope.tenant(7L), 9L, request);

        assertEquals(64, imported.contentHash().length());
        assertEquals(script, servicePackage(imported).requireFile("scripts/run.js").content());
        String skillMd = "---\nname: safe-skill\ndescription: Safe skill\n---\nDo work";
        assertEquals(Math.max(1L, (skillMd.length() + 3L) / 4L), imported.descriptor().get("estimated_tokens"));
        verify(catalog).createDraftVersion(any(), eq(9L), eq("1.0.0"), eq("safe-skill"),
                eq("Safe skill"), eq(imported.contentHash()), any());
    }

    @Test
    void readsListsAndDownloadsFrozenPackage() throws Exception {
        SkillPackage skillPackage = new SkillPackageValidator().fromDirectory(Map.of(
                "SKILL.md", "---\nname: frozen\ndescription: Frozen\n---\nBody".getBytes(StandardCharsets.UTF_8),
                "references/a.txt", "evidence".getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> descriptor = SkillPackageDescriptorMapper.descriptor(skillPackage,
                true, false, SkillActivationMode.ON_DEMAND, null, List.of(), 10);
        when(catalog.getVersion(any(), eq(3L))).thenReturn(new CapabilityVersionView(
                3L, 2L, 7L, CapabilityType.SKILL, "v1", "frozen", "Frozen", "TENANT",
                descriptor, new SkillPackageValidator().packageSha256(skillPackage), "PUBLISHED",
                Instant.EPOCH, Instant.EPOCH));

        assertEquals(2, service.listFiles(CapabilityCatalogScope.tenant(7L), 3L).size());
        assertEquals("evidence", service.getFile(CapabilityCatalogScope.tenant(7L), 3L,
                "references/a.txt").content());
        Map<String, byte[]> downloaded = unzip(service.download(CapabilityCatalogScope.tenant(7L), 3L));
        assertArrayEquals("evidence".getBytes(StandardCharsets.UTF_8), downloaded.get("references/a.txt"));
    }

    @Test
    void rejectsMalformedBase64AndNonSkillVersion() {
        assertThrows(RuntimeException.class, () -> service.importDirectory(
                CapabilityCatalogScope.tenant(7L), 9L, request(Map.of("SKILL.md", "%%%"))));
        when(catalog.getVersion(any(), anyLong())).thenReturn(new CapabilityVersionView(
                3L, 2L, 7L, CapabilityType.TOOL, "v1", "tool", "Tool", "TENANT",
                Map.of(), "hash", "PUBLISHED", Instant.EPOCH, Instant.EPOCH));
        assertThrows(RuntimeException.class, () -> service.getPackage(
                CapabilityCatalogScope.tenant(7L), 3L));
    }

    private SkillPackage servicePackage(CapabilityVersionView version) {
        return ((SkillDescriptor) codec.decode(new CapabilityRef(CapabilityType.SKILL, "9", "4"),
                7L, version.name(), version.description(), "TENANT", version.status(),
                version.contentHash(), version.descriptor())).skillPackage();
    }

    private static SkillPackageImportRequest request(Map<String, String> files) {
        return new SkillPackageImportRequest("1.0.0", true, false, SkillActivationMode.ON_DEMAND, null, List.of(), files);
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> result = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) result.put(entry.getName(), zip.readAllBytes());
        }
        return result;
    }
}

package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SkillPackageValidatorTest {
    private final SkillPackageValidator validator = new SkillPackageValidator();

    @Test
    void planRequirementUsesOnlyExactFrozenMetadataDeclaration() {
        String header = "---\nname: example\ndescription: Example\n";
        var absent = validator.fromDirectory(Map.of("SKILL.md", bytes(header
                + "---\nmetadata: skillforge-plan required\n1. Must plan first")));
        assertEquals("optional", absent.metadata().planRequirement());
        assertEquals(false, absent.metadata().requiresPlan());
        for (String declaration : java.util.List.of("required", "optional")) {
            var value = validator.fromDirectory(Map.of("SKILL.md", bytes(header
                    + "metadata:\n  skillforge-plan: \"" + declaration + "\"\n---\nInstructions")));
            assertEquals(declaration, value.metadata().planRequirement());
            assertEquals("required".equals(declaration), value.metadata().requiresPlan());
        }
        for (String invalid : java.util.List.of("Required", "true", "always", " required", "required ", "")) {
            assertThrows(IllegalArgumentException.class, () -> validator.fromDirectory(Map.of(
                    "SKILL.md", bytes(header + "metadata:\n  skillforge-plan: \"" + invalid + "\"\n---\nBody"))));
        }
        assertThrows(IllegalArgumentException.class, () -> validator.fromDirectory(Map.of(
                "SKILL.md", bytes(header + "metadata:\n  skillforge-plan: true\n---\nBody"))));
    }

    @Test
    void preservesUnknownFrontmatterFieldsWithoutRewritingUpstreamPackage() {
        String header = "---\nname: example\ndescription: Example\nmetadata:\n";
        String original = header + "version: \"1.0.0\"\nhomepage: https://example.com\n---\nInstructions";
        var imported = validator.fromDirectory(Map.of("SKILL.md", bytes(original)));
        assertEquals(original, imported.requireFile("SKILL.md").content());
        assertEquals(java.util.List.of("SKILL_FRONTMATTER_UNINTERPRETED", "SKILL_FRONTMATTER_UNINTERPRETED"),
                validator.notices(imported).stream().map(SkillDraftValidation.Issue::code).toList());
        assertEquals(java.util.List.of(SkillDraftValidation.Severity.WARNING, SkillDraftValidation.Severity.WARNING),
                validator.notices(imported).stream().map(SkillDraftValidation.Issue::severity).toList());
        var corrected = validator.fromDirectory(Map.of("SKILL.md", bytes(header
                + "  version: \"1.0.0\"\n  homepage: https://example.com\n---\nInstructions")));
        assertEquals("example", corrected.metadata().name());
    }

    @Test
    void reportsUnsupportedExecutionDeclarationsAndDoesNotExecuteInlineCommands() {
        String original = "---\nname: example\ndescription: Example\n"
                + "hooks:\n  PreToolUse:\n    - command: touch import-must-not-run\n"
                + "context: fork\nagent: custom\nmodel: remote-model\n"
                + "compatibility: Requires an account and arbitrary dependencies\n"
                + "allowed-tools: [Bash, Read]\n---\n!`touch load-must-not-run`\n";
        var imported = validator.fromDirectory(Map.of("SKILL.md", bytes(original),
                "scripts/run.sh", bytes("touch execution-must-not-run")));
        assertEquals(original, imported.requireFile("SKILL.md").content());
        var notices = validator.notices(imported);
        assertEquals(4, notices.stream().filter(issue -> issue.code().equals(
                "SKILL_EXECUTION_EXTENSION_UNSUPPORTED")).count());
        assertEquals(java.util.Set.of("SKILL_EXECUTION_EXTENSION_UNSUPPORTED", "SKILL_INLINE_COMMAND_UNSUPPORTED",
                "SKILL_COMPATIBILITY_DECLARED", "SKILL_TOOL_DECLARATIONS_ONLY", "SKILL_EXECUTION_NOT_VERIFIED"),
                notices.stream().map(SkillDraftValidation.Issue::code).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.List.of("Bash", "Read"), imported.metadata().allowedTools());
        var codec = new CapabilityDescriptorCodec(new com.fasterxml.jackson.databind.ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE));
        var decoded = (SkillDescriptor) codec.decode(new CapabilityRef(CapabilityType.SKILL, "1", "2"), null,
                "example", "Example", "PLATFORM", "DRAFT", validator.packageSha256(imported),
                SkillPackageDescriptorMapper.descriptor(imported, true, true, SkillActivationMode.ON_DEMAND, null,
                        java.util.List.of()));
        assertEquals(imported, decoded.skillPackage());
    }

    @Test
    void rejectsDirectoryPayloadBeforeImplicitZipDrainAndBoundsDirectoryEntries() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validator.safeZip(zip(Map.of(
                "payload/", new byte[SkillPackageValidator.MAX_PACKAGE_BYTES + 1]))));
        var directories = new LinkedHashMap<String, byte[]>();
        for (int index = 0; index <= SkillPackageValidator.MAX_FILES * 2; index++) {
            directories.put("folder-" + index + "/", new byte[0]);
        }
        assertThrows(IllegalArgumentException.class, () -> validator.safeZip(zip(directories)));
    }

    @Test
    void editingIntakeAllowsIncompleteContentButKeepsSafetyChecks() throws Exception {
        assertEquals(Map.of(), validator.safeDirectory(Map.of()));
        assertEquals(java.util.Set.of("nested/SKILL.md"), validator.safeDirectory(
                Map.of("nested/SKILL.md", bytes("unfinished"))).keySet());
        var incomplete = Map.of("SKILL.md", bytes("---\nname: [unfinished"),
                "assets/font.woff2", new byte[]{-1, 0, 127});
        var unpacked = validator.safeZip(zip(incomplete));
        assertArrayEquals(incomplete.get("assets/font.woff2"), unpacked.get("assets/font.woff2"));
        assertThrows(IllegalArgumentException.class, () -> validator.fromDirectory(unpacked));
        assertThrows(IllegalArgumentException.class, () -> validator.safeDirectory(Map.of("../escape", bytes("x"))));
        assertThrows(IllegalArgumentException.class, () -> validator.safeDirectory(Map.of("a.md", new byte[]{(byte) 0xff})));
        assertThrows(IllegalArgumentException.class, () -> validator.safeDirectory(Map.of("references", bytes("file"), "references/a.md", bytes("nested"))));
        assertThrows(IllegalArgumentException.class, () -> validator.safeDirectory(Map.of("assets/huge.bin", new byte[SkillPackageValidator.MAX_FILE_BYTES + 1])));
        assertThrows(IllegalArgumentException.class, () -> validator.safeDirectory(Map.of("references/a.md", bytes("x"), "references\\a.md", bytes("y"))));
        assertThrows(IllegalArgumentException.class, () -> validator.safeZip(zip(Map.of("../a.md", bytes("x")))));
        assertThrows(IllegalArgumentException.class, () -> validator.safeZip(bytes("not an archive")));
        assertThrows(IllegalArgumentException.class, () -> validator.safeZip(new byte[]{'P', 'K', 3, 4}));
        byte[] valid = zip(Map.of("SKILL.md", bytes("unfinished")));
        assertThrows(IllegalArgumentException.class, () -> validator.safeZip(java.util.Arrays.copyOf(valid, valid.length - 22)));
        assertEquals(Map.of(), validator.safeZip(zip(Map.of())));
    }

    @Test
    void importsStandardPackageWithFoldedDescriptionAndProgressiveResources() {
        SkillPackage value = validator.fromDirectory(Map.of(
                "SKILL.md", bytes("---\nname: incident-helper\ndescription: >\n  Diagnose incidents and\n  propose safe remediation.\nlicense: Apache-2.0\nmetadata:\n  owner: platform\nallowed-tools:\n  - logs.search\n  - ticket.create\n---\n# Instructions"),
                "references/runbook.md", bytes("# Runbook"),
                "scripts/check.py", bytes("print('resource only')"),
                "assets/icon.png", new byte[]{0, 1, 2}));

        assertEquals("incident-helper", value.metadata().name());
        assertEquals("Diagnose incidents and propose safe remediation.\n", value.metadata().description());
        assertEquals(java.util.List.of("logs.search", "ticket.create"), value.metadata().allowedTools());
        assertEquals(4, value.files().size());
        assertEquals(SkillPackage.Encoding.BASE64, value.requireFile("assets/icon.png").encoding());
    }

    @Test
    void acceptsZipAndPreservesEveryByteForDownloadRoundTrip() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", bytes("---\nname: minimal\ndescription: Minimal skill\n---\nUse it."));
        files.put("assets/data.bin", new byte[]{-1, 0, 127});

        SkillPackage value = validator.fromZip(zip(files));

        assertArrayEquals(files.get("assets/data.bin"), java.util.Base64.getDecoder()
                .decode(value.requireFile("assets/data.bin").content()));
        assertEquals(64, validator.packageSha256(value).length());
    }

    @Test
    void unwrapsZipWhoseOnlyRootIsASkillDirectory() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("incident-helper/SKILL.md",
                bytes("---\nname: incident-helper\ndescription: Diagnose incidents\n---\nUse it."));
        files.put("incident-helper/references/runbook.md", bytes("# Runbook"));

        SkillPackage value = validator.fromZip(zip(files));

        assertEquals("incident-helper", value.metadata().name());
        assertEquals("SKILL.md", value.entrypoint());
        assertEquals("# Runbook", value.requireFile("references/runbook.md").content());
    }

    @Test
    void rejectsZipWithMultipleRootDirectoriesAndNoRootSkillMd() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("one/SKILL.md", bytes("---\nname: one\ndescription: One\n---\n"));
        files.put("two/readme.md", bytes("no"));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> validator.fromZip(zip(files)));
        assertEquals("skill package requires root SKILL.md", failure.getMessage());
    }

    @Test
    void rejectsTraversalAbsoluteDuplicateAndMissingEntrypoint() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validator.fromDirectory(Map.of(
                "../SKILL.md", bytes("x"))));
        assertThrows(IllegalArgumentException.class, () -> validator.fromDirectory(Map.of(
                "C:/SKILL.md", bytes("x"))));
        assertThrows(IllegalArgumentException.class, () -> validator.fromDirectory(Map.of(
                "README.md", bytes("x"))));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(bytes("---\nname: one\ndescription: One\n---\n"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("references/a.md"));
            zip.write(bytes("first"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("references\\a.md"));
            zip.write(bytes("duplicate after normalization"));
            zip.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () -> validator.fromZip(output.toByteArray()));
    }

    @Test
    void rejectsInvalidStandardFrontmatterAndPackageBudgets() {
        assertThrows(IllegalArgumentException.class, () -> directory(
                "---\nname: Bad_Name\ndescription: Test\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> directory(
                "---\nname: good\ndescription: Test\nmetadata: [invalid]\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> directory(
                "---\nname: good\ndescription: Test\nallowed-tools: [same, same]\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> validator.fromDirectory(Map.of(
                "SKILL.md", new byte[SkillPackageValidator.MAX_FILE_BYTES + 1])));
    }

    private SkillPackage directory(String skillMd) {
        return validator.fromDirectory(Map.of("SKILL.md", bytes(skillMd)));
    }

    private static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

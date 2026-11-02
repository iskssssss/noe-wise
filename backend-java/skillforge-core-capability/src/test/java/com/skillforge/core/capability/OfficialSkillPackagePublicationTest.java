package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 官方市场包的独立发布校验；资源只在测试类路径，不经应用启动安装。 */
class OfficialSkillPackagePublicationTest {
    private static final String RESOURCE_ROOT = "official-skills/";
    private static final Pattern ENTRY_RESOURCES = Pattern.compile("(?:scripts|examples|references)/[A-Za-z0-9_./-]+");

    @Test
    void everyOfficialPackageCanBePublishedWithCompleteReferencedResources() throws Exception {
        var json = new ObjectMapper();
        var index = json.readValue(resource("index.json"), PackageIndex.class);
        assertEquals(List.of("document", "spreadsheet", "presentation", "web-app", "code-delivery",
                "configuration", "document-read", "webapp-testing", "research", "mcp-builder"),
                index.packages().stream().map(PackageEntry::name).toList());
        var validator = new SkillPackageValidator();
        for (var entry : index.packages()) {
            assertEquals(entry.files().size(), new HashSet<>(entry.files()).size());
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (String path : entry.files()) files.put(path, resource(entry.name() + "/" + path));
            var skill = validator.fromDirectory(files);
            assertEquals(entry.name(), skill.metadata().name());
            assertTrue(skill.metadata().extensions().get("version").matches("\\d+\\.\\d+\\.\\d+"));
            assertTrue(validator.packageSha256(skill).matches("[a-f0-9]{64}"));
            String instructions = new String(files.get("SKILL.md"), StandardCharsets.UTF_8);
            var references = ENTRY_RESOURCES.matcher(instructions);
            while (references.find()) assertTrue(files.containsKey(references.group()),
                    () -> entry.name() + " 指令引用的资源必须随包发布: " + references.group());
            for (var file : files.entrySet()) assertFalse(new String(file.getValue(), StandardCharsets.UTF_8)
                    .contains("public_result_file"), entry.name() + " 仍含旧结果选择器");
            if (entry.name().equals("web-app")) {
                assertTrue(files.containsKey("references/html-input.md"));
                assertEquals(skill.metadata().extensions().get("version"),
                        json.readTree(files.get("manifest.json")).get("version").textValue());
            }
        }
    }

    private static byte[] resource(String path) throws Exception {
        try (var input = OfficialSkillPackagePublicationTest.class.getClassLoader().getResourceAsStream(RESOURCE_ROOT + path)) {
            assertNotNull(input, "官方包缺少发布资源: " + path);
            return input.readAllBytes();
        }
    }

    private record PackageIndex(List<PackageEntry> packages) { }
    private record PackageEntry(String name, List<String> files) { }
}

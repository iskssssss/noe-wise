package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 资源冻结保持原始 UTF-8 字节、摘要和稳定路径顺序。 */
class WorkflowDefinitionResourcesTest {

    @Test
    void freezesAndExportsOriginalBytesInPathOrder() {
        byte[] python = "print('申请')\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] javascript = "console.log('ok');\n".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> definition = Map.of(
                "nodes", List.of(
                        script("python", "scripts/z.py"),
                        script("javascript", "scripts/a.js")),
                "resources", List.of(
                        resource("scripts/z.py", python),
                        resource("scripts/a.js", javascript)));

        Map<String, Object> frozen = WorkflowDefinitionResources.freeze(definition);
        List<?> resources = (List<?>) frozen.get("resources");
        assertEquals("scripts/a.js", ((Map<?, ?>) resources.get(0)).get("path"));
        assertEquals("scripts/z.py", ((Map<?, ?>) resources.get(1)).get("path"));
        assertEquals(List.of("path", "content_base64", "sha256"),
                ((Map<?, ?>) resources.get(0)).keySet().stream().toList());

        var files = WorkflowDefinitionResources.exportFiles(frozen);
        assertEquals(List.of("scripts/a.js", "scripts/z.py"),
                files.stream().map(WorkflowDefinitionResources.ExportFile::path).toList());
        assertEquals(javascript.length,
                files.get(0).content().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(python.length,
                files.get(1).content().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(sha256(python), files.get(1).sha256());
    }

    @Test
    void rejectsNonUtf8ScriptContentBeforeItCanBePublishedOrExported() {
        byte[] invalid = {(byte) 0xc3, (byte) 0x28};
        Map<String, Object> definition = Map.of(
                "nodes", List.of(script("bad", "scripts/bad.py")),
                "resources", List.of(resource("scripts/bad.py", invalid)));

        assertThrows(IllegalArgumentException.class,
                () -> WorkflowDefinitionResources.freeze(definition));
    }

    private static Map<String, Object> script(String id, String path) {
        return Map.of("id", id, "type", "script",
                "args", Map.of("script", path));
    }

    private static Map<String, Object> resource(String path, byte[] content) {
        return Map.of(
                "path", path,
                "content_base64", Base64.getEncoder().encodeToString(content),
                "sha256", sha256(content));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}

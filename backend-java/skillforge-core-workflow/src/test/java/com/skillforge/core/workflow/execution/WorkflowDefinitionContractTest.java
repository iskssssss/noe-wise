package com.skillforge.core.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Workflow 脚本只能引用 DefinitionVersion 内经过摘要核验的静态资源。 */
class WorkflowDefinitionContractTest {

    @Test
    void validatesHumanResponseDestinationBeforePublication() {
        for (String target : List.of("state", "extracted", "state.confirmation", "extracted.requirements")) {
            WorkflowDefinitionContract.validate(Map.of("nodes", List.of(Map.of(
                    "id", "confirm", "type", "human_input", "result_to", target))));
        }
        for (String target : List.of("confirmation", "state.nested.value", "state\\x", "state.", "state.1value")) {
            assertThrows(IllegalArgumentException.class, () -> WorkflowDefinitionContract.validate(
                    Map.of("nodes", List.of(Map.of("id", "confirm", "type", "human_input", "result_to", target)))), target);
        }
    }

    @Test
    void acceptsCanonicalPublishedPythonAndJavascriptResources() {
        Map<String, Object> definition = definition(
                "scripts/jobs/run.py", "print('ok')\n".getBytes(StandardCharsets.UTF_8));

        WorkflowDefinitionContract.validate(definition);

        var resource = WorkflowDefinitionContract.resources(definition)
                .get("scripts/jobs/run.py");
        assertEquals("scripts/jobs/run.py", resource.path());
        assertEquals(sha256("print('ok')\n".getBytes(StandardCharsets.UTF_8)),
                resource.sha256());

        Map<String, Object> javascript = definition(
                "scripts/run.js", "console.log('ok');\n".getBytes(StandardCharsets.UTF_8));
        WorkflowDefinitionContract.validate(javascript);
    }

    @Test
    void rejectsTraversalAbsoluteUnsupportedDuplicateAndUnpublishedResources() {
        byte[] content = "print('ok')\n".getBytes(StandardCharsets.UTF_8);
        for (String path : List.of(
                "/scripts/run.py", "C:/scripts/run.py", "scripts/../run.py",
                "scripts\\run.py", "scripts/run.sh", "run.py")) {
            assertThrows(IllegalArgumentException.class,
                    () -> WorkflowDefinitionContract.validate(definition(path, content)), path);
        }

        Map<String, Object> base = definition("scripts/run.py", content);
        Map<String, Object> duplicate = Map.of(
                "nodes", base.get("nodes"),
                "resources", List.of(resource("scripts/run.py", content),
                        resource("scripts/run.py", content)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowDefinitionContract.validate(duplicate));

        assertThrows(IllegalArgumentException.class,
                () -> WorkflowDefinitionContract.validate(Map.of(
                        "nodes", List.of(Map.of(
                                "id", "script", "type", "script",
                                "args", Map.of("script", "scripts/missing.py"))),
                        "resources", List.of())));
    }

    @Test
    void rejectsNonCanonicalBase64WrongDigestAndAcceptsExpandedFileBudget() {
        byte[] content = "print('ok')\n".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> valid = resource("scripts/run.py", content);
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowDefinitionContract.resources(Map.of(
                        "resources", List.of(Map.of(
                                "path", valid.get("path"),
                                "content_base64", valid.get("content_base64") + "\n",
                                "sha256", valid.get("sha256"))))));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowDefinitionContract.resources(Map.of(
                        "resources", List.of(Map.of(
                                "path", valid.get("path"),
                                "content_base64", valid.get("content_base64"),
                                "sha256", "0".repeat(64))))));

        byte[] oversized = new byte[1024 * 1024 + 1];
        assertEquals(1,WorkflowDefinitionContract.resources(Map.of(
                "resources",List.of(resource("scripts/large.py",oversized)))).size());

        byte[] oneMiB = new byte[1024 * 1024];
        assertEquals(5,WorkflowDefinitionContract.resources(Map.of("resources",List.of(
                resource("scripts/one.py",oneMiB),resource("scripts/two.py",oneMiB),resource("scripts/three.py",oneMiB),
                resource("scripts/four.py",oneMiB),resource("scripts/five.py",oneMiB)))).size());
    }

    @Test
    void rejectsResourceCountBeforeDecodingContents() {
        var resources = java.util.stream.IntStream.range(0, com.skillforge.execution.ExecutionLimits.MAX_FILES+1)
                .mapToObj(index -> resource("scripts/" + index + ".py", new byte[0])).toList();
        assertEquals("脚本资源最多 2048 个", assertThrows(IllegalArgumentException.class,
                () -> WorkflowDefinitionContract.resources(Map.of("resources", resources))).getMessage());
    }

    private static Map<String, Object> definition(String path, byte[] content) {
        return Map.of(
                "nodes", List.of(Map.of(
                        "id", "script", "type", "script",
                        "args", Map.of("script", path))),
                "resources", List.of(resource(path, content)));
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

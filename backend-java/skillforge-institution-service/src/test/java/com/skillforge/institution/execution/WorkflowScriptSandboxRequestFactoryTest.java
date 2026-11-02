package com.skillforge.institution.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 请求准备只使用冻结资源和已插值节点输入。 */
class WorkflowScriptSandboxRequestFactoryTest {
    private static final Instant DEADLINE = Instant.parse("2026-09-27T02:00:00Z");
    private static final WorkflowScriptSandboxRequestFactory.TaskIdentity IDENTITY =
            new WorkflowScriptSandboxRequestFactory.TaskIdentity("tenant-1", "execution-1", "script", 2, 1);

    @Test
    void sameIdentityAndDifferentMapOrdersProduceStableTaskAndCanonicalInput() throws Exception {
        Map<String, Object> firstInput = new LinkedHashMap<>();
        firstInput.put("z", 1);
        firstInput.put("nested", Map.of("b", 2, "a", 1));
        Map<String, Object> secondInput = new LinkedHashMap<>();
        secondInput.put("nested", Map.of("a", 1, "b", 2));
        secondInput.put("z", 1);

        var first = WorkflowScriptSandboxRequestFactory.prepare(
                definition(resource("scripts/helper.py", "print('helper')"), resource("scripts/main.py", "print('main')")),
                node("scripts/main.py"), firstInput, IDENTITY, DEADLINE, policy(), "workflow-python-v1");
        var second = WorkflowScriptSandboxRequestFactory.prepare(
                definition(resource("scripts/main.py", "print('main')"), resource("scripts/helper.py", "print('helper')")),
                node("scripts/main.py"), secondInput, IDENTITY, DEADLINE, policy(), "workflow-python-v1");

        assertEquals(first.taskId(), second.taskId());
        var request = new ObjectMapper().readTree(first.canonicalJson());
        assertEquals("{\"nested\":{\"a\":1,\"b\":2},\"z\":1}", request.path("inputJson").textValue());
        assertEquals(2, request.path("inputs").size());
        org.junit.jupiter.api.Assertions.assertFalse(first.canonicalJson().contains("content"));
        assertEquals(2,first.files().size());
        assertEquals("scripts/helper.py", request.path("inputs").get(0).path("path").textValue());
        assertEquals("scripts/main.py", request.path("inputs").get(1).path("path").textValue());
    }

    @Test
    void rejectsScriptThatIsNotFrozenOrUsesTraversalPath() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowScriptSandboxRequestFactory.prepare(
                definition(resource("scripts/main.py", "print('main')")), node("scripts/missing.py"), Map.of(),
                IDENTITY, DEADLINE, policy(), "workflow-python-v1"));

        assertThrows(IllegalArgumentException.class, () -> WorkflowScriptSandboxRequestFactory.prepare(
                definition(resource("scripts/../main.py", "print('main')")), node("scripts/../main.py"), Map.of(),
                IDENTITY, DEADLINE, policy(), "workflow-python-v1"));
    }

    private static WorkflowNode node(String path) {
        return new WorkflowNode("script", "script", null, null, null, Map.of("script", path),
                "state", null, Map.of(), List.of(), null, List.of(), null, List.of(), Map.of(), null,
                null, Map.of(), null);
    }

    private static Map<String, Object> definition(Map<String, Object>... resources) {
        return Map.of("resources", List.of(resources));
    }

    private static Map<String, Object> resource(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return Map.of("path", path, "content_base64", Base64.getEncoder().encodeToString(bytes),
                "sha256", sha256(bytes));
    }

    private static com.skillforge.execution.ExecutionPolicy policy() {
        return new com.skillforge.execution.ExecutionPolicy(1,300,15,24,60,4194304,2048,4,"1g",new java.math.BigDecimal("2"),256);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}

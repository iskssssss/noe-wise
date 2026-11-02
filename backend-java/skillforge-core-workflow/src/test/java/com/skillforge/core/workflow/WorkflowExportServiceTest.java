package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.workflow.builder.WorkflowExportService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Workflow Builder export canonicalization coverage. */
class WorkflowExportServiceTest {

    private final WorkflowExportService service = new WorkflowExportService();

    @Test
    void exportsCanonicalNodesEdgesMetaAndArtifactEnvelope() {
        byte[] scriptContent = "print('ok')\r\n".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> canvas = Map.ofEntries(
                Map.entry("name", "demo"),
                Map.entry("description", "description"),
                Map.entry("display_name", "Demo"),
                Map.entry("type", "state-machine"),
                Map.entry("version", "2.0.0"),
                Map.entry("entry", "start"),
                Map.entry("capability_requirements", List.of(
                        Map.of("slot", "search_slot", "type", "tool", "capability_id", "search",
                                "version_constraint", "1.x", "label", "搜索"),
                        Map.of("slot", "docs", "type", "knowledge_source", "capability_id", "product-docs",
                                "version_constraint", "2.0.0", "label", "产品资料"))),
                Map.entry("tools", List.of(Map.of(
                        "name", "search", "description", "搜索",
                        "requirement", "search_slot"))),
                Map.entry("nodes", List.of(
                        Map.of("id", "start", "type", "tool", "tool", "search",
                                "args", Map.of("q", "hello"), "x", 12, "y", 34),
                        Map.of("id", "knowledge", "type", "knowledge_query", "requirement", "docs",
                                "query", "${state.question}", "budget", Map.of("max_items", 5, "max_tokens", 1000,
                                        "max_characters", 4000, "timeout_ms", 3000, "max_sources", 2),
                                "result_to", "state.docs"),
                        Map.of("id", "agent", "type", "agentic", "agent_slot", "specialist",
                                "task", Map.of("objective", "分析", "context", "当前会话",
                                        "constraints", "只返回结论", "expected_output", "结论"),
                                "result_to", "state.analysis"),
                        Map.of("id", "parallel", "type", "parallel", "branches", List.of("knowledge", "agent"),
                                "result_to", "state.parallel_results"),
                        Map.of("id", "script", "type", "script",
                                "args", Map.of("script", "scripts/check.py", "args", List.of("${state.id}"))))),
                Map.entry("edges", List.of(Map.of("from", "start", "to", "parallel"))),
                Map.entry("resources", List.of(resource(
                        "scripts/check.py", scriptContent))));

        Map<String, Object> result = service.export(canvas);
        assertEquals("exported", result.get("status"));
        assertEquals("workflow.yaml", ((Map<?, ?>) ((List<?>) result.get("files")).getFirst()).get("name"));
        String yamlText = (String) result.get("workflow_yaml");
        Map<?, ?> parsed = new Yaml().load(yamlText);
        Map<?, ?> node = (Map<?, ?>) ((List<?>) parsed.get("nodes")).getFirst();
        Map<?, ?> knowledge = (Map<?, ?>) ((List<?>) parsed.get("nodes")).get(1);
        Map<?, ?> agent = (Map<?, ?>) ((List<?>) parsed.get("nodes")).get(2);
        Map<?, ?> parallel = (Map<?, ?>) ((List<?>) parsed.get("nodes")).get(3);
        Map<?, ?> script = (Map<?, ?>) ((List<?>) parsed.get("nodes")).get(4);
        Map<?, ?> edge = (Map<?, ?>) ((List<?>) parsed.get("edges")).getFirst();
        Map<?, ?> meta = (Map<?, ?>) parsed.get("meta");
        Map<?, ?> resource = (Map<?, ?>) ((List<?>) parsed.get("resources")).getFirst();

        assertTrue(!parsed.containsKey("name"));
        assertTrue(!parsed.containsKey("description"));
        assertTrue(!parsed.containsKey("on_error"));
        assertEquals("search", node.get("tool"));
        assertEquals(Map.of("q", "hello"), node.get("args"));
        assertTrue(!node.containsKey("layout"));
        assertEquals("start", edge.get("from"));
        assertEquals("parallel", edge.get("to"));
        assertEquals("docs", knowledge.get("requirement"));
        assertEquals("${state.question}", knowledge.get("query"));
        assertEquals(3000, ((Map<?, ?>) knowledge.get("budget")).get("timeout_ms"));
        assertEquals("specialist", agent.get("agent_slot"));
        assertEquals("结论", ((Map<?, ?>) agent.get("task")).get("expected_output"));
        assertEquals("state.parallel_results", parallel.get("result_to"));
        assertEquals("scripts/check.py", ((Map<?, ?>) script.get("args")).get("script"));
        assertEquals("demo", meta.get("name"));
        assertEquals("search_slot", ((Map<?, ?>) ((List<?>) meta.get("tools")).getFirst()).get("requirement"));
        assertEquals("product-docs", ((Map<?, ?>) ((List<?>) meta.get("capability_requirements")).get(1)).get("capability_id"));
        assertTrue(!meta.containsKey("connector_requirements"));
        assertEquals("scripts/check.py", resource.get("path"));
        assertEquals(Base64.getEncoder().encodeToString(scriptContent),
                resource.get("content_base64"));
        assertEquals(sha256(scriptContent), resource.get("sha256"));
        List<?> files = (List<?>) result.get("files");
        Map<?, ?> scriptFile = (Map<?, ?>) files.get(1);
        assertEquals("scripts/check.py", scriptFile.get("name"));
        assertEquals("print('ok')\r\n", scriptFile.get("content"));
        assertTrue(yamlText.contains("display_name: Demo"));
    }

    @Test
    void exportedGovernanceRoundTripsThroughTheRealDraftValidatorWithoutInventingDefaults() {
        Map<String, Object> capability = Map.of(
                "model_invocable", true, "user_invocable", true,
                "input_schema", Map.of("type", "object"), "output_schema", Map.of("type", "object"),
                "supports_hitl", false, "export_fields", List.of(), "timeout_ms", 1000, "concurrency_safe", true);
        var canvas = new java.util.LinkedHashMap<String, Object>(Map.of(
                "name", "demo", "entry", "end", "nodes", List.of(Map.of("id", "end", "type", "end")),
                "edges", List.of(), "capability", capability));
        Map<String, Object> definition = new Yaml().load(service.exportWorkflowYaml(canvas));
        assertEquals(capability, ((Map<?, ?>) definition.get("meta")).get("capability"));
        assertTrue(new WorkflowDraftValidator().validate(definition).isEmpty());

        canvas.remove("capability");
        Map<String, Object> undeclared = new Yaml().load(service.exportWorkflowYaml(canvas));
        assertTrue(!((Map<?, ?>) undeclared.get("meta")).containsKey("capability"));
        assertTrue(new WorkflowDraftValidator().validate(undeclared).stream()
                .anyMatch(problem -> problem.code().equals("WF_CAPABILITY_GOVERNANCE_REQUIRED")));
    }

    @Test
    void malformedCollectionsAndRemovedContractsAreRejected() {
        assertThrows(RuntimeException.class, () -> service.exportWorkflowYaml(
                Map.of("name", "minimal", "entry", "start", "nodes", "invalid", "edges", List.of())));
        assertThrows(RuntimeException.class, () -> service.exportWorkflowYaml(
                Map.of("name", "minimal", "entry", "start", "nodes", List.of(), "edges", List.of(),
                        "connector_requirements", List.of(Map.of(
                                "capability", "calendar", "label", "日历", "connector_type", "legacy")))));
        assertThrows(RuntimeException.class, () -> service.exportWorkflowYaml(
                Map.of("name", "minimal", "entry", "agent", "edges", List.of(),
                        "nodes", List.of(Map.of("id", "agent", "type", "agentic",
                                "agent_slot", "specialist", "task", Map.of("objective", "分析", "expected_output", "结果"),
                                "result_to", "state", "args", Map.of("tools", List.of("legacy")))))));
        assertThrows(RuntimeException.class, () -> service.exportWorkflowYaml(
                Map.of("name", "minimal", "entry", "tool", "edges", List.of(),
                        "capability_requirements", List.of(Map.of("slot", "search_slot", "type", "tool",
                                "capability_id", "search", "version_constraint", "1.x", "label", "搜索")),
                        "tools", List.of(Map.of("name", "search", "requires", "legacy")),
                        "nodes", List.of(Map.of("id", "tool", "type", "tool", "tool", "search")))));
        assertThrows(RuntimeException.class, () -> service.exportWorkflowYaml(
                Map.of("name", "minimal", "entry", "script", "edges", List.of(),
                        "nodes", List.of(Map.of("id", "script", "type", "script",
                                "args", Map.of("script", "scripts/missing.py"))))));
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

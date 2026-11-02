package com.skillforge.core.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 工作流解析器单元测试。
 */
class WorkflowParserTest {

    @Test
    void rawHttpNodeIsRejectedByTargetContract() {
        Map<String, Object> root = Map.of(
                "nodes", List.of(Map.of("id", "fetch", "type", "http")));

        ApiException error = assertThrows(ApiException.class,
                () -> WorkflowParser.validateTargetContract(root));

        assertEquals(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
        assertEquals("Workflow 原始 http 节点已移除，请发布 ToolVersion 并使用 tool 节点: fetch",
                error.getMessage());
    }

    @Test
    void workflowMemoryDeclarationIsRejectedByTargetContract() {
        Map<String, Object> root = Map.of("meta", Map.of("memory", Map.of("enabled", true)));

        assertThrows(RuntimeException.class, () -> WorkflowParser.validateTargetContract(root));
    }

    @Test
    void instructionWorkflowAndMetadataAreRejectedByTargetContract() {
        assertThrows(RuntimeException.class, () -> WorkflowParser.validateTargetContract(
                Map.of("meta", Map.of("type", "instruction"))));
        assertThrows(RuntimeException.class, () -> WorkflowParser.validateTargetContract(
                Map.of("meta", Map.of("type", "state-machine", "instruction", "hidden"))));
    }

    @Test
    void explicitEntryIsRequired() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entry", "check_user");
        assertEquals("check_user", WorkflowParser.resolveEntry(root));
        assertThrows(ApiException.class, () -> WorkflowParser.resolveEntry(Map.of()));
    }

    @Test
    void parsesCurrentStructuredDefinition() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "menu");
        node.put("type", "menu");
        node.put("name", "Choose");
        node.put("prompt", "pick");
        node.put("tool", "lookup");
        node.put("args", Map.of("q", "${state.q}"));
        node.put("result_to", "extracted");
        node.put("message", "done");
        node.put("branches", List.of("a", "b"));
        node.put("workflow", "child");
        node.put("extract", List.of("answer"));
        node.put("on_error", "message");
        node.put("actions", List.of(Map.of("id", "ok", "to", "end")));
        node.put("render", Map.of("format", "markdown"));
        node.put("merge_message_id", "m-1");
        node.put("timeout_seconds", 30);
        Map<String, Object> numericEdge = new LinkedHashMap<>();
        numericEdge.put("from", "menu");
        numericEdge.put("to", "end");
        numericEdge.put("priority", 3);
        numericEdge.put("transfer_context", Map.of("q", "state.q"));
        Map<String, Object> root = Map.of(
                "entry", "menu",
                "nodes", List.of(node),
                "edges", List.of(numericEdge));

        WorkflowModels.Workflow workflow = new WorkflowParser().parse("flow-1", root);

        assertThat(workflow.key()).isEqualTo("flow-1");
        assertThat(workflow.entry()).isEqualTo("menu");
        WorkflowNode parsed = workflow.nodes().get(0);
        assertThat(parsed.actions()).containsExactly(Map.of("id", "ok", "to", "end"));
        assertThat(parsed.timeoutSeconds()).isEqualTo(30);
        assertThat(workflow.edges()).extracting("priority").containsExactly(3);
    }

    @Test
    void rejectsRemovedNodeAndEdgeFields() {
        WorkflowParser parser = new WorkflowParser();
        Map<String, Object> base = Map.of("entry", "n", "nodes", List.of(
                Map.of("id", "n", "type", "output", "card", Map.of("title", "old"))), "edges", List.of());
        assertThrows(ApiException.class, () -> parser.parse("flow", base));
        Map<String, Object> terminal = Map.of("entry", "n", "nodes", List.of(
                Map.of("id", "n", "type", "end", "terminal", true)), "edges", List.of());
        assertThrows(ApiException.class, () -> parser.parse("flow", terminal));
        Map<String, Object> stringPriority = Map.of("entry", "n", "nodes", List.of(
                Map.of("id", "n", "type", "end")), "edges", List.of(
                        Map.of("from", "n", "to", "n", "priority", "2")));
        assertThrows(ApiException.class, () -> parser.parse("flow", stringPriority));
    }

    @Test
    void rejectsMalformedDefinitionInsteadOfApplyingDefaults() {
        WorkflowParser parser = new WorkflowParser();
        assertThrows(ApiException.class, () -> parser.parse("flow", null));
        assertThrows(ApiException.class, () -> parser.parse("flow",
                Map.of("entry", "n", "nodes", "invalid", "edges", List.of())));
        assertThrows(ApiException.class, () -> parser.parse("flow",
                Map.of("entry", "n", "nodes", List.of(Map.of("id", "n")), "edges", List.of())));
        assertThrows(ApiException.class, () -> parser.parse("flow",
                Map.of("entry", "n", "nodes", List.of(Map.of("id", "n", "type", "end",
                        "args", "invalid")), "edges", List.of())));
        assertThrows(ApiException.class, () -> parser.parse("flow",
                Map.of("entry", "n", "nodes", List.of(Map.of("id", "n", "type", "end",
                        "timeout_seconds", "30")), "edges", List.of())));
        assertThrows(ApiException.class, () -> parser.parse("flow",
                Map.of("entry", "n", "nodes", List.of(Map.of("id", "n", "type", "end")),
                        "edges", List.of(Map.of("from", "n")))));
    }
}

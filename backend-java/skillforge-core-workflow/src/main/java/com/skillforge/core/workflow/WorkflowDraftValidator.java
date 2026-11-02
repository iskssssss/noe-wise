package com.skillforge.core.workflow;

import com.skillforge.core.workflow.dto.response.WorkflowValidationProblem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Builder 草稿的无副作用服务端验证；保存可以带 error，发布必须拒绝 error。 */
@Component
public class WorkflowDraftValidator {

    private static final Set<String> NODE_TYPES = Set.of(
            "start", "end", "condition", "llm", "tool", "agentic", "knowledge_query",
            "human_input", "menu", "async_task", "card", "script", "assign", "output",
            "parallel", "subgraph");
    private static final List<String> CAPABILITY_FIELDS = List.of(
            "model_invocable", "user_invocable", "input_schema", "output_schema",
            "supports_hitl", "export_fields", "timeout_ms", "concurrency_safe");
    private static final Set<String> CAPABILITY_FIELD_SET = Set.copyOf(CAPABILITY_FIELDS);

    @SuppressWarnings("unchecked")
    public List<WorkflowValidationProblem> validate(Map<String, Object> definition) {
        List<WorkflowValidationProblem> problems = new ArrayList<>();
        if (definition == null || definition.isEmpty()) {
            return List.of(error("WF_SCHEMA_ROOT", "workflow", "$", "工作流定义不能为空"));
        }
        if (definition.containsKey("memory")
                || definition.get("meta") instanceof Map<?, ?> meta && meta.containsKey("memory")) {
            problems.add(error("WF_POLICY_MEMORY", "workflow", "memory",
                    "Workflow 不允许声明 memory；跨轮事实由 Agent Memory Runtime 管理"));
        }
        validateCapabilityGovernance(definition, problems);
        try {
            com.skillforge.core.workflow.execution.WorkflowDefinitionContract.requireExecutable(definition);
        } catch (IllegalArgumentException rejected) {
            problems.add(error("WF_NODE_UNAVAILABLE", "workflow", "nodes", rejected.getMessage()));
        }
        try {
            com.skillforge.core.workflow.execution.WorkflowDefinitionContract.validate(definition);
        } catch (IllegalArgumentException rejected) {
            problems.add(error("WF_CAPABILITY_BINDING_CONTRACT", "workflow", "meta.capability_requirements", rejected.getMessage()));
        }
        Object rawNodes = definition.get("nodes");
        if (!(rawNodes instanceof List<?> nodes) || nodes.isEmpty()) {
            problems.add(error("WF_SCHEMA_NODES", "workflow", "nodes", "nodes 必须是非空数组"));
            return problems;
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            Object item = nodes.get(index);
            if (!(item instanceof Map<?, ?> rawNode)) {
                problems.add(error("WF_SCHEMA_NODE", "nodes[" + index + "]", "nodes[" + index + "]",
                        "节点必须是对象"));
                continue;
            }
            Map<String, Object> node = (Map<String, Object>) rawNode;
            String id = value(node.get("id"));
            String type = value(node.get("type"));
            String ref = id.isBlank() ? "nodes[" + index + "]" : "node:" + id;
            if (id.isBlank()) {
                problems.add(error("WF_SCHEMA_NODE_ID", ref, "nodes[" + index + "].id", "节点 id 不能为空"));
            } else if (!ids.add(id)) {
                problems.add(error("WF_GRAPH_DUPLICATE_NODE", ref, "nodes[" + index + "].id", "节点 id 必须唯一"));
            }
            if ("http".equals(type)) {
                problems.add(error("WF_NODE_HTTP_REMOVED", ref, "nodes[" + index + "].type",
                        "原始 http 节点已移除，请发布 ToolVersion 并使用 tool 节点"));
            } else if (!NODE_TYPES.contains(type)) {
                problems.add(error("WF_NODE_TYPE", ref, "nodes[" + index + "].type", "不支持的节点类型: " + type));
            }
            if ("tool".equals(type) && value(node.get("tool")).isBlank()) {
                problems.add(error("WF_CAPABILITY_TOOL_SLOT", ref, "nodes[" + index + "].tool",
                        "tool 节点必须绑定声明的工具能力槽"));
            }
        }
        String entry = value(definition.get("entry"));
        if (entry.isBlank() || !ids.contains(entry)) {
            problems.add(error("WF_GRAPH_ENTRY", "workflow", "entry", "entry 必须指向存在的节点"));
        }
        Object rawEdges = definition.getOrDefault("edges", List.of());
        if (!(rawEdges instanceof List<?> edges)) {
            problems.add(error("WF_SCHEMA_EDGES", "workflow", "edges", "edges 必须是数组"));
            return problems;
        }
        for (int index = 0; index < edges.size(); index++) {
            if (!(edges.get(index) instanceof Map<?, ?> edge)) {
                problems.add(error("WF_SCHEMA_EDGE", "edges[" + index + "]", "edges[" + index + "]", "连线必须是对象"));
                continue;
            }
            String from = value(edge.get("from"));
            String to = value(edge.get("to"));
            String ref = "edge:" + from + "->" + to;
            if (!ids.contains(from) || !ids.contains(to)) {
                problems.add(error("WF_GRAPH_EDGE_ENDPOINT", ref, "edges[" + index + "]", "连线端点必须存在"));
            } else if (from.equals(to)) {
                problems.add(error("WF_GRAPH_SELF_LOOP", ref, "edges[" + index + "]", "不允许节点连接到自身"));
            }
        }
        return List.copyOf(problems);
    }

    /**
     * Workflow 的治理契约只能来自 {@code definition.meta.capability}。
     *
     * <p>这里故意不读取节点图来补齐任何字段：发布时由 Producer 将这些 snake_case
     * 字段显式写入 Catalog Codec 的持久化 descriptor；Codec 再解码为 camelCase Java
     * record。保持问题顺序稳定，让 Builder 能够在同一字段上可靠聚焦，也避免调用方以
     * 任意环境默认值绕过治理。
     */
    private static void validateCapabilityGovernance(
            Map<String, Object> definition, List<WorkflowValidationProblem> problems) {
        Object rawMeta = definition.get("meta");
        if (!(rawMeta instanceof Map<?, ?> meta)) {
            problems.add(error("WF_CAPABILITY_GOVERNANCE_REQUIRED", "workflow", "meta.capability",
                    "meta.capability 必须是包含完整治理字段的对象"));
            return;
        }
        Object rawCapability = meta.get("capability");
        if (!(rawCapability instanceof Map<?, ?> capability)) {
            problems.add(error("WF_CAPABILITY_GOVERNANCE_REQUIRED", "workflow", "meta.capability",
                    "meta.capability 必须是包含完整治理字段的对象"));
            return;
        }

        capability.keySet().stream()
                .map(String::valueOf)
                .filter(key -> !CAPABILITY_FIELD_SET.contains(key))
                .sorted(Comparator.naturalOrder())
                .forEach(key -> problems.add(error("WF_CAPABILITY_GOVERNANCE_UNKNOWN",
                        "workflow", "meta.capability." + key,
                        "未知 Workflow 治理字段: " + key)));

        for (String field : CAPABILITY_FIELDS) {
            if (!capability.containsKey(field)) {
                problems.add(error("WF_CAPABILITY_GOVERNANCE_REQUIRED", "workflow",
                        "meta.capability." + field, "Workflow 治理字段不能为空: " + field));
                continue;
            }
            Object value = capability.get(field);
            if (!isCapabilityFieldType(field, value)) {
                problems.add(error("WF_CAPABILITY_GOVERNANCE_TYPE", "workflow",
                        "meta.capability." + field,
                        "Workflow 治理字段类型错误: " + field));
            }
        }
    }

    private static boolean isCapabilityFieldType(String field, Object value) {
        return switch (field) {
            case "model_invocable", "user_invocable", "supports_hitl", "concurrency_safe" ->
                    value instanceof Boolean;
            case "input_schema", "output_schema" -> value instanceof Map<?, ?>;
            case "export_fields" -> value instanceof List<?> list
                    && list.stream().allMatch(item -> item instanceof String)
                    && list.stream().map(String.class::cast).allMatch(item -> !item.isBlank())
                    && list.stream().map(String.class::cast).distinct().count() == list.size();
            case "timeout_ms" -> value instanceof Number number && isPositiveIntegral(number);
            default -> false;
        };
    }

    private static boolean isPositiveIntegral(Number value) {
        try {
            BigDecimal decimal = new BigDecimal(value.toString());
            return decimal.signum() > 0
                    && decimal.scale() <= 0
                    && decimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static WorkflowValidationProblem error(String code, String objectRef, String fieldPath, String message) {
        return new WorkflowValidationProblem(code, "error", objectRef, fieldPath, message);
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

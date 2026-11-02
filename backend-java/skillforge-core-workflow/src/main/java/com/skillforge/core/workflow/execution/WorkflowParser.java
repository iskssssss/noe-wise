package com.skillforge.core.workflow.execution;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.execution.WorkflowModels.Workflow;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowEdge;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 工作流解析器。
 *
 * <p>将调用方已经按租户和版本冻结的 DefinitionVersion 解析为 {@link Workflow}。
 * 解析器不自行查找可变定义、资产或磁盘文件。
 */
@Service
public class WorkflowParser {

    /**
     * 解析已由调用方冻结的不可变定义。
     *
     * <p>运行时通过此入口消费安装时绑定的 DefinitionVersion，避免重新按 workflowId 查询可变的
     * 模板/资产来源。调用方必须先完成租户和版本归属校验。
     *
     * @param workflowId 工作流业务 ID
     * @param definition 不可变定义 JSON
     * @return 解析后的工作流
     */
    public Workflow parse(String workflowId, Map<String, Object> definition) {
        if (workflowId == null || workflowId.isBlank() || workflowId.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "非法的工作流 ID: " + workflowId);
        }
        if (definition == null) {
            throw invalid("Workflow 定义不能为空");
        }
        Map<String, Object> root = definition;
        validateTargetContract(root);
        try {
            List<WorkflowNode> nodes = new ArrayList<>();
            for (Object item : requiredList(root, "nodes")) {
                nodes.add(toNode(requiredMap(item, "Workflow nodes 项")));
            }
            List<WorkflowEdge> edges = new ArrayList<>();
            for (Object item : requiredList(root, "edges")) {
                edges.add(toEdge(requiredMap(item, "Workflow edges 项")));
            }
            String entry = resolveEntry(root);
            return new Workflow(workflowId, entry, nodes, edges);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "解析工作流失败: " + e.getMessage());
        }
    }

    /**
     * 解析入口节点 id。
     *
     * @param root  YAML 根映射
     * @return 入口节点 id
     */
    static String resolveEntry(Map<String, Object> root) {
        Object rawEntry = root.get("entry");
        if (!(rawEntry instanceof String entry) || entry.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Workflow 定义必须显式声明 entry");
        }
        return entry;
    }

    /**
     * 校验当前目标规范的执行域边界。
     *
     * <p>外部 HTTP 必须通过已声明的 ToolDeployment；Workflow 不拥有会话级
     * memory 配置。校验在节点实例化前执行，避免执行器成为发布校验旁路。
     *
     * @param root 工作流定义根映射
     */
    static void validateTargetContract(Map<String, Object> root) {
        try {
            WorkflowDefinitionContract.validate(root);
        } catch (IllegalArgumentException rejected) {
            throw invalid(rejected.getMessage());
        }
        Map<String, Object> meta = optionalMap(root, "meta");
        if (root.containsKey("memory") || meta.containsKey("memory")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Workflow 不允许声明 memory；跨轮事实由 Agent Memory Runtime 管理");
        }
        if (meta.containsKey("instruction")
                || "instruction".equals(String.valueOf(meta.get("type")))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Workflow instruction 已移除；纯指令内容必须作为标准 Skill 包发布");
        }
        for (Object item : optionalList(root, "nodes")) {
            Map<String, Object> node = requiredMap(item, "Workflow nodes 项");
            if ("http".equals(String.valueOf(node.get("type")))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Workflow 原始 http 节点已移除，请发布 ToolVersion 并使用 tool 节点: "
                                + node.get("id"));
            }
        }
    }

    /**
     * 将 YAML 节点映射转换为工作流节点。
     *
     * @param map YAML 节点映射
     * @return 工作流节点
     */
    private WorkflowNode toNode(Map<String, Object> map) {
        String id = requiredString(map, "id", "Workflow node");
        String type = requiredString(map, "type", "Workflow node " + id);
        Map<String, Object> card = optionalMap(map, "card");
        if (!card.isEmpty() && ("output".equals(type) || "menu".equals(type))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "output/menu 节点不允许 card 配置: " + map.get("id"));
        }
        if (map.containsKey("terminal")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Workflow 节点不允许 terminal 字段: " + map.get("id"));
        }
        if (map.containsKey("layout")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Workflow 运行定义不允许 layout 字段: " + map.get("id"));
        }
        return new WorkflowNode(
                id,
                type,
                optionalString(map, "name", "Workflow node " + id),
                optionalString(map, "prompt", "Workflow node " + id),
                optionalString(map, "tool", "Workflow node " + id),
                optionalMap(map, "args"),
                map.get("result_to") == null ? "state" : requiredString(map, "result_to", "Workflow node " + id),
                optionalString(map, "message", "Workflow node " + id),
                card,
                stringList(map, "branches", "Workflow node " + id),
                optionalString(map, "workflow", "Workflow node " + id),
                stringList(map, "extract", "Workflow node " + id),
                optionalString(map, "on_error", "Workflow node " + id),
                mapList(map, "actions", "Workflow node " + id),
                optionalMap(map, "render"),
                optionalString(map, "merge_message_id", "Workflow node " + id),
                optionalInteger(map, "timeout_seconds", "Workflow node " + id),
                Map.of(), new WorkflowModels.ExternalNodeSpec(
                        optionalString(map, "requirement", "node " + id), optionalString(map, "query", "node " + id),
                        optionalMap(map, "budget"), optionalString(map, "agent_slot", "node " + id), optionalMap(map, "task")));
    }

    /**
     * 将 YAML 边映射转换为工作流边。
     *
     * @param map YAML 边映射
     * @return 工作流边
     */
    private WorkflowEdge toEdge(Map<String, Object> map) {
        String from = requiredString(map, "from", "Workflow edge");
        String to = requiredString(map, "to", "Workflow edge " + from);
        Object rawPriority = map.get("priority");
        if (rawPriority != null && !(rawPriority instanceof Number)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Workflow edge.priority 必须为数字");
        }
        int priority = rawPriority instanceof Number number ? number.intValue() : 0;
        return new WorkflowEdge(
                from,
                to,
                optionalString(map, "condition", "Workflow edge " + from + " -> " + to),
                priority,
                optionalMap(map, "transfer_context"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(Object value, String owner) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(owner + " 必须为对象");
        }
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> optionalMap(Map<String, Object> owner, String field) {
        Object value = owner.get(field);
        return value == null ? Map.of() : requiredMap(value, field);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requiredList(Map<String, Object> owner, String field) {
        Object value = owner.get(field);
        if (!(value instanceof List<?> list)) {
            throw invalid("Workflow " + field + " 必须为数组");
        }
        return (List<Object>) list;
    }

    private static List<Object> optionalList(Map<String, Object> owner, String field) {
        return owner.get(field) == null ? List.of() : requiredList(owner, field);
    }

    private static String requiredString(Map<String, Object> owner, String field, String context) {
        Object value = owner.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(context + "." + field + " 必须为非空字符串");
        }
        return text;
    }

    private static String optionalString(Map<String, Object> owner, String field, String context) {
        Object value = owner.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw invalid(context + "." + field + " 必须为字符串");
        }
        return text;
    }

    private static List<String> stringList(Map<String, Object> owner, String field, String context) {
        List<Object> values = optionalList(owner, field);
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw invalid(context + "." + field + " 项必须为非空字符串");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> mapList(
            Map<String, Object> owner, String field, String context) {
        List<Object> values = optionalList(owner, field);
        List<Map<String, Object>> result = new ArrayList<>(values.size());
        for (Object value : values) {
            result.add(requiredMap(value, context + "." + field + " 项"));
        }
        return List.copyOf(result);
    }

    private static Integer optionalInteger(Map<String, Object> owner, String field, String context) {
        Object value = owner.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw invalid(context + "." + field + " 必须为数字");
        }
        return number.intValue();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}

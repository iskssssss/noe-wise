package com.skillforge.core.workflow.builder;

import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.workflow.constant.BuilderConstants;
import com.skillforge.core.workflow.constant.WorkflowConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.execution.WorkflowDefinitionContract;
import com.skillforge.core.workflow.WorkflowDefinitionResources;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 可视化构建器产物导出服务。
 *
 * <p>将当前 Builder 定义导出为 workflow.yaml 与其引用的冻结脚本资源。运行定义只包含
 * 当前 Schema；layout/viewport 由 BuilderSnapshot 单独持久化。
 */
@Component
public class WorkflowExportService {

    private static final Set<String> CAPABILITY_REQUIREMENT_FIELDS = Set.of(
            "slot", "type", "capability_id", "version_constraint", "label", "required", "description");

    /** YAML 序列化器，输出 Unicode 且保持键顺序。 */
    private final Yaml yaml;

    /**
     * 构造导出服务。
     */
    public WorkflowExportService() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setAllowUnicode(true);
        this.yaml = new Yaml(options);
    }

    /**
     * 导出完整工作流包内容（workflow.yaml 与冻结脚本，含 meta 段）。
     *
     * @param canvasData 画布数据
     * @return 导出结果，包含 workflow.yaml
     */
    public Map<String, Object> export(Map<String, Object> canvasData) {
        Map<String, Object> definition = workflowDefinition(canvasData);
        String workflowYaml = yaml.dump(definition);
        List<Map<String, Object>> files = new ArrayList<>();
        files.add(Map.of(
                BuilderConstants.FILE_NAME, BuilderConstants.WORKFLOW_YAML_FILE,
                BuilderConstants.FILE_CONTENT, workflowYaml));
        WorkflowDefinitionResources.exportFiles(definition).forEach(file ->
                files.add(Map.of(
                        BuilderConstants.FILE_NAME, file.path(),
                        BuilderConstants.FILE_CONTENT, file.content())));
        return Map.of(
                ApiConstants.STATUS, BuilderConstants.STATUS_EXPORTED,
                BuilderConstants.WORKFLOW_YAML, workflowYaml,
                BuilderConstants.FILES, List.copyOf(files));
    }

    /**
     * 导出 workflow.yaml（顶层含 meta 段：原 WORKFLOW.md frontmatter）。
     *
     * @param canvasData 画布数据
     * @return workflow.yaml 内容
     */
    public String exportWorkflowYaml(Map<String, Object> canvasData) {
        return yaml.dump(workflowDefinition(canvasData));
    }

    private Map<String, Object> workflowDefinition(
            Map<String, Object> canvasData) {
        String name = String.valueOf(canvasData.getOrDefault("name", "workflow"));
        Object rawEntry = canvasData.get("entry");
        if (!(rawEntry instanceof String entry) || entry.isBlank()) {
            throw invalid("Builder entry 必须为非空字符串");
        }
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("version", "1.0");
        workflow.put("entry", entry);
        workflow.put("nodes", canonicalNodes(requiredMapList(canvasData.get("nodes"), "nodes")));
        workflow.put("edges", canonicalEdges(requiredMapList(canvasData.get("edges"), "edges")));
        if (canvasData.containsKey("resources")) {
            workflow.put("resources", canvasData.get("resources"));
        }
        workflow.put(WorkflowConstants.KEY_META, metaFromCanvas(canvasData, name));
        try {
            Map<String, Object> frozen = WorkflowDefinitionResources.freeze(workflow);
            WorkflowDefinitionContract.validate(frozen);
            return frozen;
        } catch (IllegalArgumentException rejected) {
            throw invalid(rejected.getMessage());
        }
    }

    /**
     * 从画布数据组装 meta 段（原 frontmatter 全字段 + body 正文）。
     *
     * @param canvasData 画布数据
     * @param name       工作流标识（无 -workflow 后缀）
     * @return meta 段 Map
     */
    private Map<String, Object> metaFromCanvas(Map<String, Object> canvasData, String name) {
        if (canvasData.containsKey("connector_requirements")) {
            throw invalid("connector_requirements 已移除，请使用 capability_requirements");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put(WorkflowConstants.KEY_TYPE, canvasData.getOrDefault(WorkflowConstants.KEY_TYPE,
                WorkflowConstants.TYPE_STATE_MACHINE));
        meta.put("version", canvasData.getOrDefault("version",
                WorkflowConstants.DEFAULT_VERSION));
        meta.put("description", canvasData.getOrDefault("description", ""));
        meta.put("display_name", canvasData.getOrDefault("display_name", name));
        meta.put("author", canvasData.getOrDefault("author", "builder"));
        meta.put("category", canvasData.getOrDefault("category", ""));
        meta.put("tags", canvasData.getOrDefault("tags", List.of()));
        meta.put("config_schema", canvasData.getOrDefault("config_schema", List.of()));
        meta.put("capability_requirements", capabilityRequirements(canvasData.get("capability_requirements")));
        meta.put("tools", canvasData.getOrDefault("tools", List.of()));
        if (canvasData.containsKey("capability")) {
            meta.put("capability", canvasData.get("capability"));
        }
        if (canvasData.containsKey("required_permissions")) {
            meta.put("required_permissions", canvasData.get("required_permissions"));
        }
        return meta;
    }

    /**
     * 复制当前节点字段并剥离 Builder 布局。
     *
     * @param rawNodes 原始节点列表
     * @return 标准化节点列表
     */
    private List<Object> canonicalNodes(List<Map<String, Object>> rawNodes) {
        List<Object> nodes = new ArrayList<>();
        for (Map<String, Object> node : rawNodes) {
            Map<String, Object> canonical = new LinkedHashMap<>(node);
            canonical.remove("x");
            canonical.remove("y");
            canonical.remove("layout");
            String type = String.valueOf(canonical.getOrDefault("type", ""));
            if (("knowledge_query".equals(type) || "agentic".equals(type)) && canonical.containsKey("args")) {
                throw invalid(type + ".args 已移除，请使用类型化顶层字段");
            }
            nodes.add(canonical);
        }
        return nodes;
    }

    /**
     * 复制当前边字段。
     *
     * @param rawEdges 原始边列表
     * @return 标准化边列表
     */
    private List<Object> canonicalEdges(List<Map<String, Object>> rawEdges) {
        List<Object> edges = new ArrayList<>();
        for (Map<String, Object> edge : rawEdges) {
            Map<String, Object> canonical = new LinkedHashMap<>(edge);
            edges.add(canonical);
        }
        return edges;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> requiredMapList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw invalid("Builder " + field + " 必须为数组");
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw invalid("Builder " + field + " 项必须为对象");
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    private List<Map<String, Object>> capabilityRequirements(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Map<String, Object>> requirements = requiredMapList(value, "capability_requirements");
        Set<String> slots = new java.util.HashSet<>();
        for (Map<String, Object> requirement : requirements) {
            if (!CAPABILITY_REQUIREMENT_FIELDS.containsAll(requirement.keySet())) {
                throw invalid("capability_requirements 包含未知字段");
            }
            Object slot = requirement.get("slot");
            Object type = requirement.get("type");
            Object capability = requirement.get("capability_id");
            Object version = requirement.get("version_constraint");
            Object label = requirement.get("label");
            if (!(slot instanceof String slotText) || slotText.isBlank()
                    || !(type instanceof String typeText) || typeText.isBlank()
                    || !(capability instanceof String capabilityText) || capabilityText.isBlank()
                    || !(version instanceof String versionText) || versionText.isBlank()
                    || !(label instanceof String labelText) || labelText.isBlank()) {
                throw invalid("capability_requirements 的 slot、type、capability_id、version_constraint、label 必须为非空字符串");
            }
            if (!slots.add(slotText)) throw invalid("能力槽重复: " + slotText);
            if (requirement.containsKey("required") && !(requirement.get("required") instanceof Boolean)) {
                throw invalid("capability_requirements.required 必须为布尔值");
            }
            if (requirement.containsKey("description")
                    && !(requirement.get("description") instanceof String)) {
                throw invalid("capability_requirements.description 必须为字符串");
            }
        }
        return requirements;
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}

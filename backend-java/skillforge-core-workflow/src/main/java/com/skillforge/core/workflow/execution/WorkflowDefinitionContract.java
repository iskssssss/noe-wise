package com.skillforge.core.workflow.execution;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 发布、安装与执行共享的能力槽契约；不解析租户资源或凭据。 */
public final class WorkflowDefinitionContract {
    /** 结构解析保留完整目标契约；生产发布和准入只接受当前已装配的节点。 */
    public static void requireExecutable(Map<String, Object> definition) {
        for (Object raw : list(definition.getOrDefault("nodes", List.of()), "nodes")) {
            var node = object(raw, "node");
            if (Set.of("parallel", "subgraph", "agentic").contains(node.getOrDefault("type", "")))
                throw invalid("当前版本暂不支持执行节点: " + node.get("type")
                        + "（" + node.getOrDefault("id", "未命名") + "）");
        }
    }

    private static final long MAX_RESOURCE_BYTES = com.skillforge.execution.ExecutionLimits.TRANSFER_BYTES;
    private static final long MAX_RESOURCES_BYTES = com.skillforge.execution.ExecutionLimits.TRANSFER_BYTES;
    private static final int MAX_RESOURCES = com.skillforge.execution.ExecutionLimits.MAX_FILES;
    private static final Set<String> RESOURCE_FIELDS = Set.of(
            "path", "content_base64", "sha256");

    private WorkflowDefinitionContract() {}

    public record Requirement(String slot, String type, String capabilityId,
            String versionConstraint, boolean required) {}

    /** DefinitionVersion 内冻结的脚本资源；正文为原始字节的规范 Base64。 */
    public record Resource(String path, String contentBase64, String sha256) {}

    public static Map<String, Requirement> requirements(Map<String, Object> definition) {
        Map<String, Object> meta = object(definition.getOrDefault("meta", Map.of()), "meta");
        if (meta.containsKey("connector_requirements")) throw invalid("meta.connector_requirements 已移除");
        var result = new LinkedHashMap<String, Requirement>();
        for (Object item : list(meta.getOrDefault("capability_requirements", List.of()), "capability_requirements")) {
            var value = object(item, "capability requirement");
            String slot = text(value, "slot");
            String type = text(value, "type");
            if (!slot.matches("[a-z][a-z0-9_]{0,63}") || !Set.of("tool", "knowledge_source").contains(type))
                throw invalid("非法能力槽: " + slot);
            String constraint = text(value, "version_constraint");
            if (!constraint.equals("*") && !constraint.matches("[A-Za-z0-9:_-]+(?:\\.[A-Za-z0-9_-]+)*(?:\\.[xX*])?"))
                throw invalid("版本约束必须为精确版本、* 或末位 x 通配: " + slot);
            Object required = value.getOrDefault("required", true);
            if (!(required instanceof Boolean)) throw invalid("required 必须为布尔值: " + slot);
            var requirement = new Requirement(slot, type, text(value, "capability_id"), constraint, (Boolean) required);
            if (result.putIfAbsent(slot, requirement) != null) throw invalid("能力槽重复: " + slot);
        }
        return Map.copyOf(result);
    }

    public static Map<String, String> tools(Map<String, Object> definition) {
        var requirements = requirements(definition);
        var meta = object(definition.getOrDefault("meta", Map.of()), "meta");
        var result = new LinkedHashMap<String, String>();
        for (Object item : list(meta.getOrDefault("tools", List.of()), "tools")) {
            var tool = object(item, "tool");
            if (tool.containsKey("requires")) throw invalid("tools[].requires 已移除");
            String name = text(tool, "name"), slot = text(tool, "requirement");
            var requirement = requirements.get(slot);
            if (requirement == null || !requirement.type().equals("tool")) throw invalid("工具必须引用 tool 能力槽: " + name);
            if (result.putIfAbsent(name, slot) != null) throw invalid("工具声明重复: " + name);
        }
        return Map.copyOf(result);
    }

    /**
     * 读取并校验 DefinitionVersion 的脚本资源快照。
     *
     * <p>路径只允许 {@code scripts/} 下的相对 {@code .py}/{@code .js} 文件；内容、摘要与
     * 路径共同组成不可变资源身份，禁止重复路径或非规范 Base64。
     */
    public static Map<String, Resource> resources(Map<String, Object> definition) {
        var result = new LinkedHashMap<String, Resource>();
        long totalBytes = 0;
        var resources = list(definition.getOrDefault("resources", List.of()), "resources");
        if (resources.size() > MAX_RESOURCES) throw invalid("脚本资源最多 2048 个");
        for (Object item : resources) {
            var value = object(item, "resource");
            if (!value.keySet().equals(RESOURCE_FIELDS)) {
                throw invalid("resources[] 只允许 path、content_base64、sha256");
            }
            String path = resourcePath(string(value, "path"));
            String encoded = string(value, "content_base64");
            if (encoded.length() > 4 * ((MAX_RESOURCE_BYTES + 2) / 3)) {
                throw invalid("脚本资源超过单文件传输预算限制: " + path);
            }
            byte[] content;
            try {
                content = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException rejected) {
                throw invalid("资源 content_base64 不是合法 Base64: " + path);
            }
            if (!Base64.getEncoder().encodeToString(content).equals(encoded)) {
                throw invalid("资源 content_base64 必须使用规范 Base64: " + path);
            }
            totalBytes += content.length;
            if (content.length > MAX_RESOURCE_BYTES || totalBytes > MAX_RESOURCES_BYTES) {
                throw invalid("脚本资源超过总计 1 GiB 传输限制: " + path);
            }
            String sha256 = string(value, "sha256");
            if (!sha256.matches("[0-9a-f]{64}") || !sha256.equals(sha256(content))) {
                throw invalid("资源 sha256 与原始字节不一致: " + path);
            }
            var resource = new Resource(path, encoded, sha256);
            if (result.putIfAbsent(path, resource) != null) {
                throw invalid("资源路径重复: " + path);
            }
        }
        return Map.copyOf(result);
    }

    /** 草稿摄取阶段只核验脚本静态字段与资源引用，不要求其余节点已经可发布。 */
    public static void validateScriptResources(Map<String, Object> definition) {
        var resources = resources(definition);
        Object rawNodes = definition.get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) return;
        for (Object raw : nodes) {
            if (!(raw instanceof Map<?, ?>)) continue;
            var node = object(raw, "node");
            if ("script".equals(node.get("type"))) validateScriptNode(node, resources);
        }
    }

    public static final class HumanResponseDestinationRejected extends IllegalArgumentException {
        public HumanResponseDestinationRejected() {
            super("human_input.result_to 必须为 state、extracted 或其单层字段路径");
        }
    }

    static boolean isHumanResponseDestination(String target) {
        return target != null && target.matches("(?:state|extracted)(?:\\.[A-Za-z][A-Za-z0-9_]*)?");
    }

    /** 节点字段与槽引用在发布和运行时同时校验，导入不能成为旁路。 */
    public static void validate(Map<String, Object> definition) {
        var requirements = requirements(definition);
        var tools = tools(definition);
        var resources = resources(definition);
        for (Object raw : list(definition.getOrDefault("nodes", List.of()), "nodes")) {
            var node = object(raw, "node");
            String type = text(node, "type");
            if (type.equals("tool") && !tools.containsKey(text(node, "tool"))) throw invalid("tool 节点引用未声明工具");
            if (type.equals("knowledge_query")) {
                var requirement = requirements.get(text(node, "requirement"));
                if (requirement == null || !requirement.type().equals("knowledge_source")) throw invalid("knowledge_query 必须引用知识槽");
                text(node, "query"); text(node, "result_to");
                var budget = object(node.get("budget"), "knowledge_query.budget");
                for (String field : List.of("max_items", "max_tokens", "max_characters", "timeout_ms", "max_sources"))
                    positive(budget, field);
                if (object(node.getOrDefault("args", Map.of()), "args").containsKey("operation")) throw invalid("knowledge_query.args.operation 已移除");
            }
            if (type.equals("human_input") && node.containsKey("result_to")
                    && !isHumanResponseDestination(text(node, "result_to")))
                throw new HumanResponseDestinationRejected();
            if (type.equals("async_task") && node.containsKey("timeout_seconds")
                    && positive(node,"timeout_seconds") > Integer.MAX_VALUE) throw invalid("async_task.timeout_seconds 超出范围");
            if (type.equals("agentic")) {
                text(node, "agent_slot"); text(node, "result_to");
                var task = object(node.get("task"), "agentic.task");
                text(task, "objective"); text(task, "expected_output");
            }
            if (type.equals("parallel")) {
                text(node, "result_to");
                var branches = list(node.get("branches"), "parallel.branches");
                if (branches.isEmpty() || branches.size() > 32 || branches.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank())
                        || branches.stream().distinct().count() != branches.size()) throw invalid("parallel 分支必须为 1 至 32 个且唯一");
            }
            if (type.equals("script")) {
                validateScriptNode(node, resources);
            }
        }
    }

    public static boolean matchesVersion(String constraint, String version) {
        if (constraint.equals("*")) return true;
        if (constraint.endsWith(".x") || constraint.endsWith(".X") || constraint.endsWith(".*"))
            return version.startsWith(constraint.substring(0, constraint.length() - 1));
        return constraint.equals(version);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String))) throw invalid(field + " 必须为对象");
        return (Map<String, Object>) map;
    }

    public static List<?> list(Object value, String field) {
        if (!(value instanceof List<?> list)) throw invalid(field + " 必须为数组");
        return list;
    }

    public static String text(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof String text) || text.isBlank()) throw invalid(field + " 必须为非空字符串");
        return text;
    }

    private static String string(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof String text)) throw invalid(field + " 必须为字符串");
        return text;
    }

    private static String resourcePath(String path) {
        if (path.length() > 240 || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0
                || path.startsWith("/") || path.matches("^[A-Za-z]:.*")
                || !path.startsWith("scripts/")
                || !(path.endsWith(".py") || path.endsWith(".js"))) {
            throw invalid("脚本资源必须是 scripts/ 下的相对 .py 或 .js 路径: " + path);
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw invalid("脚本资源路径禁止空段、. 或 ..: " + path);
            }
        }
        return path;
    }

    private static void validateScriptNode(
            Map<String, Object> node, Map<String, Resource> resources) {
        var args = object(node.get("args"), "script.args");
        if (args.containsKey("input")) object(args.get("input"), "script.args.input");
        if (args.containsKey("output_schema")) object(args.get("output_schema"), "script.args.output_schema");
        String path = resourcePath(text(args, "script"));
        if (!resources.containsKey(path)) {
            throw invalid("script 节点引用未发布资源: " + path);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long positive(Map<String, Object> value, String field) {
        try {
            long number = new java.math.BigDecimal(String.valueOf(value.get(field))).longValueExact();
            if (number > 0) return number;
        } catch (ArithmeticException | NumberFormatException ignored) { }
        throw invalid(field + " 必须为正整数");
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}

package com.skillforge.institution.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.execution.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.workflow.execution.WorkflowDefinitionContract;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * 从已冻结的 Workflow 定义准备 Workflow Script Sandbox 请求。
 *
 * <p>调用方必须在传入前完成节点参数插值；本工厂不读取当前定义、配置或运行状态。</p>
 */
public final class WorkflowScriptSandboxRequestFactory {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).findAndRegisterModules();

    private WorkflowScriptSandboxRequestFactory() {
    }

    /** 组成稳定任务身份的可信 Workflow 节点调用坐标。 */
    public record TaskIdentity(
            String tenantId,
            String executionId,
            String nodeId,
            int visit,
            int call) {
        public TaskIdentity {
            tenantId = requireIdentityPart(tenantId, "tenantId");
            executionId = requireIdentityPart(executionId, "executionId");
            nodeId = requireIdentityPart(nodeId, "nodeId");
            if (visit < 1 || call < 1) {
                throw new IllegalArgumentException("workflow_script_sandbox_call_identity_invalid");
            }
        }
    }

    /**
     * 构造可直接交给 {@link WorkflowScriptSandboxClient#submit} 的确定性请求。
     *
     * @param frozenDefinition 已发布并冻结的 definitionVersion JSON
     * @param scriptNode 当前 script 节点
     * @param scriptInput 已完成插值的脚本输入对象，不包含 {@code args.script}
     * @param identity 本次节点访问的稳定调用身份
     * @param deadlineAt 已冻结的绝对截止时间
     * @param executionPolicy 已冻结的 Sandbox 执行策略 JSON 对象
     * @param environmentVersion 与脚本语言精确对应的固定环境版本
     */
    public static WorkflowScriptSandboxClient.PreparedRequest prepare(
            Map<String, Object> frozenDefinition,
            WorkflowNode scriptNode,
            Map<String, Object> scriptInput,
            TaskIdentity identity,
            java.time.Instant deadlineAt,
            ExecutionPolicy executionPolicy,
            String environmentVersion) {
        Objects.requireNonNull(frozenDefinition, "frozenDefinition");
        Objects.requireNonNull(scriptNode, "scriptNode");
        Objects.requireNonNull(scriptInput, "scriptInput");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        if (!identity.nodeId().equals(scriptNode.id()) || !"script".equals(scriptNode.type())) {
            throw new IllegalArgumentException("workflow_script_sandbox_node_identity_invalid");
        }
        environmentVersion = requireText(environmentVersion, "environmentVersion");

        WorkflowDefinitionContract.validateScriptResources(frozenDefinition);
        String scriptPath = scriptPath(scriptNode);
        String language = language(scriptPath);
        Map<String, WorkflowDefinitionContract.Resource> resources =
                WorkflowDefinitionContract.resources(frozenDefinition);
        if (!resources.containsKey(scriptPath)) {
            throw new IllegalArgumentException("workflow_script_sandbox_script_not_frozen");
        }

        String taskId = taskId(identity);
        var sources=resources.values().stream()
                .filter(resource->resource.path().endsWith(language.equals("python")?".py":".js"))
                .sorted(Comparator.comparing(WorkflowDefinitionContract.Resource::path))
                .map(resource->{
                    byte[] bytes=java.util.Base64.getDecoder().decode(resource.contentBase64());
                    return new ExecutionInputSource(new ArtifactFile(resource.path(),bytes.length,resource.sha256()),
                            ()->new java.io.ByteArrayInputStream(bytes));
                }).toList();
        if(sources.isEmpty())throw new IllegalArgumentException("workflow_script_sandbox_resources_missing");
        try {
            var policy=executionPolicy;
            var request=new ExecuteRequest(taskId,Language.fromValue(language),"",java.util.List.of(),
                    sources.stream().map(ExecutionInputSource::file).toList(),scriptPath,false,deadlineAt,
                    policy,java.util.List.of(),ExecutionProfile.WORKFLOW_SCRIPT_V1,environmentVersion,
                    canonicalObject(scriptInput,"scriptInput"));
            return new WorkflowScriptSandboxClient.PreparedRequest(taskId,JSON.writeValueAsString(request),sources);
        } catch(Exception invalid) { throw new IllegalArgumentException("workflow_script_sandbox_request_invalid",invalid); }
    }

    private static String scriptPath(WorkflowNode node) {
        Object raw = node.args().get("script");
        if (!(raw instanceof String path) || path.isBlank()) {
            throw new IllegalArgumentException("workflow_script_sandbox_script_path_invalid");
        }
        return path;
    }

    private static String language(String scriptPath) {
        if (scriptPath.endsWith(".py")) {
            return "python";
        }
        if (scriptPath.endsWith(".js")) {
            return "node";
        }
        throw new IllegalArgumentException("workflow_script_sandbox_language_unsupported");
    }

    private static ObjectNode object(Map<String, Object> value, String name) {
        JsonNode node = JSON.valueToTree(value);
        if (!node.isObject()) {
            throw new IllegalArgumentException("workflow_script_sandbox_" + name + "_invalid");
        }
        return (ObjectNode) node;
    }

    private static String canonicalObject(Map<String, Object> value, String name) {
        try {
            JsonNode node = JSON.valueToTree(value);
            if (!node.isObject()) {
                throw new IllegalArgumentException("workflow_script_sandbox_" + name + "_invalid");
            }
            return JSON.writeValueAsString(node);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception serializationFailure) {
            throw new IllegalArgumentException("workflow_script_sandbox_" + name + "_invalid", serializationFailure);
        }
    }

    private static String taskId(TaskIdentity identity) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream value = new DataOutputStream(bytes)) {
                writePart(value, identity.tenantId());
                writePart(value, identity.executionId());
                writePart(value, identity.nodeId());
                value.writeInt(identity.visit());
                value.writeInt(identity.call());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("workflow_script_sandbox_task_id_unavailable", impossible);
        }
    }

    private static void writePart(DataOutputStream target, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        target.writeInt(bytes.length);
        target.write(bytes);
    }

    private static String requireIdentityPart(String value, String name) {
        String result = requireText(value, name);
        if (result.indexOf(0) >= 0) {
            throw new IllegalArgumentException("workflow_script_sandbox_call_identity_invalid");
        }
        return result;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workflow_script_sandbox_" + name + "_invalid");
        }
        return value;
    }
}

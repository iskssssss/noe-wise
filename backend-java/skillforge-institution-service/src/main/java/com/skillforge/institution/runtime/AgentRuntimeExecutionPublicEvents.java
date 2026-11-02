package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.contract.ChatCitationView;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;

/** 新执行链公开等待的宿主安全边界；拒绝呈现不更改实际工具结果或 Run 等待状态。 */
final class AgentRuntimeExecutionPublicEvents {
    /** 表单不可公开的宿主事实；正文不保留原请求或 opaque continuation。 */
    static final String REJECTED_EVENT = "execution.interaction-rejected";
    /** 拒绝事件只包含已知控制身份及固定公开原因。 */
    static final String REJECTED_SCHEMA = "skillforge.runtime.execution.interaction-rejected.v1";

    /** 只提供当前执行链的公开边界函数，不拥有可变状态。 */
    private AgentRuntimeExecutionPublicEvents() { }

    /**
     * 在与真实结果同次提交前保护等待事件；安全拒绝仍保存真实结果及原 Wait。
     * 技术解析错误继续抛出原因，不能伪造一次安全拒绝或业务失败。
     */
    static ExecutionStore.Event protectWaiting(ExecutionStore.Event event, PublicOutputGuard guard, ObjectMapper json) {
        if (!AgentExecutionProgram.WAITING_EVENT.equals(event.type())
                || !AgentExecutionProgram.WAITING_SCHEMA.equals(event.data().schemaId()))
            throw new IllegalArgumentException("execution_public_event_unsupported");
        try {
            var body = json.readTree(event.data().canonicalJson());
            if (!rejectsWaiting(body, guard.capture(), json)) return event;
            var safe = json.createObjectNode();
            for (var field : List.of("inputId", "rootInputId", "traceId", "spanId", "agentId", "actionId", "generation")) {
                var value = body.get(field);
                if (value == null || !value.isTextual() || value.textValue().isBlank())
                    throw new IllegalArgumentException("execution_public_event_invalid");
                safe.set(field, value);
            }
            safe.put("reason", PublicOutputGuard.REJECTION_CODE);
            return new ExecutionStore.Event(event.id(), REJECTED_EVENT,
                    new TypedPayload(REJECTED_SCHEMA, json.writeValueAsString(safe)));
        } catch (IOException invalid) { throw new IllegalArgumentException("execution_public_event_invalid", invalid); }
    }

    /** 检查实际会显示的消息、原因和表单内容；控制身份及私有 requestState 从不当正文呈现。 */
    static boolean rejectsWaiting(JsonNode body, PublicOutputGuard.Scope scope, ObjectMapper json) throws IOException {
        if (body == null || !body.isObject() || !body.path("requests").isObject())
            throw new IllegalArgumentException("execution_wait_requests_invalid");
        if ("human".equals(body.path("kind").asText())) {
            if (!body.path("requests").isEmpty() || !body.path("question").isTextual() || body.path("question").asText().isBlank())
                throw new IllegalArgumentException("execution_human_wait_invalid");
            return scope.rejects(body.path("question").textValue());
        }
        if (java.util.Set.of("delegation", "workflow", "plan").contains(body.path("kind").asText())) {
            if (!body.path("requests").isEmpty() || !body.path("question").isNull())
                throw new IllegalArgumentException("execution_dependency_wait_invalid");
            if (body.has("workflow")) {
                if (!"workflow".equals(body.path("kind").asText())) throw new IllegalArgumentException("execution_workflow_interaction_invalid");
                var workflow = body.get("workflow");
                for (var field : List.of("executionId", "suspendRef", "actionKey", "executionRevision")) {
                    if (!workflow.path(field).isTextual() || workflow.get(field).textValue().isBlank()) throw new IllegalArgumentException("execution_workflow_interaction_invalid");
                }
                if (workflow.size()!=5 || !"application/json".equals(workflow.path("card").path("schemaId").asText())
                        || !workflow.path("card").path("canonicalJson").isTextual()) throw new IllegalArgumentException("execution_workflow_interaction_invalid");
                var card = json.readTree(workflow.path("card").get("canonicalJson").textValue());
                if (card == null || !card.isObject()) throw new IllegalArgumentException("execution_workflow_interaction_invalid");
                return rejectsPresentation(card, scope, json);
            }
            return false;
        }
        if (!"tool".equals(body.path("kind").asText())) throw new IllegalArgumentException("execution_wait_kind_unsupported");
        for (var request : body.path("requests")) {
            var encoded = request.path("params").get("canonicalJson");
            if (encoded == null || !encoded.isTextual()) throw new IllegalArgumentException("execution_wait_request_invalid");
            var params = json.readTree(encoded.textValue());
            if (params == null || !params.isObject()) throw new IllegalArgumentException("execution_wait_request_invalid");
            if (rejectsPresentation(params, scope, json)) return true;
        }
        return false;
    }

    /** 已提交卡片在历史和重连时也按当前政策检查全部可见文字与控件初值。 */
    static boolean rejectsPresentation(JsonNode value, PublicOutputGuard.Scope scope, ObjectMapper json) {
        return PublicPresentationTexts.read(value).stream().anyMatch(scope::rejects)
                || scope.rejects(value.path("reason").asText()) || unsafeFormDefaults(value, scope, json);
    }

    /** 公开 Workflow 协议只接受封闭字段；导出结果被当前政策拒绝时移除正文，保留实际状态。 */
    static ExecutionStore.Event protectWorkflow(ExecutionStore.Event event, PublicOutputGuard guard, ObjectMapper json) {
        boolean started = AgentExecutionProgram.WORKFLOW_STARTED_EVENT.equals(event.type())
                && AgentExecutionProgram.WORKFLOW_STARTED_SCHEMA.equals(event.data().schemaId());
        boolean finished = AgentExecutionProgram.WORKFLOW_RESULT_EVENT.equals(event.type())
                && AgentExecutionProgram.WORKFLOW_RESULT_SCHEMA.equals(event.data().schemaId());
        if (!started && !finished) throw new IllegalArgumentException("execution_public_event_unsupported");
        try {
            var body = json.readTree(event.data().canonicalJson());
            var expected = new java.util.HashSet<>(java.util.Set.of("actionId", "executionId", "status", "summary",
                    "inputId", "rootInputId", "traceId", "spanId", "agentId"));
            if (finished) expected.addAll(java.util.Set.of("executionRevision", "outputAccepted", "output"));
            if (body == null || !body.isObject()) throw new IllegalArgumentException("execution_workflow_event_invalid");
            var fields = new java.util.HashSet<String>(); body.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(expected)) throw new IllegalArgumentException("execution_workflow_event_invalid");
            for (String field : java.util.Set.of("actionId", "executionId", "status", "summary", "inputId", "rootInputId", "traceId", "spanId", "agentId"))
                requiredText(body, field);
            String status = body.path("status").textValue();
            if (started && !"DISPATCHED".equals(status)
                    || finished && !java.util.Set.of("COMPLETED", "FAILED", "TERMINATED", "CANCELLED", "UNKNOWN").contains(status))
                throw new IllegalArgumentException("execution_workflow_event_invalid");
            var scope = guard.capture();
            if (finished) {
                var revision = body.get("executionRevision");
                if (!revision.isNull() && (!revision.isTextual() || !revision.textValue().matches("[1-9][0-9]*")))
                    throw new IllegalArgumentException("execution_workflow_event_invalid");
                if (!body.path("outputAccepted").isBoolean()) throw new IllegalArgumentException("execution_workflow_event_invalid");
                boolean accepted = body.path("outputAccepted").booleanValue(); var output = body.get("output");
                if (accepted) {
                    if (!"COMPLETED".equals(status) || !output.isObject() || output.size() != 2
                            || !output.path("schemaId").isTextual() || output.path("schemaId").textValue().isBlank()
                            || !output.path("canonicalJson").isTextual()) throw new IllegalArgumentException("execution_workflow_event_invalid");
                    var document = json.readTree(output.path("canonicalJson").textValue());
                    if (document == null) throw new IllegalArgumentException("execution_workflow_event_invalid");
                    // 整份声明导出的文档会在 Trace 内容中可见，逐字符串检查其业务值与公开字段名。
                    if (scope.rejects(output.path("schemaId").textValue()) || scope.rejects(output.path("canonicalJson").textValue())
                            || rejectsWorkflowOutput(document, scope)) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) body).putNull("output");
                        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("outputAccepted", false);
                        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("summary", PublicOutputGuard.SAFE_MESSAGE);
                    }
                } else if (!output.isNull()) throw new IllegalArgumentException("execution_workflow_event_invalid");
            }
            if (scope.rejects(body.path("summary").textValue()))
                ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("summary", PublicOutputGuard.SAFE_MESSAGE);
            return new ExecutionStore.Event(event.id(), event.type(), new TypedPayload(event.data().schemaId(), json.writeValueAsString(body)));
        } catch (IOException invalid) { throw new IllegalArgumentException("execution_public_event_invalid", invalid); }
    }

    /** 解析后的字段名与字符串均可见，不能用 Unicode 转义或深层对象绕过同一公开规则。 */
    private static boolean rejectsWorkflowOutput(JsonNode value, PublicOutputGuard.Scope scope) {
        if (value.isTextual()) return scope.rejects(value.textValue());
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (scope.rejects(field.getKey()) || rejectsWorkflowOutput(field.getValue(), scope)) return true;
            }
        } else if (value.isArray()) {
            for (var item : value) if (rejectsWorkflowOutput(item, scope)) return true;
        }
        return value.isContainerNode() && scope.rejects(value.toString());
    }

    /** 实际工具状态不因呈现拒绝被改写；只有公开概要替换成固定安全文案。 */
    static ExecutionStore.Event protectToolResult(ExecutionStore.Event event, PublicOutputGuard guard, ObjectMapper json) {
        if (!AgentExecutionProgram.TOOL_RESULT_EVENT.equals(event.type())
                || !AgentExecutionProgram.TOOL_RESULT_SCHEMA.equals(event.data().schemaId()))
            throw new IllegalArgumentException("execution_public_event_unsupported");
        try {
            var body = json.readTree(event.data().canonicalJson());
            if (body == null || !body.isObject() || !body.path("summary").isTextual())
                throw new IllegalArgumentException("execution_tool_result_invalid");
            if (!guard.capture().rejects(body.path("summary").textValue())) return event;
            ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("summary", PublicOutputGuard.SAFE_MESSAGE);
            return new ExecutionStore.Event(event.id(), event.type(), new TypedPayload(event.data().schemaId(), json.writeValueAsString(body)));
        } catch (IOException invalid) { throw new IllegalArgumentException("execution_public_event_invalid", invalid); }
    }

    /** 模型结果只保留实际状态与固定概要，不发布模型私有推理或结构化决策。 */
    static ExecutionStore.Event protectModelResult(ExecutionStore.Event event, PublicOutputGuard guard, ObjectMapper json) {
        if (!AgentExecutionProgram.MODEL_RESULT_EVENT.equals(event.type())
                || !AgentExecutionProgram.MODEL_RESULT_SCHEMA.equals(event.data().schemaId()))
            throw new IllegalArgumentException("execution_public_event_unsupported");
        try {
            var body = json.readTree(event.data().canonicalJson());
            if (body == null || !body.isObject() || !body.path("summary").isTextual())
                throw new IllegalArgumentException("execution_model_result_invalid");
            if (!guard.capture().rejects(body.path("summary").textValue())) return event;
            ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("summary", PublicOutputGuard.SAFE_MESSAGE);
            return new ExecutionStore.Event(event.id(), event.type(), new TypedPayload(event.data().schemaId(), json.writeValueAsString(body)));
        } catch (IOException invalid) { throw new IllegalArgumentException("execution_public_event_invalid", invalid); }
    }

    /** 只投影真实证据的最小公开字段，不返回bundle、私有resultRef、路径或来源URL。 */
    static List<ChatCitationView> citations(JsonNode completed) {
        var evidence = completed.path("evidence");
        if (evidence.isMissingNode()) return List.of();
        if (!evidence.isArray()) throw new IllegalArgumentException("execution_citations_invalid");
        var result = new ArrayList<ChatCitationView>();
        var unique = new HashSet<String>();
        for (var item : evidence) {
            String itemId = requiredText(item, "itemId");
            if (!unique.add(itemId)) throw new IllegalArgumentException("execution_citation_duplicate");
            int ordinal = Math.incrementExact(result.size());
            result.add(new ChatCitationView(ordinal, itemId, requiredText(item, "sourceId"),
                    requiredText(item, "sourceVersion"), "知识依据 " + ordinal, requiredText(item, "citationId")));
        }
        return List.copyOf(result);
    }

    /** 即使身份字段属于程序选择器，也不能借此公开宿主已知私有路径或明确凭据。 */
    static boolean citationsRejected(JsonNode completed, PublicOutputGuard.Scope scope) {
        return citations(completed).stream().anyMatch(citation -> scope.rejects(citation.itemId())
                || scope.rejects(citation.sourceId()) || scope.rejects(citation.sourceVersion()) || scope.rejects(citation.label()));
    }

    /** 证据身份必须来自实际采用的版本，不将null或数值补成字符串。 */
    private static String requiredText(JsonNode value, String field) {
        var text = value.get(field);
        if (text == null || !text.isTextual() || text.textValue().isBlank()) throw new IllegalArgumentException("execution_citations_invalid");
        return text.textValue();
    }

    /** 默认值实际进入控件；保留字段名检测真实凭据，而不拒绝仅声明密码字段的空表单。 */
    private static boolean unsafeFormDefaults(JsonNode value, PublicOutputGuard.Scope scope, ObjectMapper json) {
        if (value == null || !value.isContainerNode()) return false;
        if (value.isObject()) {
            var properties = value.path("properties");
            if (properties.isObject()) {
                var fields = properties.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (field.getValue().has("default")) {
                        var shown = json.createObjectNode();
                        shown.set(field.getKey(), field.getValue().get("default"));
                        if (scope.rejects(shown.toString())) return true;
                    }
                }
            }
            if (value.has("enumNames") && scope.rejects(value.get("enumNames").toString())) return true;
        }
        for (var child : value) if (unsafeFormDefaults(child, scope, json)) return true;
        return false;
    }
}

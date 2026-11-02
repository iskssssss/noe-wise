package com.skillforge.institution.runtime;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import com.skillforge.runtime.session.spi.ExecutionStore.WaitRef;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;

/** 产品工具输入协议的唯一解码边界；响应只引用原 Action 的等待代数，不携带执行授权。 */
final class AgentRuntimeExecutionResponses {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** 已受理的人类文本公开投影，不取代私有准确响应材料。 */
    static final String USER_MESSAGE_EVENT = "execution.user-message";
    /** 公开文本与准确新输入因果的固定协议。 */
    static final String USER_MESSAGE_SCHEMA = "skillforge.runtime.execution.user-message.v1";
    private AgentRuntimeExecutionResponses() { }

    /** 不同响应保持具体封闭类型；所有类型共享原Run/等待身份，不携带执行或设备授权。 */
    sealed interface Reply permits ToolReply, HumanReply, WorkflowReply {
        RunRef run();
        long revision();
        WaitRef waiting();
    }

    /** 工具表单与AskUser回复各有精确结构，禁止将两个等待类型混合。 */
    static Reply decode(TenantId tenant, Map<String, Object> fields, AgentRuntimePayloadValidation payloads) {
        if (fields != null && fields.containsKey("workflow_input")) return workflow(tenant, fields, payloads);
        if (fields != null && fields.containsKey("human_input")) return human(tenant, fields);
        return tool(tenant, fields, payloads);
    }

    /** Workflow Human与普通AskUser各有封闭协议；SDK等待身份全部由用户看到的卡片原样返回。 */
    private static WorkflowReply workflow(TenantId tenant, Map<String, Object> fields, AgentRuntimePayloadValidation payloads) {
        if (!fields.keySet().equals(Set.of("execution_id", "run_revision", "workflow_input"))
                || !(fields.get("workflow_input") instanceof Map<?, ?> input)
                || !input.keySet().equals(Set.of("wait", "execution_ref", "suspend_ref", "action_key", "execution_revision", "response"))
                || !(input.get("wait") instanceof Map<?, ?> waiting)
                || !waiting.keySet().equals(Set.of("action_id", "generation"))
                || !(input.get("response") instanceof Map<?, ?> response)
                || !response.keySet().equals(Set.of("schema_id", "canonical_json"))) throw invalid();
        var payload = new TypedPayload(text(response.get("schema_id")), text(response.get("canonical_json")));
        if (!"application/json".equals(payload.schemaId()) || !payloads.accepts(payload)) throw invalid();
        try {
            if (!JSON.readTree(payload.canonicalJson()).isObject()) throw invalid();
        } catch (java.io.IOException malformed) { throw invalid(); }
        return new WorkflowReply(new RunRef(tenant, text(fields.get("execution_id"))), decimal(fields.get("run_revision")),
                new WaitRef(new ActionRef(tenant, text(waiting.get("action_id"))), decimal(waiting.get("generation"))),
                new ExecutionRef(tenant, text(input.get("execution_ref"))), text(input.get("suspend_ref")), text(input.get("action_key")),
                decimal(input.get("execution_revision")), payload);
    }

    /** AskUser文本仍须绑定用户看到的精确等待和版本，不能从当前运行状态补齐。 */
    private static HumanReply human(TenantId tenant, Map<String, Object> fields) {
        if (!fields.keySet().equals(Set.of("execution_id", "run_revision", "human_input"))
                || !(fields.get("human_input") instanceof Map<?, ?> input)
                || !input.keySet().equals(Set.of("wait", "text"))
                || !(input.get("wait") instanceof Map<?, ?> waiting)
                || !waiting.keySet().equals(Set.of("action_id", "generation"))) throw invalid();
        return new HumanReply(new RunRef(tenant, text(fields.get("execution_id"))), decimal(fields.get("run_revision")),
                new WaitRef(new ActionRef(tenant, text(waiting.get("action_id"))), decimal(waiting.get("generation"))), text(input.get("text")));
    }

    /** 严格读取已有 HTTP DTO 的开放响应字段，固定身份字段不经过通用对象转换链。 */
    static ToolReply tool(TenantId tenant, Map<String, Object> fields, AgentRuntimePayloadValidation payloads) {
        if (fields == null || !fields.keySet().equals(Set.of("execution_id", "run_revision", "tool_input"))) throw invalid();
        if (!(fields.get("tool_input") instanceof Map<?, ?> input)
                || !input.keySet().equals(Set.of("wait", "responses"))
                || !(input.get("wait") instanceof Map<?, ?> waiting)
                || !waiting.keySet().equals(Set.of("action_id", "generation"))
                || !(input.get("responses") instanceof Map<?, ?> raw) || raw.isEmpty() || raw.size() > 32) throw invalid();
        var responses = new TreeMap<String, ToolInputResponse>();
        raw.forEach((key, value) -> {
            if (!(value instanceof Map<?, ?> response) || !response.keySet().equals(Set.of("method", "result"))
                    || !(response.get("result") instanceof Map<?, ?> result)
                    || !result.keySet().equals(Set.of("schema_id", "canonical_json"))) throw invalid();
            var payload = new TypedPayload(text(result.get("schema_id")), text(result.get("canonical_json")));
            if (!payloads.accepts(payload)) throw invalid();
            final ToolInputMethod method;
            try { method = ToolInputMethod.fromWireName(text(response.get("method"))); }
            catch (IllegalArgumentException rejected) { throw invalid(); }
            responses.put(text(key), new ToolInputResponse(method, payload));
        });
        return new ToolReply(new RunRef(tenant, text(fields.get("execution_id"))), decimal(fields.get("run_revision")),
                new WaitRef(new ActionRef(tenant, text(waiting.get("action_id"))), decimal(waiting.get("generation"))), responses);
    }

    /** 版本身份必须为规范正十进制字符串，拒绝浮点数、前导零和当前状态补全。 */
    private static long decimal(Object value) {
        if (!(value instanceof String text)) throw invalid();
        try {
            long parsed = Long.parseLong(text);
            if (parsed <= 0 || !Long.toString(parsed).equals(text)) throw invalid();
            return parsed;
        } catch (NumberFormatException malformed) { throw invalid(); }
    }

    /** 显式拒绝缺少身份或请求键，错误不回显输入正文。 */
    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) throw invalid();
        return text;
    }

    /** 输入结构错误不会写入等待或改变原 Run。 */
    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "工具回复缺少准确的等待身份、版本或响应");
    }

    /** @param run 原运行 @param revision 用户看到的等待版本 @param waiting 原动作等待 @param responses 精确键和方法的业务响应 */
    record ToolReply(RunRef run, long revision, WaitRef waiting, Map<String, ToolInputResponse> responses) implements Reply {
        ToolReply { responses = Map.copyOf(responses); }
    }

    /** @param run 原运行 @param revision 原等待版本 @param waiting 原等待身份 @param text 用户实际回复，原样保存 */
    record HumanReply(RunRef run, long revision, WaitRef waiting, String text) implements Reply { }

    record WorkflowReply(RunRef run, long revision, WaitRef waiting, ExecutionRef execution,
            String suspendRef, String actionKey, long executionRevision, TypedPayload response) implements Reply { }
}

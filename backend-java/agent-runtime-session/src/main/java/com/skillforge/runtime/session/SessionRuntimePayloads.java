package com.skillforge.runtime.session;

import com.skillforge.runtime.json.RuntimeJson;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.PayloadValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;

/**
 * 生产 registry：上下文正文、模型私有输出、决策投影、知识证据正文和完整 model-result envelope
 * 各用自己的 schema，不能把只接受 context-text 的准备校验当成模型结果或知识检索已接通。
 */
public final class SessionRuntimePayloads implements PayloadValidator {
    static final String KNOWLEDGE_CHUNK_SCHEMA = "skillforge.runtime.knowledge.chunk.v1";
    static final String KNOWLEDGE_LINK_SCHEMA = "skillforge.runtime.knowledge.link.v1";
    public static final String TOOL_RESULT_SCHEMA = "skillforge.runtime.tool.result.v1";
    public static final String PLAN_REVIEW_RESPONSE_SCHEMA = "skillforge.runtime.plan-review-response.v2";
    private final SessionPreparationContent context = new SessionPreparationContent();

    /** 宿主的有界输入校验入口，不向普通业务服务暴露 Core 解析配置。 */
    public static final class InputValidator {
        private final CodecLimits limits;

        /** 构造时固定并校验资源配额，与当前 Runtime 的 schema 解释保持一致。 */
        public InputValidator(int maxCodeUnits, int maxNodes, int maxDepth) {
            limits = new CodecLimits(maxCodeUnits, maxNodes, maxDepth);
        }

        /** 仅校验给定 schema 与输入，不授予工作流、来源或调用权限。 */
        public void validate(TypedPayload schema, TypedPayload payload) {
            validateAgainstSchema(schema, payload, limits);
        }
    }

    /** 用冻结 schema 校验参数；schema 描述与 application/json 实例不是同一种 payload。 */
    public static void validateAgainstSchema(TypedPayload schema, TypedPayload payload, CodecLimits limits) {
        if (!"json-schema".equals(schema.schemaId()) || !"application/json".equals(payload.schemaId())) { throw invalid(); }
        var definition = SessionRuntimeJsonSchema.project(SessionRuntimePayloadJson.parse(schema.canonicalJson(), limits));
        SessionRuntimeJsonSchema.validate(definition, SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits));
    }

    /** 参数拒绝的安全定位；不含参数值、候选枚举或原始异常，无法诊断时由调用方保留固定失败反馈。 */
    public static java.util.Optional<SchemaViolation> inputSchemaViolation(TypedPayload schema, TypedPayload payload, CodecLimits limits) {
        try {
            if (schema == null || payload == null || limits == null
                    || !"json-schema".equals(schema.schemaId()) || !"application/json".equals(payload.schemaId()))
                return java.util.Optional.empty();
            var definition = SessionRuntimeJsonSchema.project(SessionRuntimePayloadJson.parse(schema.canonicalJson(), limits));
            return java.util.Optional.ofNullable(SessionRuntimeJsonSchema.firstViolation(definition,
                    SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits), ""));
        } catch (RuntimeException invalid) {
            return java.util.Optional.empty();
        }
    }

    /** JSON Pointer 仅定位实例字段；keyword 不携带约束值。空路径表示根实例，/_ 表示名称或深度已隐藏。 */
    public record SchemaViolation(String path, String keyword) {
        public SchemaViolation {
            if (path == null || path.length() > 256 || !path.isEmpty() && !path.startsWith("/")
                    || !Set.of("additionalProperties", "required", "type", "enum", "minLength", "maxLength", "minimum", "maximum", "oneOf", "anyOf").contains(keyword))
                throw new IllegalArgumentException("schema_violation_invalid");
        }
    }

    /** Provider-neutral Tool 结果以冻结 output schema 约束 structuredContent；空 schema 才允许仅 content。 */
    public static void validateToolOutput(TypedPayload schema, TypedPayload payload, CodecLimits limits) {
        if (!"json-schema".equals(schema.schemaId()) || !TOOL_RESULT_SCHEMA.equals(payload.schemaId())) { throw invalid(); }
        var definition = SessionRuntimeJsonSchema.project(SessionRuntimePayloadJson.parse(schema.canonicalJson(), limits));
        var envelope = SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
        toolEnvelope(envelope);
        if (envelope.has("structuredContent")) {
            SessionRuntimeJsonSchema.validate(definition, envelope.get("structuredContent"));
        } else if (!definition.isEmpty()) { throw invalid(); }
    }

    /**
     * 先校验原始业务结果，再只保留安全 envelope。敏感字段从 structuredContent 删除；
     * 仅当本次结果里真正去掉了敏感输出时才丢弃自由文本 content，避免把入参脱敏名单
     *（如 Authorization headers）套到响应体上。
     */
    public static TypedPayload safeToolOutput(TypedPayload schema,TypedPayload payload,
            Set<String> sensitiveFields,CodecLimits limits){
        validateToolOutput(schema,payload,limits);
        sensitiveFields=Set.copyOf(sensitiveFields);
        JsonNode envelope=SessionRuntimePayloadJson.parse(payload.canonicalJson(),limits);
        var safe=new TreeMap<String,Object>();
        boolean stripped=false;
        if(envelope.has("structuredContent")){
            @SuppressWarnings("unchecked")
            var structured=new TreeMap<String,Object>((Map<String,Object>)jsonValue(envelope.get("structuredContent")));
            for(String field:sensitiveFields){
                if(structured.containsKey(field)){
                    structured.remove(field);
                    stripped=true;
                }
            }
            safe.put("structuredContent",structured);
        }
        safe.put("content",stripped?List.of():jsonValue(envelope.get("content")));
        if(envelope.has("isError"))safe.put("isError",envelope.get("isError").booleanValue());
        if(envelope.has("resultType"))safe.put("resultType",envelope.get("resultType").textValue());
        var result=new TypedPayload(TOOL_RESULT_SCHEMA,SessionRuntimePayloadJson.write(safe));
        toolEnvelope(SessionRuntimePayloadJson.parse(result.canonicalJson(),limits));
        return result;
    }

    private static void toolEnvelope(com.fasterxml.jackson.databind.JsonNode value) {
        if (!value.isObject() || !value.path("content").isArray()
                || value.has("structuredContent") && !value.get("structuredContent").isObject()
                || value.has("isError") && !value.get("isError").isBoolean()
                || value.has("resultType")&&(!value.get("resultType").isTextual()
                ||value.get("resultType").textValue().isBlank())) { throw invalid(); }
        for (var item : value.get("content")) {
            if (!item.isObject() || !item.path("type").isTextual() || item.get("type").textValue().isBlank()) { throw invalid(); }
        }
    }

    private static Object jsonValue(JsonNode value){
        if(value.isObject()){
            var object=new TreeMap<String,Object>();value.fields().forEachRemaining(field->object.put(field.getKey(),jsonValue(field.getValue())));return object;
        }
        if(value.isArray()){
            var array=new ArrayList<Object>();value.forEach(item->array.add(jsonValue(item)));return array;
        }
        if(value.isTextual())return value.textValue();if(value.isBoolean())return value.booleanValue();if(value.isNull())return null;
        if(value.isIntegralNumber())return value.bigIntegerValue();if(value.isFloatingPointNumber())return value.decimalValue();throw invalid();
    }

    @Override
    public void validate(TypedPayload payload, CodecLimits limits) {
        if (payload == null || limits == null) {
            throw invalid();
        }
        String schema = payload.schemaId();
        if ("application/json".equals(schema)) {
            SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
            return;
        }
        if ("json-schema".equals(schema)) {
            SessionRuntimeJsonSchema.project(SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits));
            return;
        }
        if (TOOL_RESULT_SCHEMA.equals(schema)) {
            toolEnvelope(SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits));
            return;
        }
        if ("skillforge.runtime.tool.mcp-input-request.v1".equals(schema)) {
            // MCP params 是标准 JSON 对象；方法、请求身份和响应配对仍由 continuation journal 校验。
            if (!SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits).isObject()) throw invalid();
            return;
        }
        if (com.skillforge.runtime.session.spi.SessionWorkflowWakeups.SIGNAL_SCHEMA.equals(schema)) {
            var value = SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
            if (!value.isObject() || !value.path("kind").isTextual()) throw invalid();
            if ("node-result".equals(value.get("kind").textValue())) {
                if (value.size()!=3 || !value.path("nodeRun").isTextual() || value.get("nodeRun").textValue().isBlank()
                        || !value.path("resultRef").isTextual() || value.get("resultRef").textValue().isBlank()) throw invalid();
                return;
            }
            String field = switch (value.get("kind").textValue()) {
                case "script" -> "taskId";
                case "async-task-timeout" -> "deadlineAt";
                default -> throw invalid();
            };
            if (value.size() != 2 || !value.path(field).isTextual() || value.get(field).textValue().isBlank()) throw invalid();
            if (field.equals("deadlineAt")) {
                try { java.time.Instant.parse(value.get(field).textValue()); }
                catch (java.time.DateTimeException malformed) { throw invalid(); }
            }
            // 此处只注册载荷形状；系统身份与原等待来源仍由恢复 journal 核验。
            return;
        }
        if (com.skillforge.runtime.session.spi.SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA.equals(schema)) {
            var value = SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
            if (!value.isObject() || value.size() != 2) throw invalid();
            if ("completed".equals(value.path("status").asText()) && value.path("result").isObject()) return;
            if ("failed".equals(value.path("status").asText()) && value.path("reason_code").isTextual()
                    && value.get("reason_code").textValue().matches("[a-z][a-z0-9_]{0,127}")) return;
            throw invalid();
        }
        if ("skillforge.runtime.plan-review-request.v1".equals(schema)) {
            var value = SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
            if (!value.isObject() || value.size() != 3
                    || !value.path("plan").isTextual() || value.path("plan").textValue().isBlank()
                    || !value.path("title").isTextual() || value.path("title").textValue().isBlank()
                    || !value.path("options").isArray() || value.path("options").size() != 2
                    || !value.path("options").path(0).isTextual()
                    || !"approve".equals(value.path("options").path(0).textValue())
                    || !value.path("options").path(1).isTextual()
                    || !"keep_planning".equals(value.path("options").path(1).textValue())) throw invalid();
            return;
        }
        if (PLAN_REVIEW_RESPONSE_SCHEMA.equals(schema)) {
            var value = SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
            if (!value.isObject() || value.size() != 3
                    || !positiveDecimalRevision(value.path("checkpointRevision"))
                    || !value.path("decision").isTextual() || !value.path("feedback").isTextual()
                    || !Set.of("approve", "keep_planning").contains(value.path("decision").textValue())) throw invalid();
            return;
        }
        if ("skillforge.runtime.workflow.engine-result.v1".equals(schema)) {
            var value = SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
            // 导出字段和业务 schema 由冻结 Workflow 契约约束；公共 registry 不强制导出 messages。
            if (!value.isObject()) { throw invalid(); }
            if (value.has("messages")) {
                if (!value.get("messages").isArray()) { throw invalid(); }
                for (var message : value.get("messages")) { if (!message.isObject()) { throw invalid(); } }
            }
            return;
        }
        if (SessionPreparationContent.SCHEMA.equals(schema)) {
            context.validate(payload, limits);
            return;
        }
        if (KNOWLEDGE_CHUNK_SCHEMA.equals(schema) || "skillforge.runtime.knowledge.document-slice.v1".equals(schema)) {
            // 知识适配器正文是外部 JSON；Unicode 转义与短转义等价，不属于 Session 私有 wire。
            var value = SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
            if (!value.isObject() || !value.path("text").isTextual()) {
                throw invalid();
            }
            return;
        }
        if (KNOWLEDGE_LINK_SCHEMA.equals(schema)) {
            var value = SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
            if (!value.isObject() || !value.path("url").isTextual() || !value.path("contentType").isTextual() || !value.path("text").isTextual()) throw invalid();
            return;
        }
        if (ModelDecisionProjectionPolicy.TEXT_SCHEMA.equals(schema)) {
            if (!(RuntimeJson.parseCanonical(payload.canonicalJson(), limits) instanceof String)) {
                throw invalid();
            }
            return;
        }
        if (ModelDecisionProjectionPolicy.JSON_SCHEMA.equals(schema)) {
            // 供应商 JSON 对象可含标准 number；不得用 Session 私有 wire 提前拒绝。
            var value = SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
            if (!value.isObject()) {
                throw invalid();
            }
            return;
        }
        if (ModelDecisionProjectionPolicy.DECISION_SCHEMA.equals(schema)) {
            if (ModelDecisionProjectionPolicy.SINGLE_CONTROL_REQUIRED.equals(payload)
                    || ModelDecisionProjectionPolicy.TOOL_NOT_AVAILABLE.equals(payload)
                    || ModelDecisionProjectionPolicy.TOOL_ARGUMENTS_INVALID_JSON.equals(payload)
                    || ModelDecisionProjectionPolicy.TOOL_ARGUMENTS_NOT_OBJECT.equals(payload)
                    || ModelDecisionProjectionPolicy.CONTROL_ARGUMENTS_FIELDS_INVALID.equals(payload)
                    || ModelDecisionProjectionPolicy.CONTROL_COMMENTARY_INVALID.equals(payload)
                    || ModelDecisionProjectionPolicy.CONTROL_ARGUMENTS_INVALID.equals(payload)
                    || ModelDecisionProjectionPolicy.PLAN_TASK_SELF_REPLACEMENT.equals(payload)) return;
            if ("{}".equals(payload.canonicalJson())) {
                return;
            }
            var decoded = new AgentDecisionDecoder(limits).decodeSnapshot(payload);
            if (!(decoded instanceof AgentDecisionDecoder.Decoded)
                    && !(decoded instanceof AgentDecisionDecoder.DecodedBatch)) {
                throw invalid();
            }
            return;
        }
        throw invalid();
    }

    private static boolean positiveDecimalRevision(JsonNode value) {
        if (!value.isTextual()) return false;
        String text = value.textValue();
        try {
            long revision = Long.parseLong(text);
            return revision > 0 && Long.toString(revision).equals(text);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("session_runtime_payload_invalid");
    }
}

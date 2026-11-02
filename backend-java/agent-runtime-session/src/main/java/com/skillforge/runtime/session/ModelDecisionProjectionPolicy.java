package com.skillforge.runtime.session;

import com.skillforge.runtime.json.RuntimeJson;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.spi.SessionModelProjections.Policy;
import java.util.Objects;

/** 将最终模型内容投影为封闭决策；不证明原attempt、策略授权或SessionLog提交。 */
public final class ModelDecisionProjectionPolicy {
    public static final String VERSION = "skillforge.runtime.model-decision-projection.v8";
    public static final String TEXT_SCHEMA = "skillforge.runtime.model.text.v1";
    public static final String JSON_SCHEMA = "skillforge.runtime.model.json.v1";
    public static final String DECISION_SCHEMA = "skillforge.runtime.agent-decision.v1";
    private static final TypedPayload INVALID = new TypedPayload(DECISION_SCHEMA, "{}");
    /** 仅保存治理层已知的封闭拒绝原因，不带供应商调用参数、正文或可执行动作。 */
    static final TypedPayload SINGLE_CONTROL_REQUIRED = new TypedPayload(DECISION_SCHEMA,
            "{\"reason\":\"CONTROL_ACTION_REQUIRES_SINGLE_CALL\",\"type\":\"decision_rejected\"}");
    static final TypedPayload TOOL_NOT_AVAILABLE = new TypedPayload(DECISION_SCHEMA,
            "{\"reason\":\"TOOL_NOT_AVAILABLE\",\"type\":\"decision_rejected\"}");
    static final TypedPayload TOOL_ARGUMENTS_INVALID_JSON = new TypedPayload(DECISION_SCHEMA,
            "{\"reason\":\"TOOL_ARGUMENTS_INVALID_JSON\",\"type\":\"decision_rejected\"}");
    static final TypedPayload TOOL_ARGUMENTS_NOT_OBJECT = new TypedPayload(DECISION_SCHEMA,
            "{\"reason\":\"TOOL_ARGUMENTS_NOT_OBJECT\",\"type\":\"decision_rejected\"}");
    static final TypedPayload CONTROL_ARGUMENTS_FIELDS_INVALID = new TypedPayload(DECISION_SCHEMA,
            "{\"reason\":\"CONTROL_ARGUMENTS_FIELDS_INVALID\",\"type\":\"decision_rejected\"}");
    static final TypedPayload CONTROL_COMMENTARY_INVALID = new TypedPayload(DECISION_SCHEMA,
            "{\"reason\":\"CONTROL_COMMENTARY_INVALID\",\"type\":\"decision_rejected\"}");
    static final TypedPayload CONTROL_ARGUMENTS_INVALID = new TypedPayload(DECISION_SCHEMA,
            "{\"reason\":\"CONTROL_ARGUMENTS_INVALID\",\"type\":\"decision_rejected\"}");
    static final TypedPayload PLAN_TASK_SELF_REPLACEMENT = new TypedPayload(DECISION_SCHEMA,
            "{\"reason\":\"PLAN_TASK_SELF_REPLACEMENT\",\"type\":\"decision_rejected\"}");
    private final Policy policy;
    private final CodecLimits parser;
    private final AgentDecisionDecoder decoder;

    /** 限额是需随投影策略来源冻结的显式配置，不从模型响应推断预算。 */
    public ModelDecisionProjectionPolicy(Policy policy) {
        this.policy = Objects.requireNonNull(policy);
        if (!VERSION.equals(policy.version()) || policy.maxCodeUnits() < 2) {
            throw new IllegalArgumentException("model_decision_projection_policy_unsupported");
        }
        this.parser = new CodecLimits(policy.maxCodeUnits(), policy.maxNodes(), policy.maxDepth());
        this.decoder = new AgentDecisionDecoder(parser);
    }

    public Policy policy() { return policy; }

    /** 重编码合法动作与封闭拒绝事实；其他失败和超限输出固定非法决策，原文不进入安全历史。 */
    public TypedPayload project(TypedPayload privateOutput) {
        if (privateOutput == null) return INVALID;
        try {
            var raw = privateOutput.canonicalJson();
            if (raw.length() > parser.maxCodeUnits()) return INVALID;
            final String wire;
            if (TEXT_SCHEMA.equals(privateOutput.schemaId())) {
                var value = RuntimeJson.parseCanonical(raw, parser);
                if (!(value instanceof String text)) return INVALID;
                // 正文不承载内部动作协议；即使文字看似JSON也不得升级为执行意图。
                return text.codePointCount(0,text.length())<=policy.maxOutputCharacters()?privateOutput:INVALID;
            } else if (JSON_SCHEMA.equals(privateOutput.schemaId())) {
                // Decoder只接受完整七动作对象；不抽取片段或移除未知reasoning字段。
                wire = raw;
            } else return INVALID;
            var decoded = decoder.decodeWire(wire);
            if (decoded instanceof AgentDecisionDecoder.Failure failure
                    && failure.reason() == AgentDecisionDecoder.Reason.CONTROL_ACTION_REQUIRES_SINGLE_CALL) {
                return SINGLE_CONTROL_REQUIRED.canonicalJson().length() <= policy.maxOutputCharacters()
                        ? SINGLE_CONTROL_REQUIRED : INVALID;
            }
            if (decoded instanceof AgentDecisionDecoder.Failure failure
                    && failure.reason() == AgentDecisionDecoder.Reason.TOOL_NOT_AVAILABLE) {
                return TOOL_NOT_AVAILABLE.canonicalJson().length() <= policy.maxOutputCharacters()
                        ? TOOL_NOT_AVAILABLE : INVALID;
            }
            if (decoded instanceof AgentDecisionDecoder.Failure failure
                    && failure.reason() == AgentDecisionDecoder.Reason.TOOL_ARGUMENTS_INVALID_JSON) {
                return TOOL_ARGUMENTS_INVALID_JSON.canonicalJson().length() <= policy.maxOutputCharacters()
                        ? TOOL_ARGUMENTS_INVALID_JSON : INVALID;
            }
            if (decoded instanceof AgentDecisionDecoder.Failure failure
                    && failure.reason() == AgentDecisionDecoder.Reason.TOOL_ARGUMENTS_NOT_OBJECT) {
                return TOOL_ARGUMENTS_NOT_OBJECT.canonicalJson().length() <= policy.maxOutputCharacters()
                        ? TOOL_ARGUMENTS_NOT_OBJECT : INVALID;
            }
            if (decoded instanceof AgentDecisionDecoder.Failure failure && failure.reason() == AgentDecisionDecoder.Reason.CONTROL_ARGUMENTS_FIELDS_INVALID)
                return CONTROL_ARGUMENTS_FIELDS_INVALID.canonicalJson().length() <= policy.maxOutputCharacters() ? CONTROL_ARGUMENTS_FIELDS_INVALID : INVALID;
            if (decoded instanceof AgentDecisionDecoder.Failure failure && failure.reason() == AgentDecisionDecoder.Reason.CONTROL_COMMENTARY_INVALID)
                return CONTROL_COMMENTARY_INVALID.canonicalJson().length() <= policy.maxOutputCharacters() ? CONTROL_COMMENTARY_INVALID : INVALID;
            if (decoded instanceof AgentDecisionDecoder.Failure failure && failure.reason() == AgentDecisionDecoder.Reason.CONTROL_ARGUMENTS_INVALID)
                return CONTROL_ARGUMENTS_INVALID.canonicalJson().length() <= policy.maxOutputCharacters() ? CONTROL_ARGUMENTS_INVALID : INVALID;
            if (decoded instanceof AgentDecisionDecoder.Failure failure && failure.reason() == AgentDecisionDecoder.Reason.PLAN_TASK_SELF_REPLACEMENT)
                return PLAN_TASK_SELF_REPLACEMENT.canonicalJson().length() <= policy.maxOutputCharacters() ? PLAN_TASK_SELF_REPLACEMENT : INVALID;
            if (!accepted(decoded)) return INVALID;
            var output = decoder.encodeResult(decoded);
            var json = output.canonicalJson();
            return json.codePointCount(0, json.length()) <= policy.maxOutputCharacters() ? output : INVALID;
        } catch (IllegalArgumentException invalid) {
            return INVALID;
        }
    }

    /** Registry只接纳规范决策和已知封闭失败文档，不接受任意同schema载荷。 */
    public void validateProjection(TypedPayload projection) {
        if(projection!=null&&TEXT_SCHEMA.equals(projection.schemaId())) {
            try {
                var value=RuntimeJson.parseCanonical(projection.canonicalJson(),parser);
                if(value instanceof String text&&text.codePointCount(0,text.length())<=policy.maxOutputCharacters())return;
            } catch(IllegalArgumentException invalid) {throw new IllegalArgumentException("model_decision_projection_invalid");}
            throw new IllegalArgumentException("model_decision_projection_invalid");
        }
        if (projection == null || projection.canonicalJson().codePointCount(0, projection.canonicalJson().length()) > policy.maxOutputCharacters()
                || !INVALID.equals(projection) && !SINGLE_CONTROL_REQUIRED.equals(projection) && !TOOL_NOT_AVAILABLE.equals(projection)
                    && !TOOL_ARGUMENTS_INVALID_JSON.equals(projection) && !TOOL_ARGUMENTS_NOT_OBJECT.equals(projection)
                    && !CONTROL_ARGUMENTS_FIELDS_INVALID.equals(projection)
                    && !CONTROL_COMMENTARY_INVALID.equals(projection)
                    && !CONTROL_ARGUMENTS_INVALID.equals(projection)
                    && !PLAN_TASK_SELF_REPLACEMENT.equals(projection)
                    && !accepted(decoder.decodeSnapshot(projection))) {
            throw new IllegalArgumentException("model_decision_projection_invalid");
        }
    }

    private static boolean accepted(AgentDecisionDecoder.Result result) {
        if (result instanceof AgentDecisionDecoder.Decoded decoded
                && (decoded.action() instanceof com.skillforge.runtime.core.AgentAction.Respond
                    || decoded.action() instanceof com.skillforge.runtime.core.AgentAction.AskUser)) return true;
        // 公开进度是可选的同轮输出；缺失时仍接纳合法动作，不合成或另发摘要调用。
        return (result instanceof AgentDecisionDecoder.Decoded || result instanceof AgentDecisionDecoder.DecodedBatch)
                && result.commentary().map(text -> !text.isBlank()
                    && text.codePointCount(0, text.length()) <= 600).orElse(true);
    }

}

package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Objects;

/** 执行拥有者提交的安全操作事实；不承载权限、请求正文或另一份执行状态。 */
public final class ExecutionObservation {
    public static final String EVENT_TYPE = "execution.observation";
    public static final String SCHEMA = "skillforge.runtime.execution.observation.v1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);

    private ExecutionObservation() { }

    public enum Kind { ENTRY, AGENT, STEP, LLM, TOOL, RETRIEVAL, MEMORY, GUARDRAIL, COMPACTION, WORKFLOW }
    public enum Status { REGISTERED, SUCCEEDED, FAILED, UNKNOWN, WAITING, REJECTED, CANCELLED, TERMINATED }
    public enum Stage { MODEL_INPUT, MODEL_OUTPUT, TOOL_INPUT, TOOL_OUTPUT, AGENT_OUTPUT }

    /**
     * 明确记录真实父操作与本轮原 MODEL Action；重试保留 operationId，attemptId 分别表达。
     * elapsedNanos 仅接受执行边界实测值；持久提交时间不属于 Provider 耗时。
     */
    public record Fact(
            String operationId, String parentOperationId, Kind kind, String name, Status status,
            String runId, String actionId, String attemptId, String stepId, String iteration,
            String inputId, String rootInputId, String traceId, String spanId,
            Stage stage, String reasonCode, Long elapsedNanos) {
        public Fact {
            text(operationId); text(parentOperationId); Objects.requireNonNull(kind); text(name);
            Objects.requireNonNull(status); text(runId); text(inputId); text(rootInputId); optionalText(traceId); optionalText(spanId);
            optionalText(actionId); optionalText(attemptId); optionalText(stepId); optionalText(iteration);
            optionalText(reasonCode);
            if ((traceId == null) != (spanId == null)) throw invalid();
            if (iteration != null && !iteration.matches("[1-9][0-9]*")
                    || elapsedNanos != null && elapsedNanos < 0
                    || (kind == Kind.GUARDRAIL) != (stage != null)) throw invalid();
        }
        @Override public String toString() { return "ExecutionObservation[kind=" + kind + ",status=" + status + "]"; }
    }

    /** 同一操作结果重建得到同一事件身份；本方法不会读取时钟或触发外部调用。 */
    static ExecutionStore.Event event(Fact fact, CodecLimits limits) {
        Objects.requireNonNull(fact);
        var id = new EventId("execution-observation:" + AgentExecutionCodec.digest(fact.operationId()
                + "\0" + Objects.toString(fact.attemptId(), "") + "\0" + Objects.toString(fact.stage(), "")
                + "\0" + fact.status().name()));
        return new ExecutionStore.Event(id, EVENT_TYPE, encode(fact, limits));
    }

    /** 观测事实在所属边界统一编码，调用方不维护字段路径或解释私有材料。 */
    static TypedPayload encode(Fact fact, CodecLimits limits) {
        Objects.requireNonNull(fact); Objects.requireNonNull(limits);
        String value = SessionRuntimePayloadJson.write(fact);
        SessionRuntimePayloadJson.parse(value, limits);
        return new TypedPayload(SCHEMA, value);
    }

    /** 严格读取当前观测协议；缺失身份、非法状态和未知字段均不能变成默认成功。 */
    static Fact decode(TypedPayload payload, CodecLimits limits) {
        Objects.requireNonNull(payload); Objects.requireNonNull(limits);
        if (!SCHEMA.equals(payload.schemaId())) throw invalid();
        var value = SessionRuntimePayloadJson.parse(payload.canonicalJson(), limits);
        try {
            var fact = JSON.treeToValue(value, Fact.class);
            if (!encode(fact, limits).equals(payload)) throw invalid();
            return fact;
        }
        catch (Exception rejected) { throw invalid(); }
    }

    /** 宿主读取边界只接收解析预算数值，不依赖Core内部类型。 */
    public static Fact decode(TypedPayload payload, int maxCodeUnits, int maxNodes, int maxDepth) {
        return decode(payload, new CodecLimits(maxCodeUnits, maxNodes, maxDepth));
    }

    /** 宿主受控夹具与生产者使用同一协议编码，不公开Core类型。 */
    public static TypedPayload encode(Fact fact, int maxCodeUnits, int maxNodes, int maxDepth) {
        return encode(fact, new CodecLimits(maxCodeUnits, maxNodes, maxDepth));
    }

    public static String agentId(String runId) { text(runId); return "agent:" + runId; }
    public static String stepId(String modelActionId) { text(modelActionId); return "step:" + modelActionId; }
    public static String retrievalId(String actionId) { text(actionId); return actionId + ":retrieval"; }

    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private static void optionalText(String value) { if (value != null) text(value); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_observation_invalid"); }
}

package com.skillforge.runtime.session;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.PlanCommand;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Objects;
import java.util.Optional;

/** 本地Plan命令与实际业务回执的显式协议；不创建Provider Attempt、预算票据或旧journal。 */
public final class SessionExecutionPlans {
    public static final String REQUEST_SCHEMA = "skillforge.runtime.execution.plan-request.v1";
    public static final String RESULT_SCHEMA = "skillforge.runtime.execution.plan-result.v1";
    public static final String RESPONSE_SCHEMA = "skillforge.runtime.execution.plan-response.v1";
    private final AgentExecutionCodec codec;
    private final AgentDecisionDecoder decisions;

    /** 与执行主链共用有界解析配置，冻结的命令不从当前业务状态重建。 */
    public SessionExecutionPlans(CodecLimits limits) { codec = new AgentExecutionCodec(Objects.requireNonNull(limits)); decisions = new AgentDecisionDecoder(limits); }

    /** 原模型控制调用、完整业务命令及本次真实模型已包含的补充序号。 */
    public record Request(PlanCommand command, String callId, String wireName, TypedPayload arguments, long supplementalIncludedThrough) {
        public Request {
            Objects.requireNonNull(command); text(callId); text(wireName); Objects.requireNonNull(arguments);
            if (!"application/json".equals(arguments.schemaId()) || supplementalIncludedThrough < 0) throw invalid();
        }
        @Override public String toString() { return "ExecutionPlanRequest[REDACTED]"; }
    }

    /** 所属PlanPort真实value与真实等待身份；领域拒绝仍是已执行的本地控制回执。 */
    public record Result(TypedPayload value, Optional<ExecutionStore.PlanWait> waiting, PlanPort.State state) {
        public Result { Objects.requireNonNull(value); Objects.requireNonNull(waiting); Objects.requireNonNull(state); if (!"application/json".equals(value.schemaId())) throw invalid(); }
        @Override public String toString() { return "ExecutionPlanResult[REDACTED]"; }
    }

    /** 固定字段包含原命令快照，不从native参数逆向猜任务图或默认revision。 */
    public TypedPayload encodeRequest(Request request) {
        Objects.requireNonNull(request);
        if (!codec.parse(request.arguments(), "application/json").isObject()) throw invalid();
        var value = AgentExecutionCodec.object(); value.set("command", AgentExecutionCodec.payload(decisions.encode(new AgentAction.Plan(request.command()))));
        value.put("callId", request.callId()); value.put("wireName", request.wireName()); value.set("arguments", AgentExecutionCodec.payload(request.arguments()));
        value.put("supplementalIncludedThrough", Long.toString(request.supplementalIncludedThrough())); return codec.encode(REQUEST_SCHEMA, value);
    }

    /** 精确恢复同版本协议，额外字段或非规范原命令明确拒绝。 */
    public Request decodeRequest(TypedPayload payload) {
        var value = codec.parse(payload, REQUEST_SCHEMA); AgentExecutionCodec.fields(value, "command", "callId", "wireName", "arguments", "supplementalIncludedThrough");
        var decoded = decisions.decodeSnapshot(AgentExecutionCodec.readPayload(value.get("command")));
        if (!(decoded instanceof AgentDecisionDecoder.Decoded single) || single.commentary().isPresent() || !(single.action() instanceof AgentAction.Plan plan)) throw invalid();
        var request = new Request(plan.command(), AgentExecutionCodec.string(value, "callId"), AgentExecutionCodec.string(value, "wireName"),
                AgentExecutionCodec.readPayload(value.get("arguments")), AgentExecutionCodec.number(value, "supplementalIncludedThrough"));
        if (!encodeRequest(request).equals(payload)) throw invalid(); return request;
    }

    /** 本地业务与Session context同事务保存这份原回执，不能把等待投影当原结果。 */
    public TypedPayload encodeResult(Result result) { return encodeResult(result, RESULT_SCHEMA); }
    public Result decodeResult(TypedPayload payload) { return decodeResult(payload, RESULT_SCHEMA); }
    /** 等待解除由Host核实真实领域事实；此编码只保存该次实际系统响应。 */
    public TypedPayload encodeResponse(TypedPayload value, PlanPort.State state) { return encodeResult(new Result(value, Optional.empty(), state), RESPONSE_SCHEMA); }
    public Result decodeResponse(TypedPayload payload) { var result = decodeResult(payload, RESPONSE_SCHEMA); if (result.waiting().isPresent()) throw invalid(); return result; }

    /** 业务反馈保留自己的JSON契约，内部依赖只保存精确身份。 */
    private TypedPayload encodeResult(Result result, String schema) {
        codec.parse(result.value(), "application/json"); var value = AgentExecutionCodec.object(); value.set("value", AgentExecutionCodec.payload(result.value()));
        if (result.waiting().isEmpty()) value.putNull("waiting");
        else { var dependency = result.waiting().orElseThrow(); var waiting = value.putObject("waiting"); waiting.put("planId", dependency.planId()); waiting.put("planRevision", Long.toString(dependency.planRevision())); waiting.put("businessWaitId", dependency.businessWaitId()); }
        var state = value.putObject("state"); state.put("exists", result.state().exists()); state.put("executionApproved", result.state().executionApproved()); state.put("complete", result.state().complete());
        state.put("inherited", result.state().inherited()); if (result.state().activeTaskId().isPresent()) state.put("activeTaskId", result.state().activeTaskId().orElseThrow()); else state.putNull("activeTaskId");
        return codec.encode(schema, value);
    }

    /** 不补缺失的Plan revision，也不把无等待响应改成新的业务等待。 */
    private Result decodeResult(TypedPayload payload, String schema) {
        var value = codec.parse(payload, schema); AgentExecutionCodec.fields(value, "value", "waiting", "state"); Optional<ExecutionStore.PlanWait> dependency = Optional.empty();
        if (!value.get("waiting").isNull()) {
            var waiting = value.get("waiting"); AgentExecutionCodec.fields(waiting, "planId", "planRevision", "businessWaitId");
            dependency = Optional.of(new ExecutionStore.PlanWait(AgentExecutionCodec.string(waiting, "planId"), AgentExecutionCodec.number(waiting, "planRevision"), AgentExecutionCodec.string(waiting, "businessWaitId")));
        }
        var state = value.get("state"); AgentExecutionCodec.fields(state, "exists", "executionApproved", "complete", "inherited", "activeTaskId");
        var result = new Result(AgentExecutionCodec.readPayload(value.get("value")), dependency, new PlanPort.State(bool(state,"exists"), bool(state,"executionApproved"), bool(state,"complete"),
                state.get("activeTaskId").isNull() ? Optional.empty() : Optional.of(AgentExecutionCodec.string(state,"activeTaskId")), bool(state,"inherited")));
        if (!encodeResult(result, schema).equals(payload)) throw invalid(); return result;
    }
    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private static boolean bool(com.fasterxml.jackson.databind.JsonNode value, String name) { if (!value.path(name).isBoolean()) throw invalid(); return value.get(name).booleanValue(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_plan_invalid"); }
}

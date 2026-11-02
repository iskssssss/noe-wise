package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.json.CodecLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 原终态事件的只读安全事实；读取不授予执行、账本或配置访问权。 */
public final class ExecutionTerminationObservation {
    public static final String BUDGET_FAILURE_SCHEMA = "skillforge.runtime.execution.closing-budget-failure.v1";
    public static final String BUDGET_FAILURE_CODE = "execution_output_closing_budget_insufficient";
    private ExecutionTerminationObservation() { }

    public record Termination(String status, String reason, Optional<ExecutionBudgetPort.ClosingFailure> budgetFailure) { }

    /** 旧终态缺少新字段时保持未记录；存在但损坏的预算事实必须拒绝。 */
    public static Optional<Termination> read(String eventType, TypedPayload payload) {
        if (!AgentExecutionProgram.COMPLETED_EVENT.equals(eventType)) return Optional.empty();
        // 原持久终态可能携带大正文/证据；读取安全字段不能另加比原写入契约更小的内容门槛。
        int length = Math.max(1, payload.canonicalJson().length());
        var value = new AgentExecutionCodec(new CodecLimits(length, length, 128)).parse(payload, AgentExecutionProgram.COMPLETED_SCHEMA);
        String status = AgentExecutionCodec.string(value, "status");
        String reason = AgentExecutionCodec.string(value, "reason");
        var failure = !value.has("budget_failure") || value.get("budget_failure").isNull()
                ? Optional.<ExecutionBudgetPort.ClosingFailure>empty() : Optional.of(decode(value.get("budget_failure")));
        if (failure.isPresent() && !BUDGET_FAILURE_CODE.equals(reason)) throw invalid();
        return Optional.of(new Termination(status, reason, failure));
    }

    static ObjectNode encode(ExecutionBudgetPort.ClosingFailure failure) {
        var value = AgentExecutionCodec.object(); value.put("schema_id", BUDGET_FAILURE_SCHEMA); value.put("code", BUDGET_FAILURE_CODE);
        value.put("phase", failure.phase().name()); var items = value.putArray("shortfalls");
        for (var fact : failure.shortfalls()) {
            var item = items.addObject(); item.put("scope", fact.scope().name()); item.put("dimension", fact.dimension()); item.put("unit", fact.unit());
            item.put("required", Long.toString(fact.required())); item.put("available", Long.toString(fact.available()));
            item.put("deficit", Long.toString(fact.deficit())); item.put("consumed", Long.toString(fact.consumed()));
            item.put("reserved", Long.toString(fact.reserved())); item.put("limit", Long.toString(fact.limit()));
        }
        var actual = failure.timeCalculation(); var time = value.putObject("time_calculation");
        time.put("model_calls", Integer.toString(actual.modelCalls())); time.put("model_timeout_ms", Long.toString(actual.modelTimeoutMillis()));
        time.put("model_window_ms", Long.toString(actual.modelWindowMillis())); time.put("local_processing_ms", Long.toString(actual.localProcessingMillis()));
        time.put("required_ms", Long.toString(actual.requiredMillis())); return value;
    }

    private static ExecutionBudgetPort.ClosingFailure decode(JsonNode value) {
        AgentExecutionCodec.fields(value,"schema_id","code","phase","shortfalls","time_calculation");
        if (!BUDGET_FAILURE_SCHEMA.equals(AgentExecutionCodec.string(value,"schema_id"))
                || !BUDGET_FAILURE_CODE.equals(AgentExecutionCodec.string(value,"code"))
                || !value.get("shortfalls").isArray() || value.get("shortfalls").size() > 32) throw invalid();
        var items = new ArrayList<ExecutionBudgetPort.ClosingShortfall>();
        for (var item : value.get("shortfalls")) {
            AgentExecutionCodec.fields(item,"scope","dimension","unit","required","available","deficit","consumed","reserved","limit");
            var fact = new ExecutionBudgetPort.ClosingShortfall(ExecutionBudgetPort.BalanceScope.valueOf(AgentExecutionCodec.string(item,"scope")),
                    AgentExecutionCodec.string(item,"dimension"),number(item,"required"),number(item,"available"),number(item,"deficit"),
                    number(item,"consumed"),number(item,"reserved"),number(item,"limit"));
            if (!fact.unit().equals(AgentExecutionCodec.string(item,"unit"))) throw invalid(); items.add(fact);
        }
        var time = value.get("time_calculation"); AgentExecutionCodec.fields(time,"model_calls","model_timeout_ms","model_window_ms","local_processing_ms","required_ms");
        var calculation = new ExecutionBudgetPort.ClosingTimeCalculation(Math.toIntExact(number(time,"model_calls")),number(time,"model_timeout_ms"),
                number(time,"model_window_ms"),number(time,"local_processing_ms"),number(time,"required_ms"));
        var result = new ExecutionBudgetPort.ClosingFailure(ExecutionBudgetPort.Phase.valueOf(AgentExecutionCodec.string(value,"phase")),List.copyOf(items),calculation);
        if (!encode(result).equals(value)) throw invalid(); return result;
    }
    private static long number(JsonNode value,String field) { return AgentExecutionCodec.number(value,field); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_termination_observation_invalid"); }
}

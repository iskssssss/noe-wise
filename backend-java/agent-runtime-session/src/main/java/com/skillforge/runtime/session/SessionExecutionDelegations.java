package com.skillforge.runtime.session;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.DelegationCommand;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.json.CodecLimits;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 新执行链委派请求与实际领域回执的纯协议；不持有调用权、不查询或启动子Run。 */
public final class SessionExecutionDelegations {
    public static final String REQUEST_SCHEMA = "skillforge.runtime.execution.delegation-request.v1";
    public static final String RESULT_SCHEMA = "skillforge.runtime.execution.delegation-result.v1";
    public static final String RESPONSE_SCHEMA = "skillforge.runtime.execution.delegation-response.v1";
    private final AgentExecutionCodec codec;
    private final AgentDecisionDecoder decisions;

    public SessionExecutionDelegations(CodecLimits limits) {
        codec = new AgentExecutionCodec(limits); decisions = new AgentDecisionDecoder(limits);
    }

    public record Request(DelegationCommand command, String callId, String wireName) {
        public Request { Objects.requireNonNull(command); text(callId); text(wireName); }
        @Override public String toString() { return "ExecutionDelegationRequest[REDACTED]"; }
    }
    /** 子Run清单按原业务Wait顺序冻结，不从终态文字推导依赖。 */
    public record Dependency(String businessWaitId, List<String> delegationIds, List<RunRef> children) {
        public Dependency {
            text(businessWaitId); delegationIds = List.copyOf(delegationIds); children = List.copyOf(children);
            if (delegationIds.isEmpty() || delegationIds.size() != children.size()
                    || new HashSet<>(delegationIds).size() != delegationIds.size()
                    || new HashSet<>(children).size() != children.size()) throw invalid();
            delegationIds.forEach(SessionExecutionDelegations::text);
        }
    }
    public record Result(TypedPayload value, Optional<Dependency> waiting) {
        public Result { Objects.requireNonNull(value); Objects.requireNonNull(waiting);
            if (!"application/json".equals(value.schemaId())) throw invalid(); }
        @Override public String toString() { return "ExecutionDelegationResult[REDACTED]"; }
    }

    public TypedPayload encodeRequest(Request request) {
        var value = AgentExecutionCodec.object(); value.put("callId", request.callId()); value.put("wireName", request.wireName());
        value.set("command", AgentExecutionCodec.payload(decisions.encode(new AgentAction.Delegate(request.command()))));
        return codec.encode(REQUEST_SCHEMA, value);
    }
    public Request decodeRequest(TypedPayload payload) {
        var value = codec.parse(payload, REQUEST_SCHEMA); AgentExecutionCodec.fields(value, "callId", "wireName", "command");
        var command = decisions.decodeSnapshot(AgentExecutionCodec.readPayload(value.get("command")));
        if (!(command instanceof AgentDecisionDecoder.Decoded decoded) || decoded.commentary().isPresent()
                || !(decoded.action() instanceof AgentAction.Delegate delegate)) throw invalid();
        var result = new Request(delegate.command(), AgentExecutionCodec.string(value, "callId"), AgentExecutionCodec.string(value, "wireName"));
        if (!encodeRequest(result).equals(payload)) throw invalid();
        return result;
    }
    public TypedPayload encodeResult(Result result) { return encodeResult(result, RESULT_SCHEMA); }
    public TypedPayload encodeResponse(TypedPayload value) { return encodeResult(new Result(value, Optional.empty()), RESPONSE_SCHEMA); }
    private TypedPayload encodeResult(Result result, String schema) {
        var value = AgentExecutionCodec.object(); value.set("value", AgentExecutionCodec.payload(result.value()));
        if (result.waiting().isEmpty()) value.putNull("waiting");
        else {
            var dependency = result.waiting().orElseThrow(); var waiting = value.putObject("waiting");
            waiting.put("businessWaitId", dependency.businessWaitId()); var ids = waiting.putArray("delegationIds");
            dependency.delegationIds().forEach(ids::add); var children = waiting.putArray("children");
            for (var child : dependency.children()) { var item = children.addObject(); item.put("tenantId", child.tenantId().value()); item.put("runId", child.runId()); }
        }
        return codec.encode(schema, value);
    }
    public Result decodeResult(TypedPayload payload) { return decodeResult(payload, RESULT_SCHEMA); }
    public TypedPayload decodeResponse(TypedPayload payload) {
        var result = decodeResult(payload, RESPONSE_SCHEMA); if (result.waiting().isPresent()) throw invalid(); return result.value();
    }
    private Result decodeResult(TypedPayload payload, String schema) {
        var value = codec.parse(payload, schema); AgentExecutionCodec.fields(value, "value", "waiting");
        var feedback = AgentExecutionCodec.readPayload(value.get("value"));
        // 同一有界解析器核对领域反馈文档，不能通过嵌套canonicalJson绕过结构配额。
        codec.parse(feedback, "application/json");
        Optional<Dependency> dependency = Optional.empty();
        if (!value.get("waiting").isNull()) {
            var waiting = value.get("waiting"); AgentExecutionCodec.fields(waiting, "businessWaitId", "delegationIds", "children");
            if (!waiting.get("delegationIds").isArray() || !waiting.get("children").isArray()) throw invalid();
            var ids = new ArrayList<String>(); for (var id : waiting.get("delegationIds")) { if (!id.isTextual()) throw invalid(); ids.add(id.textValue()); }
            var children = new ArrayList<RunRef>();
            for (var child : waiting.get("children")) { AgentExecutionCodec.fields(child, "tenantId", "runId");
                children.add(new RunRef(new TenantId(AgentExecutionCodec.string(child, "tenantId")), AgentExecutionCodec.string(child, "runId"))); }
            dependency = Optional.of(new Dependency(AgentExecutionCodec.string(waiting, "businessWaitId"), ids, children));
        }
        var result = new Result(feedback, dependency); if (!encodeResult(result, schema).equals(payload)) throw invalid(); return result;
    }
    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_delegation_invalid"); }
}

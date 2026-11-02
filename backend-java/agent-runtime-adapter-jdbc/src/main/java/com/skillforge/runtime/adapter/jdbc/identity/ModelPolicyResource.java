package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.ModelResolutionPort.ModelResolutionRequest;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** 模型解析与调用的明确授权资源；完整 Run、绑定版本与请求策略封闭在当前 owner 内。 */
public final class ModelPolicyResource {
    public static final String RESOLVE_SCHEMA = "skillforge.runtime.model.resolve-resource.v2";
    public static final String DISPATCH_SCHEMA = "skillforge.runtime.model.dispatch-resource.v2";
    private ModelPolicyResource() { }

    public static TypedPayload resolution(ModelResolutionRequest request) {
        return resource(RESOLVE_SCHEMA, request.runIdentity(), request.binding(), request.requestedStrategy());
    }

    public static TypedPayload dispatch(ModelCall call) {
        return dispatch(call.context().callContext().runIdentity(), call.target().binding(), call.requestedStrategy());
    }

    /** 派发登记前以已准备的真实目标构造授权资源，不伪造ProviderCall或旧permit。 */
    public static TypedPayload dispatch(RunIdentity run, ModelBindingRef binding, RequestedModelStrategy strategy) {
        return resource(DISPATCH_SCHEMA, run, binding, strategy);
    }

    /** 只解析两个固定 schema 的规范字符串数组，不把 DTO 资源存在当成权限事实。 */
    public static Optional<SessionOwner> owner(String action, TypedPayload resource) {
        if (resource == null || !("model.resolve".equals(action) && RESOLVE_SCHEMA.equals(resource.schemaId())
                || ("model.generate".equals(action) || "model.stream".equals(action)) && DISPATCH_SCHEMA.equals(resource.schemaId()))) {
            return Optional.empty();
        }
        try {
            var f = AdmissionResource.flatStrings(resource.canonicalJson(), 21);
            var tenant = new TenantId(f.get(0));
            var owner = OwnerPolicyFields.owner(tenant,f.get(4),f.get(1),f.get(2),f.get(5));
            var trace = new TraceEnvelope(f.get(9), f.get(10), new InputId(f.get(11)),
                    f.get(12).isEmpty() ? Optional.empty() : Optional.of(new EventId(f.get(12))));
            RunPolicyFields.restore(owner, new AgentId(f.get(3)), new InputId(f.get(7)),
                    new RunRef(tenant, f.get(6)), trace, new ResultRef(f.get(8)),f,17);
            new ModelBindingRef(tenant, f.get(13), positive(f.get(14)));
            new RequestedModelStrategy(f.get(15), f.get(16));
            return Optional.of(owner);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    private static TypedPayload resource(String schema, RunIdentity run, ModelBindingRef binding, RequestedModelStrategy strategy) {
        var owner = run.owner();
        var trace = run.trace();
        return new TypedPayload(schema, java.util.stream.Stream.concat(List.of(owner.sessionRef().tenantId().value(), owner.target().kind().name(),
                owner.target().id(), run.agentId().value(), owner.sessionRef().sessionId(),
                OwnerPolicyFields.subject(owner), run.runRef().runId(),
                run.causationInputId().value(), run.causationIdentityRef().value(), trace.traceId(), trace.spanId(),
                trace.rootInputId().value(), trace.causedByEventId().map(EventId::value).orElse(""), binding.bindingId(),
                Long.toString(binding.revision()), strategy.strategyId(), strategy.strategyVersion())
                .stream(), RunPolicyFields.values(run).stream()).map(ModelPolicyResource::quote).collect(Collectors.joining(",", "[", "]")));
    }

    /** 完整模型资源校验后返回真实 Run 与 actor，防止 Team 新发布成员影响冻结执行。 */
    public static Optional<SessionPolicyResource.ExecutionTarget> execution(String action,TypedPayload resource) {
        if(owner(action,resource).isEmpty())return Optional.empty();
        var fields=AdmissionResource.flatStrings(resource.canonicalJson(),21);
        return Optional.of(new SessionPolicyResource.ExecutionTarget(new RunRef(new TenantId(fields.get(0)),fields.get(6)),new AgentId(fields.get(3))));
    }

    private static long positive(String text) {
        long value = Long.parseLong(text);
        if (value <= 0 || !Long.toString(value).equals(text)) throw new IllegalArgumentException("model_resource_number");
        return value;
    }

    private static String quote(String text) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if ("\b\t\n\f\r".indexOf(c) >= 0) out.append('\\').append("btnfr".charAt("\b\t\n\f\r".indexOf(c)));
            else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}

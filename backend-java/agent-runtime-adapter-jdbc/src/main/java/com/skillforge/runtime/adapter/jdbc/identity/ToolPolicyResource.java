package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** tool.invoke 固定资源：完整 Run、精确目标及幂等键，不接受通配动作。 */
public final class ToolPolicyResource {
    public static final String SCHEMA = "skillforge.runtime.tool.invoke-resource.v5";
    private ToolPolicyResource() { }

    public static TypedPayload invocation(ToolCall call) {
        return invocation(call.context().callContext().runIdentity(), call.binding(), call.target(),
                call.idempotencyKey());
    }

    /** continuation 与首次调用使用同一资源身份，响应正文从不参与 IAM 资源匹配。 */
    public static TypedPayload invocation(ToolContinuationCall call) {
        return invocation(call.context().callContext().runIdentity(), call.binding(), call.target(),
                call.idempotencyKey());
    }

    /** 派发登记前只消费原真实绑定和目标构造资源，不伪造外调许可。 */
    public static TypedPayload invocation(RunIdentity run, ToolBinding binding, FrozenToolTarget target,
            IdempotencyKey idempotencyKey) {
        var owner = run.owner();
        var trace = run.trace();
        return new TypedPayload(SCHEMA, java.util.stream.Stream.concat(List.of(owner.sessionRef().tenantId().value(), owner.target().kind().name(),
                owner.target().id(), run.agentId().value(), owner.sessionRef().sessionId(),
                OwnerPolicyFields.subject(owner), run.runRef().runId(),
                run.causationInputId().value(), run.causationIdentityRef().value(), trace.traceId(), trace.spanId(),
                trace.rootInputId().value(), trace.causedByEventId().map(EventId::value).orElse(""),
                binding.providerType().name(), binding.providerRef(), binding.toolName(),
                target.handle().value(),
                target.capability().capabilityId(), target.capability().versionId(), target.deploymentId(), Long.toString(target.deploymentRevision()),
                target.inputSchema().schemaId(), target.inputSchema().canonicalJson(), target.outputSchema().schemaId(), target.outputSchema().canonicalJson(),
                target.effect().name(), Boolean.toString(target.idempotent()), target.timeout().toString(),
                Boolean.toString(target.modelInvocable()),target.confirmation().name(),packed(target.sensitiveFields()),
                packed(target.providerRequirements()),Boolean.toString(target.outputSchemaDeclared()),target.callIdentityPolicy().name(),idempotencyKey.value())
                .stream(), RunPolicyFields.values(run).stream()).map(ToolPolicyResource::quote).collect(Collectors.joining(",", "[", "]")));
    }

    /** 严格验证字段数、类型、规范数字/Duration 和 Tool 类型，不从资源文本构造授权主体。 */
    public static Optional<SessionOwner> owner(String action, TypedPayload resource) {
        if (!"tool.invoke".equals(action) || resource == null || !SCHEMA.equals(resource.schemaId())) return Optional.empty();
        try {
            var f = AdmissionResource.flatStrings(resource.canonicalJson(), 39);
            var tenant = new TenantId(f.get(0));
            var owner = OwnerPolicyFields.owner(tenant,f.get(4),f.get(1),f.get(2),f.get(5));
            var trace = new TraceEnvelope(f.get(9), f.get(10), new InputId(f.get(11)),
                    f.get(12).isEmpty() ? Optional.empty() : Optional.of(new EventId(f.get(12))));
            var run = RunPolicyFields.restore(owner, new AgentId(f.get(3)), new InputId(f.get(7)),
                    new RunRef(tenant, f.get(6)), trace, new ResultRef(f.get(8)),f,35);
            new ToolBinding(ToolBinding.ProviderType.valueOf(f.get(13)), f.get(14), f.get(15));
            if (!booleanText(f.get(26))||!booleanText(f.get(28))||!booleanText(f.get(32)))return Optional.empty();
            var timeout = Duration.parse(f.get(27));
            if (!timeout.toString().equals(f.get(27))) return Optional.empty();
            new FrozenToolTarget(new ToolHandle(f.get(16)), new CapabilityRef(CapabilityType.TOOL, f.get(17), f.get(18)),
                    f.get(19), positive(f.get(20)), new TypedPayload(f.get(21), f.get(22)), new TypedPayload(f.get(23), f.get(24)),
                    ToolEffect.valueOf(f.get(25)), Boolean.parseBoolean(f.get(26)), timeout,
                    Boolean.parseBoolean(f.get(28)),ToolConfirmationPolicy.valueOf(f.get(29)),unpacked(f.get(30)),
                    unpacked(f.get(31)),Boolean.parseBoolean(f.get(32)),ToolCallIdentityPolicy.valueOf(f.get(33)));
            new IdempotencyKey(f.get(34));
            return Optional.of(owner);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    private static long positive(String text) {
        long number = Long.parseLong(text);
        if (number <= 0 || !Long.toString(number).equals(text)) throw new IllegalArgumentException("tool_resource_number");
        return number;
    }

    /** 完整工具资源校验后定位实际冻结 Run，不用当前 Team 列表替换旧执行范围。 */
    public static Optional<SessionPolicyResource.ExecutionTarget> execution(String action,TypedPayload resource) {
        if(owner(action,resource).isEmpty())return Optional.empty();
        var fields=AdmissionResource.flatStrings(resource.canonicalJson(),39);
        return Optional.of(new SessionPolicyResource.ExecutionTarget(new RunRef(new TenantId(fields.get(0)),fields.get(6)),new AgentId(fields.get(3))));
    }

    private static boolean booleanText(String value){return "true".equals(value)||"false".equals(value);}

    /** 集合字段使用长度前缀且先排序，避免分隔符歧义和 Set 迭代顺序影响 IAM 资源。 */
    private static String packed(Set<String> values){
        var result=new StringBuilder();
        values.stream().sorted().forEach(value->result.append(value.length()).append(':').append(value));
        return result.toString();
    }

    private static Set<String> unpacked(String packed){
        var result=new java.util.LinkedHashSet<String>();int index=0;
        while(index<packed.length()){
            int colon=packed.indexOf(':',index);if(colon<=index)throw new IllegalArgumentException("tool_resource_set");
            int length=Integer.parseInt(packed.substring(index,colon));
            int end=Math.addExact(colon+1,length);if(length<=0||end>packed.length())throw new IllegalArgumentException("tool_resource_set");
            String value=packed.substring(colon+1,end);if(!result.add(value))throw new IllegalArgumentException("tool_resource_set");
            index=end;
        }
        return Set.copyOf(result);
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

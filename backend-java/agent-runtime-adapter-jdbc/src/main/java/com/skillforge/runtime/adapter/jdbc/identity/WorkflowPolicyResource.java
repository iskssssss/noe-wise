package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/** 五个明确 Workflow IAM 资源；定长字符串数组绑定完整 Run/版本化目标及操作参数。 */
public final class WorkflowPolicyResource {
    private WorkflowPolicyResource() { }
    public static TypedPayload dispatch(WorkflowDispatch call) {
        var fields = common(call.context(), call.snapshot(), call.target(), call.executionRef());
        fields.addAll(List.of(call.parameters().schemaId(), call.parameters().canonicalJson(), evidenceDigest(call.evidenceRefs()), call.idempotencyKey().value()));
        return resource("dispatch", fields);
    }
    public static TypedPayload resume(WorkflowResume call) {
        var fields = common(call.context(), call.snapshot(), call.target(), call.executionRef());
        fields.addAll(List.of(call.suspendRef(), call.actionKey(), call.response().schemaId(), call.response().canonicalJson(), Long.toString(call.expectedRevision()), call.idempotencyKey().value()));
        return resource("resume", fields);
    }
    public static TypedPayload recovery(WorkflowResultQuery call) {
        var fields = common(call.context(), call.snapshot(), call.target(), call.executionRef());
        fields.add(call.context().dispatch().attemptId());
        return resource("recover", fields);
    }
    /** IAM候选来自实际准备值；不为尚未登记的派发伪造Attempt或Provider调用上下文。 */
    public static TypedPayload dispatchResource(RunIdentity run,CapabilitySnapshotRef snapshot,FrozenWorkflowTarget target,ExecutionRef execution,
            TypedPayload parameters,List<EvidenceRef> evidenceRefs,ActionRef action,ResultRef request,String fingerprint) {
        var fields=prepared(run,snapshot,target,execution,action,request,fingerprint);
        fields.addAll(List.of(parameters.schemaId(),parameters.canonicalJson(),evidenceDigest(evidenceRefs),action.actionId()));return resource("dispatch",fields);
    }
    public static TypedPayload resumeResource(RunIdentity run,CapabilitySnapshotRef snapshot,FrozenWorkflowTarget target,ExecutionRef execution,
            String suspendRef,String actionKey,long expectedRevision,TypedPayload response,ActionRef action,ResultRef request,String fingerprint) {
        var fields=prepared(run,snapshot,target,execution,action,request,fingerprint);
        fields.addAll(List.of(suspendRef,actionKey,response.schemaId(),response.canonicalJson(),Long.toString(expectedRevision),action.actionId()));return resource("resume",fields);
    }
    public static TypedPayload cancelResource(RunIdentity run,CapabilitySnapshotRef snapshot,FrozenWorkflowTarget target,ExecutionRef execution,
            String actionKey,long expectedRevision,String reasonCode,ActionRef action,ResultRef request,String fingerprint) {
        var fields=prepared(run,snapshot,target,execution,action,request,fingerprint);
        fields.addAll(List.of(actionKey,Long.toString(expectedRevision),reasonCode,action.actionId()));return resource("cancel",fields);
    }
    public static TypedPayload recoveryResource(RunIdentity run,CapabilitySnapshotRef snapshot,FrozenWorkflowTarget target,ExecutionRef execution,
            ActionRef action,ResultRef request,String fingerprint) {
        var fields=prepared(run,snapshot,target,execution,action,request,fingerprint);fields.add(action.actionId());return resource("recover",fields);
    }
    public static TypedPayload cancel(WorkflowCancel call) {
        var fields = common(call.context(), call.snapshot(), call.target(), call.executionRef());
        fields.addAll(List.of(call.actionKey(), Long.toString(call.expectedRevision()), call.reasonCode(), call.idempotencyKey().value()));
        return resource("cancel", fields);
    }
    public static TypedPayload query(WorkflowQuery query, AgentId actualAgentId) {
        return queryResource(query, actualAgentId, "query");
    }
    /** Worker 执行是独立当前写授权，不复用Provider调用许可。 */
    public static TypedPayload execution(WorkflowQuery query, AgentId actualAgentId) {
        return queryResource(query, actualAgentId, "execute");
    }
    private static TypedPayload queryResource(
            WorkflowQuery query, AgentId actualAgentId, String kind) {
        Objects.requireNonNull(actualAgentId);
        var owner = query.identity().owner();
        return resource(kind, List.of(owner.sessionRef().tenantId().value(), owner.target().kind().name(),
                owner.target().id(), actualAgentId.value(), owner.sessionRef().sessionId(),
                OwnerPolicyFields.subject(owner), query.executionRef().executionId()));
    }

    /** 只识别显式动作与同名schema，规范字段通过API值对象校验；不以资源文本签发身份。 */
    public static Optional<SessionOwner> owner(String action, TypedPayload payload) {
        int count = switch (action) {
            case "workflow.dispatch", "workflow.cancel" -> 35;
            case "workflow.resume" -> 37;
            case "workflow.recover" -> 32;
            case "workflow.query", "workflow.execute" -> 7;
            default -> 0;
        };
        if (count == 0 || payload == null || !("skillforge.runtime." + action + "-resource.v4").equals(payload.schemaId())) return Optional.empty();
        try {
            var f = AdmissionResource.flatStrings(payload.canonicalJson(), count);
            var tenant = new TenantId(f.get(0));
            var owner = OwnerPolicyFields.owner(tenant,f.get(4),f.get(1),f.get(2),f.get(5));
            var agentId = new AgentId(f.get(3));
            if (count == 7) { new ExecutionRef(tenant, f.get(6)); return Optional.of(owner); }
            var trace = new TraceEnvelope(f.get(9), f.get(10), new InputId(f.get(11)), f.get(12).isEmpty() ? Optional.empty() : Optional.of(new EventId(f.get(12))));
            var run = RunPolicyFields.restore(owner, agentId, new InputId(f.get(7)), new RunRef(tenant, f.get(6)), trace, new ResultRef(f.get(8)),f,27);
            new CapabilitySnapshotRef(owner.sessionRef(), run.causationInputId(), f.get(13), positive(f.get(14)));
            var timeout = Duration.parse(f.get(20)); if (!timeout.toString().equals(f.get(20))) return Optional.empty();
            new FrozenWorkflowTarget(new WorkflowHandle(f.get(15)), f.get(18), positive(f.get(19)), new CapabilityRef(CapabilityType.WORKFLOW, f.get(16), f.get(17)), timeout, positive(f.get(25)));
            new ExecutionRef(tenant, f.get(21)); new ActionRef(tenant, f.get(22)); text(f.get(23)); new ResultRef(f.get(24));
            if(!f.get(26).matches("[0-9a-f]{64}"))return Optional.empty();
            switch (action) {
                case "workflow.dispatch" -> { new TypedPayload(f.get(31), f.get(32)); if (!f.get(33).matches("[0-9a-f]{64}")) return Optional.empty(); new IdempotencyKey(f.get(34)); }
                case "workflow.resume" -> { text(f.get(31)); text(f.get(32)); new TypedPayload(f.get(33), f.get(34)); positive(f.get(35)); new IdempotencyKey(f.get(36)); }
                case "workflow.cancel" -> { text(f.get(31)); positive(f.get(32)); text(f.get(33)); new IdempotencyKey(f.get(34)); }
                case "workflow.recover" -> text(f.get(31));
                default -> throw new IllegalArgumentException("workflow_resource_action");
            }
            return Optional.of(owner);
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    /** 从已完整校验的资源还原准确 Run 来源；不赋予调用或恢复权限。 */
    public static Optional<RunIdentity> runIdentity(String action, TypedPayload payload) {
        var owner = owner(action, payload);
        int count = switch (action) {
            case "workflow.dispatch", "workflow.cancel" -> 35;
            case "workflow.resume" -> 37;
            case "workflow.recover" -> 32;
            default -> 0;
        };
        if (owner.isEmpty() || count == 0) return Optional.empty();
        var fields = AdmissionResource.flatStrings(payload.canonicalJson(), count);
        var trace = new TraceEnvelope(fields.get(9), fields.get(10), new InputId(fields.get(11)),
                fields.get(12).isEmpty() ? Optional.empty() : Optional.of(new EventId(fields.get(12))));
        return Optional.of(RunPolicyFields.restore(owner.orElseThrow(), new AgentId(fields.get(3)), new InputId(fields.get(7)),
                new RunRef(owner.orElseThrow().sessionRef().tenantId(), fields.get(6)), trace, new ResultRef(fields.get(8)),fields,27));
    }

    private static ArrayList<String> common(ProviderCallContext context, CapabilitySnapshotRef snapshot, FrozenWorkflowTarget target, ExecutionRef execution) {
        var dispatch=context.dispatch();return prepared(context.callContext().runIdentity(),snapshot,target,execution,dispatch.action(),dispatch.requestRef(),dispatch.fingerprint());
    }
    private static ArrayList<String> prepared(RunIdentity run,CapabilitySnapshotRef snapshot,FrozenWorkflowTarget target,ExecutionRef execution,
            ActionRef action,ResultRef request,String fingerprint) {
        var owner = run.owner(); var trace = run.trace();
        if(!owner.sessionRef().equals(snapshot.sessionRef())||!run.causationInputId().equals(snapshot.inputId())
                ||!execution.tenantId().equals(run.runRef().tenantId())||!action.tenantId().equals(run.runRef().tenantId())
                ||fingerprint==null||!fingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("workflow_resource_source_mismatch");
        var fields = new ArrayList<>(List.of(owner.sessionRef().tenantId().value(), owner.target().kind().name(),
                owner.target().id(), run.agentId().value(), owner.sessionRef().sessionId(),
                OwnerPolicyFields.subject(owner), run.runRef().runId(), run.causationInputId().value(),
                run.causationIdentityRef().value(), trace.traceId(), trace.spanId(), trace.rootInputId().value(),
                trace.causedByEventId().map(EventId::value).orElse(""), snapshot.snapshotId(), Long.toString(snapshot.generation()), target.handle().value(), target.capability().capabilityId(),
                target.capability().versionId(), target.instanceId(), Long.toString(target.instanceRevision()), target.timeout().toString(), execution.executionId(), action.actionId(),
                action.actionId(), request.value(), Long.toString(target.installationRevision()),fingerprint));
        fields.addAll(RunPolicyFields.values(run));return fields;
    }

    /** 新派发与续答会继续执行业务，需在同一次 IAM 中核对原 Run 的执行 Agent 与应用授权。 */
    public static Optional<SessionPolicyResource.ExecutionTarget> executionTarget(String action,TypedPayload resource) {
        int count=switch(action){case "workflow.dispatch"->35;case "workflow.resume"->37;default->0;};
        // 取消及原结果回收不新增业务执行；查询资源只有 executionRef，不能冒充 RunRef。
        if(count==0||owner(action,resource).isEmpty())return Optional.empty();
        var fields=AdmissionResource.flatStrings(resource.canonicalJson(),count);
        return Optional.of(new SessionPolicyResource.ExecutionTarget(
                new RunRef(new TenantId(fields.get(0)),fields.get(6)),new AgentId(fields.get(3))));
    }
    private static TypedPayload resource(String kind, List<String> fields) {
        return new TypedPayload("skillforge.runtime.workflow." + kind + "-resource.v4", fields.stream().map(WorkflowPolicyResource::quote).collect(Collectors.joining(",", "[", "]")));
    }
    /** 有序 Evidence 全字段长度前缀摘要；既保持固定资源长度，也覆盖item顺序与完整来源身份。 */
    private static String evidenceDigest(List<EvidenceRef> refs) {
        try {
            var digest = MessageDigest.getInstance("SHA-256"); var values = new ArrayList<String>(); values.add("workflow-evidence-v1"); values.add(Integer.toString(refs.size()));
            for (var ref : refs) {
                values.addAll(List.of(ref.sessionRef().tenantId().value(), ref.sessionRef().sessionId(), ref.bundleId(), Long.toString(ref.version()), ref.sourceEventId().value(), Integer.toString(ref.itemIds().size())));
                values.addAll(ref.itemIds());
            }
            for (var value : values) { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("sha256_unavailable"); }
    }
    private static long positive(String value) { long number = Long.parseLong(value); if (number <= 0 || !Long.toString(number).equals(value)) throw new IllegalArgumentException("workflow_resource_number"); return number; }
    private static void text(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("workflow_resource_text"); }
    private static String quote(String text) {
        var result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if ("\b\t\n\f\r".indexOf(c) >= 0) result.append('\\').append("btnfr".charAt("\b\t\n\f\r".indexOf(c)));
            else if (c < 32) result.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
}

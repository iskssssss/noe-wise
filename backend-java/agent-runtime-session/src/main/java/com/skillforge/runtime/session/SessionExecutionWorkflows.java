package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;

/** 新执行Workflow请求的唯一显式协议；只保存真实冻结来源，不保存旧permit/journal或整个运行状态。 */
public final class SessionExecutionWorkflows {
    public static final String REQUEST_SCHEMA = "skillforge.runtime.execution.workflow-request.v3";
    public static final String RESULT_SCHEMA = "skillforge.runtime.execution.workflow-result.v1";
    private final AgentExecutionCodec codec;
    private final CodecLimits limits;

    public SessionExecutionWorkflows(CodecLimits limits) { this.limits = Objects.requireNonNull(limits); codec = new AgentExecutionCodec(limits); }

    public sealed interface Request permits Dispatch, Resume, Cancel, Query {
        CapabilitySnapshotRef snapshot();
        FrozenWorkflowTarget target();
        ExecutionRef executionRef();
        PortalCallKind callKind();
    }
    /** 模型选择和可信起始意图是不同来源，可信输入绝不制造 MODEL native call。 */
    public sealed interface InvocationOrigin permits ModelOrigin, AuthorizedInputOrigin {}
    public record ModelOrigin(String callId, String wireName, TypedPayload arguments) implements InvocationOrigin {
        public ModelOrigin { nativeOrigin(callId, wireName, arguments); }
    }
    public record AuthorizedInputOrigin(String intentRef) implements InvocationOrigin {
        public AuthorizedInputOrigin { text(intentRef); }
    }
    public record Dispatch(CapabilitySnapshotRef snapshot, FrozenWorkflowTarget target, ExecutionRef executionRef,
            TypedPayload parameters, List<EvidenceRef> evidenceRefs, InvocationOrigin origin) implements Request {
        public Dispatch {
            common(snapshot, target, executionRef); Objects.requireNonNull(parameters); evidenceRefs = List.copyOf(evidenceRefs);
            if (new HashSet<>(evidenceRefs).size() != evidenceRefs.size()
                    || evidenceRefs.stream().anyMatch(evidence -> !evidence.sessionRef().equals(snapshot.sessionRef()))) throw invalid();
            Objects.requireNonNull(origin);
        }
        public Dispatch(CapabilitySnapshotRef snapshot, FrozenWorkflowTarget target, ExecutionRef executionRef,
                TypedPayload parameters, List<EvidenceRef> evidenceRefs, String callId, String wireName, TypedPayload arguments) {
            this(snapshot,target,executionRef,parameters,evidenceRefs,new ModelOrigin(callId,wireName,arguments));
        }
        public String callId() { return ((ModelOrigin) origin).callId(); }
        public String wireName() { return ((ModelOrigin) origin).wireName(); }
        public TypedPayload arguments() { return ((ModelOrigin) origin).arguments(); }
        @Override public PortalCallKind callKind() { return PortalCallKind.WORKFLOW_DISPATCH; }
        @Override public String toString() { return "ExecutionWorkflowDispatch[REDACTED]"; }
    }
    public record Resume(CapabilitySnapshotRef snapshot, FrozenWorkflowTarget target, ExecutionRef executionRef,
            String suspendRef, String actionKey, long expectedRevision, TypedPayload response,
            InvocationOrigin origin, ResultRef responseRef, InputId inputId) implements Request {
        public Resume {
            common(snapshot, target, executionRef); text(suspendRef); text(actionKey); positive(expectedRevision); Objects.requireNonNull(response);
            Objects.requireNonNull(origin); Objects.requireNonNull(responseRef);
            Objects.requireNonNull(inputId);
        }
        public Resume(CapabilitySnapshotRef snapshot,FrozenWorkflowTarget target,ExecutionRef executionRef,String suspendRef,
                String actionKey,long expectedRevision,TypedPayload response,String callId,String wireName,TypedPayload arguments,ResultRef responseRef,InputId inputId) {
            this(snapshot,target,executionRef,suspendRef,actionKey,expectedRevision,response,new ModelOrigin(callId,wireName,arguments),responseRef,inputId);
        }
        public String callId() { return ((ModelOrigin) origin).callId(); }
        public String wireName() { return ((ModelOrigin) origin).wireName(); }
        public TypedPayload arguments() { return ((ModelOrigin) origin).arguments(); }
        @Override public PortalCallKind callKind() { return PortalCallKind.WORKFLOW_RESUME; }
        @Override public String toString() { return "ExecutionWorkflowResume[REDACTED]"; }
    }
    public record Cancel(CapabilitySnapshotRef snapshot, FrozenWorkflowTarget target, ExecutionRef executionRef,
            String actionKey, long expectedRevision, String reasonCode) implements Request {
        public Cancel { common(snapshot, target, executionRef); text(actionKey); positive(expectedRevision); text(reasonCode); }
        @Override public PortalCallKind callKind() { return PortalCallKind.WORKFLOW_CANCEL; }
        @Override public String toString() { return "ExecutionWorkflowCancel[REDACTED]"; }
    }
    public record Query(CapabilitySnapshotRef snapshot, FrozenWorkflowTarget target, ExecutionRef executionRef) implements Request {
        public Query { common(snapshot, target, executionRef); }
        @Override public PortalCallKind callKind() { return PortalCallKind.WORKFLOW_RECOVER_RESULT; }
        @Override public String toString() { return "ExecutionWorkflowQuery[REDACTED]"; }
    }
    /** 完整原PortalOutcome与真实Engine等待引用同存；不以缩略业务结果替代raw outcome。 */
    public record HumanInteraction(TypedPayload card) {
        public HumanInteraction { Objects.requireNonNull(card); if (!"application/json".equals(card.schemaId())) throw invalid(); }
        @Override public String toString() { return "WorkflowHumanInteraction[REDACTED]"; }
    }
    public record Result(TypedPayload outcome, Optional<ExecutionStore.WorkflowWait> waiting, Optional<HumanInteraction> human) {
        public Result { Objects.requireNonNull(outcome);Objects.requireNonNull(waiting);Objects.requireNonNull(human);
            if(!AgentExecutionCodec.OUTCOME_SCHEMA.equals(outcome.schemaId()))throw invalid(); }
        public Result(TypedPayload outcome, Optional<ExecutionStore.WorkflowWait> waiting) { this(outcome,waiting,Optional.empty()); }
        @Override public String toString(){return "ExecutionWorkflowResult[REDACTED]";}
    }
    public TypedPayload encodeResult(Result result) {
        var raw=codec.decodeOutcome(result.outcome());
        if(!java.util.Set.of(PortalCallKind.WORKFLOW_DISPATCH,PortalCallKind.WORKFLOW_RESUME,PortalCallKind.WORKFLOW_CANCEL,PortalCallKind.WORKFLOW_RECOVER_RESULT).contains(raw.callKind())
                ||result.waiting().isPresent()!=raw.outcomeType().equals("Suspended")||raw.outcomeType().equals("Accepted"))throw invalid();
        if (result.human().isPresent() && result.waiting().isEmpty()) throw invalid();
        if (result.waiting().isPresent()) {
            var body = codec.parse(raw.outcome(), raw.outcome().schemaId());
            if (raw.callKind() == PortalCallKind.WORKFLOW_RECOVER_RESULT) body = body.get("result");
            var reference = body.get("executionRef"); var wait = result.waiting().orElseThrow();
            if (!AgentExecutionCodec.string(reference,"tenantId").equals(wait.execution().tenantId().value())
                    || !AgentExecutionCodec.string(reference,"executionId").equals(wait.execution().executionId())
                    || !AgentExecutionCodec.string(body,"suspendRef").equals(wait.suspendRef())
                    || AgentExecutionCodec.number(body,"revision") != wait.executionRevision()) throw invalid();
            if (result.human().isPresent() && (!"WORKFLOW_HITL".equals(AgentExecutionCodec.string(body,"reason"))
                    || !codec.parse(result.human().orElseThrow().card(),"application/json").isObject())) throw invalid();
        }
        var root=AgentExecutionCodec.object();root.set("outcome",AgentExecutionCodec.payload(result.outcome()));
        if(result.waiting().isEmpty())root.putNull("waiting");
        else {
            var wait=result.waiting().orElseThrow();var value=root.putObject("waiting");
            value.put("tenantId",wait.execution().tenantId().value());value.put("executionId",wait.execution().executionId());
            value.put("suspendRef",wait.suspendRef());value.put("actionKey",wait.actionKey());value.put("executionRevision",Long.toString(wait.executionRevision()));
        }
        if(result.human().isPresent())root.set("human",AgentExecutionCodec.payload(result.human().orElseThrow().card()));else root.putNull("human");
        return codec.encode(RESULT_SCHEMA,root);
    }
    public Result decodeResult(TypedPayload payload) {
        var root=codec.parse(payload,RESULT_SCHEMA);AgentExecutionCodec.fields(root,"outcome","waiting","human");
        Optional<ExecutionStore.WorkflowWait> waiting=Optional.empty();
        if(!root.get("waiting").isNull()) {
            var value=root.get("waiting");AgentExecutionCodec.fields(value,"tenantId","executionId","suspendRef","actionKey","executionRevision");
            waiting=Optional.of(new ExecutionStore.WorkflowWait(new ExecutionRef(new TenantId(AgentExecutionCodec.string(value,"tenantId")),AgentExecutionCodec.string(value,"executionId")),
                    AgentExecutionCodec.string(value,"suspendRef"),AgentExecutionCodec.string(value,"actionKey"),AgentExecutionCodec.number(value,"executionRevision")));
        }
        var human=root.get("human").isNull()?Optional.<HumanInteraction>empty():Optional.of(new HumanInteraction(AgentExecutionCodec.readPayload(root.get("human"))));
        var result=new Result(AgentExecutionCodec.readPayload(root.get("outcome")),waiting,human);if(!encodeResult(result).equals(payload))throw invalid();return result;
    }

    public TypedPayload encode(Request request) {
        Objects.requireNonNull(request);
        var root = AgentExecutionCodec.object();
        root.put("operation", switch (request) { case Dispatch ignored -> "dispatch"; case Resume ignored -> "resume"; case Cancel ignored -> "cancel"; case Query ignored -> "query"; });
        var snapshot = root.putObject("snapshot"); snapshot.put("tenantId", request.snapshot().sessionRef().tenantId().value());
        snapshot.put("sessionId", request.snapshot().sessionRef().sessionId()); snapshot.put("inputId", request.snapshot().inputId().value());
        snapshot.put("snapshotId", request.snapshot().snapshotId()); snapshot.put("generation", Long.toString(request.snapshot().generation()));
        var target = request.target(); var frozen = root.putObject("target"); frozen.put("handle", target.handle().value());
        frozen.put("instanceId", target.instanceId()); frozen.put("instanceRevision", Long.toString(target.instanceRevision()));
        frozen.put("installationRevision", Long.toString(target.installationRevision())); frozen.put("timeout", target.timeout().toString());
        var capability = frozen.putObject("capability"); capability.put("type", target.capability().type().name());
        capability.put("capabilityId", target.capability().capabilityId()); capability.put("versionId", target.capability().versionId());
        var execution = root.putObject("executionRef"); execution.put("tenantId", request.executionRef().tenantId().value()); execution.put("executionId", request.executionRef().executionId());
        switch (request) {
            case Dispatch dispatch -> {
                validate(dispatch.parameters()); root.set("parameters", AgentExecutionCodec.payload(dispatch.parameters()));
                var refs = root.putArray("evidenceRefs");
                for (var evidence : dispatch.evidenceRefs()) {
                    var ref = refs.addObject(); ref.put("tenantId", evidence.sessionRef().tenantId().value()); ref.put("sessionId", evidence.sessionRef().sessionId());
                    ref.put("bundleId", evidence.bundleId()); ref.put("version", Long.toString(evidence.version())); ref.put("sourceEventId", evidence.sourceEventId().value());
                    var ids = ref.putArray("itemIds"); evidence.itemIds().forEach(ids::add);
                }
                root.set("origin", encodeOrigin(dispatch.origin()));
            }
            case Resume resume -> {
                validate(resume.response()); root.put("suspendRef", resume.suspendRef()); root.put("actionKey", resume.actionKey());
                root.put("expectedRevision", Long.toString(resume.expectedRevision())); root.set("response", AgentExecutionCodec.payload(resume.response()));
                root.set("origin", encodeOrigin(resume.origin()));
                root.put("responseRef",resume.responseRef().value());
                root.put("inputId", resume.inputId().value());
            }
            case Cancel cancel -> { root.put("actionKey", cancel.actionKey()); root.put("expectedRevision", Long.toString(cancel.expectedRevision())); root.put("reasonCode", cancel.reasonCode()); }
            case Query ignored -> { }
        }
        return codec.encode(REQUEST_SCHEMA, root);
    }

    public Request decode(TypedPayload payload) {
        var root = codec.parse(payload, REQUEST_SCHEMA); var operation = AgentExecutionCodec.string(root, "operation");
        switch (operation) {
            case "dispatch" -> AgentExecutionCodec.fields(root, "operation", "snapshot", "target", "executionRef", "parameters", "evidenceRefs", "origin");
            case "resume" -> AgentExecutionCodec.fields(root, "operation", "snapshot", "target", "executionRef", "suspendRef", "actionKey", "expectedRevision", "response", "origin", "responseRef", "inputId");
            case "cancel" -> AgentExecutionCodec.fields(root, "operation", "snapshot", "target", "executionRef", "actionKey", "expectedRevision", "reasonCode");
            case "query" -> AgentExecutionCodec.fields(root, "operation", "snapshot", "target", "executionRef");
            default -> throw invalid();
        }
        var snapshot = snapshot(root.get("snapshot")); var target = target(root.get("target")); var execution = root.get("executionRef");
        AgentExecutionCodec.fields(execution, "tenantId", "executionId");
        var reference = new ExecutionRef(new TenantId(AgentExecutionCodec.string(execution, "tenantId")), AgentExecutionCodec.string(execution, "executionId"));
        Request result = switch (operation) {
            case "dispatch" -> {
                if (!root.get("evidenceRefs").isArray()) throw invalid(); var refs = new ArrayList<EvidenceRef>();
                for (var ref : root.get("evidenceRefs")) {
                    AgentExecutionCodec.fields(ref, "tenantId", "sessionId", "bundleId", "version", "sourceEventId", "itemIds");
                    if (!ref.get("itemIds").isArray()) throw invalid(); var ids = new ArrayList<String>();
                    for (var id : ref.get("itemIds")) { if (!id.isTextual() || id.textValue().isBlank()) throw invalid(); ids.add(id.textValue()); }
                    refs.add(new EvidenceRef(new SessionRef(new TenantId(AgentExecutionCodec.string(ref, "tenantId")), AgentExecutionCodec.string(ref, "sessionId")),
                            AgentExecutionCodec.string(ref, "bundleId"), ids, AgentExecutionCodec.number(ref, "version"), new EventId(AgentExecutionCodec.string(ref, "sourceEventId"))));
                }
                yield new Dispatch(snapshot, target, reference, AgentExecutionCodec.readPayload(root.get("parameters")), refs,
                        decodeOrigin(root.get("origin")));
            }
            case "resume" -> new Resume(snapshot, target, reference, AgentExecutionCodec.string(root, "suspendRef"), AgentExecutionCodec.string(root, "actionKey"),
                    AgentExecutionCodec.number(root, "expectedRevision"), AgentExecutionCodec.readPayload(root.get("response")), decodeOrigin(root.get("origin")),new ResultRef(AgentExecutionCodec.string(root,"responseRef")), new InputId(AgentExecutionCodec.string(root, "inputId")));
            case "cancel" -> new Cancel(snapshot, target, reference, AgentExecutionCodec.string(root, "actionKey"), AgentExecutionCodec.number(root, "expectedRevision"), AgentExecutionCodec.string(root, "reasonCode"));
            case "query" -> new Query(snapshot, target, reference);
            default -> throw invalid();
        };
        if (!encode(result).equals(payload)) throw invalid(); return result;
    }
    /** 真实invoke终态进入模型的受控文档；原raw outcome始终由Core.PortalResults单独无损保存。 */
    public Optional<TypedPayload> feedback(WorkflowResult result) {
        Objects.requireNonNull(result);
        if (result instanceof WorkflowResult.Suspended || result instanceof WorkflowResult.OutcomeUnknown) return Optional.empty();
        if (result instanceof WorkflowResult.Accepted) throw new IllegalArgumentException("execution_workflow_accepted_not_supported");
        var value = AgentExecutionCodec.object();
        switch (result) {
            case WorkflowResult.Completed completed -> {
                new SessionRuntimePayloads().validate(completed.output(), limits);
                validate(completed.output()); value.put("status", "completed"); value.put("executionId", completed.executionRef().executionId());
                value.put("revision", Long.toString(completed.revision())); value.set("output", AgentExecutionCodec.payload(completed.output()));
            }
            case WorkflowResult.Failed failed -> {
                value.put("status", "failed"); value.put("executionId", failed.executionRef().executionId());
                if (failed.revision().isPresent()) value.put("revision", Long.toString(failed.revision().getAsLong())); else value.putNull("revision");
                value.put("reasonCode", failed.failure().reasonCode());
            }
            case WorkflowResult.Terminated terminated -> {
                value.put("status", "terminated"); value.put("executionId", terminated.executionRef().executionId()); value.put("revision", Long.toString(terminated.revision()));
                value.put("reasonCode", terminated.reason().name());
            }
            case WorkflowResult.Cancelled cancelled -> { value.put("status", "cancelled"); value.put("executionId", cancelled.executionRef().executionId()); value.put("revision", Long.toString(cancelled.revision())); }
            default -> throw invalid();
        }
        return Optional.of(codec.encode("application/json", value));
    }
    private JsonNode encodeOrigin(InvocationOrigin origin) {
        var value=AgentExecutionCodec.object();
        switch(origin) {
            case ModelOrigin model -> { validateArguments(model.arguments()); value.put("kind","MODEL"); value.put("callId",model.callId());
                value.put("wireName",model.wireName()); value.set("arguments",AgentExecutionCodec.payload(model.arguments())); }
            case AuthorizedInputOrigin input -> { value.put("kind","AUTHORIZED_INPUT"); value.put("intentRef",input.intentRef()); }
        }
        return value;
    }
    private InvocationOrigin decodeOrigin(JsonNode value) {
        return switch(AgentExecutionCodec.string(value,"kind")) {
            case "MODEL" -> { AgentExecutionCodec.fields(value,"kind","callId","wireName","arguments");
                yield new ModelOrigin(AgentExecutionCodec.string(value,"callId"),AgentExecutionCodec.string(value,"wireName"),AgentExecutionCodec.readPayload(value.get("arguments"))); }
            case "AUTHORIZED_INPUT" -> { AgentExecutionCodec.fields(value,"kind","intentRef"); yield new AuthorizedInputOrigin(AgentExecutionCodec.string(value,"intentRef")); }
            default -> throw invalid();
        };
    }
    private static CapabilitySnapshotRef snapshot(JsonNode value) {
        AgentExecutionCodec.fields(value, "tenantId", "sessionId", "inputId", "snapshotId", "generation");
        return new CapabilitySnapshotRef(new SessionRef(new TenantId(AgentExecutionCodec.string(value, "tenantId")), AgentExecutionCodec.string(value, "sessionId")),
                new InputId(AgentExecutionCodec.string(value, "inputId")), AgentExecutionCodec.string(value, "snapshotId"), AgentExecutionCodec.number(value, "generation"));
    }
    private static FrozenWorkflowTarget target(JsonNode value) {
        AgentExecutionCodec.fields(value, "handle", "instanceId", "instanceRevision", "installationRevision", "timeout", "capability");
        var capability = value.get("capability"); AgentExecutionCodec.fields(capability, "type", "capabilityId", "versionId");
        return new FrozenWorkflowTarget(new WorkflowHandle(AgentExecutionCodec.string(value, "handle")), AgentExecutionCodec.string(value, "instanceId"),
                AgentExecutionCodec.number(value, "instanceRevision"), new CapabilityRef(CapabilityType.valueOf(AgentExecutionCodec.string(capability, "type")),
                        AgentExecutionCodec.string(capability, "capabilityId"), AgentExecutionCodec.string(capability, "versionId")),
                Duration.parse(AgentExecutionCodec.string(value, "timeout")), AgentExecutionCodec.number(value, "installationRevision"));
    }
    private void validate(TypedPayload value) { codec.parse(value, value.schemaId()); }
    private void validateArguments(TypedPayload value) { if (!codec.parse(value, "application/json").isObject()) throw invalid(); }
    private static void common(CapabilitySnapshotRef snapshot, FrozenWorkflowTarget target, ExecutionRef execution) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(target); Objects.requireNonNull(execution);
        if (!snapshot.sessionRef().tenantId().equals(execution.tenantId())) throw invalid();
    }
    private static void nativeOrigin(String callId, String wireName, TypedPayload arguments) {
        text(callId); text(wireName); Objects.requireNonNull(arguments); if (!"application/json".equals(arguments.schemaId())) throw invalid();
    }
    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private static void positive(long value) { if (value <= 0) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_workflow_request_invalid"); }
}

package com.skillforge.core.workflow.runtime;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.*;
import com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Workflow Provider 私有 checkpoint；完整保存原挂载来源及真实 Engine 节点边界。 */
final class WorkflowCheckpointCodec {
    static final String SCHEMA = "skillforge.runtime.workflow.engine-state.v1";
    static final String OUTPUT_SCHEMA = "skillforge.runtime.workflow.engine-result.v1";
    private static final int MAX_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(MAX_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    record ToolWait(ExecutionRef executionRef, String nodeId, int visit, int call, RunRef nodeRun,ActionRef actionRef,
                    long generation,ResultRef sourceResult) {
        ToolWait {
            Objects.requireNonNull(executionRef);Objects.requireNonNull(actionRef);Objects.requireNonNull(nodeRun);Objects.requireNonNull(sourceResult);
            if(generation<=0
                    ||nodeId==null||nodeId.isBlank()||visit<=0||call<=0
                    ||!executionRef.tenantId().equals(actionRef.tenantId())||!executionRef.tenantId().equals(nodeRun.tenantId()))throw invalid();
        }
    }

    record AsyncWait(String nodeId, int visit, int call, Optional<Instant> deadlineAt) {
        AsyncWait {
            if(nodeId==null||nodeId.isBlank()||visit<=0||call<=0)throw invalid();
            Objects.requireNonNull(deadlineAt);
        }
    }

    record State(FrozenWorkflowTarget target, CapabilitySnapshotRef snapshot, TypedPayload parameters,
                 List<EvidenceRef> evidenceRefs, Optional<Checkpoint> engine, Map<String,Object> response,
                 Optional<ToolWait> toolWait, Optional<AsyncWait> asyncWait) {
        State {
            Objects.requireNonNull(target);Objects.requireNonNull(snapshot);Objects.requireNonNull(parameters);
            evidenceRefs=List.copyOf(evidenceRefs);Objects.requireNonNull(engine);
            response=Collections.unmodifiableMap(new LinkedHashMap<>(response));
            Objects.requireNonNull(toolWait);Objects.requireNonNull(asyncWait);
            if(toolWait.isPresent()&&asyncWait.isPresent())throw invalid();
            toolWait.ifPresent(wait -> {
                var checkpoint=engine.orElseThrow(WorkflowCheckpointCodec::invalid);
                if((checkpoint.waitReason().filter(reason->reason==WaitReason.TOOL_INPUT).isEmpty()
                        && !((checkpoint.boundary()==Boundary.PROGRESS || checkpoint.waitReason().filter(reason->reason==WaitReason.CALLBACK).isPresent())
                            && wait.nodeId().equals(checkpoint.cursor().activeNode())))
                        ||!wait.nodeId().equals(checkpoint.nodeId())
                        ||!Integer.valueOf(wait.visit()).equals(checkpoint.cursor().visits().get(checkpoint.nodeId()))
                        ||!wait.executionRef().tenantId().equals(snapshot.sessionRef().tenantId()))throw invalid();
            });
            asyncWait.ifPresent(wait -> {
                var checkpoint=engine.orElseThrow(WorkflowCheckpointCodec::invalid);
                if(!retainsAsyncWait(checkpoint,wait))throw invalid();
            });
        }
        State checkpoint(Checkpoint value) {
            var retained=toolWait.filter(wait->(value.boundary()==Boundary.PROGRESS || value.waitReason().filter(reason->reason==WaitReason.CALLBACK).isPresent())
                    &&wait.nodeId().equals(value.cursor().activeNode())
                    &&Integer.valueOf(wait.visit()).equals(value.cursor().visits().get(wait.nodeId())));
            var retainedAsync=asyncWait.filter(wait->retainsAsyncWait(value,wait));
            return new State(target,snapshot,parameters,evidenceRefs,Optional.of(value),response,retained,retainedAsync);
        }
        State response(Map<String,Object> value) { return new State(target,snapshot,parameters,evidenceRefs,engine,value,toolWait,asyncWait); }
        State toolWait(ToolWait value) { return new State(target,snapshot,parameters,evidenceRefs,engine,response,Optional.of(value),asyncWait); }
        State asyncWait(AsyncWait value) { return new State(target,snapshot,parameters,evidenceRefs,engine,response,toolWait,Optional.of(value)); }
    }

    static State initial(WorkflowDispatch call) {
        return new State(call.target(),call.snapshot(),call.parameters(),call.evidenceRefs(),Optional.empty(),Map.of(),Optional.empty(),Optional.empty());
    }
    static TypedPayload encode(State state) {
        try {
            var root=JSON.createObjectNode();var t=state.target();
            var target=root.putObject("target");target.put("handle",t.handle().value());target.put("instanceId",t.instanceId());
            target.put("instanceRevision",t.instanceRevision());target.put("installationRevision",t.installationRevision());target.set("capability",JSON.valueToTree(t.capability()));target.put("timeout",t.timeout().toString());
            root.set("snapshot",JSON.valueToTree(state.snapshot()));root.set("parameters",JSON.valueToTree(state.parameters()));
            root.set("evidenceRefs",JSON.valueToTree(state.evidenceRefs()));root.set("response",JSON.valueToTree(state.response()));
            root.set("toolWait",state.toolWait().<JsonNode>map(JSON::valueToTree).orElse(NullNode.instance));
            if(state.asyncWait().isEmpty())root.putNull("asyncWait");
            else {
                var wait=state.asyncWait().orElseThrow();var async=root.putObject("asyncWait");
                async.put("nodeId",wait.nodeId());async.put("visit",wait.visit());async.put("call",wait.call());
                if(wait.deadlineAt().isPresent())async.put("deadlineAt",wait.deadlineAt().orElseThrow().toString());else async.putNull("deadlineAt");
            }
            if(state.engine().isEmpty())root.putNull("engine");
            else {
                var c=state.engine().orElseThrow();var e=root.putObject("engine");e.put("boundary",c.boundary().name());
                e.put("nodeId",c.nodeId());e.put("nextNodeId",c.nextNodeId());e.set("state",JSON.valueToTree(c.state()));
                e.set("extracted",JSON.valueToTree(c.extracted()));e.set("messages",JSON.valueToTree(c.messages()));
                e.set("cursor",JSON.valueToTree(c.cursor()));e.set("card",JSON.valueToTree(c.card()));e.put("timeoutSeconds",c.timeoutSeconds());
                if(c.loopLimit().isPresent())e.put("loopLimit",c.loopLimit().orElseThrow().name());else e.putNull("loopLimit");
                if(c.waitReason().isPresent())e.put("waitReason",c.waitReason().orElseThrow().name());else e.putNull("waitReason");
            }
            return new TypedPayload(SCHEMA,canonical(root));
        } catch(Exception invalid) { throw invalid(); }
    }
    static State decode(TypedPayload payload) {
        try {
            if(!SCHEMA.equals(payload.schemaId()))throw invalid();var root=parse(payload.canonicalJson());
            keys(root,"target","snapshot","parameters","evidenceRefs","response","engine","toolWait","asyncWait");
            var t=root.get("target");keys(t,"handle","instanceId","instanceRevision","capability","timeout","installationRevision");
            var target=new FrozenWorkflowTarget(new WorkflowHandle(text(t,"handle")),text(t,"instanceId"),positive(t,"instanceRevision"),
                    JSON.treeToValue(t.get("capability"),CapabilityRef.class),Duration.parse(text(t,"timeout")),positive(t,"installationRevision"));
            var snapshot=JSON.treeToValue(root.get("snapshot"),CapabilitySnapshotRef.class);
            var parameters=JSON.treeToValue(root.get("parameters"),TypedPayload.class);
            if(!root.get("evidenceRefs").isArray())throw invalid();var evidence=new ArrayList<EvidenceRef>();
            for(var value:root.get("evidenceRefs"))evidence.add(JSON.treeToValue(value,EvidenceRef.class));
            Optional<Checkpoint> engine=Optional.empty();var e=root.get("engine");
            if(!e.isNull()) {
                keys(e,"boundary","nodeId","nextNodeId","state","extracted","messages","card","timeoutSeconds","loopLimit","waitReason","cursor");
                var messages=new ArrayList<Map<String,Object>>();if(!e.get("messages").isArray())throw invalid();
                for(var m:e.get("messages"))messages.add(map(m));
                Integer timeout=null;
                if(!e.get("timeoutSeconds").isNull()) { long seconds=positive(e,"timeoutSeconds");timeout=Math.toIntExact(seconds); }
                engine=Optional.of(new Checkpoint(Boundary.valueOf(text(e,"boundary")),nullableText(e,"nodeId"),nullableText(e,"nextNodeId"),
                        map(e.get("state")),map(e.get("extracted")),messages,e.get("card").isNull()?null:map(e.get("card")),timeout,
                        e.get("loopLimit").isNull()?Optional.empty():Optional.of(LoopLimit.valueOf(text(e,"loopLimit"))),
                        e.get("waitReason").isNull()?Optional.empty():Optional.of(WaitReason.valueOf(text(e,"waitReason"))),JSON.treeToValue(e.get("cursor"),Cursor.class)));
            }
            var state=new State(target,snapshot,parameters,evidence,engine,map(root.get("response")),
                    root.get("toolWait").isNull()?Optional.empty():Optional.of(JSON.treeToValue(root.get("toolWait"),ToolWait.class)),
                    asyncWait(root.get("asyncWait")));
            if(!encode(state).equals(payload))throw invalid();return state;
        } catch(Exception invalid) { throw invalid(); }
    }
    static Map<String,Object> object(TypedPayload payload) { return object(payload.canonicalJson()); }
    static Map<String,Object> object(String value) { try { return map(parse(value)); } catch(Exception invalid) { throw invalid(); } }
    static TypedPayload output(Checkpoint checkpoint, Set<String> exportFields) {
        if(checkpoint.boundary()!=Boundary.COMPLETED)throw invalid();
        Objects.requireNonNull(exportFields);
        var root=JSON.createObjectNode();
        for(String field:exportFields) {
            // 导出使用显式顶层业务字段；内部游标、上下文和整个状态对象永不隐式公开。
            if(field==null||!field.matches("[A-Za-z][A-Za-z0-9_]*")||field.startsWith("ctx_"))throw invalid();
            if(field.equals("messages"))root.set(field,JSON.valueToTree(checkpoint.messages()));
            else if(checkpoint.extracted().containsKey(field))root.set(field,JSON.valueToTree(checkpoint.extracted().get(field)));
            else if(checkpoint.state().containsKey(field))root.set(field,JSON.valueToTree(checkpoint.state().get(field)));
        }
        return new TypedPayload(OUTPUT_SCHEMA,canonical(root));
    }
    static Set<String> exportFields(String descriptorJson) {
        Object fields=object(descriptorJson).get("export_fields");
        if(!(fields instanceof List<?> list))throw invalid();
        var result=new LinkedHashSet<String>();
        for(Object field:list) {
            if(!(field instanceof String text)||!result.add(text))throw invalid();
        }
        return Collections.unmodifiableSet(result);
    }
    private static JsonNode parse(String text) throws Exception {
        if(text.length()>MAX_BYTES||text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid();
        try(var parser=JSON.createParser(text)) { JsonNode root=JSON.readTree(parser);if(root==null||parser.nextToken()!=null)throw invalid();return root; }
    }
    private static String canonical(JsonNode value) {
        try {String result=JSON.writeValueAsString(sorted(value,0));
            if(result.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid();return result;
        } catch(Exception invalid) { throw invalid(); }
    }
    private static JsonNode sorted(JsonNode value,int depth) {
        if(depth>64)throw invalid();
        if(value.isObject()) {var out=JSON.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);keys.forEach(k->out.set(k,sorted(value.get(k),depth+1)));return out;}
        if(value.isArray()) {var out=JSON.createArrayNode();value.forEach(v->out.add(sorted(v,depth+1)));return out;}return value;
    }
    private static boolean retainsAsyncWait(Checkpoint checkpoint, AsyncWait wait) {
        return (checkpoint.boundary()==Boundary.PROGRESS
                ||checkpoint.waitReason().filter(reason->reason==WaitReason.CALLBACK).isPresent())
                &&wait.nodeId().equals(checkpoint.nodeId())&&wait.nodeId().equals(checkpoint.cursor().activeNode())
                &&Integer.valueOf(wait.visit()).equals(checkpoint.cursor().visits().get(wait.nodeId()));
    }
    private static Optional<AsyncWait> asyncWait(JsonNode value) {
        if(value==null)throw invalid();if(value.isNull())return Optional.empty();keys(value,"nodeId","visit","call","deadlineAt");
        Optional<Instant> deadline=value.get("deadlineAt").isNull()?Optional.empty():Optional.of(Instant.parse(text(value,"deadlineAt")));
        return Optional.of(new AsyncWait(text(value,"nodeId"),Math.toIntExact(positive(value,"visit")),Math.toIntExact(positive(value,"call")),deadline));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(JsonNode value) {
        if(value==null||!value.isObject())throw invalid();return JSON.convertValue(value,LinkedHashMap.class);
    }
    private static void keys(JsonNode value,String... expected) {
        if(value==null||!value.isObject())throw invalid();var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(Set.of(expected)))throw invalid();
    }
    private static String text(JsonNode value,String field) {var v=value.get(field);if(v==null||!v.isTextual()||v.textValue().isBlank())throw invalid();return v.textValue();}
    private static String nullableText(JsonNode value,String field) {return value.get(field).isNull()?null:text(value,field);}
    private static long positive(JsonNode value,String field) {var v=value.get(field);if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<=0)throw invalid();return v.longValue();}
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("workflow_checkpoint_invalid");}
}

package com.skillforge.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.*;

/** 一次真实程序RPC只派发一个TOOL；来源和生命周期独立于Workflow节点。 */
public final class ProgramToolProgram implements ExecutionCoordinator.Program<ProgramToolProgram.Materials> {
    public static final String CONTEXT_SCHEMA="skillforge.runtime.execution.program-tool-context.v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Sources sources;
    private final AgentExecutionCodec codec;
    private final CodecLimits limits;
    public record Configuration(VerifiedIdentity actor,ResultRef capability,ExecutionStore.Material request) {
        public Configuration { Objects.requireNonNull(actor);Objects.requireNonNull(capability);Objects.requireNonNull(request); }
    }
    public interface Sources { Configuration configuration(ExecutionStore.Run run); }
    public enum State { READY, SUCCEEDED, FAILED, DIRECT_REQUIRED, UNKNOWN }
    public record Materials(Configuration configuration,State state,Optional<ResultRef> result) {
        public Materials { Objects.requireNonNull(configuration);Objects.requireNonNull(state);Objects.requireNonNull(result);if((state==State.READY)==result.isPresent())throw invalid(); }
    }
    public ProgramToolProgram(Sources sources,CodecLimits limits) { this.sources=Objects.requireNonNull(sources);this.limits=Objects.requireNonNull(limits);codec=new AgentExecutionCodec(limits); }
    public static ExecutionStore.Material initial(RunRef run) { return context(run,State.READY,Optional.empty()); }
    public static ActionRef action(RunRef run) { return new ActionRef(run.tenantId(),"program-tool-effect:"+AgentExecutionCodec.digest(run.runId())); }
    @Override public Materials open(ExecutionStore.Run run,ExecutionStore.Material material) {
        var configuration=sources.configuration(run);
        if(!configuration.actor().owner().equals(run.identity().owner())||!configuration.actor().verificationRef().equals(run.actorVerificationRef()))throw invalid();
        var value=parse(material.content());var state=State.valueOf(text(value,"state"));
        var result=value.path("result").isNull()?Optional.<ResultRef>empty():Optional.of(new ResultRef(text(value,"result")));
        if(!context(run.identity().runRef(),state,result).equals(material))throw invalid();
        codec.decodeTool(configuration.request().content());return new Materials(configuration,state,result);
    }
    @Override public ExecutionCoordinator.Step next(ExecutionStore.Run run,Materials materials) {
        if(materials.state()==State.UNKNOWN)return new ExecutionCoordinator.Step.Yield("program_tool_result_unknown");
        if(materials.state()!=State.READY)return new ExecutionCoordinator.Step.Finish(materials.state()==State.SUCCEEDED?RunStatus.COMPLETED:RunStatus.FAILED,List.of());
        var request=codec.decodeTool(materials.configuration().request().content());if(request.continuation().isPresent())throw invalid();
        var prepared=new ExecutionStore.Prepared(action(run.identity().runRef()),1,ExecutionStore.Kind.TOOL,request.target().handle().value(),
                materials.configuration().capability(),materials.configuration().request(),AgentExecutionCodec.digest(materials.configuration().request().content().schemaId()+"\0"+materials.configuration().request().content().canonicalJson()));
        var attempt=prepared.action().actionId()+":attempt:1";
        return new ExecutionCoordinator.Step.Dispatch(prepared,attempt,new ExecutionBudgetPort.Demand(run.identity().runRef(),attempt,prepared.fingerprint(),
                ExecutionBudgetPort.Category.TOOL,new ExecutionBudgetPort.Resources(0,0,1,0,0,0,0),false),materials.configuration().actor(),Optional.empty(),
                List.of(observation(run,prepared,request.wireName(),ExecutionObservation.Status.REGISTERED)));
    }
    @Override public ExecutionCoordinator.Adoption<Materials> adopt(ExecutionStore.Run run,Materials before,ExecutionStore.Prepared prepared,ExecutionCoordinator.CapabilityResult actual) {
        var request=codec.decodeTool(before.configuration().request().content());
        if(before.state()!=State.READY||prepared.kind()!=ExecutionStore.Kind.TOOL||prepared.ordinal()!=1||!prepared.action().equals(action(run.identity().runRef()))
                ||!prepared.capability().equals(before.configuration().capability())||!prepared.target().equals(request.target().handle().value())
                ||!prepared.request().equals(before.configuration().request()))throw invalid();
        var outcome=codec.decodeToolOutcome(actual.result().content());Optional<TypedPayload> feedback;boolean rejectedOutput=false;
        try { feedback=codec.safeToolFeedback(request,outcome); }
        catch(AgentExecutionCodec.ToolOutputRejected rejected) { if(!(outcome instanceof ToolOutcome.Succeeded))throw invalid();feedback=Optional.empty();rejectedOutput=true; }
        if(!feedback.equals(actual.feedback().map(ExecutionStore.Material::content)))throw invalid();
        var expected=switch(outcome){case ToolOutcome.Succeeded ignored->ExecutionStore.ResultKind.SUCCEEDED;case ToolOutcome.Failed ignored->ExecutionStore.ResultKind.FAILED;
            case ToolOutcome.InputRequired ignored->ExecutionStore.ResultKind.INPUT_REQUIRED;case ToolOutcome.OutcomeUnknown ignored->ExecutionStore.ResultKind.UNKNOWN;};
        if(actual.kind()!=expected)throw invalid();
        State state=switch(outcome){case ToolOutcome.Succeeded ignored->rejectedOutput?State.FAILED:State.SUCCEEDED;case ToolOutcome.Failed ignored->State.FAILED;
            case ToolOutcome.InputRequired ignored->State.DIRECT_REQUIRED;case ToolOutcome.OutcomeUnknown ignored->State.UNKNOWN;};
        var result=Optional.of(actual.result().reference());var context=context(run.identity().runRef(),state,result);
        Optional<ExecutionStore.WaitDeclaration> waiting=outcome instanceof ToolOutcome.InputRequired required?Optional.of(new ExecutionStore.WaitDeclaration(
                new ExecutionStore.WaitRef(prepared.action(),1),ExecutionStore.WaitKind.TOOL,required.inputRequests().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,entry->entry.getValue().method())),Optional.empty())):Optional.empty();
        return new ExecutionCoordinator.Adoption<>(new Materials(before.configuration(),state,result),new ExecutionStore.Progress(0,1,0,context.reference()),context,
                List.of(observation(run,prepared,request.wireName(),AgentExecutionObservations.status(actual.kind()))),waiting);
    }
    @Override public ExecutionCoordinator.Step continuation(ExecutionStore.Run run,Materials value,ExecutionStore.Waiting wait,ExecutionStore.Material response){throw invalid();}
    @Override public ExecutionCoordinator.Adoption<Materials> acceptResponse(ExecutionStore.Run run,Materials value,ExecutionStore.Waiting wait,ExecutionStore.Material response){throw invalid();}
    @Override public ExecutionCoordinator.Adoption<Materials> acceptInput(ExecutionStore.Run run,Materials value,ExecutionStore.SupplementalInput input,ExecutionStore.Material content){throw invalid();}
    @Override public ExecutionCoordinator.Adoption<Materials> planAdoption(ExecutionStore.Run run,Materials value,ExecutionCoordinator.Step.PlanLocal command,ExecutionStore.Material actual){throw invalid();}
    @Override public boolean batchParallel(Materials materials) { throw invalid(); }
    @Override public ExecutionCoordinator.BatchResult inspectBatch(ExecutionStore.Run run,Materials value,ExecutionCoordinator.BatchCall result,int slot){throw invalid();}
    @Override public ExecutionCoordinator.Adoption<Materials> adoptBatch(ExecutionStore.Run run,Materials value,List<ExecutionCoordinator.BatchOutcome> results){throw invalid();}
    @Override public ExecutionCoordinator.BatchPlan<Materials> batchContinuation(ExecutionStore.Run run,Materials value,List<ExecutionCoordinator.BatchOutcome> results,List<ExecutionCoordinator.BatchResponse> responses){throw invalid();}
    /** 程序子调用没有模型决定步骤，只记录自身真实操作与原始调用身份。 */
    private ExecutionStore.Event observation(ExecutionStore.Run run,ExecutionStore.Prepared prepared,String name,ExecutionObservation.Status status) {
        var identity=run.identity();var action=prepared.action().actionId();
        return ExecutionObservation.event(new ExecutionObservation.Fact(action,ExecutionObservation.agentId(identity.runRef().runId()),
                ExecutionObservation.Kind.TOOL,name,status,identity.runRef().runId(),action,action+":attempt:1",null,null,
                identity.causationInputId().value(),identity.trace().rootInputId().value(),identity.trace().traceId(),identity.trace().spanId(),null,null,null),limits);
    }
    private static ExecutionStore.Material context(RunRef run,State state,Optional<ResultRef> result) {
        try {var value=new TreeMap<String,Object>();value.put("state",state.name());value.put("result",result.map(ResultRef::value).orElse(null));
            var payload=new TypedPayload(CONTEXT_SCHEMA,JSON.writeValueAsString(value));return new ExecutionStore.Material(new ResultRef("program-tool-context:"+AgentExecutionCodec.digest(run.runId()+"\0"+payload.canonicalJson())),payload);
        }catch(java.io.IOException failed){throw invalid();}
    }
    private static JsonNode parse(TypedPayload payload) {if(!CONTEXT_SCHEMA.equals(payload.schemaId()))throw invalid();try {var value=JSON.readTree(payload.canonicalJson());var keys=new HashSet<String>();value.fieldNames().forEachRemaining(keys::add);if(!keys.equals(Set.of("state","result")))throw invalid();return value;}catch(java.io.IOException failed){throw invalid();}}
    private static String text(JsonNode value,String key){var item=value.get(key);if(item==null||!item.isTextual()||item.asText().isBlank())throw invalid();return item.asText();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("program_tool_program_invalid");}
}

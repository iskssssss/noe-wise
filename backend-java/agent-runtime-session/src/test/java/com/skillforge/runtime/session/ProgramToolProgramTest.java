package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProgramToolProgramTest {
    @Test void successfulToolFeedbackSurvivesAdoptionContextReloadAndFinishesWithoutAnotherDispatch() {
        var fixture=new Fixture();
        var before=fixture.program.open(fixture.run,ProgramToolProgram.initial(SessionFixtures.IDENTITY.runRef()));
        var prepared=((ExecutionCoordinator.Step.Dispatch)fixture.program.next(fixture.run,before)).action();
        var outcome=new ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                "{\"content\":[],\"isError\":false,\"resultType\":\"complete\",\"structuredContent\":{\"rendered\":\"sample=27\",\"success\":true}}"),
                new ResultRef("actual-success"),List.of());
        var feedback=fixture.codec.safeToolFeedback(fixture.tool,outcome).map(value->new ExecutionStore.Material(new ResultRef("feedback"),value));
        var adopted=fixture.program.adopt(fixture.run,before,prepared,fixture.actual(outcome,ExecutionStore.ResultKind.SUCCEEDED,feedback));
        assertEquals(ProgramToolProgram.State.SUCCEEDED,adopted.materials().state());
        var observed=observation(adopted.events());
        assertEquals(ExecutionObservation.Status.SUCCEEDED,observed.status());
        assertEquals(prepared.action().actionId(),observed.operationId());
        assertEquals(prepared.action().actionId()+":attempt:1",observed.attemptId());
        assertFalse(adopted.events().getFirst().data().canonicalJson().contains("sample=27"));
        var reloaded=fixture.program.open(fixture.run,adopted.context());
        assertEquals(adopted.materials(),reloaded);
        var finish=assertInstanceOf(ExecutionCoordinator.Step.Finish.class,fixture.program.next(fixture.run,reloaded));
        assertEquals(RunStatus.COMPLETED,finish.status());
    }

    @Test void usesIndependentProgramIdentityAndOneActualToolBudget() {
        var fixture=new Fixture();var initial=ProgramToolProgram.initial(SessionFixtures.IDENTITY.runRef());var materials=fixture.program.open(fixture.run,initial);
        var dispatch=(ExecutionCoordinator.Step.Dispatch)fixture.program.next(fixture.run,materials);
        assertEquals(ProgramToolProgram.action(SessionFixtures.IDENTITY.runRef()),dispatch.action().action());
        assertTrue(dispatch.action().action().actionId().startsWith("program-tool-effect:"));assertFalse(dispatch.action().action().actionId().contains("workflow"));
        assertEquals(1,dispatch.budget().resources().tools());assertEquals(0,dispatch.budget().resources().businessModels());
        assertEquals(fixture.request,dispatch.action().request());assertEquals(SessionFixtures.ADMISSION,dispatch.actor());
        var observed=observation(dispatch.events());
        assertEquals(ExecutionObservation.Kind.TOOL,observed.kind());
        assertEquals("get_current_time",observed.name());
        assertEquals(ExecutionObservation.Status.REGISTERED,observed.status());
        assertEquals(SessionFixtures.IDENTITY.runRef().runId(),observed.runId());
        assertEquals(ExecutionObservation.agentId(observed.runId()),observed.parentOperationId());
        assertEquals(SessionFixtures.IDENTITY.causationInputId().value(),observed.inputId());
        assertEquals(SessionFixtures.IDENTITY.trace().rootInputId().value(),observed.rootInputId());
        assertEquals(SessionFixtures.IDENTITY.trace().traceId(),observed.traceId());
        assertEquals(SessionFixtures.IDENTITY.trace().spanId(),observed.spanId());
        assertNull(observed.stepId());assertNull(observed.iteration());assertNull(observed.stage());
        assertEquals(dispatch.events(),((ExecutionCoordinator.Step.Dispatch)fixture.program.next(fixture.run,materials)).events());
    }
    @Test void realInputRequiredKeepsActualPrivateWaitWithoutBecomingSuccess() {
        var fixture=new Fixture();var before=fixture.program.open(fixture.run,ProgramToolProgram.initial(SessionFixtures.IDENTITY.runRef()));
        var prepared=((ExecutionCoordinator.Step.Dispatch)fixture.program.next(fixture.run,before)).action();
        var required=new ToolOutcome.InputRequired(Map.of("confirmation",new ToolInputRequest(ToolInputMethod.ELICITATION_CREATE,new TypedPayload("application/json","{}"))),Optional.of("original-opaque-state"));
        var actual=fixture.actual(required,ExecutionStore.ResultKind.INPUT_REQUIRED,Optional.empty());
        var adopted=fixture.program.adopt(fixture.run,before,prepared,actual);
        assertEquals(ProgramToolProgram.State.DIRECT_REQUIRED,adopted.materials().state());assertEquals(Optional.of(actual.result().reference()),adopted.materials().result());
        assertEquals(ExecutionStore.WaitKind.TOOL,adopted.waiting().orElseThrow().kind());assertEquals("confirmation",adopted.waiting().orElseThrow().requested().keySet().iterator().next());
        assertEquals(ExecutionObservation.Status.WAITING,observation(adopted.events()).status());
        assertFalse(adopted.events().getFirst().data().canonicalJson().contains("original-opaque-state"));
    }
    @Test void failedToolKeepsItsActualSafeFeedbackAndUnknownKeepsItsOriginalResultWithoutRedispatch() {
        var fixture=new Fixture();var before=fixture.program.open(fixture.run,ProgramToolProgram.initial(SessionFixtures.IDENTITY.runRef()));
        var prepared=((ExecutionCoordinator.Step.Dispatch)fixture.program.next(fixture.run,before)).action();
        var outcome=new ToolOutcome.Failed(new PortFailure("actual_tool_failed",RetryMode.NONE,new ResultRef("actual-failure")));
        var feedback=fixture.codec.safeToolFeedback(fixture.tool,outcome).map(value->new ExecutionStore.Material(new ResultRef("feedback"),value));
        var adopted=fixture.program.adopt(fixture.run,before,prepared,fixture.actual(outcome,ExecutionStore.ResultKind.FAILED,feedback));
        assertEquals(ProgramToolProgram.State.FAILED,adopted.materials().state());
        assertEquals(ExecutionObservation.Status.FAILED,observation(adopted.events()).status());
        assertThrows(IllegalArgumentException.class,()->fixture.program.adopt(fixture.run,before,prepared,
                fixture.actual(outcome,ExecutionStore.ResultKind.SUCCEEDED,feedback)));
        var unknown=new ToolOutcome.OutcomeUnknown(new PortFailure("actual_unknown",RetryMode.NONE,new ResultRef("original-unknown")));
        var actual=fixture.actual(unknown,ExecutionStore.ResultKind.UNKNOWN,Optional.empty());
        var unresolved=fixture.program.adopt(fixture.run,before,prepared,actual);
        assertEquals(ProgramToolProgram.State.UNKNOWN,unresolved.materials().state());
        assertEquals(Optional.of(actual.result().reference()),unresolved.materials().result());
        assertEquals(ExecutionObservation.Status.UNKNOWN,observation(unresolved.events()).status());
        var recovered=fixture.program.open(fixture.run,unresolved.context());
        assertEquals(unresolved.materials(),recovered);
        assertInstanceOf(ExecutionCoordinator.Step.Yield.class,fixture.program.next(fixture.run,recovered));
    }
    private static ExecutionObservation.Fact observation(List<ExecutionStore.Event> events) {
        assertEquals(1,events.size());
        assertEquals(ExecutionObservation.EVENT_TYPE,events.getFirst().type());
        return ExecutionObservation.decode(events.getFirst().data(),SessionFixtures.LIMITS);
    }
    private static final class Fixture {
        final AgentExecutionCodec codec=new AgentExecutionCodec(SessionFixtures.LIMITS);
        final ExecutionStore.Run run=new ExecutionStore.Run(SessionFixtures.IDENTITY,new ResultRef("program-configuration"),RunStatus.QUEUED,1,0,false,
                ExecutionStore.Phase.READY,new ExecutionStore.Progress(0,0,0,ProgramToolProgram.initial(SessionFixtures.IDENTITY.runRef()).reference()),Optional.empty(),Optional.empty(),
                Optional.empty(),Optional.empty(),SessionFixtures.ADMISSION.verificationRef(),Optional.of(SessionFixtures.NOW),SessionFixtures.NOW,SessionFixtures.NOW);
        final FrozenToolTarget target=new FrozenToolTarget(new ToolHandle("tool:clock"),new CapabilityRef(CapabilityType.TOOL,"clock","1"),"clock-deployment",1,
                new TypedPayload("json-schema","{\"type\":\"object\"}"),new TypedPayload("json-schema","{\"type\":\"object\"}"),ToolEffect.READ_ONLY,true,Duration.ofSeconds(2),true,ToolConfirmationPolicy.NEVER,Set.of(),Set.of(),true);
        final AgentExecutionCodec.ToolRequest tool=new AgentExecutionCodec.ToolRequest(target,new ToolBinding(ToolBinding.ProviderType.PLATFORM,"get_current_time","get_current_time"),new TypedPayload("application/json","{}"),"call","get_current_time");
        final ExecutionStore.Material request=new ExecutionStore.Material(new ResultRef("program-request"),codec.encodeTool(tool));
        final ProgramToolProgram program=new ProgramToolProgram(current->new ProgramToolProgram.Configuration(SessionFixtures.ADMISSION,new ResultRef("capability"),request),SessionFixtures.LIMITS);
        ExecutionCoordinator.CapabilityResult actual(ToolOutcome outcome,ExecutionStore.ResultKind kind,Optional<ExecutionStore.Material> feedback) {
            var payloads=new SessionRuntimePayloads();var core=new CoreRuntime(SessionFixtures.LIMITS,payloads);
            var saved=new ExecutionStore.Material(new ResultRef("actual-result"),codec.encodeOutcome(core.portalResults(payloads,payloads,payloads).encodeTool(PortalCallKind.TOOL_INVOKE,outcome)));
            return new ExecutionCoordinator.CapabilityResult(kind,saved,feedback,Optional.empty(),ExecutionBudgetPort.Usage.unreported());
        }
    }
}

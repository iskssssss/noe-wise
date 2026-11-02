package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativePlanDecisionTest {
    private final AgentDecisionDecoder decoder = new AgentDecisionDecoder(new CodecLimits(100000,10000,64));
    @Test void primaryOnlyPlanAndAllOperationsSurviveFrozenRoundTrip() {
        var task = new PlanCommand.Task("analysis","分析",new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY,""),
                new DelegationCommand.Task("分析资料","",List.of(),"结论"),List.of(),Set.of("conclusion"),Set.of());
        var commands=List.of(
            new PlanCommand(PlanCommand.Operation.SUBMIT,"",0,Optional.of(new PlanCommand.Plan("报告","",List.of(task))),"","","",List.of(),List.of()),
            new PlanCommand(PlanCommand.Operation.GET,"work",0,Optional.empty(),"","","",List.of(),List.of()),
            new PlanCommand(PlanCommand.Operation.START_STEP,"work",1,Optional.empty(),"analysis","","",List.of(),List.of()),
            new PlanCommand(PlanCommand.Operation.FINISH_STEP,"work",1,Optional.empty(),"analysis","completed","结论",List.of(new ResultRef("real-result")),List.of(),Map.of("facts","事实结论","analysis","分析结论")),
            new PlanCommand(PlanCommand.Operation.WAIT,"work",0,Optional.empty(),"","","",List.of(),List.of("member-task")));
        for(var command:commands) {
            var action=new AgentAction.Plan(command);var frozen=decoder.encode(action);
            assertEquals(new AgentDecisionDecoder.Decoded(action),decoder.decodeSnapshot(frozen));
            assertEquals(new AgentDecisionDecoder.Decoded(action),decoder.decodeWire(frozen.canonicalJson()));
        }
    }
    @Test void finishMayOmitReferencesForTrustedHostBindingWithoutAcceptingUnknownFields() {
        String wire="{\"type\":\"plan\",\"operation\":\"finish_step\",\"work_id\":\"work\",\"expected_revision\":1,\"task_id\":\"step\",\"outcome\":\"completed\",\"summary\":\"结论\",\"outputs\":{\"answer\":\"内容\"}}";
        var result=assertInstanceOf(AgentDecisionDecoder.Decoded.class,decoder.decodeWire(wire));
        var action=assertInstanceOf(AgentAction.Plan.class,result.action());
        assertTrue(action.command().resultRefs().isEmpty());
        assertInstanceOf(AgentDecisionDecoder.Failure.class,decoder.decodeWire(wire.replace("\"outputs\"","\"unknown\":true,\"outputs\"")));
    }

    @Test void legacyDelegationPlanIsRejectedAndPlanControlsDoNotNeedDelegation() {
        assertInstanceOf(AgentDecisionDecoder.Failure.class,decoder.decodeWire("{\"type\":\"delegate\",\"operation\":\"submit_plan\",\"plan\":{}}"));
        assertTrue(NativeAgentTools.find("submit_plan").isEmpty());
        for(var op:PlanCommand.Operation.values()) {
            String wire=NativeAgentTools.wireName("plan",Optional.of(op.name().toLowerCase(Locale.ROOT)));
            assertEquals("plan",NativeAgentTools.find(wire).orElseThrow().actionType());
        }
    }
    @Test void primaryCannotImpersonateMemberAndCyclesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY,"agent:me"));
        assertThrows(IllegalArgumentException.class,()->new PlanCommand.Executor(PlanCommand.ExecutorType.MEMBER,""));
        var input=new PlanCommand.Input("self","one","output");
        var task=new PlanCommand.Task("one","One",new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY,""),new DelegationCommand.Task("goal","",List.of(),"result"),List.of(input),Set.of("output"),Set.of());
        assertThrows(IllegalArgumentException.class,()->new PlanCommand(PlanCommand.Operation.SUBMIT,"",0,Optional.of(new PlanCommand.Plan("Title","",List.of(task))),"","","",List.of(),List.of()));
    }
    @Test void futureTaskFragmentRoundTripsWithoutRequiringAdmittedDefinitionAtDecoder() {
        var future = new PlanCommand.Task("T2", "分析", new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("分析事实", "", List.of(), "结论"),
                List.of(new PlanCommand.Input("facts", "T1", "facts")), Set.of("answer"), Set.of());
        for (var tasks : List.of(List.of(future), List.<PlanCommand.Task>of())) {
            var command = new PlanCommand(PlanCommand.Operation.SUBMIT, "work", 3,
                    Optional.of(new PlanCommand.Plan("计划", "只修订未来任务", tasks)), "", "", "", List.of(), List.of());
            var action = new AgentAction.Plan(command);
            var frozen = decoder.encode(action);
            assertEquals(new AgentDecisionDecoder.Decoded(action), decoder.decodeWire(frozen.canonicalJson()));
            assertEquals(new AgentDecisionDecoder.Decoded(action), decoder.decodeSnapshot(frozen));
            assertInstanceOf(AgentDecisionDecoder.Failure.class,
                    decoder.decodeWire(frozen.canonicalJson().replace("\"expected_revision\":3", "\"expected_revision\":0")));
            if (!tasks.isEmpty()) {
                String wire = frozen.canonicalJson();
                assertInstanceOf(AgentDecisionDecoder.Failure.class,
                        decoder.decodeWire(wire.replace("\"inputs\":", "\"untrusted\":true,\"inputs\":")));
                assertInstanceOf(AgentDecisionDecoder.Failure.class,
                        decoder.decodeWire(wire.replace("\"cancelled\":false,", "")));
                assertInstanceOf(AgentDecisionDecoder.Failure.class,
                        decoder.decodeWire(wire.replace("[\"answer\"]", "[\"answer\",\"answer\"]")));
            }
        }
    }

    @Test void selfReplacementHasClosedReasonWithoutRelaxingApiOrCanonicalSnapshotRules() {
        var task = new PlanCommand.Task("step2", "第二步", new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("分析", "", List.of(), "结论"), List.of(), Set.of("answer"), Set.of());
        var command = new PlanCommand(PlanCommand.Operation.SUBMIT, "work", 2,
                Optional.of(new PlanCommand.Plan("计划", "修订", List.of(task))), "", "", "", List.of(), List.of());
        var valid = decoder.encode(new AgentAction.Plan(command));
        String invalid = valid.canonicalJson().replace("\"replaces_task_id\":\"\"", "\"replaces_task_id\":\"step2\"");
        assertNotEquals(valid.canonicalJson(), invalid);
        var expected = new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.PLAN_TASK_SELF_REPLACEMENT);
        assertEquals(expected, decoder.decodeWire(invalid));
        assertEquals(expected, decoder.decodeSnapshot(new com.skillforge.runtime.api.TypedPayload(valid.schemaId(), invalid)));
        assertEquals(expected, decoder.decodeWire(" " + invalid));
        assertEquals(new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.INVALID_DECISION),
                decoder.decodeSnapshot(new com.skillforge.runtime.api.TypedPayload(valid.schemaId(), " " + invalid)));
        assertInstanceOf(AgentDecisionDecoder.Decoded.class, decoder.decodeSnapshot(valid));
        assertThrows(IllegalArgumentException.class, () -> new PlanCommand.Task(task.id(), task.title(), task.executor(),
                task.task(), task.inputs(), task.outputs(), task.exclusiveResources(), false, task.id()));
    }

}

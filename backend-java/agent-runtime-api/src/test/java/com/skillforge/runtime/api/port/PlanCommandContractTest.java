package com.skillforge.runtime.api.port;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanCommandContractTest {
    @Test void snapshotPreservesExplicitJsonNullWithoutExposingMutableMap() {
        var values=new LinkedHashMap<String,Object>();values.put("approved_revision",null);values.put("status","pending_approval");
        var reply=new PlanPort.Reply(values,Optional.empty());values.put("status","changed");
        assertTrue(reply.value().containsKey("approved_revision"));assertNull(reply.value().get("approved_revision"));
        assertEquals("pending_approval",reply.value().get("status"));
        assertThrows(UnsupportedOperationException.class,()->reply.value().put("another",null));
    }
    @Test void failedOrUnknownStepCannotCarryCompletedLogicalOutputs() {
        for(var outcome:List.of("failed","unknown"))
            assertThrows(IllegalArgumentException.class,()->new PlanCommand(PlanCommand.Operation.FINISH_STEP,"work",1,Optional.empty(),"step",outcome,"reason",List.of(),List.of(),Map.of("answer","not complete")));
    }
    @Test void revisionCarriesFutureTasksAndMayReferenceAdmittedTasksOrRemoveAllFutureTasks() {
        var future = task("T2", List.of(new PlanCommand.Input("facts", "T1", "facts")));
        assertDoesNotThrow(() -> submit(2, List.of(future)));
        assertDoesNotThrow(() -> submit(2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> submit(0, List.of(future)), "初次图不能引用缺失任务");
        assertThrows(IllegalArgumentException.class, () -> submit(0, List.of()), "初次完整图不能为空");
        var first = task("T1", List.of());
        assertDoesNotThrow(() -> submit(0, List.of(first, future)));
    }

    @Test void fragmentStillRejectsDuplicateIdsAndInvalidLocalShape() {
        var one = task("T2", List.of());
        assertThrows(IllegalArgumentException.class, () -> submit(2, List.of(one, one)));
        var same = new PlanCommand.Input("facts", "T1", "facts");
        assertThrows(IllegalArgumentException.class, () -> task("T2", List.of(same, same)));
        assertThrows(IllegalArgumentException.class, () -> new PlanCommand.Input("", "T1", "facts"));
        assertThrows(IllegalArgumentException.class, () -> new PlanCommand.Input("facts", "", "facts"));
        assertThrows(IllegalArgumentException.class, () -> new PlanCommand.Input("facts", "T1", ""));
        var cyclic = task("T1", List.of(new PlanCommand.Input("self", "T1", "facts")));
        assertThrows(IllegalArgumentException.class, () -> submit(0, List.of(cyclic)));
        var missingOutput = task("T2", List.of(new PlanCommand.Input("facts", "T1", "absent")));
        assertThrows(IllegalArgumentException.class, () -> submit(0, List.of(task("T1", List.of()), missingOutput)));
    }

    @Test void initialGraphRejectsMultiStepCyclesAndAcceptsSharedUpstream() {
        var first = task("T1", List.of(new PlanCommand.Input("facts", "T2", "facts")));
        var second = task("T2", List.of(new PlanCommand.Input("facts", "T1", "facts")));
        assertThrows(IllegalArgumentException.class, () -> submit(0, List.of(first, second)));
        var upstream = task("T0", List.of());
        var left = task("T1", List.of(new PlanCommand.Input("facts", "T0", "facts")));
        var right = task("T2", List.of(new PlanCommand.Input("facts", "T0", "facts")));
        assertDoesNotThrow(() -> submit(0, List.of(upstream, left, right)));
    }

    private static PlanCommand.Task task(String id, List<PlanCommand.Input> inputs) {
        return new PlanCommand.Task(id, "Task " + id, new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("分析", "", List.of(), "事实"), inputs, Set.of("facts"), Set.of());
    }
    private static PlanCommand submit(long revision, List<PlanCommand.Task> tasks) {
        return new PlanCommand(PlanCommand.Operation.SUBMIT, revision == 0 ? "" : "work", revision,
                Optional.of(new PlanCommand.Plan("计划", "修订未来任务", tasks)), "", "", "", List.of(), List.of());
    }

}

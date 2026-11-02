package com.skillforge.institution.artifact;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 成果发布只消费真实完成事务中的独立成果证明，不能借模型结果或单独终态放行。 */
class DeliverableExecutionProofTest {
    private final ObjectMapper json=new ObjectMapper();
    private final TenantId tenant=new TenantId("1");
    private final RunRef reference=new RunRef(tenant,"run");

    /** 同版本完成事件明确携带宿主已冻结的taskResultRef才具有发布资格。 */
    @Test void exactCompletedTransactionCarriesFrozenTaskResultReference() {
        var run=run();
        assertTrue(DeliverableService.completedReference(run,event(reference,7,"COMPLETED","task-result:run","model-result:unrelated"),"task-result:run",json));
    }

    /** 普通模型引用、缺失证明、另一Run和不同提交版本都不能变成成果发布。 */
    @Test void modelReferenceWrongRunWrongRevisionAndMissingProofAreRejected() {
        var run=run();
        for(var saved:java.util.List.of(event(reference,7,"COMPLETED",null,"task-result:run"),
                event(reference,7,"COMPLETED","task-result:other","task-result:run"),
                event(new RunRef(tenant,"other"),7,"COMPLETED","task-result:run",null),
                event(reference,6,"COMPLETED","task-result:run",null),
                event(reference,7,"FAILED","task-result:run",null)))
            assertFalse(DeliverableService.completedReference(run,saved,"task-result:run",json));
        when(run.status()).thenReturn(RunStatus.RUNNING);
        assertFalse(DeliverableService.completedReference(run,event(reference,7,"COMPLETED","task-result:run",null),"task-result:run",json));
    }

    /** 仅替换Store只读事实，生产验证仍核当前真实Run身份和FINISHED版本。 */
    private ExecutionStore.Run run() {
        var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("agent"),new EndUserId(9));
        var input=new InputId("input");
        var identity=new RunIdentity(owner, new AgentId((owner).target().id()), input, reference, new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("original"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var run=mock(ExecutionStore.Run.class);when(run.identity()).thenReturn(identity);when(run.status()).thenReturn(RunStatus.COMPLETED);
        when(run.phase()).thenReturn(ExecutionStore.Phase.FINISHED);when(run.revision()).thenReturn(7L);return run;
    }

    /** 真实业务事件结构与同Store.finish提交身份；不构造旧RunOutcome或presentation。 */
    private ExecutionStore.SavedEvent event(RunRef run,long revision,String status,String taskResult,String modelResult) {
        var value=json.createObjectNode();value.put("status",status);
        if(taskResult==null)value.putNull("taskResultRef");else value.put("taskResultRef",taskResult);
        if(modelResult==null)value.putNull("resultRef");else value.put("resultRef",modelResult);
        return new ExecutionStore.SavedEvent(run,10,revision,Instant.EPOCH,new ExecutionStore.Event(
                AgentExecutionProgram.finishedEventId(run),AgentExecutionProgram.COMPLETED_EVENT,
                new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA,value.toString())));
    }
}

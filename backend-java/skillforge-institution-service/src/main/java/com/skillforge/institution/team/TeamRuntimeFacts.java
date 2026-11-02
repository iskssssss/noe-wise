package com.skillforge.institution.team;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 团队成果仅从新链准确已采纳Attempt及不可变实际材料取证，不读取旧RunOutcome或Action账本。 */
@Component
public final class TeamRuntimeFacts {
    private final ExecutionStore store;
    private final AgentExecutionCodec codec;
    private final ObjectMapper json;

    public TeamRuntimeFacts(ExecutionStore store,AgentExecutionCodec codec,ObjectMapper json) {
        this.store=store;this.codec=codec;this.json=json;
    }

    public ExecutionStore.Run run(SessionOwner owner,RunRef reference) {
        var run=store.load(reference).orElseThrow(()->invalid("task_result_run_fact_missing"));
        if(!run.identity().runRef().equals(reference)||!run.identity().owner().equals(owner))throw invalid("task_result_run_owner_mismatch");return run;
    }

    public String completedText(ExecutionStore.Run run) {
        if(run.status()!=RunStatus.COMPLETED)throw invalid("task_result_run_not_completed");
        ExecutionStore.Action last=null;long after=0;
        while(true) {
            var page=store.actions(run.identity().runRef(),after,100);
            for(var action:page) {
                if(action.ordinal()<=after||!action.run().equals(run.identity().runRef()))throw invalid("task_result_action_owner_mismatch");after=action.ordinal();
                if(action.kind()==ExecutionStore.Kind.MODEL)last=action;
            }
            if(page.size()<100)break;
        }
        if(last==null)throw invalid("task_result_final_model_missing");var attempt=selected(last);
        if(last.status()!=ExecutionStore.ActionStatus.SUCCEEDED||attempt.resultKind().orElseThrow(()->invalid("task_result_final_model_not_succeeded"))!=ExecutionStore.ResultKind.SUCCEEDED)throw invalid("task_result_final_model_not_succeeded");
        var actual=codec.decodeModelOutcome(material(run.identity().runRef(),attempt.result().orElseThrow()).content());
        if(!(actual instanceof ModelResult.Completed))throw invalid("task_result_final_model_not_completed");
        // 唯一完成事件保存的是Runtime已经解释并通过引用/输出检查的答复，原生正文不能重当动作JSON解析。
        var eventId=AgentExecutionProgram.finishedEventId(run.identity().runRef());
        var saved=store.event(run.identity().runRef(),eventId).orElseThrow(()->invalid("task_result_final_response_missing"));
        if(!saved.run().equals(run.identity().runRef()) || saved.revision()!=run.revision()
                || !saved.event().id().equals(eventId) || !saved.event().type().equals(AgentExecutionProgram.COMPLETED_EVENT))
            throw invalid("task_result_final_response_mismatch");
        if(!saved.event().data().schemaId().equals(AgentExecutionProgram.COMPLETED_SCHEMA)) throw invalid("task_result_final_response_mismatch");
        final JsonNode response;
        try { response=json.readTree(saved.event().data().canonicalJson()); }
        catch(JsonProcessingException malformed) { throw invalid("task_result_final_response_mismatch"); }
        if(!response.path("status").asText().equals(RunStatus.COMPLETED.name())
                || !response.path("reason").asText().equals("completed")
                || !response.path("resultRef").asText().equals(attempt.result().orElseThrow().value())
                || !response.path("inputId").asText().equals(run.identity().causationInputId().value())
                || !response.path("agentId").asText().equals(run.identity().agentId().value())
                || !response.path("text").isTextual() || response.path("text").asText().isBlank())
            throw invalid("task_result_final_response_mismatch");
        return response.path("text").asText();
    }

    public Operation operation(RunIdentity expected,ActionRef reference,String invocation,String attemptId,String resultRef) {
        var run=run(expected.owner(),expected.runRef());if(!run.identity().equals(expected))throw invalid("operation_result_run_identity_mismatch");
        var action=store.action(reference).orElseThrow(()->invalid("operation_result_action_missing"));
        if(!action.run().equals(expected.runRef())||action.kind()!=ExecutionStore.Kind.TOOL||!action.reference().equals(reference)
                ||!reference.actionId().equals(invocation)||!action.selectedAttempt().equals(Optional.of(attemptId)))throw invalid("operation_result_receipt_mismatch");
        var attempt=selected(action);
        if(!attempt.id().equals(attemptId)||!attempt.result().orElseThrow().value().equals(resultRef))throw invalid("operation_result_receipt_mismatch");
        if(attempt.resultKind().isEmpty()||attempt.resultKind().orElseThrow()==ExecutionStore.ResultKind.UNKNOWN||action.status()==ExecutionStore.ActionStatus.UNKNOWN)
            throw invalid("operation_result_action_not_determinate");
        var request=codec.decodeTool(material(expected.runRef(),attempt.request()).content());
        var outcome=codec.decodeToolOutcome(material(expected.runRef(),attempt.result().orElseThrow()).content());
        String status;TypedPayload observation;
        if(action.status()==ExecutionStore.ActionStatus.SUCCEEDED&&attempt.resultKind().orElseThrow()==ExecutionStore.ResultKind.SUCCEEDED&&outcome instanceof ToolOutcome.Succeeded) {
            status="succeeded";observation=material(expected.runRef(),attempt.feedback().orElseThrow(()->invalid("operation_result_public_observation_missing"))).content();
        } else if(action.status()==ExecutionStore.ActionStatus.FAILED&&attempt.resultKind().orElseThrow()==ExecutionStore.ResultKind.FAILED&&outcome instanceof ToolOutcome.Failed) {
            status="failed";observation=material(expected.runRef(),attempt.feedback().orElseThrow(()->invalid("operation_result_failure_reason_missing"))).content();
        } else throw invalid("operation_result_action_not_determinate");
        if(!codec.safeToolFeedback(request,outcome).equals(Optional.of(observation)))throw invalid("operation_result_public_observation_mismatch");
        return new Operation(request.binding().toolName(),status,action.status().name(),observation);
    }

    private ExecutionStore.Attempt selected(ExecutionStore.Action action) {
        var attempt=store.attempt(action.run(),action.selectedAttempt().orElseThrow(()->invalid("operation_result_action_not_determinate"))).orElseThrow(()->invalid("operation_result_fact_missing"));
        if(!attempt.action().equals(action.reference())||!attempt.run().equals(action.run())||!attempt.adopted()||attempt.result().isEmpty())throw invalid("operation_result_receipt_mismatch");return attempt;
    }
    private ExecutionStore.Material material(RunRef run,ResultRef reference){var result=store.material(run.tenantId(),reference).orElseThrow(()->invalid("task_result_material_missing"));if(!result.reference().equals(reference))throw invalid("task_result_material_mismatch");return result;}
    public record Operation(String toolName,String status,String actionState,TypedPayload observation) { }
    public static final class EvidenceRejected extends IllegalStateException {
        private EvidenceRejected(String reason){super(reason);}
    }
    private static EvidenceRejected invalid(String reason){return new EvidenceRejected(reason);}
}

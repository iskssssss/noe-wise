package com.skillforge.institution.team;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.AgentExecutionProgram;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 团队私有成果必须来自新链已采纳的准确模型结果，不能用公开投影代替。 */
class TeamRuntimeFactsTest {
    @Test void readsExactAdoptedFinalRespondFromActualModelOutcome() {
        var fixture = new Fixture();
        fixture.outcome("{\"type\":\"respond\",\"output\":\"准确私有成果\",\"evidence_item_ids\":[]}");
        fixture.finished("准确私有成果", fixture.result);
        assertEquals("准确私有成果", fixture.facts.completedText(fixture.run));
    }

    @Test void readsAdoptedNativeFinalResponseWithoutReinterpretingBodyAsAnAction() {
        var fixture = new Fixture();
        String text = "{\"type\":\"ask_user\",\"question\":\"业务示例正文\"}";
        var turn = new ModelPort.AssistantTurn(List.of(new ModelPort.AssistantContent.Text(text)));
        fixture.actual(new ModelResult.Completed(new TypedPayload(ModelPort.TEXT_OUTPUT_SCHEMA, new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(text).toString()), "model",
                new ModelUsage(OptionalLong.of(3), OptionalLong.of(2)), "stop", new ResultRef("provider-result"),
                Optional.empty(), Optional.of(turn), Optional.empty()));
        fixture.finished(text, fixture.result);
        assertEquals(text, fixture.facts.completedText(fixture.run));
    }

    @Test void rejectsFinalResponseBoundToAnotherResultOrRevision() {
        var fixture = new Fixture();
        fixture.outcome("{\"type\":\"respond\",\"output\":\"成果\",\"evidence_item_ids\":[]}");
        fixture.finished("成果", new ResultRef("another-result"));
        assertEquals("task_result_final_response_mismatch", assertThrows(TeamRuntimeFacts.EvidenceRejected.class,
                () -> fixture.facts.completedText(fixture.run)).getMessage());
        fixture.finished("成果", fixture.result);
        when(fixture.run.revision()).thenReturn(8L);
        assertEquals("task_result_final_response_mismatch", assertThrows(TeamRuntimeFacts.EvidenceRejected.class,
                () -> fixture.facts.completedText(fixture.run)).getMessage());
    }

    @Test void rejectsSuccessfulModelThatStillRequestsAnotherAction() {
        var fixture = new Fixture();
        fixture.outcome("{\"type\":\"ask_user\",\"question\":\"请补充\"}");
        assertEquals("task_result_final_response_missing", assertThrows(TeamRuntimeFacts.EvidenceRejected.class,
                () -> fixture.facts.completedText(fixture.run)).getMessage());
    }

    @Test void rejectsUnadoptedOrUnknownSelectedAttempt() {
        var fixture = new Fixture();
        fixture.outcome("{\"type\":\"respond\",\"output\":\"不能凭内容宣布成功\",\"evidence_item_ids\":[]}");
        when(fixture.attempt.adopted()).thenReturn(false);
        assertEquals("operation_result_receipt_mismatch", assertThrows(TeamRuntimeFacts.EvidenceRejected.class,
                () -> fixture.facts.completedText(fixture.run)).getMessage());
        when(fixture.attempt.adopted()).thenReturn(true);
        when(fixture.attempt.resultKind()).thenReturn(Optional.of(ExecutionStore.ResultKind.UNKNOWN));
        assertEquals("task_result_final_model_not_succeeded", assertThrows(TeamRuntimeFacts.EvidenceRejected.class,
                () -> fixture.facts.completedText(fixture.run)).getMessage());
    }

    @Test void refusesNonterminalRunAndAnotherRunsSelectedAttempt() {
        var fixture = new Fixture();
        when(fixture.run.status()).thenReturn(RunStatus.RUNNING);
        assertEquals("task_result_run_not_completed", assertThrows(TeamRuntimeFacts.EvidenceRejected.class,
                () -> fixture.facts.completedText(fixture.run)).getMessage());
        when(fixture.run.status()).thenReturn(RunStatus.COMPLETED);
        when(fixture.attempt.run()).thenReturn(new RunRef(fixture.tenant,"other-run"));
        assertEquals("operation_result_receipt_mismatch", assertThrows(TeamRuntimeFacts.EvidenceRejected.class,
                () -> fixture.facts.completedText(fixture.run)).getMessage());
    }

    private static final class Fixture {
        final TenantId tenant = new TenantId("1");
        final RunRef reference = new RunRef(tenant,"member-run");
        final ExecutionStore store = mock(ExecutionStore.class);
        final ExecutionStore.Run run = mock(ExecutionStore.Run.class);
        final ExecutionStore.Action action = mock(ExecutionStore.Action.class);
        final ExecutionStore.Attempt attempt = mock(ExecutionStore.Attempt.class);
        final CodecLimits limits = new CodecLimits(1_000_000,100_000,128);
        final AgentExecutionCodec codec = new AgentExecutionCodec(limits);
        final TeamRuntimeFacts facts = new TeamRuntimeFacts(store,codec,new com.fasterxml.jackson.databind.ObjectMapper());
        final ResultRef result = new ResultRef("actual-model-result");
        Fixture() {
            var owner = SessionOwner.user(new SessionRef(tenant,"member-session"),new AgentId("7"),new EndUserId(9));
            var input = new InputId("input");
            var identity = new RunIdentity(owner, new AgentId((owner).target().id()), input, reference, new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            var actionRef = new ActionRef(tenant,"final-model");
            when(run.identity()).thenReturn(identity);when(run.status()).thenReturn(RunStatus.COMPLETED);when(run.revision()).thenReturn(7L);
            when(store.actions(reference,0,100)).thenReturn(List.of(action));
            when(action.run()).thenReturn(reference);when(action.reference()).thenReturn(actionRef);
            when(action.ordinal()).thenReturn(4L);when(action.kind()).thenReturn(ExecutionStore.Kind.MODEL);
            when(action.status()).thenReturn(ExecutionStore.ActionStatus.SUCCEEDED);
            when(action.selectedAttempt()).thenReturn(Optional.of("selected"));
            when(store.attempt(reference,"selected")).thenReturn(Optional.of(attempt));
            when(attempt.run()).thenReturn(reference);when(attempt.action()).thenReturn(actionRef);
            when(attempt.adopted()).thenReturn(true);when(attempt.result()).thenReturn(Optional.of(result));
            when(attempt.resultKind()).thenReturn(Optional.of(ExecutionStore.ResultKind.SUCCEEDED));
        }
        void outcome(String document) {
            var actual = new ModelResult.Completed(new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA,document),"model",
                    new ModelUsage(OptionalLong.of(3),OptionalLong.of(2)),"stop",new ResultRef("provider-result"));
            actual(actual);
        }
        void actual(ModelResult.Completed actual) {
            var payloads = new SessionRuntimePayloads();
            var core = new CoreRuntime(limits,payloads);
            var raw = codec.encodeOutcome(core.portalResults(payloads,payloads,payloads).encodeModel(PortalCallKind.MODEL_GENERATE,actual));
            when(store.material(tenant,result)).thenReturn(Optional.of(new ExecutionStore.Material(result,raw)));
        }
        void finished(String text, ResultRef source) {
            var body = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            body.put("text", text);body.put("status", "COMPLETED");body.put("reason", "completed");
            body.put("resultRef", source.value());body.put("inputId", run.identity().causationInputId().value());
            body.put("agentId", run.identity().agentId().value());
            var id = AgentExecutionProgram.finishedEventId(reference);
            var event = new ExecutionStore.Event(id, AgentExecutionProgram.COMPLETED_EVENT, new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA, body.toString()));
            when(store.event(reference, id)).thenReturn(Optional.of(new ExecutionStore.SavedEvent(reference, 10, 7, Instant.EPOCH, event)));
        }
    }
}

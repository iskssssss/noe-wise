package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 团队回复只消费新链准确原child的Run/Wait/版本，不接受旧checkpoint或来源重建协议。 */
class TeamRuntimeBridgeTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final TenantId TENANT=new TenantId("7");
    private static final RunRef RUN=new RunRef(TENANT,"actual-child-run");
    private static final ExecutionStore.WaitRef WAIT=new ExecutionStore.WaitRef(new ActionRef(TENANT,"actual-tool-action"),3);

    /** 原child和parent完成后重放同一Human输入，仍走当前读授权和正式Gateway/Store精确回执核验。 */
    @Test void completedDelegateHumanReplayUsesOriginalReceiptAndCurrentReadAuthorization() {
        var fixture=new CompletedHumanFixture();
        var result=fixture.respond(fixture.input.value(),WAIT.action().actionId()+":3","原始回复");
        assertFalse(result.accepted());assertEquals(fixture.waiting,result.waiting());
        verify(fixture.bindings).authorizeRead(fixture.binding,"8");verify(fixture.bindings,never()).authorize(any());
        verify(fixture.gateway).verify(fixture.run,fixture.input);
        verify(fixture.gateway).resumeHuman(fixture.run,fixture.actor,fixture.input,WAIT,4L,"原始回复");
        verify(fixture.store,never()).waiting(any(),any(ExecutionStore.WaitRef.class));verify(fixture.store,never()).batchWaits(any());
    }

    /** 回执存在也必须通过当前读权限；撤权后不触达命令Gateway或恢复输入。 */
    @Test void completedDelegateHumanReplayRejectsRevokedCurrentReadAccess() {
        var fixture=new CompletedHumanFixture();
        doThrow(new SecurityException("delegation_requester_revoked")).when(fixture.bindings).authorizeRead(fixture.binding,"8");
        assertThrows(SecurityException.class,()->fixture.respond(fixture.input.value(),WAIT.action().actionId()+":3","原始回复"));
        verifyNoInteractions(fixture.gateway);verify(fixture.bindings,never()).authorize(any());
    }

    /** 终态child的新input没有原回执，必须维持原执行授权拒绝，不能借历史回答重新激活。 */
    @Test void completedDelegateNewHumanInputStillRequiresExecutionAuthorization() {
        var fixture=new CompletedHumanFixture();
        var denied=assertThrows(SecurityException.class,()->fixture.respond("new-human-input",WAIT.action().actionId()+":3","原始回复"));
        assertEquals("delegation_parent_ended",denied.getMessage());
        verify(fixture.bindings).authorize(fixture.binding);verify(fixture.bindings,never()).authorizeRead(any(),anyString());
        verifyNoInteractions(fixture.gateway);verify(fixture.store,never()).waiting(any(),any(ExecutionStore.WaitRef.class));
    }

    /** 重放不直接返回成功；错Wait在Bridge拒绝，替换正文继续由正式Gateway/Store冲突结果拒绝。 */
    @Test void completedDelegateReplayRejectsChangedWaitAndForwardsChangedBodyToStoreGuard() {
        var wrongWait=new CompletedHumanFixture();
        assertEquals(409,assertThrows(ApiException.class,()->wrongWait.respond(wrongWait.input.value(),"other-action:3","原始回复")).getStatus().value());
        verifyNoInteractions(wrongWait.gateway);
        var wrongBody=new CompletedHumanFixture();
        doThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT,"execution_response_conflict")).when(wrongBody.gateway)
                .resumeHuman(wrongBody.run,wrongBody.actor,wrongBody.input,WAIT,4L,"被替换的回复");
        assertEquals(409,assertThrows(ApiException.class,()->wrongBody.respond(wrongBody.input.value(),WAIT.action().actionId()+":3","被替换的回复")).getStatus().value());
        verify(wrongBody.gateway).resumeHuman(wrongBody.run,wrongBody.actor,wrongBody.input,WAIT,4L,"被替换的回复");
        verify(wrongBody.bindings,never()).authorize(any());verify(wrongBody.store,never()).waiting(any(),any(ExecutionStore.WaitRef.class));
    }

    @Test void earlyBatchReplyKeepsDeclaredRevisionWhileSiblingAttemptStillRuns() throws Exception {
        var fixture=new BatchFixture();var fields=body();fields.put("run_revision","4");
        var result=fixture.bridge.respond(fixture.work,RUN,"early-input","workflow",WAIT.action().actionId()+":3","4",JSON.writeValueAsString(fields),java.time.Instant.now());
        assertTrue(result.accepted());verify(fixture.gateway).resume(same(fixture.run),same(fixture.actor),eq(new com.skillforge.runtime.api.InputId("early-input")),eq(WAIT),eq(4L),anyMap());
        verify(fixture.store).batchWaits(fixture.batch);assertEquals(ExecutionStore.Phase.AWAITING_RESULT,fixture.run.phase());
    }

    @Test void duplicateBatchReplyUsesOriginalWaitAndSuppliedVersionWithoutRevisionArithmetic() throws Exception {
        var fixture=new BatchFixture();var input=new com.skillforge.runtime.api.InputId("early-input");
        var receipt=new ExecutionStore.AcceptedResponse(input,new com.skillforge.runtime.api.ResultRef("original-response"),"fingerprint",new com.skillforge.runtime.api.ResultRef("actor"),9);
        var waiting=new ExecutionStore.Waiting(WAIT,ExecutionStore.WaitKind.TOOL,RUN,"original-attempt",Map.of("actual-request",ToolInputMethod.ELICITATION_CREATE),java.util.Optional.empty(),java.util.Optional.of(receipt),java.util.Optional.empty(),java.util.OptionalLong.empty());
        when(fixture.store.response(RUN,input)).thenReturn(java.util.Optional.of(waiting));var fields=body();fields.put("run_revision","4");
        fixture.bridge.respond(fixture.work,RUN,input.value(),"workflow",WAIT.action().actionId()+":3","4",JSON.writeValueAsString(fields),java.time.Instant.now());
        verify(fixture.gateway).resume(same(fixture.run),same(fixture.actor),eq(input),eq(WAIT),eq(4L),anyMap());verify(fixture.store,never()).batchWaits(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryConstructionAndStateReadsDoNotResolveCommandGateway() {
        var gateway=(org.springframework.beans.factory.ObjectProvider<AgentRuntimeExecutionGateway>)
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var store=org.mockito.Mockito.mock(ExecutionStore.class);
        var bridge=new TeamRuntimeBridge(store,gateway,org.mockito.Mockito.mock(AgentRuntimeExecutionSteering.class),
                org.mockito.Mockito.mock(AgentRunBindings.class),JSON,value->true,org.mockito.Mockito.mock(AgentRuntimeExecutionProjection.class));
        var run=org.mockito.Mockito.mock(ExecutionStore.Run.class);
        org.mockito.Mockito.when(run.status()).thenReturn(com.skillforge.runtime.api.RunStatus.COMPLETED);
        assertTrue(bridge.terminal(run));
        org.mockito.Mockito.verifyNoInteractions(gateway,store);
    }

    private Map<String,Object> body() {
        return new HashMap<>(Map.of("execution_id",RUN.runId(),"run_revision","9","tool_input",Map.of(
                "wait",Map.of("action_id",WAIT.action().actionId(),"generation","3"),
                "responses",Map.of("actual-request",Map.of("method","elicitation/create",
                        "result",Map.of("schema_id","application/json","canonical_json","{\"buildingParkId\":\"actual-choice\"}"))))));
    }

    @Test
    void keepsExactOriginalChildWaitVersionAndTypedResponse() throws Exception {
        var reply=TeamRuntimeBridge.toolReply(RUN,WAIT,9,JSON.writeValueAsString(body()),JSON,value->"application/json".equals(value.schemaId()));
        assertEquals(RUN,reply.run());assertEquals(WAIT,reply.waiting());assertEquals(9,reply.revision());
        var response=reply.responses().get("actual-request");assertEquals(ToolInputMethod.ELICITATION_CREATE,response.method());
        assertEquals("{\"buildingParkId\":\"actual-choice\"}",response.result().canonicalJson());
    }

    @Test
    void rejectsOtherRunWaitGenerationAndStaleRevision() throws Exception {
        var run=body();run.put("execution_id","parent-or-another-child");reject(run);
        var revision=body();revision.put("run_revision","8");reject(revision);
        var numeric=body();numeric.put("run_revision",9);reject(numeric);
        var otherAction=body();otherAction.put("tool_input",Map.of("wait",Map.of("action_id","other-action","generation","3"),
                "responses",((Map<?,?>)body().get("tool_input")).get("responses")));reject(otherAction);
        var otherGeneration=body();otherGeneration.put("tool_input",Map.of("wait",Map.of("action_id",WAIT.action().actionId(),"generation","4"),
                "responses",((Map<?,?>)body().get("tool_input")).get("responses")));reject(otherGeneration);
    }

    @Test
    void rejectsOldCheckpointEnvelopeUnknownFieldsAndUnregisteredBusinessPayload() throws Exception {
        var old=body();old.put("checkpoint_revision","4");reject(old);
        assertThrows(ApiException.class,()->TeamRuntimeBridge.toolReply(RUN,WAIT,9,JSON.writeValueAsString(body()),JSON,value->false));
        assertThrows(ApiException.class,()->TeamRuntimeBridge.toolReply(RUN,WAIT,9,"{\"suspend_ref\":\"old\",\"checkpoint_revision\":\"4\"}",JSON,value->true));
    }

    @Test
    void pendingIdentityBindsTheOriginalActionAndGeneration() {
        var pending=new TeamRuntimeBridge.Pending(RUN,WAIT,9,ExecutionStore.WaitKind.TOOL,new com.skillforge.runtime.api.TypedPayload("card","{}"));
        assertEquals("actual-tool-action:3",pending.reference());assertEquals(9,pending.revision());assertEquals(WAIT,pending.waiting());
    }

    private void reject(Map<String,Object> body) throws Exception {
        String text=JSON.writeValueAsString(body);
        assertThrows(ApiException.class,()->TeamRuntimeBridge.toolReply(RUN,WAIT,9,text,JSON,value->true));
    }

    /** 模拟已完成委派的原Human回执；acceptedRevision与声明版本刻意不同，不可做减一推导。 */
    private static final class CompletedHumanFixture {
        final ExecutionStore store=mock(ExecutionStore.class);final AgentRuntimeExecutionGateway gateway=mock(AgentRuntimeExecutionGateway.class);
        final AgentRunBindings bindings=mock(AgentRunBindings.class);final AgentRunBindings.Binding binding=mock(AgentRunBindings.Binding.class,RETURNS_DEEP_STUBS);
        final ExecutionStore.Run run=mock(ExecutionStore.Run.class);final com.skillforge.runtime.api.port.VerifiedIdentity actor=mock(com.skillforge.runtime.api.port.VerifiedIdentity.class);
        final com.skillforge.runtime.api.dag.DagWork work=mock(com.skillforge.runtime.api.dag.DagWork.class,RETURNS_DEEP_STUBS);
        final com.skillforge.runtime.api.InputId input=new com.skillforge.runtime.api.InputId("original-human-input");
        final ExecutionStore.Waiting waiting;final TeamRuntimeBridge bridge;

        /** 原父owner和私有child owner通过冻结binding准确关联，命令Gateway仍是唯一写入入口。 */
        @SuppressWarnings("unchecked") CompletedHumanFixture() {
            var parentOwner=com.skillforge.runtime.api.port.SessionOwner.user(new com.skillforge.runtime.api.SessionRef(TENANT,"parent-session"),new com.skillforge.runtime.api.AgentId("7"),new com.skillforge.runtime.api.EndUserId(9));
            var owner=com.skillforge.runtime.api.port.SessionOwner.user(new com.skillforge.runtime.api.SessionRef(TENANT,"delegation:actual-child"),new com.skillforge.runtime.api.AgentId("8"),parentOwner.requireUser());
            var parentRun=new RunRef(TENANT,"actual-parent-run");var originalInput=new com.skillforge.runtime.api.InputId("child-input");
            var identity=new com.skillforge.runtime.api.port.RunIdentity(owner, new com.skillforge.runtime.api.AgentId("8"), originalInput, RUN, new com.skillforge.runtime.api.TraceEnvelope("trace","span",originalInput,java.util.Optional.empty()), new com.skillforge.runtime.api.ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (originalInput).value()));
            when(work.key()).thenReturn(new com.skillforge.runtime.api.dag.DagWork.Key(parentOwner,new com.skillforge.runtime.api.InputId("parent-input")));
            when(work.request().controlRun()).thenReturn(parentRun);when(binding.scope().origin()).thenReturn(parentOwner);when(binding.owner()).thenReturn(owner);
            when(binding.actor()).thenReturn(identity.agentId());when(binding.role()).thenReturn(AgentRunBindings.Role.DELEGATE);when(binding.parentRunRef()).thenReturn(java.util.Optional.of(parentRun));
            when(bindings.find(RUN)).thenReturn(java.util.Optional.of(binding));doThrow(new SecurityException("delegation_parent_ended")).when(bindings).authorize(binding);
            when(store.load(RUN)).thenReturn(java.util.Optional.of(run));when(run.identity()).thenReturn(identity);when(run.phase()).thenReturn(ExecutionStore.Phase.FINISHED);
            when(run.status()).thenReturn(com.skillforge.runtime.api.RunStatus.COMPLETED);when(run.revision()).thenReturn(77L);when(run.currentBatch()).thenReturn(java.util.Optional.empty());when(run.waiting()).thenReturn(java.util.Optional.empty());
            var receipt=new ExecutionStore.AcceptedResponse(input,new com.skillforge.runtime.api.ResultRef("original-human-response"),"original-fingerprint",new com.skillforge.runtime.api.ResultRef("original-actor"),27);
            waiting=new ExecutionStore.Waiting(WAIT,ExecutionStore.WaitKind.HUMAN,RUN,"original-model-attempt",Map.of(),java.util.Optional.empty(),java.util.Optional.of(receipt),java.util.Optional.empty(),java.util.OptionalLong.of(28));
            when(store.response(eq(RUN),any())).thenReturn(java.util.Optional.empty());when(store.response(RUN,input)).thenReturn(java.util.Optional.of(waiting));
            when(gateway.verify(run,input)).thenReturn(actor);when(gateway.resumeHuman(run,actor,input,WAIT,4L,"原始回复")).thenReturn(new ExecutionStore.ResumeResult(run,waiting,false));
            var commands=(org.springframework.beans.factory.ObjectProvider<AgentRuntimeExecutionGateway>)mock(org.springframework.beans.factory.ObjectProvider.class);when(commands.getObject()).thenReturn(gateway);
            bridge=new TeamRuntimeBridge(store,commands,mock(AgentRuntimeExecutionSteering.class),bindings,JSON,value->true,mock(AgentRuntimeExecutionProjection.class));
        }

        /** 保留用户声明的版本4与准确原Wait，不从当前77或接受27派生版本。 */
        ExecutionStore.ResumeResult respond(String inputId,String pending,String text) { return bridge.respond(work,RUN,inputId,"reply",pending,"4",text,java.time.Instant.now()); }
    }

    private static final class BatchFixture {
        final ExecutionStore store=mock(ExecutionStore.class);final AgentRuntimeExecutionGateway gateway=mock(AgentRuntimeExecutionGateway.class);
        final AgentRunBindings bindings=mock(AgentRunBindings.class);final ExecutionStore.Run run=mock(ExecutionStore.Run.class);
        final com.skillforge.runtime.api.port.VerifiedIdentity actor=mock(com.skillforge.runtime.api.port.VerifiedIdentity.class);
        final com.skillforge.runtime.api.dag.DagWork work=mock(com.skillforge.runtime.api.dag.DagWork.class,RETURNS_DEEP_STUBS);
        final ExecutionStore.BatchRef batch=new ExecutionStore.BatchRef(RUN,"actual-native-model-attempt",2);
        final TeamRuntimeBridge bridge;
        @SuppressWarnings("unchecked") BatchFixture() {
            var owner=com.skillforge.runtime.api.port.SessionOwner.user(new com.skillforge.runtime.api.SessionRef(TENANT,"session"),new com.skillforge.runtime.api.AgentId("7"),new com.skillforge.runtime.api.EndUserId(9));
            var input=new com.skillforge.runtime.api.InputId("parent-input");var identity=new com.skillforge.runtime.api.port.RunIdentity(owner, new AgentId((owner).target().id()), input, RUN, new com.skillforge.runtime.api.TraceEnvelope("trace","span",input,java.util.Optional.empty()), new com.skillforge.runtime.api.ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            when(work.key()).thenReturn(new com.skillforge.runtime.api.dag.DagWork.Key(owner,input));
            var binding=mock(AgentRunBindings.Binding.class,RETURNS_DEEP_STUBS);when(binding.scope().origin()).thenReturn(owner);when(binding.owner()).thenReturn(owner);
            when(binding.actor()).thenReturn(identity.agentId());when(binding.role()).thenReturn(AgentRunBindings.Role.PRIMARY);when(bindings.find(RUN)).thenReturn(java.util.Optional.of(binding));
            when(store.load(RUN)).thenReturn(java.util.Optional.of(run));when(run.identity()).thenReturn(identity);when(run.phase()).thenReturn(ExecutionStore.Phase.AWAITING_RESULT);
            when(run.revision()).thenReturn(9L);when(run.currentBatch()).thenReturn(java.util.Optional.of(batch));when(run.waiting()).thenReturn(java.util.Optional.empty());
            var waiting=new ExecutionStore.Waiting(WAIT,ExecutionStore.WaitKind.TOOL,RUN,"original-attempt",Map.of("actual-request",ToolInputMethod.ELICITATION_CREATE),java.util.Optional.empty(),java.util.Optional.empty(),java.util.Optional.empty(),java.util.OptionalLong.empty());
            when(store.batchWaits(batch)).thenReturn(java.util.List.of(waiting));when(store.response(eq(RUN),any())).thenReturn(java.util.Optional.empty());
            when(gateway.verify(same(run),any())).thenReturn(actor);when(gateway.resume(same(run),same(actor),any(),eq(WAIT),anyLong(),anyMap())).thenReturn(new ExecutionStore.ResumeResult(run,waiting,true));
            var commands=(org.springframework.beans.factory.ObjectProvider<AgentRuntimeExecutionGateway>)mock(org.springframework.beans.factory.ObjectProvider.class);when(commands.getObject()).thenReturn(gateway);
            bridge=new TeamRuntimeBridge(store,commands,mock(AgentRuntimeExecutionSteering.class),bindings,JSON,value->true,mock(AgentRuntimeExecutionProjection.class));
        }
    }
}

package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExecutionContextReferenceReaderTest {
    @Test void exactOriginalInputPreservesUnicodeOffsetsAndStableServerReference() {
        var fixture=new Fixture();fixture.input("甲🙂乙𠮷丙");var reader=fixture.reader.forCall(fixture.context);
        var first=reader.readSource(fixture.identity,"accepted",1,2);
        assertEquals("🙂乙",first.content());assertEquals(3,first.nextOffset());assertFalse(first.end());
        assertEquals(5,first.reference().characters());assertTrue(first.reference().referenceId().startsWith("context:"));
        var last=reader.read(fixture.identity,first.reference().referenceId(),3,2);
        assertEquals("𠮷丙",last.content());assertTrue(last.end());assertEquals(first.reference(),last.reference());
        var located=reader.findSource(fixture.identity,"accepted","𠮷",0,1);assertEquals(3,located.offset());assertTrue(located.matched());assertEquals("𠮷",located.page().content());
    }
    @Test void currentIamAndExactCurrentAttemptAreRequiredBeforeHistoryReads() {
        var denied=new Fixture();when(denied.iam.authorize(any(IdentityPolicyPort.AuthorizationRequest.class))).thenReturn(
                new IdentityPolicyPort.AuthorizationResult.Denied("revoked",2,new ResultRef("audit-denied")));
        assertThrows(IllegalStateException.class,()->denied.reader.forCall(denied.context));verify(denied.store,never()).events(any(),anyLong(),anyInt());
        var stale=new Fixture();when(stale.action.currentAttempt()).thenReturn("another-attempt");
        assertThrows(IllegalStateException.class,()->stale.reader.forCall(stale.context));verifyNoInteractions(stale.iam);
    }
    @Test void crossOwnerSourceAndCallerRebindingAreRejected() {
        var fixture=new Fixture();fixture.input("原始正文");var reader=fixture.reader.forCall(fixture.context);
        var other=new RunIdentity(SessionOwner.user(fixture.owner.sessionRef(),new AgentId("7"),new EndUserId(10)), new AgentId((SessionOwner.user(fixture.owner.sessionRef(),new AgentId("7"),new EndUserId(10))).target().id()), fixture.identity.causationInputId(), new RunRef(fixture.owner.sessionRef().tenantId(),"other"), fixture.identity.trace(), new ResultRef("other-identity"), new RunRequester.EndUser((SessionOwner.user(fixture.owner.sessionRef(),new AgentId("7"),new EndUserId(10))).requireUser()), java.util.Optional.empty(), new RunOriginRef((SessionOwner.user(fixture.owner.sessionRef(),new AgentId("7"),new EndUserId(10))).sessionRef().tenantId(), "input:" + (fixture.identity.causationInputId()).value()));
        assertThrows(IllegalStateException.class,()->reader.readSource(other,"accepted",0,3));
        when(fixture.run.identity()).thenReturn(other);
        assertThrows(IllegalStateException.class,()->reader.readSource(fixture.identity,"accepted",0,3));
    }
    @Test void privateInputAndInvalidPageBoundsDoNotLeakContent() {
        var fixture=new Fixture();fixture.input(fixture.privateRoot);var reader=fixture.reader.forCall(fixture.context);
        assertThrows(IllegalStateException.class,()->reader.readSource(fixture.identity,"accepted",0,1));
        fixture.input("正文");assertThrows(IllegalStateException.class,()->reader.readSource(fixture.identity,"accepted",0,32769));
    }
    @Test void toolSourceRequiresExactAdoptedSafeFeedbackAndNeverReturnsOriginalSensitiveFields() {
        var fixture=new Fixture();var sourceAction=new ActionRef(fixture.identity.runRef().tenantId(),"actual-tool-action");
        var action=mock(ExecutionStore.Action.class);var selected=mock(ExecutionStore.Attempt.class);
        when(fixture.store.action(sourceAction)).thenReturn(Optional.of(action));when(action.reference()).thenReturn(sourceAction);when(action.run()).thenReturn(fixture.identity.runRef());
        when(action.kind()).thenReturn(ExecutionStore.Kind.TOOL);when(action.selectedAttempt()).thenReturn(Optional.of("selected"));when(fixture.store.attempt(fixture.identity.runRef(),"selected")).thenReturn(Optional.of(selected));
        when(selected.run()).thenReturn(fixture.identity.runRef());when(selected.action()).thenReturn(sourceAction);when(selected.adopted()).thenReturn(true);
        var target=new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:clock"),new CapabilityRef(CapabilityType.TOOL,"clock","1"),"deployment",1,
                new TypedPayload("json-schema","{}"),new TypedPayload("json-schema","{}"),ToolEffect.READ_ONLY,true,java.time.Duration.ofSeconds(1),true,ToolPortal.ToolConfirmationPolicy.NEVER,Set.of("token"),Set.of(),true);
        var request=new AgentExecutionCodec.ToolRequest(target,new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.PLATFORM,"get_current_time","get_current_time"),new TypedPayload("application/json","{}"),"call","get_current_time");
        var outcome=new ToolPortal.ToolOutcome.Succeeded(new TypedPayload(com.skillforge.runtime.session.SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                "{\"content\":[{\"type\":\"text\",\"text\":\"original-sensitive-copy\"}],\"structuredContent\":{\"token\":\"original-sensitive-copy\",\"value\":\"结果🙂\"}}"),new ResultRef("actual-provider-result"),List.of());
        var payloads=new com.skillforge.runtime.session.SessionRuntimePayloads();var core=new com.skillforge.runtime.core.CoreRuntime(new CodecLimits(1_000_000,100_000,128),payloads);
        var raw=fixture.codec.encodeOutcome(core.portalResults(payloads,payloads,payloads).encodeTool(PortalCallKind.TOOL_INVOKE,outcome));
        var requestRef=new ResultRef("actual-request");var resultRef=new ResultRef("actual-result");var feedbackRef=new ResultRef("actual-feedback");
        when(selected.request()).thenReturn(requestRef);when(selected.result()).thenReturn(Optional.of(resultRef));when(selected.feedback()).thenReturn(Optional.of(feedbackRef));
        when(fixture.store.material(fixture.identity.runRef().tenantId(),requestRef)).thenReturn(Optional.of(new ExecutionStore.Material(requestRef,fixture.codec.encodeTool(request))));
        when(fixture.store.material(fixture.identity.runRef().tenantId(),resultRef)).thenReturn(Optional.of(new ExecutionStore.Material(resultRef,raw)));
        when(fixture.store.material(fixture.identity.runRef().tenantId(),feedbackRef)).thenReturn(Optional.of(new ExecutionStore.Material(feedbackRef,fixture.codec.safeToolFeedback(request,outcome).orElseThrow())));
        var event=new ExecutionStore.Event(new EventId("tool-source"),AgentExecutionProgram.TOOL_RESULT_EVENT,new TypedPayload(AgentExecutionProgram.TOOL_RESULT_SCHEMA,"{\"actionId\":\"actual-tool-action\"}"));
        when(fixture.store.event(fixture.owner.sessionRef(),event.id())).thenReturn(Optional.of(new ExecutionStore.SavedEvent(fixture.identity.runRef(),2,3,Instant.now(),event)));
        var reader=fixture.reader.forCall(fixture.context);var page=reader.readSource(fixture.identity,"tool-source",0,4096);
        assertTrue(page.content().contains("结果🙂"));assertFalse(page.content().contains("original-sensitive-copy"));assertFalse(page.content().contains("token"));
        when(selected.adopted()).thenReturn(false);assertThrows(IllegalStateException.class,()->reader.readSource(fixture.identity,"tool-source",0,4096));
    }
    @Test void exactCompactedMaterialIsReadableWithCurrentIamAndIncludesFullArgumentsAndBody() throws Exception {
        var f=new Fixture();
        var sourceContext=new ResultRef("source-context");
        when(f.store.material(f.identity.runRef().tenantId(),sourceContext)).thenReturn(Optional.of(new ExecutionStore.Material(sourceContext,new TypedPayload("source.v1","{}"))));
        var call=new ModelToolCall("safe-call","query",new TypedPayload("arguments","{\"count\":17}"));
        var messages=List.of(new ModelMessage("assistant",ModelRole.ASSISTANT,"查询",List.of(call),Optional.empty(),Optional.empty()),
                new ModelMessage("tool",ModelRole.TOOL,"中间完整正文🙂与精确数值117",List.of(),Optional.of("safe-call"),Optional.empty()));
        var exact=f.codec.encodeInput(messages,new TypedPayload("source.v1","{}"));
        var json=new ObjectMapper();var body=json.createObjectNode().put("sourceContext",sourceContext.value());
        body.putObject("messages").put("schemaId",exact.schemaId()).put("canonicalJson",exact.canonicalJson());
        var event=new ExecutionStore.Event(new EventId("compacted-source"),com.skillforge.runtime.session.AgentExecutionContextViews.EVENT,
                new TypedPayload(com.skillforge.runtime.session.AgentExecutionContextViews.SCHEMA,json.writeValueAsString(body)));
        when(f.store.event(f.owner.sessionRef(),event.id())).thenReturn(Optional.of(new ExecutionStore.SavedEvent(f.identity.runRef(),2,3,Instant.now(),event)));
        var reader=f.reader.forCall(f.context);var page=reader.readSource(f.identity,"compacted-source",0,4096);
        assertTrue(page.content().contains("中间完整正文🙂与精确数值117"));assertTrue(page.content().contains("safe-call"));
        assertTrue(page.content().contains("count"));assertTrue(page.content().contains("17"));assertTrue(page.end());
        when(f.store.material(f.identity.runRef().tenantId(),sourceContext)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,()->reader.readSource(f.identity,"compacted-source",0,4096));
    }
    @Test void exactSourcePagesAndFindDoNotReadUnrelatedBrokenHistory() {
        var f=new Fixture();f.input("甲🙂乙𠮷丙");
        when(f.store.events(f.owner.sessionRef(),0,100)).thenThrow(new IllegalStateException("unrelated broken history"));
        var reader=f.reader.forCall(f.context);
        var first=reader.readSource(f.identity,"accepted",1,2);
        var last=reader.readSource(f.identity,"accepted",3,2);
        var located=reader.findSource(f.identity,"accepted","𠮷",0,1);
        assertEquals("🙂乙",first.content());assertEquals("𠮷丙",last.content());
        assertEquals(first.reference(),last.reference());assertEquals(3,located.offset());
        assertEquals("𠮷",located.page().content());assertTrue(located.matched());
        verify(f.store,never()).events(any(),anyLong(),anyInt());
        verify(f.store,times(3)).event(f.owner.sessionRef(),new EventId("accepted"));
        assertThrows(IllegalStateException.class,()->reader.read(f.identity,first.reference().referenceId(),0,1));
        assertThrows(IllegalStateException.class,()->reader.readSource(f.identity,"missing",0,1));
        var malformed=new ExecutionStore.Event(new EventId("broken-source"),
                com.skillforge.runtime.session.AgentExecutionContextViews.EVENT,new TypedPayload("wrong-source-schema","{}"));
        when(f.store.event(f.owner.sessionRef(),malformed.id())).thenReturn(Optional.of(
                new ExecutionStore.SavedEvent(f.identity.runRef(),2,2,Instant.now(),malformed)));
        assertThrows(IllegalStateException.class,()->reader.readSource(f.identity,"broken-source",0,1));
    }

    @Test void exactSourceCanReadAnEarlierRunOnlyUnderTheSameCompleteOwner() {
        var f=new Fixture();var previousRef=new RunRef(f.owner.sessionRef().tenantId(),"previous-run");
        var previousIdentity=new RunIdentity(f.owner,f.identity.agentId(),f.input,previousRef,f.identity.trace(),
                new ResultRef("previous-identity"),f.identity.requester(),Optional.empty(),f.identity.originRef());
        var previous=mock(ExecutionStore.Run.class);when(previous.identity()).thenReturn(previousIdentity);
        when(f.store.load(previousRef)).thenReturn(Optional.of(previous));
        var event=new ExecutionStore.Event(new EventId("previous-input"),AgentExecutionProgram.ACCEPTED_EVENT,new TypedPayload("accepted","{}"));
        when(f.store.event(f.owner.sessionRef(),event.id())).thenReturn(Optional.of(new ExecutionStore.SavedEvent(previousRef,1,1,Instant.now(),event)));
        var reference=new ResultRef("execution-input:"+previousRef.runId());
        when(f.store.material(previousRef.tenantId(),reference)).thenReturn(Optional.of(new ExecutionStore.Material(reference,
                f.codec.encodeInput(List.of(new ModelMessage("input:"+f.input.value(),ModelRole.USER,"前轮原文🙂")),new TypedPayload("input.v1","{}")))));
        var reader=f.reader.forCall(f.context);
        assertEquals("前轮原文🙂",reader.readSource(f.identity,event.id().value(),0,100).content());
        verify(f.store,never()).events(any(),anyLong(),anyInt());
        when(f.store.load(previousRef)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,()->reader.readSource(f.identity,event.id().value(),0,100));
    }

    private static final class Fixture {
        final ExecutionStore store=mock(ExecutionStore.class);final JdbcIdentityPolicyPort iam=mock(JdbcIdentityPolicyPort.class);
        final ExecutionStore.Run run=mock(ExecutionStore.Run.class);final ExecutionStore.Action action=mock(ExecutionStore.Action.class);final ExecutionStore.Attempt attempt=mock(ExecutionStore.Attempt.class);
        final SessionOwner owner=SessionOwner.user(new SessionRef(new TenantId("1"),"session"),new AgentId("7"),new EndUserId(9));
        final InputId input=new InputId("input");final RunIdentity identity=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(owner.sessionRef().tenantId(),"run"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        final VerifiedIdentity actor=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(owner.sessionRef().tenantId(),owner.requireUser()),owner,Set.of(),1,new ResultRef("verified"));
        final PlatformToolExecutionContext context=new PlatformToolExecutionContext(new PortCallContext(identity,actor,new InvocationControl(Instant.now().plusSeconds(60),()->false)),"context-action","context-attempt");
        final AgentExecutionCodec codec=new AgentExecutionCodec(new CodecLimits(1_000_000,100_000,128));
        final String privateRoot=java.nio.file.Path.of("private-context-artifacts").toAbsolutePath().normalize().toString();
        final ExecutionContextReferenceReader reader=new ExecutionContextReferenceReader(store,codec,iam,mock(AgentRuntimeExecutionProjection.class),new PublicOutputGuard(privateRoot),new ObjectMapper());
        Fixture() {
            when(store.load(identity.runRef())).thenReturn(Optional.of(run));when(run.identity()).thenReturn(identity);when(run.phase()).thenReturn(ExecutionStore.Phase.AWAITING_RESULT);
            when(run.fence()).thenReturn(1L);when(run.actorVerificationRef()).thenReturn(actor.verificationRef());
            var reference=new ActionRef(owner.sessionRef().tenantId(),context.invocationId());when(store.action(reference)).thenReturn(Optional.of(action));when(action.reference()).thenReturn(reference);
            when(action.run()).thenReturn(identity.runRef());when(action.kind()).thenReturn(ExecutionStore.Kind.TOOL);when(action.currentAttempt()).thenReturn(context.attemptId());
            when(store.attempt(identity.runRef(),context.attemptId())).thenReturn(Optional.of(attempt));when(attempt.id()).thenReturn(context.attemptId());when(attempt.action()).thenReturn(reference);
            when(attempt.run()).thenReturn(identity.runRef());when(attempt.fence()).thenReturn(1L);when(attempt.result()).thenReturn(Optional.empty());
            when(iam.authorize(any(IdentityPolicyPort.AuthorizationRequest.class))).thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(1,new ResultRef("audit")));
        }
        void input(String text) {
            var material=new ExecutionStore.Material(new ResultRef("execution-input:"+identity.runRef().runId()),codec.encodeInput(List.of(new ModelMessage("input:"+input.value(),ModelRole.USER,text)),new TypedPayload("input.v1","{}")));
            when(store.material(identity.runRef().tenantId(),material.reference())).thenReturn(Optional.of(material));
            var event=new ExecutionStore.Event(new EventId("accepted"),AgentExecutionProgram.ACCEPTED_EVENT,new TypedPayload("accepted","{}"));
            var saved=new ExecutionStore.SavedEvent(identity.runRef(),1,1,Instant.now(),event);
            when(store.events(owner.sessionRef(),0,100)).thenReturn(List.of(saved));
            when(store.event(owner.sessionRef(),event.id())).thenReturn(Optional.of(saved));
        }
    }
}

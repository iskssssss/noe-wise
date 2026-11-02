package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.ChatRuntimeGateway;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformMessageRequest;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AgentRuntimeExecutionGatewayOpenPlatformMessageTest {
    private final ServicePrincipalContext.Identity principal =
            new ServicePrincipalContext.Identity("sp","1","integration",1,Set.of("sessions.write"));

    @Test void realMessageConsumerSeparatesBoundedPublicMessageFromOriginalInputAndReplaysSameRun() {
        String input="open-platform:"+"a".repeat(64);
        var f=fixture(input);
        var tests=(WorkflowTestRuntimePolicy)ReflectionTestUtils.getField(f.gateway,"tests");
        var request=new OpenPlatformMessageRequest("真实外部消息");
        var first=TenantContext.callWithTenant("1",()->f.gateway.acceptMessage(principal,f.session,input,request));
        var second=TenantContext.callWithTenant("1",()->f.gateway.acceptMessage(principal,f.session,input,request));
        assertEquals(78,input.length());assertEquals(input,first.inputId());assertEquals(input,first.rootInputId());
        assertEquals("run:"+input,first.runId());assertEquals(first,second);assertTrue(first.replayed());
        var restored=TenantContext.callWithTenant("1",()->f.gateway.findOpenPlatformMessageReceipt(principal,f.session.toBuilder().integrationId("integration").build(),input)).orElseThrow();
        assertEquals(input,restored.inputId());assertEquals(first.runId(),restored.runId());assertEquals(first.eventId(),restored.eventId());
        var submissions=ArgumentCaptor.forClass(ChatRuntimeGateway.Submission.class);
        verify(tests,times(2)).input(submissions.capture(),eq(input),eq(Optional.empty()),eq(false));
        var original=submissions.getAllValues().getFirst().request();
        assertEquals(64,original.clientMessageId().length());assertTrue(original.clientMessageId().matches("[a-f0-9]{64}"));
        assertNotEquals(input,original.clientMessageId());
        assertEquals(original.clientMessageId(),submissions.getAllValues().getLast().request().clientMessageId());
        assertEquals(input,original.context().get("input_id"));assertEquals("真实外部消息",original.message());
        verify(f.store,never()).admit(any());verify(f.store,never()).resume(any());
        verify(f.store,atLeastOnce()).load(new RunRef(new TenantId("1"),"run:"+input));
        verify(f.references,never()).accepted(any(),anyString(),any());
    }

    @Test void coldReconstructionIsStableAndDifferentExternalInputHasDifferentPublicMessage() {
        String first="open-platform:"+"a".repeat(64),other="open-platform:"+"b".repeat(64);
        assertEquals(publicMessage(first),publicMessage(first));assertNotEquals(publicMessage(first),publicMessage(other));
    }

    private String publicMessage(String input) {
        var f=fixture(input);
        TenantContext.callWithTenant("1",()->f.gateway.acceptMessage(principal,f.session,input,new OpenPlatformMessageRequest("正文")));
        var submission=ArgumentCaptor.forClass(ChatRuntimeGateway.Submission.class);
        verify((WorkflowTestRuntimePolicy)ReflectionTestUtils.getField(f.gateway,"tests"))
                .input(submission.capture(),eq(input),eq(Optional.empty()),eq(false));
        return submission.getValue().request().clientMessageId();
    }

    private AgentRuntimeExecutionGatewayGoalTest.Fixture fixture(String input) {
        var f=new AgentRuntimeExecutionGatewayGoalTest.Fixture(false);
        when(f.iam.authorize(any(IdentityPolicyPort.AuthorizationRequest.class)))
                .thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(1,new ResultRef("read-allowed")));
        var ref=new RunRef(new TenantId("1"),"run:"+input);var id=new InputId(input);
        var identity=new RunIdentity(f.owner,new AgentId("7"),id,ref,new TraceEnvelope("trace","span",id,Optional.empty()),
                new ResultRef("identity"),new RunRequester.EndUser(f.owner.requireUser()),Optional.empty(),new RunOriginRef(new TenantId("1"),"input:"+input));
        var run=mock(ExecutionStore.Run.class);when(run.identity()).thenReturn(identity);when(f.store.load(ref)).thenReturn(Optional.of(run));
        var materialRef=new ResultRef("execution-input:"+ref.runId());
        var frozen=new TypedPayload("input.v1","{\"content\":[],\"extraParameters\":{}}");
        when(f.store.material(new TenantId("1"),materialRef)).thenReturn(Optional.of(new ExecutionStore.Material(materialRef,f.codec.encodeInput(List.of(),frozen))));
        when(f.references.request(anyList(),anyMap(),anyList())).thenReturn(frozen);
        when(f.references.freeze(eq(f.session),eq(frozen),any())).thenReturn(frozen);
        return f;
    }
}

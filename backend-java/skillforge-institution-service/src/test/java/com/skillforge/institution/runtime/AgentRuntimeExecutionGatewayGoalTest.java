package com.skillforge.institution.runtime;

import com.skillforge.core.billing.metering.OpenPlatformRequestMetering;

import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.ChatRuntimeGateway;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.*;
import com.skillforge.runtime.session.spi.*;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeTrustedAccess;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AgentRuntimeExecutionGatewayGoalTest {
    @Test void groupProductSessionIsRejectedBeforePersonalRuntimeIdentityOrStoreWrites() {
        var fixture=new Fixture(true);
        var groupSession=fixture.session.toBuilder().groupBindingId(77L).endUserId(null).agentId(null).teamId(null).build();
        var groupSubmission=new ChatRuntimeGateway.Submission(fixture.submission.request(),groupSession,"7",9L,null);
        var rejected=assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                ()->TenantContext.runWithTenant("1",()->fixture.gateway.submit(groupSubmission)));
        assertEquals("group_execution_not_available",rejected.getCode());
        verifyNoInteractions(fixture.store,fixture.iam,fixture.access,fixture.bindings,fixture.goals);
    }
    @Test void acceptedOrdinaryUserInputYieldsGoalOnlyAfterActualStoreAcceptance() {
        var fixture=new Fixture(true);TenantContext.runWithTenant("1",()->fixture.gateway.submit(fixture.submission));
        var ordered=inOrder(fixture.store,fixture.goals);ordered.verify(fixture.store).resume(any());ordered.verify(fixture.goals).yieldToUser(fixture.owner);
    }
    @Test void trustedGoalRoundUsesSameStoreAdmissionWithoutYieldingItsOwnRegistration() {
        var fixture=new Fixture(true);TenantContext.runWithTenant("1",()->fixture.gateway.submitGoalRound(fixture.submission));
        verify(fixture.store).resume(any());verifyNoInteractions(fixture.goals);
    }
    @Test void duplicateResponseCannotDisableALaterGoalRegistration() {
        var fixture=new Fixture(false);TenantContext.runWithTenant("1",()->fixture.gateway.submit(fixture.submission));
        verify(fixture.store).resume(any());verifyNoInteractions(fixture.goals);
    }

    /** 受信登记的当前撤权转换为正式403，不能进入输入受理、恢复或Goal副作用。 */
    @Test void currentAuthorizationRejectionReturnsForbiddenBeforeBusinessWrites() {
        var fixture=new Fixture(true);
        doThrow(new com.skillforge.core.foundation.integration.runtime.identity.TrustedAuthenticationRejectedException())
                .when(fixture.access).serviceAccess(any(),any(),any(),anyString(),anyString(),any(),anyString(),anyString());
        var rejected=assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                ()->TenantContext.runWithTenant("1",()->fixture.gateway.submit(fixture.submission)));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN,rejected.getStatus());
        assertEquals("FORBIDDEN",rejected.getCode());assertFalse(rejected.isRetryable());
        verify(fixture.iam,never()).verify(any());verify(fixture.store,never()).admit(any());verify(fixture.store,never()).resume(any());
        verifyNoInteractions(fixture.goals);
    }

    /** 数据库等技术异常保持原始失败，不会被当作确定性授权拒绝。 */
    @Test void trustedRegistrationStorageFailureKeepsItsTechnicalMeaning() {
        var fixture=new Fixture(true);var failure=new IllegalStateException("synthetic_storage_failure");
        doThrow(failure).when(fixture.access).serviceAccess(any(),any(),any(),anyString(),anyString(),any(),anyString(),anyString());
        assertSame(failure,assertThrows(IllegalStateException.class,
                ()->TenantContext.runWithTenant("1",()->fixture.gateway.submit(fixture.submission))));
        verify(fixture.store,never()).admit(any());verify(fixture.store,never()).resume(any());verifyNoInteractions(fixture.goals);
    }

    /** 登记与后续验证之间发生撤权仍403，身份基础设施不可核验单独503，均不受理业务。 */
    @Test void verificationDistinguishesDeterministicRejectionFromUnavailableAuthority() {
        for(var entry:Map.of("identity_rejected",org.springframework.http.HttpStatus.FORBIDDEN,
                "identity_unavailable",org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).entrySet()) {
            var fixture=new Fixture(true);
            when(fixture.iam.verify(any())).thenReturn(new IdentityPolicyPort.IdentityResult.Rejected(
                    new PortFailure(entry.getKey(),RetryMode.NONE,new ResultRef("rejected-audit"))));
            var rejected=assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                    ()->TenantContext.runWithTenant("1",()->fixture.gateway.submit(fixture.submission)));
            assertEquals(entry.getValue(),rejected.getStatus());
            verify(fixture.store,never()).admit(any());verify(fixture.store,never()).resume(any());verifyNoInteractions(fixture.goals);
        }
    }
    static final class Fixture {
        final ExecutionStore store=mock(ExecutionStore.class);final AgentRuntimeGoalDriver goals=mock(AgentRuntimeGoalDriver.class);
        final JdbcIdentityPolicyPort iam=mock(JdbcIdentityPolicyPort.class);final AgentRuntimeTrustedAccess access=mock(AgentRuntimeTrustedAccess.class);
        final SessionOwner owner=SessionOwner.user(new SessionRef(new TenantId("1"),"session"),new AgentId("7"),new EndUserId(9));
        final SessionView session=SessionView.builder().tenantId(1L).sessionId("session").agentId(7L)
                .endUserId(9L).scopeType("open-platform:integration").build();final SessionStorePort sessions=mock(SessionStorePort.class);
        final com.skillforge.core.chat.session.SessionService product=mock(com.skillforge.core.chat.session.SessionService.class);
        final AgentRunBindings bindings=mock(AgentRunBindings.class);
        final ChatInputReferenceResolver references=mock(ChatInputReferenceResolver.class);
        final AgentExecutionCodec codec=new AgentExecutionCodec(new CodecLimits(1_000_000,100_000,128));
        final ChatRuntimeGateway.Submission submission;final AgentRuntimeExecutionGateway gateway;
        @SuppressWarnings("unchecked") Fixture(boolean accepted) {
            var input=new InputId("original");var identity=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(owner.sessionRef().tenantId(),"run:original"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            var actor=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(owner.sessionRef().tenantId(),owner.requireUser()),owner,Set.of(),1,new ResultRef("actor"));
            var run=mock(ExecutionStore.Run.class);when(run.identity()).thenReturn(identity);when(store.load(identity.runRef())).thenReturn(Optional.of(run));
            var wait=new ExecutionStore.WaitRef(new ActionRef(owner.sessionRef().tenantId(),"human-action"),1);
            var waiting=new ExecutionStore.Waiting(wait,ExecutionStore.WaitKind.HUMAN,identity.runRef(),"model-attempt",Map.of(),Optional.empty(),Optional.empty(),Optional.empty(),OptionalLong.empty());
            when(store.resume(any())).thenReturn(new ExecutionStore.ResumeResult(run,waiting,accepted));
            when(iam.verify(any())).thenReturn(new IdentityPolicyPort.IdentityResult.Verified(actor));
            when(access.serviceAccess(any(),eq(owner),eq(identity.agentId()),anyString(),eq("request"),any(),anyString(),anyString())).thenAnswer(call->
                    new AccessEnvelope(new AuthenticationRef("authenticated"),owner.sessionRef().tenantId(),owner.target(),Optional.of(identity.agentId()),owner.subject(),call.getArgument(5),call.getArgument(3),"request","trace","span"));
            var principals=mock(ServicePrincipalMapper.class);var principal=new ServicePrincipalDO();principal.setPrincipalId("chat-service");principal.setCredentialVersion(1L);when(principals.selectOne(any())).thenReturn(principal);
            when(bindings.currentPrimaryAgent(owner)).thenReturn(identity.agentId());
            var response=Map.<String,Object>of("execution_id",identity.runRef().runId(),"run_revision","4","human_input",Map.of("wait",Map.of("action_id","human-action","generation","1"),"text","实际用户回复"));
            submission=new ChatRuntimeGateway.Submission(new ChatRequest("实际用户回复","session","7",null,null,null,null,null,"user-response",response,Map.of()),session,"7",9L,null);
            var goalProvider=(ObjectProvider<AgentRuntimeGoalDriver>)mock(ObjectProvider.class);when(goalProvider.getObject()).thenReturn(goals);
            gateway=new AgentRuntimeExecutionGateway(store,mock(AgentExecutionProgram.class),mock(AgentRuntimeExecutionWorker.class),mock(AgentRuntimeHostProvisioner.class),access,iam,principals,
                    bindings,references,mock(MemoryService.class),codec,new ObjectMapper(),value->true,
                    mock(AgentRuntimeExecutionProjection.class),mock(SessionMediaService.class),new PublicOutputGuard("./private-goal-artifacts"),mock(AgentRuntimeExecutionCatalog.class),mock(AgentRuntimeExecutionSteering.class),
                    mock(WorkflowInteractionView.class),mock(AgentRuntimeWorkflowRecoveryWorker.class),product,goalProvider,sessions,java.time.Clock.systemUTC(), org.mockito.Mockito.mock(com.skillforge.institution.runtime.WorkflowTestRuntimePolicy.class),mock(OpenPlatformRequestMetering.class));
        }
    }
}

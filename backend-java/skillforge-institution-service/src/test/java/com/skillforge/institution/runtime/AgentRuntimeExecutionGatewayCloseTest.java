package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentRuntimeExecutionGatewayCloseTest {
    private static final ServicePrincipalContext.Identity PRINCIPAL=new ServicePrincipalContext.Identity("principal","1","integration",1,Set.of("sessions.write"));
    @Test void currentWriteAuthorizationPrecedesProductAndRuntimeLifecycleClosureWithoutCancellingRun() {
        var fixture=fixture();var active=record(fixture,SessionStorePort.SessionStatus.ACTIVE,2);var closed=record(fixture,SessionStorePort.SessionStatus.CLOSED,3);
        when(fixture.sessions.find(any())).thenReturn(Optional.of(active));when(fixture.sessions.compareAndSet(any())).thenReturn(new SessionStorePort.SessionCasResult.Applied(closed));
        TenantContext.runWithTenant("1",()->fixture.gateway.closeSession(PRINCIPAL,fixture.session,"close-input","user-close"));
        var ordered=inOrder(fixture.iam,fixture.product,fixture.sessions);ordered.verify(fixture.iam).authorize(argThat(request->"runtime.session.close".equals(request.action())));
        ordered.verify(fixture.product).closeOpenPlatform("1","session","integration","user-close");ordered.verify(fixture.sessions).find(any());
        ordered.verify(fixture.sessions).compareAndSet(argThat(command->command.owner().equals(fixture.owner)&&command.expectedRevision()==2
                &&command.expectedStatus()==SessionStorePort.SessionStatus.ACTIVE&&command.targetStatus()==SessionStorePort.SessionStatus.CLOSED&&command.causationInputId().value().equals("close-input")));
        verify(fixture.store,never()).cancel(any());
    }
    @Test void readOnlyOrRevokedPrincipalCannotCloseEitherLifecycle() {
        var fixture=fixture();when(fixture.iam.authorize(any(IdentityPolicyPort.AuthorizationRequest.class))).thenReturn(new IdentityPolicyPort.AuthorizationResult.Denied("write-required",2,new ResultRef("audit-denied")));
        assertThrows(SecurityException.class,()->TenantContext.runWithTenant("1",()->fixture.gateway.closeSession(PRINCIPAL,fixture.session,"close-input",null)));
        verifyNoInteractions(fixture.product,fixture.sessions);verify(fixture.store,never()).cancel(any());
    }
    @Test void casConflictUsesActualReturnedRevisionAndReplayObservesClosedWithoutAnotherCas() {
        var fixture=fixture();var original=record(fixture,SessionStorePort.SessionStatus.ACTIVE,2);var raced=record(fixture,SessionStorePort.SessionStatus.ACTIVE,3);var closed=record(fixture,SessionStorePort.SessionStatus.CLOSED,4);
        when(fixture.sessions.find(any())).thenReturn(Optional.of(original),Optional.of(closed));
        when(fixture.sessions.compareAndSet(any())).thenReturn(new SessionStorePort.SessionCasResult.Conflict(raced,new ResultRef("cas-conflict-audit")),new SessionStorePort.SessionCasResult.Applied(closed));
        TenantContext.runWithTenant("1",()->fixture.gateway.closeSession(PRINCIPAL,fixture.session,"close-input",null));
        TenantContext.runWithTenant("1",()->fixture.gateway.closeSession(PRINCIPAL,fixture.session,"close-input",null));
        verify(fixture.sessions).compareAndSet(argThat(command->command.expectedRevision()==2));verify(fixture.sessions).compareAndSet(argThat(command->command.expectedRevision()==3));verify(fixture.sessions,times(2)).compareAndSet(any());
    }
    @Test void runtimeCasFailureIsNotReportedAsMetadataOnlySuccessAndOtherIntegrationCannotEnter() {
        var fixture=fixture();when(fixture.sessions.find(any())).thenReturn(Optional.of(record(fixture,SessionStorePort.SessionStatus.ACTIVE,2)));
        when(fixture.sessions.compareAndSet(any())).thenThrow(new IllegalStateException("actual-runtime-cas-unavailable"));
        assertThrows(IllegalStateException.class,()->TenantContext.runWithTenant("1",()->fixture.gateway.closeSession(PRINCIPAL,fixture.session,"close-input",null)));
        var other=new ServicePrincipalContext.Identity("other","1","different-integration",1,Set.of("sessions.write"));clearInvocations(fixture.iam,fixture.product,fixture.sessions);
        assertThrows(SecurityException.class,()->TenantContext.runWithTenant("1",()->fixture.gateway.closeSession(other,fixture.session,"close-input",null)));
        verifyNoInteractions(fixture.iam,fixture.product,fixture.sessions);
    }
    private static AgentRuntimeExecutionGatewayGoalTest.Fixture fixture(){var fixture=new AgentRuntimeExecutionGatewayGoalTest.Fixture(true);when(fixture.iam.authorize(any(IdentityPolicyPort.AuthorizationRequest.class))).thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(1,new ResultRef("audit")));return fixture;}
    private static SessionStorePort.SessionRecord record(AgentRuntimeExecutionGatewayGoalTest.Fixture fixture,SessionStorePort.SessionStatus status,long revision) {
        var now=Instant.parse("2026-10-22T00:00:00Z");return new SessionStorePort.SessionRecord(fixture.owner,"open-platform","integration","persona",status,revision,0,now,now);
    }
}

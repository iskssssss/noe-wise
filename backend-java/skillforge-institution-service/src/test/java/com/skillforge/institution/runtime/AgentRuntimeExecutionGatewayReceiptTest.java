package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.IdentityPolicyPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.AgentExecutionProgram;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRuntimeExecutionGatewayReceiptTest {
    private final ServicePrincipalContext.Identity principal = new ServicePrincipalContext.Identity("sp", "1", "integration", 1, Set.of("sessions.read"));

    @Test void committedRunReturnsOriginalAcceptedReceiptWithoutAdmissionWakeOrHistoryRead() {
        var f = fixture(); var session = f.session.toBuilder().integrationId("integration").build();
        var reference = new RunRef(new TenantId("1"), "run:original");
        var original = f.store.load(reference).orElseThrow();
        clearInvocations(f.store);
        var found = TenantContext.callWithTenant("1", () -> f.gateway.findOpenPlatformMessageReceipt(principal, session, "original")).orElseThrow();
        assertEquals("run:original", found.runId()); assertEquals("original", found.inputId());
        assertEquals("accepted", found.status()); assertFalse(found.replayed());
        assertEquals(AgentExecutionProgram.admissionEvent(original.identity()).id().value(), found.eventId());
        verify(f.store).load(reference); verifyNoMoreInteractions(f.store);
        verifyNoInteractions(f.references, f.goals, f.product, f.sessions);
    }

    @Test void missingCommittedRunReturnsEmptyOnlyAfterReadAuthorizationAndNeverCreatesAnything() {
        var f = fixture(); var session = f.session.toBuilder().integrationId("integration").build();
        var reference = new RunRef(new TenantId("1"), "run:missing");
        when(f.store.load(reference)).thenReturn(Optional.empty());
        assertTrue(TenantContext.callWithTenant("1", () -> f.gateway.findOpenPlatformMessageReceipt(principal, session, "missing")).isEmpty());
        verify(f.store).load(reference); verifyNoMoreInteractions(f.store);
        verify(f.iam).authorize(any(IdentityPolicyPort.AuthorizationRequest.class));
        verifyNoInteractions(f.references, f.goals, f.product, f.sessions);
    }

    @Test void currentRuntimeDenialCannotBeReportedAsNotObserved() {
        var f = fixture(); var session = f.session.toBuilder().integrationId("integration").build();
        when(f.iam.authorize(any(IdentityPolicyPort.AuthorizationRequest.class))).thenReturn(new IdentityPolicyPort.AuthorizationResult.Denied("query_denied", 1, new ResultRef("query-denied")));
        assertThrows(SecurityException.class, () -> TenantContext.runWithTenant("1", () -> f.gateway.findOpenPlatformMessageReceipt(principal, session, "missing")));
        verifyNoInteractions(f.store);
    }

    @Test void wrongTenantIntegrationOrSessionCannotDiscloseACommittedRun() {
        var f = fixture(); var session = f.session.toBuilder().integrationId("integration").build();
        assertThrows(SecurityException.class, () -> TenantContext.runWithTenant("2", () -> f.gateway.findOpenPlatformMessageReceipt(principal, session, "original")));
        assertThrows(SecurityException.class, () -> TenantContext.runWithTenant("1", () -> f.gateway.findOpenPlatformMessageReceipt(principal,
                session.toBuilder().integrationId("foreign").build(), "original")));
        var reference = new RunRef(new TenantId("1"), "run:original");
        var identity = f.store.load(reference).orElseThrow().identity();
        var otherOwner = SessionOwner.user(new SessionRef(reference.tenantId(), "foreign-session"), identity.agentId(), identity.owner().requireUser());
        var foreign = mock(ExecutionStore.Run.class);
        when(foreign.identity()).thenReturn(new RunIdentity(otherOwner, identity.agentId(), identity.causationInputId(), reference,
                identity.trace(), identity.causationIdentityRef(), identity.requester(), identity.sourceMessageRef(), identity.originRef()));
        when(f.store.load(reference)).thenReturn(Optional.of(foreign));
        assertThrows(SecurityException.class, () -> TenantContext.runWithTenant("1", () -> f.gateway.findOpenPlatformMessageReceipt(principal, session, "original")));
        verifyNoInteractions(f.references, f.goals, f.product, f.sessions);
        verify(f.store, never()).admit(any());
    }

    private AgentRuntimeExecutionGatewayGoalTest.Fixture fixture() {
        var fixture = new AgentRuntimeExecutionGatewayGoalTest.Fixture(false);
        when(fixture.iam.authorize(any(IdentityPolicyPort.AuthorizationRequest.class)))
                .thenReturn(new IdentityPolicyPort.AuthorizationResult.Allowed(1, new ResultRef("query-allowed")));
        return fixture;
    }
}

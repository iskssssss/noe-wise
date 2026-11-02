package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.runtime.callback.JdbcCallbackIngress;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.VerifiedCallbackDelivery;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.*;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentRuntimeCallbackControllerTest {
    @Test void routesVerifiedWorkflowDeliveryToTypedDependencyHandlerWithoutOldRuntimeCallback() {
        var ingress=mock(JdbcCallbackIngress.class);
        var workflows=mock(AgentRuntimeWorkflowCallbackDeliveries.class);var verified=mock(VerifiedCallbackDelivery.class);
        var tenant=new TenantId("1");var input=new InputId("callback-input");var session=new SessionRef(tenant,"session");
        var envelope=new InboundEnvelope(new AuthenticationRef("original-auth"),Optional.of(new ResultRef("original-cause")),session,SessionTarget.agent(new AgentId("1")),new AgentId("1"),SessionSubject.user(new EndUserId(7)),new com.skillforge.runtime.api.RunRequester.EndUser(new EndUserId(7)),
                input,new EventId("callback-event"),InboundKind.CALLBACK,new RunTarget.ExistingRun(new RunRef(tenant,"run")),"callback","workflow-async-task",
                new TraceEnvelope("trace","span",input,Optional.empty()),new TypedPayload("application/json","{}"),Instant.EPOCH,Optional.empty(),new RunOriginRef(tenant,"input:original"));
        var receipt=new CallbackDeliveryReceipt(new CallbackRef(tenant,"callback-real"),new CallbackDeliveryRef(tenant,"delivery-real"),CallbackDeliveryStatus.READY,1,
                Optional.of(envelope),Optional.empty(),Optional.empty(),Instant.EPOCH);
        when(ingress.verify(any(),any(),anyString(),anyString(),any())).thenReturn(verified);
        var accepted = new CallbackDeliveryResult.Ready(receipt);
        when(workflows.owns(verified)).thenReturn(true);when(workflows.acceptVerified(verified)).thenReturn(accepted);
        var response=new AgentRuntimeCallbackController(ingress,workflows).deliver("1","callback-real","delivery-real","1","signature",new byte[0]);
        assertEquals("ready",response.status());assertEquals("delivery-real",response.deliveryId());
        verify(workflows).acceptVerified(verified);
    }

    @Test
    void mapsPostIngressResolutionFailureToRetryableServiceUnavailable() {
        var ingress = mock(JdbcCallbackIngress.class);
        var callbacks = mock(AgentRuntimeWorkflowCallbackDeliveries.class);
        var verified = mock(VerifiedCallbackDelivery.class);
        when(ingress.verify(any(), any(), anyString(), anyString(), any())).thenReturn(verified);
        when(callbacks.acceptVerified(verified)).thenThrow(new IllegalStateException("ingestion failed"));
        var controller = new AgentRuntimeCallbackController(ingress, callbacks);

        var failure = assertThrows(ApiException.class,
                () -> controller.deliver("1", "callback-1", "delivery-1", "timestamp", "signature",
                        new byte[0]));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        assertEquals("Runtime callback 不可用", failure.getDetail());
    }
}

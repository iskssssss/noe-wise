package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentRuntimeExecutionGatewayWorkflowTest {
    private final ServicePrincipalContext.Identity principal=new ServicePrincipalContext.Identity("sp","1","integration",1,Set.of("sessions.write"));
    private final AgentRuntimeExecutionGateway.WorkflowInvocation invocation=new AgentRuntimeExecutionGateway.WorkflowInvocation(
            "13","91","92",new TypedPayload("application/json","{\"count\":0}"));

    @Test void sameInputReplaysOriginalRunWithoutAnotherAdmissionAndDifferentInputConflicts() {
        var f=new AgentRuntimeExecutionGatewayGoalTest.Fixture(false);
        var runRef=new RunRef(new TenantId("1"),"run:original");
        var inputRef=new ResultRef("execution-input:run:original");
        var original=new TypedPayload("input.v1","{\"content\":[],\"extraParameters\":{}}");
        when(f.store.material(new TenantId("1"),inputRef)).thenReturn(Optional.of(new ExecutionStore.Material(inputRef,f.codec.encodeInput(List.of(),original))));
        when(f.references.request(anyList(),anyMap(),anyList())).thenReturn(original);
        when(f.references.freeze(eq(f.session),eq(original),any())).thenReturn(original);
        var receipt=TenantContext.callWithTenant("1",()->f.gateway.acceptWorkflow(principal,f.session,"original",invocation));
        assertTrue(receipt.replayed());assertEquals(runRef.runId(),receipt.runId());
        verify(f.store,never()).admit(any());
        var changed=new TypedPayload("input.v1","{\"content\":[],\"extraParameters\":{\"count\":1}}");
        when(f.references.freeze(eq(f.session),eq(original),any())).thenReturn(changed);
        var failure=assertThrows(ApiException.class,()->TenantContext.runWithTenant("1",()->f.gateway.acceptWorkflow(principal,f.session,"original",invocation)));
        assertEquals(HttpStatus.CONFLICT,failure.getStatus());assertEquals("execution_input_changed",failure.getCode());
        verify(f.store,never()).admit(any());
    }

    @Test void currentIdentityRevocationRejectsEvenAnExistingInvocationBeforeReplayOrWake() {
        var f=new AgentRuntimeExecutionGatewayGoalTest.Fixture(false);
        doThrow(new com.skillforge.core.foundation.integration.runtime.identity.TrustedAuthenticationRejectedException())
                .when(f.access).serviceAccess(any(),any(),any(),anyString(),anyString(),any(),anyString(),anyString());
        var failure=assertThrows(ApiException.class,()->TenantContext.runWithTenant("1",()->f.gateway.acceptWorkflow(principal,f.session,"original",invocation)));
        assertEquals(HttpStatus.FORBIDDEN,failure.getStatus());
        verifyNoInteractions(f.references);verify(f.store,never()).admit(any());
    }
}

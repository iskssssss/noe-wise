package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class OpenPlatformWorkflowInvocationControllerTest {
    @Test void equivalentObjectOrderKeepsInputAndExactParametersStable() throws Exception {
        var f=new Fixture();
        f.controller.invoke(f.command("{\"b\":2,\"a\":false}"),"key");
        f.controller.invoke(f.command("{\"a\":false,\"b\":2}"),"key");
        var arguments=ArgumentCaptor.forClass(AgentRuntimeExecutionGateway.WorkflowInvocation.class);
        verify(f.runtime,times(2)).acceptWorkflow(eq(f.principal),eq(f.session),
                eq(OpenPlatformInputIds.create(f.principal,"session","workflow-invocation","key")),arguments.capture());
        assertEquals(arguments.getAllValues().getFirst(),arguments.getAllValues().getLast());
        assertEquals("{\"a\":false,\"b\":2}",arguments.getValue().parameters().canonicalJson());
        verify(f.access,times(2)).require("sessions.write");
    }

    @Test void canonicalizationPreservesDecimalNodesAndArrayOrderWithoutASecondDoubleConversion() {
        var f=new Fixture();
        var precise=new java.math.BigDecimal("0.12345678901234567890123456789");
        var nearby=new java.math.BigDecimal("0.12345678901234567890123456788");
        var left=f.json.createObjectNode();left.put("z",true);
        left.set("amount",com.fasterxml.jackson.databind.node.DecimalNode.valueOf(precise));
        var sequence=left.putArray("sequence");sequence.add(2);sequence.add(1);
        sequence.addObject().set("amount",com.fasterxml.jackson.databind.node.DecimalNode.valueOf(nearby));
        var right=f.json.createObjectNode();right.set("sequence",sequence.deepCopy());right.set("amount",left.get("amount"));right.put("z",true);
        f.controller.invoke(new OpenPlatformWorkflowInvocationController.Command("session","13","91","92",left),"key");
        f.controller.invoke(new OpenPlatformWorkflowInvocationController.Command("session","13","91","92",right),"key");
        right.set("amount",com.fasterxml.jackson.databind.node.DecimalNode.valueOf(nearby));
        f.controller.invoke(new OpenPlatformWorkflowInvocationController.Command("session","13","91","92",right),"key");
        var arguments=ArgumentCaptor.forClass(AgentRuntimeExecutionGateway.WorkflowInvocation.class);
        verify(f.runtime,times(3)).acceptWorkflow(eq(f.principal),eq(f.session),anyString(),arguments.capture());
        var values=arguments.getAllValues();
        assertEquals(values.get(0),values.get(1));assertNotEquals(values.get(0),values.get(2));
        assertTrue(values.getFirst().parameters().canonicalJson().contains(precise.toPlainString()));
        assertTrue(values.getFirst().parameters().canonicalJson().contains("\"sequence\":[2,1,{\"amount\":"+nearby.toPlainString()));
        // 两个值转double会相同，确保反例确实能检出旧的二次转换路径。
        assertEquals(precise.doubleValue(),nearby.doubleValue());
    }

    @Test void invalidMissingKeyOrNonObjectParametersRejectBeforeRuntime() throws Exception {
        var f=new Fixture();
        for(String key:new String[]{""," ","x".repeat(257)})
            assertThrows(ApiException.class,()->f.controller.invoke(f.command("{}"),key));
        assertThrows(ApiException.class,()->f.controller.invoke(f.command("[]"),"key"));
        verifyNoInteractions(f.runtime);
    }

    @Test void revokedPrincipalCrossIntegrationSessionAndClosedSessionCannotInvoke() throws Exception {
        var f=new Fixture();var denied=new ApiException(HttpStatus.FORBIDDEN,"revoked");
        when(f.access.require("sessions.write")).thenThrow(denied);
        assertSame(denied,assertThrows(ApiException.class,()->f.controller.invoke(f.command("{}"),"key")));
        doReturn(f.current).when(f.access).require("sessions.write");
        when(f.access.session(f.current,"session")).thenThrow(denied);
        assertSame(denied,assertThrows(ApiException.class,()->f.controller.invoke(f.command("{}"),"key")));
        doReturn(f.session).when(f.access).session(f.current,"session");
        when(f.sessions.isOpenPlatformClosed(f.session)).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,()->f.controller.invoke(f.command("{}"),"key")).getStatus());
        verifyNoInteractions(f.runtime);
    }

    @Test void duplicateConflictFromOriginalAdmissionIsPreserved() throws Exception {
        var f=new Fixture();var conflict=new ApiException(HttpStatus.CONFLICT,"execution_input_changed");
        when(f.runtime.acceptWorkflow(any(),any(),anyString(),any())).thenThrow(conflict);
        assertSame(conflict,assertThrows(ApiException.class,()->f.controller.invoke(f.command("{\"value\":1}"),"key")));
    }

    private static final class Fixture {
        final ObjectMapper json=new ObjectMapper();
        final OpenPlatformSessionAccessService access=mock(OpenPlatformSessionAccessService.class);
        final SessionService sessions=mock(SessionService.class);
        final AgentRuntimeExecutionGateway runtime=mock(AgentRuntimeExecutionGateway.class);
        final ServicePrincipalContext.Identity principal=new ServicePrincipalContext.Identity("sp","7","hxy",1,Set.of("sessions.write"));
        final OpenPlatformAccessService.Access current=new OpenPlatformAccessService.Access(principal);
        final SessionView session=SessionView.builder().sessionId("session").tenantId(7L).agentId(8L).endUserId(9L).build();
        final OpenPlatformWorkflowInvocationController controller=new OpenPlatformWorkflowInvocationController(access,sessions,runtime,json,p->true);
        Fixture(){when(access.require("sessions.write")).thenReturn(current);when(access.session(current,"session")).thenReturn(session);}
        OpenPlatformWorkflowInvocationController.Command command(String parameters) throws Exception {
            return new OpenPlatformWorkflowInvocationController.Command("session","13","91","92",json.readTree(parameters));
        }
    }
}

package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformActionRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformCallbackRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformCommandRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformCreateSessionRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformMessageRequest;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OpenPlatformSessionControllerTest {

    @AfterEach
    void clearPrincipal() {
        ServicePrincipalContext.clear();
    }

    @Test
    void createsFrozenProxySessionAndSubmitsMessageThroughRuntime() {
        SessionService sessions = mock(SessionService.class);
        EndUserService users = mock(EndUserService.class);
        AgentRuntimeExecutionGateway runtime = mock(AgentRuntimeExecutionGateway.class);
        EndUserDO user = new EndUserDO();
        user.setId(99L); user.setExternalIntegrationId("crm"); user.setExternalUserRef("user-1");
        SessionView session = openSession().toBuilder().scopeId("conversation-1").build();
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "sp-1", "7", "crm", 1L, Set.of(OpenPlatformSessionController.SESSION_WRITE_SCOPE)));
        when(users.require("7", 99L)).thenReturn(user);
        when(sessions.getOrCreateOpenPlatform("7", "crm", "user-1", "conversation-1",
                null, 99L, 7L, null)).thenReturn(session);
        when(sessions.require("7", "s-1")).thenReturn(session);
        when(runtime.acceptMessage(any(), eq(session), eq(input("message", "key-1")), eq(new OpenPlatformMessageRequest("hello"))))
                .thenReturn(receipt(input("message", "key-1")));

        OpenPlatformSessionController controller = controller(sessions, users, runtime);
        assertEquals("s-1", controller.create(new OpenPlatformCreateSessionRequest(
                null, "user-1", "conversation-1", "7", "99")).data().sessionId());
        assertEquals("accepted", controller.message("s-1", "key-1", new OpenPlatformMessageRequest("hello"))
                .status());
        verify(runtime).acceptMessage(any(), eq(session), eq(input("message", "key-1")), eq(new OpenPlatformMessageRequest("hello")));
    }

    @Test
    void rejectsCreateWithoutEndUserId() {
        SessionService sessions = mock(SessionService.class);
        EndUserService users = mock(EndUserService.class);
        AgentRuntimeExecutionGateway runtime = mock(AgentRuntimeExecutionGateway.class);
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "sp-1", "7", "crm", 1L, Set.of(OpenPlatformSessionController.SESSION_WRITE_SCOPE)));
        OpenPlatformSessionController controller = controller(sessions, users, runtime);

        ApiException failure = assertThrows(ApiException.class,
                () -> controller.create(new OpenPlatformCreateSessionRequest(
                        null, "user-1", "conversation-1", "7", null)));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getStatus());
        org.mockito.Mockito.verifyNoInteractions(sessions, users, runtime);
    }

    @Test
    void sessionIdentityFieldsAreExactDecimalStringsAndInvalidIdsRejectBeforeLookup() {
        var sessions=mock(SessionService.class);var users=mock(EndUserService.class);var runtime=mock(AgentRuntimeExecutionGateway.class);
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp-1","7","crm",1,Set.of("sessions.write")));
        var controller=controller(sessions,users,runtime);
        for(String invalid:new String[]{"0","-1","1.5","1e2","9223372036854775808"," 7"}) {
            var endUserFailure=assertThrows(ApiException.class,()->controller.create(
                    new OpenPlatformCreateSessionRequest(null,"user-1","conversation-1","7",invalid)));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,endUserFailure.getStatus());
            org.junit.jupiter.api.Assertions.assertTrue(endUserFailure.getMessage().contains("end_user_id"));
            var agentFailure=assertThrows(ApiException.class,()->controller.create(
                    new OpenPlatformCreateSessionRequest(null,"user-1","conversation-1",invalid,"99")));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,agentFailure.getStatus());
            org.junit.jupiter.api.Assertions.assertTrue(agentFailure.getMessage().contains("agent_id"));
        }
        org.mockito.Mockito.verifyNoInteractions(sessions,users,runtime);
        var user=new EndUserDO();user.setId(9007199254740993L);user.setExternalIntegrationId("crm");user.setExternalUserRef("user-1");
        var session=openSession().toBuilder().agentId(9007199254740995L).build();
        when(users.require("7",9007199254740993L)).thenReturn(user);
        when(sessions.getOrCreateOpenPlatform("7","crm","user-1","conversation-1",null,
                9007199254740993L,9007199254740995L,null)).thenReturn(session);
        var result=controller.create(new OpenPlatformCreateSessionRequest(null,"user-1","conversation-1",
                "9007199254740995","9007199254740993")).data();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE).valueToTree(result);
        assertEquals("9007199254740993",json.path("end_user_id").textValue());
        assertEquals("9007199254740995",json.path("agent_id").textValue());
    }

    @Test
    void rejectsMissingScopeAndNeverReadsOrEnqueues() {
        SessionService sessions = mock(SessionService.class);
        EndUserService users = mock(EndUserService.class);
        AgentRuntimeExecutionGateway runtime = mock(AgentRuntimeExecutionGateway.class);
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp-1", "7", "crm", 1L, Set.of()));
        var integrations = mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class);
        org.mockito.Mockito.doThrow(new ApiException(HttpStatus.FORBIDDEN, "缺少当前执行scope"))
                .when(integrations).requirePrincipal(ServicePrincipalContext.get(), "sessions.write");
        var controller = new OpenPlatformSessionController(sessions, users, runtime,
                new com.skillforge.core.chat.access.OpenPlatformSessionAccessService(new com.skillforge.core.foundation.integration.OpenPlatformAccessService(integrations), integrations, sessions), integrations);

        ApiException failure = assertThrows(ApiException.class,
                () -> controller.message("s-1", "key-1", new OpenPlatformMessageRequest("hello")));

        assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        org.mockito.Mockito.verify(integrations).requirePrincipal(ServicePrincipalContext.get(), "sessions.write");
        org.mockito.Mockito.verifyNoInteractions(sessions, users, runtime);
    }

    @Test
    void actionAndCallbackAreSubmittedToRuntimeWithStableSessionScopedKeys() {
        SessionService sessions = mock(SessionService.class);
        EndUserService users = mock(EndUserService.class);
        AgentRuntimeExecutionGateway runtime = mock(AgentRuntimeExecutionGateway.class);
        SessionView session = openSession();
        var exactReply = Map.<String, Object>of("execution_id", "exec-1", "run_revision", "7", "human_input",
                Map.of("wait", Map.of("action_id", "ask-1", "generation", "2"), "text", "继续处理"));
        when(sessions.require("7", "s-1")).thenReturn(session);
        when(runtime.acceptAction(any(), eq(session), eq(input("action", "key-action")),
                eq("exec-1"), eq("ask-1"), eq(exactReply)))
                .thenReturn(receipt(input("action", "key-action")));
        when(runtime.acceptCallback(any(), eq(session), eq(input("callback", "key-callback")),
                eq("callback-1"), eq("business_event"), eq("external-exec-1"), eq(null), any()))
                .thenReturn(receipt(input("callback", "key-callback")));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "sp-1", "7", "crm", 1L, Set.of(OpenPlatformSessionController.SESSION_WRITE_SCOPE)));
        OpenPlatformSessionController controller = controller(sessions, users, runtime);

        controller.action("s-1", "key-action",
                new OpenPlatformActionRequest("exec-1", "ask-1", exactReply));
        controller.callback("s-1", "key-callback", new OpenPlatformCallbackRequest(
                "callback-1", "business_event", "external-exec-1", null, Map.of("payment_id", "p-1")));

        verify(runtime).acceptAction(any(), eq(session), eq(input("action", "key-action")),
                eq("exec-1"), eq("ask-1"), eq(exactReply));
        verify(runtime).acceptCallback(any(), eq(session), eq(input("callback", "key-callback")),
                eq("callback-1"), eq("business_event"), eq("external-exec-1"), eq(null),
                eq(Map.of("payment_id", "p-1")));
    }

    @Test
    void rejectsReservedCallbackFieldsAndUnknownCommandsBeforeRuntimeWrite() {
        SessionService sessions = mock(SessionService.class);
        EndUserService users = mock(EndUserService.class);
        AgentRuntimeExecutionGateway runtime = mock(AgentRuntimeExecutionGateway.class);
        when(sessions.require("7", "s-1")).thenReturn(openSession());
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "sp-1", "7", "crm", 1L, Set.of(OpenPlatformSessionController.SESSION_WRITE_SCOPE)));
        OpenPlatformSessionController controller = controller(sessions, users, runtime);

        assertThrows(ApiException.class, () -> controller.callback("s-1", "key-callback",
                new OpenPlatformCallbackRequest("callback-1", "business_event", "exec-1", null,
                        Map.of("tenant_id", "999"))));
        ApiException unknown = assertThrows(ApiException.class, () -> controller.command(
                "s-1", "key-command", new OpenPlatformCommandRequest("invoke_internal", null, null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, unknown.getStatus());
        org.mockito.Mockito.verifyNoInteractions(runtime);
    }

    @Test
    void businessEventDoesNotRequireAnInternalRunOrWorkflowIdentity() {
        var sessions = mock(SessionService.class);
        var users = mock(EndUserService.class);
        var runtime = mock(AgentRuntimeExecutionGateway.class);
        var session = openSession();
        when(sessions.require("7", "s-1")).thenReturn(session);
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "sp-1", "7", "crm", 1L, Set.of(OpenPlatformSessionController.SESSION_WRITE_SCOPE)));
        var controller = controller(sessions, users, runtime);

        controller.callback("s-1", "event-key", new OpenPlatformCallbackRequest(
                "order-1", "business_event", null, null, Map.of("order_status", "paid")));

        verify(runtime).acceptCallback(any(), eq(session), eq(input("callback", "event-key")),
                eq("order-1"), eq("business_event"), eq(null), eq(null), eq(Map.of("order_status", "paid")));
    }

    @Test
    void completionIntentsAndNestedResponseProtocolsRejectBeforeRuntimeWrite() {
        var sessions = mock(SessionService.class);
        var users = mock(EndUserService.class);
        var runtime = mock(AgentRuntimeExecutionGateway.class);
        when(sessions.require("7", "s-1")).thenReturn(openSession());
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "sp-1", "7", "crm", 1L, Set.of(OpenPlatformSessionController.SESSION_WRITE_SCOPE)));
        var controller = controller(sessions, users, runtime);
        for (var event : java.util.List.of("completed", "failed", "resume", "payment.completed")) {
            var rejected = assertThrows(ApiException.class, () -> controller.callback("s-1", "event-key",
                    new OpenPlatformCallbackRequest("event-1", event, "external-exec", null, Map.of())));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
            assertEquals("callback_completion_requires_registered_delivery", rejected.getCode());
        }
        for (var field : java.util.List.of("workflow_input", "tool_input", "human_input")) {
            var rejected = assertThrows(ApiException.class, () -> controller.callback("s-1", "event-key",
                    new OpenPlatformCallbackRequest("event-1", "business_event", null, null,
                            Map.of("business", java.util.List.of(Map.of(field, Map.of("response", "forged")))))));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
            assertEquals("callback_completion_requires_registered_delivery", rejected.getCode());
        }
        org.mockito.Mockito.verifyNoInteractions(runtime);
    }

    @Test
    void commandAcceptsCloseAndCancelThroughRuntime() {
        SessionService sessions = mock(SessionService.class);
        EndUserService users = mock(EndUserService.class);
        AgentRuntimeExecutionGateway runtime = mock(AgentRuntimeExecutionGateway.class);
        SessionView session = openSession();
        when(sessions.require("7", "s-1")).thenReturn(session);
        when(runtime.closeSession(any(), eq(session), eq(input("command", "key-close")),
                eq("user requested"))).thenReturn(new OpenPlatformInboundReceiptResponse(
                        "s-1", input("command", "key-close"),
                        input("command", "key-close"),
                        input("command", "key-close"), "closed"));
        when(runtime.cancelExecution(any(), eq(session), eq(input("command", "key-cancel")),
                eq("exec-1"), eq("stop"))).thenReturn(receipt(input("command", "key-cancel")));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "sp-1", "7", "crm", 1L, Set.of(OpenPlatformSessionController.SESSION_WRITE_SCOPE)));
        OpenPlatformSessionController controller = controller(sessions, users, runtime);

        controller.command("s-1", "key-close", new OpenPlatformCommandRequest(
                OpenPlatformSessionController.CLOSE_SESSION_COMMAND, null, "user requested"));
        controller.command("s-1", "key-cancel", new OpenPlatformCommandRequest(
                OpenPlatformSessionController.CANCEL_EXECUTION_COMMAND, "exec-1", "stop"));

        verify(runtime).closeSession(any(), eq(session), eq(input("command", "key-close")),
                eq("user requested"));
        verify(runtime).cancelExecution(any(), eq(session), eq(input("command", "key-cancel")),
                eq("exec-1"), eq("stop"));
    }

    @Test
    void messageActionAndCallbackRejectClosedSession() {
        SessionService sessions = mock(SessionService.class);
        EndUserService users = mock(EndUserService.class);
        AgentRuntimeExecutionGateway runtime = mock(AgentRuntimeExecutionGateway.class);
        SessionView session = openSession();
        when(sessions.require("7", "s-1")).thenReturn(session);
        when(sessions.isOpenPlatformClosed(session)).thenReturn(true);
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "sp-1", "7", "crm", 1L, Set.of(OpenPlatformSessionController.SESSION_WRITE_SCOPE)));
        OpenPlatformSessionController controller = controller(sessions, users, runtime);

        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> controller.message("s-1", "key", new OpenPlatformMessageRequest("hello"))).getStatus());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> controller.action("s-1", "key", new OpenPlatformActionRequest("e", "a", Map.of())))
                .getStatus());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> controller.callback("s-1", "key", new OpenPlatformCallbackRequest(
                        "c", "event", "e", null, Map.of()))).getStatus());
        org.mockito.Mockito.verifyNoInteractions(runtime);
    }

    private OpenPlatformSessionController controller(SessionService sessions,EndUserService users,AgentRuntimeExecutionGateway runtime) {
        var integrations=mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class);
        when(sessions.requireOpenPlatform(any(),any(),any()))
                .thenAnswer(call->sessions.require(call.getArgument(0),call.getArgument(1)));
        return new OpenPlatformSessionController(sessions,users,runtime,
                new com.skillforge.core.chat.access.OpenPlatformSessionAccessService(new com.skillforge.core.foundation.integration.OpenPlatformAccessService(integrations), integrations, sessions),integrations);
    }

    @Test
    void nestedMessageContextCannotOverrideTrustedSourceOrBusinessScope() {
        var sessions=mock(SessionService.class);var users=mock(EndUserService.class);var runtime=mock(AgentRuntimeExecutionGateway.class);
        when(sessions.require("7","s-1")).thenReturn(openSession());
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp","7","crm",1,Set.of("sessions.write")));
        var controller=controller(sessions,users,runtime);
        for(String key:java.util.List.of("application_id","business_scope_id","integration_id","delegation_requester_id")) {
            var request=new OpenPlatformMessageRequest(com.skillforge.core.chat.contract.ChatContent.text("消息"),
                    Map.of("business",Map.of(key,"forged")),java.util.List.of());
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,assertThrows(ApiException.class,()->controller.message("s-1","key",request)).getStatus());
        }
        org.mockito.Mockito.verifyNoInteractions(runtime);
    }

    private String input(String operation,String key) {
        return OpenPlatformInputIds.create(new ServicePrincipalContext.Identity("sp-1","7","crm",1,Set.of()),"s-1",operation,key);
    }

    private SessionView openSession() {
        return SessionView.builder().sessionId("s-1").tenantId(7L).integrationId("crm")
                .agentId(7L).endUserId(99L).scopeType("open-platform:crm").metadata(Map.of()).build();
    }

    private OpenPlatformInboundReceiptResponse receipt(String inputId) {
        return new OpenPlatformInboundReceiptResponse("s-1", inputId, inputId, inputId, "accepted");
    }
}

package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OpenPlatformMessageReceiptControllerTest {
    private final OpenPlatformSessionAccessService access = mock(OpenPlatformSessionAccessService.class);
    private final AgentRuntimeExecutionGateway runtime = mock(AgentRuntimeExecutionGateway.class);
    private final OpenPlatformMessageReceiptController controller = new OpenPlatformMessageReceiptController(access, runtime);
    private final OpenPlatformAccessService.Access identity = new OpenPlatformAccessService.Access(
            new ServicePrincipalContext.Identity("sp", "7", "crm", 1, Set.of("sessions.read")));
    private final SessionView session = SessionView.builder().tenantId(7L).sessionId("s").integrationId("crm")
            .scopeType("open-platform:crm").agentId(7L).endUserId(99L).build();

    @Test void findsOriginalReceiptThroughTheSameInputIdFactoryWithoutPostingTheMessage() {
        authorize();
        String input = OpenPlatformInputIds.create(identity.principal(), "s", "message", "original-key");
        var original = new OpenPlatformInboundReceiptResponse("s", input, input, "accepted-event", "accepted", "original-run", false);
        when(runtime.findOpenPlatformMessageReceipt(identity.principal(), session, input)).thenReturn(Optional.of(original));
        var found = controller.receipt("s", "original-key").data();
        assertEquals(OpenPlatformMessageReceiptController.State.FOUND, found.observation());
        assertSame(original, found.receipt());
        var ordered = inOrder(access, runtime);
        ordered.verify(access).require("sessions.read"); ordered.verify(access).session(identity, "s");
        ordered.verify(runtime).findOpenPlatformMessageReceipt(identity.principal(), session, input);
        verifyNoMoreInteractions(runtime);
    }

    @Test void notObservedIsAnExplicitObservationWithoutAnyReceiptOrRetryPermission() throws Exception {
        authorize(); when(runtime.findOpenPlatformMessageReceipt(any(), any(), anyString())).thenReturn(Optional.empty());
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v1/open-platform/sessions/messages/receipt").queryParam("sessionId", "s")
                        .header("Idempotency-Key", "original-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.observation").value("NOT_OBSERVED"))
                .andExpect(jsonPath("$.data.receipt").doesNotExist());
        var result = controller.receipt("s", "original-key").data();
        assertNull(result.receipt());
    }

    @Test void foreignIntegrationAndRevokedScopeNeverReachRuntime() {
        when(access.require("sessions.read")).thenReturn(identity);
        when(access.session(identity, "foreign")).thenThrow(new SecurityException("foreign integration"));
        assertThrows(SecurityException.class, () -> controller.receipt("foreign", "original-key"));
        when(access.require("sessions.read")).thenThrow(new SecurityException("scope revoked"));
        assertThrows(SecurityException.class, () -> controller.receipt("s", "original-key"));
        verifyNoInteractions(runtime);
    }

    @Test void invalidOrMissingOriginalKeyCannotBecomeAnotherOperation() throws Exception {
        authorize();
        for (String key : new String[]{"", " ", "x".repeat(257)})
            assertThrows(ApiException.class, () -> controller.receipt("s", key));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v1/open-platform/sessions/messages/receipt").queryParam("sessionId", "s"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(runtime);
    }

    private void authorize() {
        when(access.require("sessions.read")).thenReturn(identity); when(access.session(identity, "s")).thenReturn(session);
    }
}

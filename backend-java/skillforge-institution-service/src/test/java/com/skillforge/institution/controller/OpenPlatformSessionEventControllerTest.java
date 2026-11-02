package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.chat.dto.chat.SessionOutboxCursorResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Open Platform 事件端点必须有 ServicePrincipal scope，且仅只读冻结 Outbox。 */
class OpenPlatformSessionEventControllerTest {

    @AfterEach
    void clearPrincipal() {
        ServicePrincipalContext.clear();
    }

    @Test
    void returnsTheSharedFrozenEventProjectionForScopedPrincipal() {
        SessionService sessions = mock(SessionService.class);
        SessionMailboxService mailbox = mock(SessionMailboxService.class);
        var outputBuilder = SessionOutboxEntry.builder();
        outputBuilder.sessionId("s1");
        outputBuilder.eventId("event-1");
        outputBuilder.sessionSequence(1L);
        outputBuilder.createdAt(Instant.parse("2026-08-24T12:00:00Z"));
        outputBuilder.payload(Map.of("event", "message.completed", "data", Map.of("answer", "ok")));
        var output = outputBuilder.build();
        when(mailbox.listOutputsAfter("7", "s1", null, 100)).thenReturn(List.of(output));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "sp-1", "7", "crm", 1L, Set.of("sessions.events.read")));
        OpenPlatformSessionEventController controller = controller(sessions,mailbox);

        SessionOutboxCursorResponse response = controller.events("s1", null, 100).data();

        assertEquals("event-1", response.nextCursor());
        assertEquals("event-1", response.events().getFirst().eventId());
        verify(sessions).requireOpenPlatform("7", "s1","crm");
    }

    @Test
    void rejectsMissingScopeBeforeReadingAnySession() {
        SessionService sessions = mock(SessionService.class);
        SessionMailboxService mailbox = mock(SessionMailboxService.class);
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp-1", "7", "crm", 1L, Set.of()));
        var integrations = mock(InstitutionIntegrationService.class);
        org.mockito.Mockito.doThrow(new ApiException(HttpStatus.FORBIDDEN, "缺少当前执行scope"))
                .when(integrations).requirePrincipal(ServicePrincipalContext.get(), "sessions.events.read");
        OpenPlatformSessionEventController controller = controller(sessions,mailbox,integrations);

        ApiException failure = assertThrows(ApiException.class, () -> controller.events("s1", null, 100));

        assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        verify(integrations).requirePrincipal(ServicePrincipalContext.get(), "sessions.events.read");
        org.mockito.Mockito.verifyNoInteractions(sessions, mailbox);
    }

    @Test
    void otherIntegrationCannotReadEventsOrStartStream() {
        var sessions=mock(SessionService.class);var mailbox=mock(SessionMailboxService.class);
        var controller=controller(sessions,mailbox);
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp","7","crm",1,Set.of("sessions.events.read")));
        when(sessions.requireOpenPlatform("7","foreign","crm"))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN,"会话不属于当前 integration"));
        assertThrows(ApiException.class,()->controller.events("foreign",null,100));
        assertThrows(ApiException.class,()->controller.stream("foreign",null,null));
        org.mockito.Mockito.verifyNoInteractions(mailbox);
    }

    private OpenPlatformSessionEventController controller(SessionService sessions,SessionMailboxService mailbox) {
        return controller(sessions, mailbox, mock(InstitutionIntegrationService.class));
    }

    private OpenPlatformSessionEventController controller(SessionService sessions,SessionMailboxService mailbox,
            InstitutionIntegrationService integrations) {
        when(sessions.requireOpenPlatform(any(),any(),any())).thenReturn(SessionView.builder()
                .sessionId("s1").tenantId(7L).integrationId("crm").scopeType("open-platform:crm").build());
        var access=new com.skillforge.core.chat.access.OpenPlatformSessionAccessService(new OpenPlatformAccessService(integrations), integrations, sessions);
        return new OpenPlatformSessionEventController(access,mailbox,mock(AgentRuntimeExecutionProjection.class),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    }
}

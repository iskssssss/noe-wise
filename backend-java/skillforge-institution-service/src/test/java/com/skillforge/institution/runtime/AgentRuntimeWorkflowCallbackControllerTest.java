package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentRuntimeWorkflowCallbackControllerTest {

    @AfterEach
    void clearPrincipal() {
        ServicePrincipalContext.clear();
    }

    @Test
    void rejectsMissingServicePrincipalBeforeReadingSession() {
        ServicePrincipalContext.clear();
        SessionService sessions = mock(SessionService.class);
        AgentRuntimeWorkflowCallbackController controller = controller(sessions);

        ApiException failure = assertThrows(ApiException.class,
                () -> controller.register(new AgentRuntimeWorkflowCallbackController.Request("session-1", "run-1")));

        assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        verifyNoInteractions(sessions);
    }

    @Test
    void rejectsServicePrincipalWithoutWriteScopeBeforeReadingSession() {
        ServicePrincipalContext.set(identity(Set.of("sessions.read")));
        SessionService sessions = mock(SessionService.class);
        AgentRuntimeWorkflowCallbackController controller = controller(sessions);

        ApiException failure = assertThrows(ApiException.class,
                () -> controller.register(new AgentRuntimeWorkflowCallbackController.Request("session-1", "run-1")));

        assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        verifyNoInteractions(sessions);
    }

    @Test
    void rejectsSessionOwnedByAnotherIntegration() {
        ServicePrincipalContext.set(identity(Set.of("sessions.write")));
        SessionService sessions = mock(SessionService.class);
        SessionView session = org.mockito.Mockito.mock(SessionView.class);
        org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        org.mockito.Mockito.when(session.getScopeType()).thenReturn("open-platform:other-integration");
        when(sessions.require("tenant-1", "session-1")).thenReturn(session);
        AgentRuntimeWorkflowCallbackController controller = controller(sessions);

        ApiException failure = assertThrows(ApiException.class,
                () -> controller.register(new AgentRuntimeWorkflowCallbackController.Request("session-1", "run-1")));

        assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        verify(sessions).require("tenant-1", "session-1");
    }

    private AgentRuntimeWorkflowCallbackController controller(SessionService sessions) {
        return new AgentRuntimeWorkflowCallbackController(
                sessions,
                mock(ExecutionStore.class),
                mock(JdbcExecutionLedgerPort.class),
                mock(JdbcIdentityPolicyPort.class),
                mock(SdkWorkflowPortal.class),
                mock(com.skillforge.runtime.api.port.CallbackRegistrationPort.class));
    }

    private ServicePrincipalContext.Identity identity(Set<String> scopes) {
        return new ServicePrincipalContext.Identity(
                "principal-1", "tenant-1", "integration-1", 1L, scopes);
    }
}

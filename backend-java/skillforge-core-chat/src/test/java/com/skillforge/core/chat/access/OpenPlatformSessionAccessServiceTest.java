package com.skillforge.core.chat.access;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionView;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 身份授权与会话归属独立装配，冻结业务范围撤销仍阻止读取。 */
class OpenPlatformSessionAccessServiceTest {
    private final InstitutionIntegrationService integrations = mock(InstitutionIntegrationService.class);
    private final OpenPlatformAccessService identities = new OpenPlatformAccessService(integrations);
    private final SessionService sessions = mock(SessionService.class);
    private final OpenPlatformSessionAccessService access =
            new OpenPlatformSessionAccessService(identities, integrations, sessions);
    private final ServicePrincipalContext.Identity identity =
            new ServicePrincipalContext.Identity("sp", "7", "crm", 2, Set.of("sessions.read"));

    @Test void delegatesIdentityAndReturnsTheAuthorizedSessionView() {
        var session = session("crm", "store");
        when(sessions.requireOpenPlatform("7", "s", "crm")).thenReturn(session);
        var grant = access.require(identity, "sessions.read");
        assertSame(identity, grant.principal());
        assertSame(session, access.session(grant, "s"));
        verify(integrations).requirePrincipal(identity, "sessions.read");
        verify(integrations).requireBusinessScopeById("7", "crm", "store");
    }

    @Test void revokedCredentialStopsBeforeSessionRead() {
        doThrow(new SecurityException("revoked")).when(integrations).requirePrincipal(identity, "sessions.read");
        assertThrows(SecurityException.class, () -> access.require(identity, "sessions.read"));
        verifyNoInteractions(sessions);
    }

    @Test void revokedFrozenBusinessScopeCannotReadItsSession() {
        when(sessions.requireOpenPlatform("7", "s", "crm")).thenReturn(session("crm", "store"));
        doThrow(new SecurityException("scope_revoked")).when(integrations).requireBusinessScopeById("7", "crm", "store");
        assertThrows(SecurityException.class,
                () -> access.session(access.require(identity, "sessions.read"), "s"));
    }

    @Test void foreignIntegrationNeverReceivesBusinessScopeAuthorization() {
        when(sessions.requireOpenPlatform("7", "s", "crm")).thenReturn(session("other", "store"));
        assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                () -> access.session(access.require(identity, "sessions.read"), "s"));
        verify(integrations, never()).requireBusinessScopeById(any(), any(), any());
    }

    private SessionView session(String integration, String businessScope) {
        return SessionView.builder().tenantId(7L).sessionId("s").integrationId(integration)
                .scopeType("open-platform:" + integration).businessScopeId(businessScope).build();
    }
}

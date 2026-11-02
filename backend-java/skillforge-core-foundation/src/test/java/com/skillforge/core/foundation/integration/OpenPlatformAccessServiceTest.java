package com.skillforge.core.foundation.integration;

import com.skillforge.common.context.ServicePrincipalContext;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 公开读取委托当前执行域授权，不包含Session存储与归属。 */
class OpenPlatformAccessServiceTest {
    private final InstitutionIntegrationService integrations=mock(InstitutionIntegrationService.class);
    private final OpenPlatformAccessService access=new OpenPlatformAccessService(integrations);
    private final ServicePrincipalContext.Identity identity=new ServicePrincipalContext.Identity("sp","7","crm",2,Set.of("sessions.read"));

    @Test void delegatesCurrentPrincipalAuthorization() {
        var verified=access.require(identity,"sessions.read");
        assertSame(identity,verified.principal());
        verify(integrations).requirePrincipal(identity,"sessions.read");
    }
    @Test void invalidCurrentCredentialStopsBeforeAnySessionRead() {
        doThrow(new SecurityException("revoked")).when(integrations).requirePrincipal(identity,"sessions.read");
        assertThrows(SecurityException.class,()->access.require(identity,"sessions.read"));
    }
    @Test void absentIdentityStillGoesThroughSingleExecutionDomainAuthorization() {
        doThrow(new SecurityException("identity_required")).when(integrations).requirePrincipal(null,"sessions.read");
        assertThrows(SecurityException.class,()->access.require((ServicePrincipalContext.Identity)null,"sessions.read"));
    }
}

package com.skillforge.core.foundation.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.application.AuditService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 同一真实授权入口投影 namespace 新值能力，不以目录为空推断 unrestricted。 */
class PlatformManagementNamespaceScopeTest {
    private final FoundationAccess access=mock(FoundationAccess.class);
    private final ManagementMapper mapper=mock(ManagementMapper.class);
    private final PlatformManagementService service=new PlatformManagementService(access,mapper,mock(CryptoService.class),new ObjectMapper(),mock(AuditService.class));
    private static final String ACTION="platform.institutions.provision";
    @AfterEach void clear(){TenantContext.clear();ManagementContext.clear();ServicePrincipalContext.clear();}
    private FoundationAccess.PlatformAuthorization person(boolean all) {
        return new FoundationAccess.PlatformAuthorization(new FoundationAccess.Subject(true,null,"401"),
                new FoundationAccess.Snapshot(List.of("issuer"),List.of(ACTION),8),new FoundationAccess.DataScope(all,"401",Set.of()));
    }
    @Test void restrictedPersonUsesExactSameActionAndGranteeFilterAsNamespaceAdmission() {
        var person=person(false);when(access.currentPlatformAuthorization()).thenReturn(person);
        when(mapper.allowedNamespaces(ACTION,person.dataScope())).thenReturn(List.of("clinic"));
        var result=service.authorizedNamespaceScope(ACTION);
        assertFalse(result.allowCreate());assertEquals(Set.of("clinic"),result.namespaces());
        verify(mapper).allowedNamespaces(ACTION,person.dataScope());
    }
    @Test void allPersonCanCreateNewNamespacesWithoutFabricatedExistingTargets() {
        when(access.currentPlatformAuthorization()).thenReturn(person(true));
        var result=service.authorizedNamespaceScope(ACTION);
        assertTrue(result.allowCreate());assertTrue(result.namespaces().isEmpty());verifyNoInteractions(mapper);
    }
    @Test void managementMachineOnlyGetsFixedTargetsEvenIfTargetListIsEmpty() {
        var row=new ManagementRows.Principal();row.setPrincipalId(3L);row.setStatus("active");row.setCredentialVersion(2L);row.setRevision(4L);
        row.setScopes(List.of(ACTION));row.setTargetNamespaces(List.of("machine-fixed"));
        when(mapper.principal(3L)).thenReturn(row);ManagementContext.set(new ManagementContext.Identity("3",2,4));
        var result=service.authorizedNamespaceScope(ACTION);
        assertFalse(result.allowCreate());assertEquals(Set.of("machine-fixed"),result.namespaces());verifyNoInteractions(access);
    }
}

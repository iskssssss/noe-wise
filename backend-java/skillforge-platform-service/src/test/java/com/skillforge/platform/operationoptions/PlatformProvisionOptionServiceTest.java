package com.skillforge.platform.operationoptions;

import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.integration.PlatformManagementService.NamespaceScope;
import com.skillforge.core.foundation.integration.ProvisioningPlanOption;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 平台开通选项与签发目标的受限交集语义。 */
class PlatformProvisionOptionServiceTest {
    private final PlatformManagementService management = mock(PlatformManagementService.class);
    private final InstitutionIntegrationMapper mapper = mock(InstitutionIntegrationMapper.class);
    private final PlatformProvisionOptionService service = new PlatformProvisionOptionService(management,mapper);
    private static final String CREATE="platform.management_principals.create";
    private static final String PROVISION="platform.institutions.provision";
    private static final String ASSOCIATE="platform.institutions.associate";

    @Test void restrictedProvisionOnlyReturnsExplicitNamespacesAndCannotCreate() {
        when(management.authorizedNamespaceScope(PROVISION)).thenReturn(new NamespaceScope(false,Set.of("clinic-a","clinic-b")));
        var result=service.namespaces(PROVISION,Set.of(),"b",1,20);
        assertFalse(result.allowCreate());assertEquals(1,result.total());assertEquals("clinic-b",result.items().getFirst().id());
    }
    @Test void managementCredentialNamespaceTargetsAreIntersectionOfEverySelectedNamespaceAction() {
        when(management.authorizedNamespaceScope(CREATE)).thenReturn(new NamespaceScope(false,Set.of()));
        when(management.authorizedNamespaceScope(PROVISION)).thenReturn(new NamespaceScope(false,Set.of("shared","provision-only")));
        when(management.authorizedNamespaceScope(ASSOCIATE)).thenReturn(new NamespaceScope(false,Set.of("shared","associate-only")));
        var result=service.namespaces(CREATE,Set.of(PROVISION,ASSOCIATE),"",1,20);
        assertFalse(result.allowCreate());assertEquals(List.of("shared"),result.items().stream().map(PlatformOperationOption::id).toList());
        verify(management).requireAction(PROVISION);verify(management).requireAction(ASSOCIATE);
    }
    @Test void restrictedCredentialWithoutProvisionOrAssociateCannotGrantNamespace() {
        when(management.authorizedNamespaceScope(CREATE)).thenReturn(new NamespaceScope(false,Set.of()));
        var result=service.namespaces(CREATE,Set.of("platform.agent_grants.read"),"",1,20);
        assertFalse(result.allowCreate());assertTrue(result.items().isEmpty());
    }
    @Test void allDataScopeExplicitlyAllowsNewNamespacesRatherThanInferringFromEmptyItems() {
        when(management.authorizedNamespaceScope(CREATE)).thenReturn(new NamespaceScope(true,Set.of()));
        when(management.authorizedNamespaceScope(PROVISION)).thenReturn(new NamespaceScope(true,Set.of()));
        var result=service.namespaces(CREATE,Set.of(PROVISION),"new-value",1,20);
        assertTrue(result.allowCreate());assertTrue(result.items().isEmpty());
    }
    @Test void selectedScopeRevokedSinceOpeningCannotReturnCredentialNamespaceOptions() {
        when(management.authorizedNamespaceScope(CREATE)).thenReturn(new NamespaceScope(false,Set.of()));
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"权限已撤销")).when(management).requireAction(ASSOCIATE);
        assertThrows(ApiException.class,()->service.namespaces(CREATE,Set.of(ASSOCIATE),"",1,20));
        verify(management,never()).authorizedNamespaceScope(ASSOCIATE);
    }
    @Test void activePlanQueryUsesOnlyProvisionPermissionAndPassesExactCatalogFilters() {
        when(mapper.activePlans("专业","pro",20,20L)).thenReturn(List.of(new ProvisioningPlanOption("pro","专业套餐","pro")));
        when(mapper.activePlanCount("专业","pro")).thenReturn(1L);
        var result=service.plans("专业","pro",2,20);
        assertEquals("pro",result.items().getFirst().id());assertEquals(1,result.total());
        verify(management).requireAction(PROVISION);verifyNoMoreInteractions(management);
    }
}

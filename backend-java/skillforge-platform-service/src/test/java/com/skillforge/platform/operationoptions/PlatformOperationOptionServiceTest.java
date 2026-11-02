package com.skillforge.platform.operationoptions;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.platform.controller.PlatformOperationOptionMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 操作候选复用当前管理闭包，拒绝路径不执行目录 SQL。 */
class PlatformOperationOptionServiceTest {
    private final PlatformManagementService management = mock(PlatformManagementService.class);
    private final PlatformOperationOptionMapper mapper = mock(PlatformOperationOptionMapper.class);
    private final PlatformOperationOptionService service = new PlatformOperationOptionService(management, mapper);

    @AfterEach void clearContext() { TenantContext.clear(); }

    @Test void operationPermissionIsSufficientAndExactTargetsReachSql() {
        String operation = "platform.agent_grants.create";
        when(management.allowedTenantIds(operation)).thenReturn(Set.of(7L));
        when(mapper.tenants(Set.of(7L), "门店", null, 20, 20L))
                .thenReturn(List.of(new PlatformOperationOption("7", "门店甲", "clinic")));
        when(mapper.tenantCount(Set.of(7L), "门店", null)).thenReturn(1L);
        var result = service.tenants(operation, "门店", null, 2, 20);
        assertEquals("7", result.items().getFirst().id());
        assertEquals(1L, result.total());
        verify(management).allowedTenantIds(operation);
        verifyNoMoreInteractions(management);
    }

    @Test void deniedCurrentActionCannotReadOrCountCandidates() {
        when(management.allowedTenantIds("platform.agent_deployments.create"))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "无操作权限"));
        assertThrows(ApiException.class, () -> service.tenants("platform.agent_deployments.create", "", null, 1, 20));
        verifyNoInteractions(mapper);
    }

    @Test void unsupportedOperationAndMalformedIdFailBeforeAuthorizationOrQuery() {
        assertThrows(ApiException.class, () -> service.tenants("foundation.tenants.read", "", null, 1, 20));
        assertThrows(ApiException.class, () -> service.tenants("platform.agent_grants.read", "", "9223372036854775808", 1, 20));
        assertThrows(ApiException.class, () -> service.tenants("platform.agent_grants.read", "", null, 0, 20));
        verifyNoInteractions(management, mapper);
    }

    @Test void directGrantCandidatesUseManageDefinitionClosureAndControlledSourceFilter() {
        when(management.allowedDefinitionIds("platform.agent_grants.create")).thenReturn(Set.of(31L));
        when(mapper.definitions(Set.of(31L), true, "", null, 20, 0L)).thenReturn(List.of());
        service.definitions("platform.agent_grants.create", "", null, 1, 20);
        verify(mapper).definitions(Set.of(31L), true, "", null, 20, 0L);
        verify(mapper).definitionCount(Set.of(31L), true, "", null);
    }

    @Test void integrationTargetIsAuthorizedBeforeRlsAndPriorContextIsRestoredAfterFailure() {
        TenantContext.setTenantId("9");
        doAnswer(invocation -> { assertNull(TenantContext.getTenantId()); return null; })
                .when(management).requireTenant("platform.institution_credentials.issue", 7L);
        when(mapper.integrations(7L, "", null, 20, 0L)).thenAnswer(invocation -> {
            assertEquals("7", TenantContext.getTenantId());
            throw new IllegalStateException("读取失败");
        });
        assertThrows(IllegalStateException.class, () -> service.integrations("platform.institution_credentials.issue", "7", "", null, 1, 20));
        assertEquals("9", TenantContext.getTenantId());
        verify(mapper, never()).integrationCount(anyLong(), anyString(), any());
    }

    @Test void managementTargetCandidatesUseTheSameManageClosureAsCredentialCreation() {
        when(management.allowedDefinitionIds("platform.management_principals.create")).thenReturn(Set.of(31L));
        when(mapper.definitions(Set.of(31L), false, "", 31L, 20, 0L)).thenReturn(List.of());
        service.definitions("platform.management_principals.create", "", "31", 1, 20);
        verify(mapper).definitions(Set.of(31L), false, "", 31L, 20, 0L);
    }
}

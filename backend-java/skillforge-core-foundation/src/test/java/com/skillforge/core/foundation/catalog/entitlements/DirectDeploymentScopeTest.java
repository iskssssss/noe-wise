package com.skillforge.core.foundation.catalog.entitlements;

import com.skillforge.common.context.TenantContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 目标授权与临时数据库绑定必须同时成立，失败后不能污染下一次机构调用。 */
class DirectDeploymentScopeTest {
    private final PlatformManagementService management=mock(PlatformManagementService.class);
    private final DirectDeploymentScope.ContextMapper mapper=mock(DirectDeploymentScope.ContextMapper.class);
    private final InstitutionIntegrationService integrations=mock(InstitutionIntegrationService.class);
    private final DirectDeploymentScope scope=new DirectDeploymentScope(management,mapper,integrations);
    @AfterEach void cleanup(){TenantContext.clear();ServicePrincipalContext.clear();}
    @Test void unauthorizedTargetNeverBindsDatabase() {
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"拒绝")).when(management).requireTenant("platform.agent_deployments.create",7);
        assertThrows(ApiException.class,()->scope.inTenant("7",()->"bad"));verifyNoInteractions(mapper);
    }
    @Test void successfulNestedTargetRestoresBothContexts() {
        when(mapper.current()).thenReturn(new DirectDeploymentScope.State("","true"));
        assertEquals("7",scope.inTenant("7",TenantContext::getTenantId));assertNull(TenantContext.getTenantId());
        verify(mapper).bindTenant("7");verify(mapper).restore("","true");
    }
    @Test void bindingFailureRestoresOriginalConnectionAndThread() {
        when(mapper.current()).thenReturn(new DirectDeploymentScope.State("","true"));
        doThrow(new IllegalStateException("unavailable")).when(mapper).bindTenant("7");
        assertThrows(IllegalStateException.class,()->scope.inTenant("7",()->"bad"));
        verify(mapper).restore("","true");assertNull(TenantContext.getTenantId());
    }
    @Test void priorDifferentTenantFailsBeforeDatabaseMutation() {
        TenantContext.setTenantId("8");assertThrows(SecurityException.class,()->scope.inTenant("7",()->"bad"));
        assertEquals("8",TenantContext.getTenantId());verifyNoInteractions(mapper);
    }
    /** 配额侧只核验执行主体及固定域，不依赖任何Session查询或会话服务装配。 */
    @Test void revokedServicePrincipalFailsBeforeContextMutation() {
        var principal=new ServicePrincipalContext.Identity("sp","7","integration",1,java.util.Set.of("resources.write"));
        ServicePrincipalContext.set(principal);TenantContext.setTenantId("7");
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"凭据已撤销")).when(integrations).requirePrincipal(principal,"resources.write");
        assertThrows(ApiException.class,()->scope.inTenant("7",()->"bad"));
        assertEquals("7",TenantContext.getTenantId());verifyNoInteractions(mapper,management);
    }

    @Test void institutionManagementCapacityUsesItsExactCurrentScopeWithoutResourcesWrite() {
        var principal=new ServicePrincipalContext.Identity("manager","7","integration",1,java.util.Set.of("tenant.sub_institutions.provision"));
        ServicePrincipalContext.set(principal);TenantContext.setTenantId("7");
        when(mapper.current()).thenReturn(new DirectDeploymentScope.State("7","false"));
        assertEquals("7",scope.inSubInstitutionTenant("7",TenantContext::getTenantId));
        verify(integrations).requirePrincipal(principal,"tenant.sub_institutions.provision");
        verify(integrations,never()).requirePrincipal(principal,"resources.write");
        verifyNoInteractions(management);verify(mapper).restore("7","false");
    }

    @Test void institutionManagementCapacityCannotUseAnotherTenantOrARevokedOrdinaryPrincipal() {
        var principal=new ServicePrincipalContext.Identity("ordinary","7","integration",1,java.util.Set.of("resources.write"));
        ServicePrincipalContext.set(principal);TenantContext.setTenantId("7");
        assertThrows(SecurityException.class,()->scope.inSubInstitutionTenant("8",()->"bad"));
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"no management scope"))
                .when(integrations).requirePrincipal(principal,"tenant.sub_institutions.provision");
        assertThrows(ApiException.class,()->scope.inSubInstitutionTenant("7",()->"bad"));
        verifyNoInteractions(mapper,management);
    }
}

package com.skillforge.core.foundation.catalog.entitlements;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts.EffectiveQuota;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubInstitutionPrincipalQuotaTest {
    @AfterEach void clear(){ServicePrincipalContext.clear();TenantContext.clear();TransactionSynchronizationManager.setActualTransactionActive(false);}
    @Test void institutionMachineUsesSameQuotaLockAndZeroLimitWithoutFoundationImpersonation() {
        var mapper=mock(EntitlementMapper.class);var catalog=mock(CatalogScope.class);var trusted=mock(DirectDeploymentScope.class);
        var quota=new ResourceQuota(mapper,catalog,trusted);var effective=mock(EffectiveQuota.class);
        when(effective.planEnabled()).thenReturn(true);when(effective.tenantStatus()).thenReturn("active");
        when(effective.limit(ResourceKind.SUB_INSTITUTION)).thenReturn(0L);when(mapper.lockQuota("7")).thenReturn(effective);
        when(trusted.inSubInstitutionTenant(eq("7"),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("manager","7","integration",1,Set.of("tenant.sub_institutions.provision")));
        TenantContext.setTenantId("7");TransactionSynchronizationManager.setActualTransactionActive(true);
        var failure=assertThrows(ApiException.class,()->quota.requireSubInstitutionCapacity("7",1));
        assertEquals("resource_quota_exceeded",failure.getCode());verify(mapper).lockQuota("7");verify(mapper).used("7","SUB_INSTITUTION");
        verifyNoInteractions(catalog);verify(trusted).inSubInstitutionTenant(eq("7"),any());
    }
}

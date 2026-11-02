package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.dto.request.InstallAgentTemplateRequest;
import com.skillforge.core.agent.mapper.AgentTemplateInstallationMapper;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceKind;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 新安装的套餐受理约束必须发生在实例、部署、挂载和安装回执写入之前。 */
class AgentTemplateInstallationServiceTest {
    private final AgentTemplateService templates=mock(AgentTemplateService.class);
    private final AgentService agents=mock(AgentService.class);
    private final AgentManagementService management=mock(AgentManagementService.class);
    private final com.skillforge.core.agent.management.AgentConfigurationPolicy configurations=mock(com.skillforge.core.agent.management.AgentConfigurationPolicy.class);
    private final CapabilityMountManagementService mounts=mock(CapabilityMountManagementService.class);
    private final CapabilityCatalogService catalog=mock(CapabilityCatalogService.class);
    private final CapabilityCatalogDatabaseContext context=mock(CapabilityCatalogDatabaseContext.class);
    private final TenantToolDeploymentProviderValidator providers=mock(TenantToolDeploymentProviderValidator.class);
    private final AgentTemplateInstallationMapper receipts=mock(AgentTemplateInstallationMapper.class);
    private final FoundationOrganizationService organization=mock(FoundationOrganizationService.class);
    private final ResourceQuota quota=mock(ResourceQuota.class);
    private final FeatureGate features=mock(FeatureGate.class);
    private final AgentTemplateInstallationService service=new AgentTemplateInstallationService(templates,agents,
            management,configurations,mounts,catalog,context,providers,receipts,new ObjectMapper().findAndRegisterModules(),
            organization,quota,features);
    private final InstallAgentTemplateRequest request=new InstallAgentTemplateRequest(31L,"受控安装","installation-request",List.of(),List.of());

    @BeforeEach void tenant(){TenantContext.setTenantId("7");}
    @AfterEach void clear(){TenantContext.clear();}

    @Test void disabledFeatureStopsBeforeTemplateAndResourceMutations() {
        var denied=new ApiException(HttpStatus.FORBIDDEN,"tenant_feature_disabled","机构未获授权",false);
        doThrow(denied).when(features).require("7","agents");
        assertSame(denied,assertThrows(ApiException.class,()->service.install(request)));
        verifyNoInteractions(templates,agents,management,configurations,mounts,catalog,organization,quota,providers);
        verify(receipts,never()).insert(anyLong(),anyString(),anyString(),anyLong(),anyLong());
    }

    @Test void exhaustedQuotaStopsBeforeTemplateAndResourceMutations() {
        var denied=new ApiException(HttpStatus.CONFLICT,"resource_quota_exceeded","资源配额不足",false);
        doThrow(denied).when(quota).requireCapacity("7",ResourceKind.AGENT,1);
        assertSame(denied,assertThrows(ApiException.class,()->service.install(request)));
        verify(features).require("7","agents");
        verifyNoInteractions(templates,agents,management,configurations,mounts,catalog,organization,providers);
        verify(receipts,never()).insert(anyLong(),anyString(),anyString(),anyLong(),anyLong());
    }
}

package com.skillforge.institution.service;

import com.skillforge.core.agent.deployment.AgentTemplateGrantService;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService;
import com.skillforge.core.foundation.institution.InstitutionTargetOption;
import com.skillforge.core.foundation.institution.InstitutionTargetOptionMapper;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 动作候选不依赖机构目录读权，授权拒绝先于任何机构投影读取。 */
class InstitutionTargetOptionServiceTest {
    private final AgentTemplateGrantService grants = mock(AgentTemplateGrantService.class);
    private final InstitutionDeploymentManagementService deployments = mock(InstitutionDeploymentManagementService.class);
    private final InstitutionTargetOptionMapper mapper = mock(InstitutionTargetOptionMapper.class);
    private final InstitutionTargetOptionService service = new InstitutionTargetOptionService(grants, deployments, mapper);

    @Test void grantCreationUsesOnlyItsCurrentActorAndDirectTargetClosureBeforePaging() {
        when(grants.requireTenantGrantTargetOptions()).thenReturn(7L);
        when(mapper.rows(7, true, Set.of(), "分店", 8L, 20, 20L)).thenReturn(List.of(new InstitutionTargetOption("8", "分店", "8")));
        when(mapper.count(7, true, Set.of(), "分店", 8L)).thenReturn(1L);
        var result = service.targets("tenant.agent_grants.create", "分店", "8", 2, 20);
        assertEquals("8", result.items().getFirst().id()); assertEquals(1, result.total());
        verify(grants).requireTenantGrantTargetOptions(); verifyNoInteractions(deployments);
    }
    @Test void deploymentsUseExactActionScopeIncludingOwnTenantWithoutDirectoryPermission() {
        String action = "tenant.agent_deployments.create";
        when(deployments.requireTargetOptions(action)).thenReturn(new InstitutionDeploymentManagementService.TargetOptionScope(7, Set.of(7L, 8L)));
        when(mapper.rows(7, false, Set.of(7L, 8L), "", null, 200, 0L)).thenReturn(List.of());
        service.targets(action, "", null, 1, 200);
        verify(mapper).rows(7, false, Set.of(7L, 8L), "", null, 200, 0L);
        verifyNoInteractions(grants);
    }
    @Test void failedActionAdmissionPreventsProjectionAndCount() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "当前动作已撤销")).when(grants).requireTenantGrantTargetOptions();
        assertThrows(ApiException.class, () -> service.targets("tenant.agent_grants.create", "", null, 1, 200));
        verifyNoInteractions(mapper, deployments);
    }
    @Test void invalidPagingAndNonCanonicalIdsNeverReachAuthorizationOrProjection() {
        for (String id : List.of("0", "+8", "08", "9223372036854775808"))
            assertThrows(ApiException.class, () -> service.targets("tenant.agent_grants.create", "", id, 1, 20));
        assertThrows(ApiException.class, () -> service.targets("tenant.agent_grants.create", "", null, 0, 20));
        verifyNoInteractions(grants, deployments, mapper);
    }
}

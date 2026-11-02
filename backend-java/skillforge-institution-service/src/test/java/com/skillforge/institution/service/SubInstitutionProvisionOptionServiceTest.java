package com.skillforge.institution.service;

import com.skillforge.core.foundation.institution.InstitutionRelationService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.integration.ProvisioningPlanOption;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 总部套餐读取授权使用正式开通边界，拒绝前不读取目录。 */
class SubInstitutionProvisionOptionServiceTest {
    @Test void provisionAuthorizationIsSufficientAndFiltersArePreserved() {
        var relations=mock(InstitutionRelationService.class);var mapper=mock(InstitutionIntegrationMapper.class);
        when(mapper.activePlans("专业","pro",20,0L)).thenReturn(List.of(new ProvisioningPlanOption("pro","专业","pro")));
        when(mapper.activePlanCount("专业","pro")).thenReturn(1L);
        var result=new SubInstitutionProvisionOptionService(relations,mapper).plans("专业","pro",1,20);
        assertEquals("pro",result.items().getFirst().id());assertEquals(1,result.total());
        verify(relations).requireProvisionOptions();verifyNoMoreInteractions(relations);
    }
    @Test void failedCurrentHeadquartersAdmissionPreventsDirectoryQueryAndCount() {
        var relations=mock(InstitutionRelationService.class);var mapper=mock(InstitutionIntegrationMapper.class);
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"总部不可开通")).when(relations).requireProvisionOptions();
        assertThrows(ApiException.class,()->new SubInstitutionProvisionOptionService(relations,mapper).plans("",null,1,20));
        verifyNoInteractions(mapper);
    }
}

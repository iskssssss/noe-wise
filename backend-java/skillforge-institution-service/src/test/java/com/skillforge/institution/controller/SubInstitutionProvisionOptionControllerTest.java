package com.skillforge.institution.controller;

import com.skillforge.institution.config.ExternalAuthPaths;
import com.skillforge.institution.service.SubInstitutionProvisionOptionService;
import com.skillforge.core.foundation.integration.ProvisioningPlanOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 总部开通套餐的HTTP字段及精确身份入口契约。 */
class SubInstitutionProvisionOptionControllerTest {
    private static final String PATH="/api/v1/tenant/sub-institutions/provision-options/plans";
    @Test void exposesOnlyTheExactInstitutionManagementPath() {
        assertTrue(ExternalAuthPaths.isInstitutionManagementPath(PATH));
        assertFalse(ExternalAuthPaths.isInstitutionManagementPath(PATH+"/other"));
        assertFalse(ExternalAuthPaths.isSignedOpenApiPath(PATH));
    }
    @Test void endpointPreservesPlanBusinessKeyAndPageFields() throws Exception {
        var service=mock(SubInstitutionProvisionOptionService.class);
        when(service.plans("专业","pro",1,200)).thenReturn(new SubInstitutionProvisionOptionService.Page(
                List.of(new ProvisioningPlanOption("pro","专业套餐","pro")),1,1,200));
        var mvc=MockMvcBuilders.standaloneSetup(new SubInstitutionProvisionOptionController(service)).build();
        mvc.perform(get(PATH).param("q","专业").param("id","pro"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value("pro"))
                .andExpect(jsonPath("$.data.items[0].label").value("专业套餐"))
                .andExpect(jsonPath("$.data.page_size").value(200));
        verify(service).plans("专业","pro",1,200);
    }
}

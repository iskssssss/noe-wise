package com.skillforge.institution.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skillforge.core.agent.AgentDefinitionLifecycleService;
import com.skillforge.core.agent.dto.query.AgentTemplateQuery;
import com.skillforge.core.agent.dto.response.AdminAgentTemplateListResponse;
import com.skillforge.core.agent.model.AgentDistributionPolicy;
import com.skillforge.institution.service.TenantAgentDefinitionImpactService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 机构共享源沿同一query契约，非法enum不得进入授权或列表服务。 */
class AgentDefinitionQueryBindingTest {
    @Test void bindsDistributionPolicyWithExistingPagingAndSearch() throws Exception {
        var service=mock(AgentDefinitionLifecycleService.class);
        when(service.list(any())).thenReturn(new AdminAgentTemplateListResponse(0,List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new TenantAgentDefinitionController(service,mock(TenantAgentDefinitionImpactService.class))).build();
        mvc.perform(get("/api/v1/tenant/agent-definitions").param("page","2").param("limit","20")
                .param("name"," needle ").param("status","published").param("distribution_policy","AUTHORIZED_DEPLOY"))
                .andExpect(status().isOk());
        verify(service).requireTenantOwner();
        verify(service).list(new AgentTemplateQuery(2,20,"published",null," needle ",AgentDistributionPolicy.AUTHORIZED_DEPLOY));
    }
    @Test void omittedPolicyKeepsExistingQueryContract() throws Exception {
        var service=mock(AgentDefinitionLifecycleService.class);
        when(service.list(any())).thenReturn(new AdminAgentTemplateListResponse(0,List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new TenantAgentDefinitionController(service,mock(TenantAgentDefinitionImpactService.class))).build();
        mvc.perform(get("/api/v1/tenant/agent-definitions").param("category","ops")).andExpect(status().isOk());
        verify(service).list(new AgentTemplateQuery(null,null,null,"ops",null,null));
    }
    @Test void invalidPolicyIsRejectedBeforeOwnerOrListService() throws Exception {
        var service=mock(AgentDefinitionLifecycleService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new TenantAgentDefinitionController(service,mock(TenantAgentDefinitionImpactService.class))).build();
        mvc.perform(get("/api/v1/tenant/agent-definitions").param("distribution_policy","INVALID")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void previewContentBindsSharedCommandWithoutWriting() throws Exception {
        var lifecycle=mock(AgentDefinitionLifecycleService.class);
        var impact=mock(TenantAgentDefinitionImpactService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new TenantAgentDefinitionController(lifecycle,impact)).build();
        mvc.perform(post("/api/v1/tenant/agent-definitions/preview-content")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"definition_id\":\"31\",\"expected_revision\":4,\"definition\":{\"system_prompt\":\"preview\"}}"))
                .andExpect(status().isOk());
        verify(lifecycle).requireTenantOwner();
        verify(impact).preview(eq(31L),eq(4L),argThat(definition->"preview".equals(definition.systemPrompt())));
    }
}

package com.skillforge.platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skillforge.core.agent.dto.query.AgentTemplateQuery;
import com.skillforge.core.agent.dto.response.AdminAgentTemplateListResponse;
import com.skillforge.core.agent.model.AgentDistributionPolicy;
import com.skillforge.platform.service.AgentDefinitionImpactService;
import com.skillforge.platform.service.AgentTemplateAdminService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 正式Controller的record构造绑定，分发策略使用snake wire与Spring enum转换。 */
class AgentDefinitionQueryBindingTest {
    @Test void bindsDistributionPolicyWithExistingPagingAndSearch() throws Exception {
        var service=mock(AgentTemplateAdminService.class);
        when(service.list(any())).thenReturn(new AdminAgentTemplateListResponse(0,List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new PlatformAgentTemplateController(service,mock(AgentDefinitionImpactService.class))).build();
        mvc.perform(get("/api/v1/platform/agent-definitions").param("page","2").param("limit","20")
                .param("name"," needle ").param("status","published").param("distribution_policy","AUTHORIZED_DEPLOY"))
                .andExpect(status().isOk());
        verify(service).list(new AgentTemplateQuery(2,20,"published",null," needle ",AgentDistributionPolicy.AUTHORIZED_DEPLOY));
    }
    @Test void omittedPolicyKeepsExistingQueryContract() throws Exception {
        var service=mock(AgentTemplateAdminService.class);
        when(service.list(any())).thenReturn(new AdminAgentTemplateListResponse(0,List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new PlatformAgentTemplateController(service,mock(AgentDefinitionImpactService.class))).build();
        mvc.perform(get("/api/v1/platform/agent-definitions").param("category","ops")).andExpect(status().isOk());
        verify(service).list(new AgentTemplateQuery(null,null,null,"ops",null,null));
    }
    @Test void invalidPolicyIsRejectedBeforeService() throws Exception {
        var service=mock(AgentTemplateAdminService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new PlatformAgentTemplateController(service,mock(AgentDefinitionImpactService.class))).build();
        mvc.perform(get("/api/v1/platform/agent-definitions").param("distribution_policy","INVALID")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

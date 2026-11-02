package com.skillforge.platform.operationoptions;

import com.skillforge.platform.controller.PlatformOperationOptionController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 查询字段、响应信封和精确字符串身份的消费契约。 */
class PlatformOperationOptionControllerTest {
    @Test void passesExactOperationAndSelectedIdentityWithoutNumericJsonConversion() throws Exception {
        var service = mock(PlatformOperationOptionService.class);
        when(service.tenants("platform.agent_grants.read", "门诊", "9223372036854775807", 2, 20))
                .thenReturn(new PlatformOperationOptionService.Page(
                        List.of(new PlatformOperationOption("9223372036854775807", "门诊机构", "clinic")), 1, 2, 20));
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformOperationOptionController(service)).build();
        mvc.perform(get("/api/v1/platform/operation-options/tenants")
                .param("operation", "platform.agent_grants.read").param("q", "门诊")
                .param("id", "9223372036854775807").param("page", "2").param("page_size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("9223372036854775807"))
                .andExpect(jsonPath("$.data.items[0].label").value("门诊机构"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page_size").value(20));
        verify(service).tenants("platform.agent_grants.read", "门诊", "9223372036854775807", 2, 20);
    }

    @Test void integrationCandidateRouteRequiresAnExplicitTenantBeforeCallingService() throws Exception {
        var service = mock(PlatformOperationOptionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformOperationOptionController(service)).build();
        mvc.perform(get("/api/v1/platform/operation-options/integrations")
                .param("operation", "platform.institution_credentials.read"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

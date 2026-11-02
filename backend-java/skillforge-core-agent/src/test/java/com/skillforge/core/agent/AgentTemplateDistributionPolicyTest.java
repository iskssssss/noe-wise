package com.skillforge.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.agent.dto.request.UpdateAgentTemplateRequest;
import com.skillforge.core.agent.model.AgentDistributionPolicy;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 服务端分发策略保护普通复制与受控部署，缺失策略必须失败关闭。 */
class AgentTemplateDistributionPolicyTest {
    private final AgentTemplateService service = new AgentTemplateService(mock(PlatformModelCatalogService.class),
            mock(CapabilityCatalogService.class), mock(PlatformToolCatalog.class), new ObjectMapper());

    @Test void controlledDefinitionRejectsOrdinaryConsumption() {
        var failure = assertThrows(ApiException.class,
                () -> service.requireLibraryTemplate(identity(AgentDistributionPolicy.AUTHORIZED_DEPLOY)));
        assertEquals("FORBIDDEN", failure.getCode());
    }

    @Test void openDefinitionAllowsIndependentInstallation() {
        assertDoesNotThrow(() -> service.requireLibraryTemplate(identity(AgentDistributionPolicy.OPEN_INSTALL)));
    }

    @Test void missingPolicyCannotDefaultToOpenInstallation() {
        assertEquals("VALIDATION_ERROR", assertThrows(ApiException.class,
                () -> service.requireLibraryTemplate(identity(null))).getCode());
        assertThrows(ApiException.class, () -> service.requireAuthorizedDeployment(identity(null)));
    }

    @Test void deploymentCannotConvertOpenDefinitionIntoControlledSource() {
        assertThrows(ApiException.class,
                () -> service.requireAuthorizedDeployment(identity(AgentDistributionPolicy.OPEN_INSTALL)));
        assertDoesNotThrow(() -> service.requireAuthorizedDeployment(identity(AgentDistributionPolicy.AUTHORIZED_DEPLOY)));
    }

    @Test void definitionOnlyJsonUpdateLeavesDistributionPolicyUnchanged() throws Exception {
        var request = updateJson().readValue("""
                {"definition":{"system_prompt":"新版定义"}}
                """, UpdateAgentTemplateRequest.class);
        assertNull(request.distributionPolicy());
        assertEquals("新版定义", request.definition().systemPrompt());
    }

    @Test void explicitNullDistributionPolicyJsonUpdateIsRejected() {
        assertThrows(JsonMappingException.class, () -> updateJson().readValue(
                "{\"distribution_policy\":null}", UpdateAgentTemplateRequest.class));
    }

    @Test void invalidDistributionPolicyJsonUpdateIsRejected() {
        for (String invalid : java.util.List.of("\"INVALID\"", "\"\"", "1", "true", "{}")) {
            assertThrows(JsonMappingException.class, () -> updateJson().readValue(
                    "{\"distribution_policy\":" + invalid + "}", UpdateAgentTemplateRequest.class));
        }
    }

    @Test void explicitValidDistributionPolicyJsonUpdateIsRetained() throws Exception {
        for (var policy : AgentDistributionPolicy.values()) {
            var request = updateJson().readValue("{\"distribution_policy\":\"" + policy.name() + "\"}",
                    UpdateAgentTemplateRequest.class);
            assertEquals(policy, request.distributionPolicy());
        }
    }

    private ObjectMapper updateJson() {
        return new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    private AgentTemplateDO identity(AgentDistributionPolicy policy) {
        var template = new AgentTemplateDO(); template.setId(31L); template.setDistributionPolicy(policy);
        return template;
    }
}

package com.skillforge.core.agent.systemagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.Mount;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformAgentResourcesTest {

    @Test
    void readsSkillInstructionsFromPublishedSkillPackage() {
        PlatformAgentResourceMapper mapper = mock(PlatformAgentResourceMapper.class);
        when(mapper.currentCapability("SKILL", "101")).thenReturn("""
                {"id":"101","definition_id":"101","name":"reporting","descriptor":{"package":{"files":[
                {"path":"SKILL.md","encoding":"UTF8","content":"# Reporting\\nUse facts."}]}}}
                """);
        PlatformAgentResources resources = resources(mapper);

        String instructions = resources.skillInstructions(List.of(
                new Mount("SKILL", "101", 10, 256L, "ALWAYS", null)));

        assertThat(instructions).isEqualTo("# Reporting\nUse facts.");
    }

    @Test
    void debugRejectsProductionToolDeployment() {
        PlatformAgentResourceMapper mapper = mock(PlatformAgentResourceMapper.class);
        when(mapper.capability("TOOL", "202")).thenReturn("""
                {"id":"202","definition_id":"202","name":"current time","descriptor":{"tool_name":"get_current_time"}}
                """);
        when(mapper.currentCapability("TOOL", "202")).thenReturn("{\"id\":\"202\",\"definition_id\":\"202\",\"descriptor\":{\"tool_name\":\"get_current_time\"}}");
        when(mapper.deployment("303")).thenReturn("""
                {"id":"303","name":"clock","tool_version_id":"202","tool_definition_id":"202","provider_type":"PLATFORM",
                "provider_ref":"get_current_time","environment":"PRODUCTION","active":true,"revision":1}
                """);
        PlatformAgentResources resources = resources(mapper);

        assertThatThrownBy(() -> resources.validate(List.of(
                new Mount("TOOL", "202", 1, null, null, "303")), true))
                .hasMessageContaining("TEST");
    }

    @Test
    void draftAllowsActiveTestToolDeployment() {
        PlatformAgentResourceMapper mapper = mock(PlatformAgentResourceMapper.class);
        when(mapper.capability("TOOL", "202")).thenReturn("""
                {"id":"202","definition_id":"202","name":"current time","descriptor":{"tool_name":"get_current_time"}}
                """);
        when(mapper.currentCapability("TOOL", "202")).thenReturn("{\"id\":\"202\",\"definition_id\":\"202\",\"descriptor\":{\"tool_name\":\"get_current_time\"}}");
        when(mapper.deployment("303")).thenReturn("""
                {"id":"303","name":"clock","tool_version_id":"202","tool_definition_id":"202","provider_type":"PLATFORM",
                "provider_ref":"get_current_time","environment":"TEST","active":true,"revision":1}
                """);

        resources(mapper).validateDraft(List.of(
                new Mount("TOOL", "202", 1, null, null, "303")));
    }

    @Test
    void executionFreezesToolVersionBoundToDeploymentInsteadOfCurrentPublication() {
        PlatformAgentResourceMapper mapper = mock(PlatformAgentResourceMapper.class);
        when(mapper.currentCapability("TOOL", "200")).thenReturn("""
                {"id":"202","definition_id":"200","descriptor":{"tool_name":"get_current_time","description":"new version"}}
                """);
        when(mapper.capability("TOOL", "201")).thenReturn("""
                {"id":"201","definition_id":"200","descriptor":{"tool_name":"get_current_time","description":"deployed version"}}
                """);
        when(mapper.deployment("303")).thenReturn("""
                {"id":"303","name":"clock","tool_version_id":"201","tool_definition_id":"200","provider_type":"PLATFORM",
                "provider_ref":"get_current_time","environment":"PRODUCTION","active":true,"revision":1}
                """);

        var frozen = resources(mapper).resolveForExecution(List.of(
                new Mount("TOOL", "200", 1, null, null, "303")), false).getFirst();

        assertThat(frozen.capabilityId()).isEqualTo("200");
        assertThat(frozen.capabilityVersionId()).isEqualTo("201");
        assertThat(frozen.descriptor()).containsEntry("description", "deployed version");
        assertThat(frozen.deployment().toolVersionId()).isEqualTo(frozen.capabilityVersionId());
        assertThat(frozen.deployment().revision()).isEqualTo(1);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).currentCapability("TOOL", "200");
    }

    private static PlatformAgentResources resources(PlatformAgentResourceMapper mapper) {
        var catalog = mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class);
        when(catalog.names()).thenReturn(java.util.Set.of("get_current_time"));
        return new PlatformAgentResources(mapper, mock(CapabilityCatalogDatabaseContext.class),
                new ObjectMapper(), catalog);
    }
}

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlatformAgentResourcesTest {

    @Test
    void readsSkillInstructionsFromPublishedSkillPackage() {
        PlatformAgentResourceMapper mapper = mock(PlatformAgentResourceMapper.class);
        when(mapper.currentCapability("SKILL", "101")).thenReturn("""
                {"id":"101","definition_id":"101","name":"reporting","descriptor":{"activation_mode":"ALWAYS","package":{"files":[
                {"path":"SKILL.md","encoding":"UTF8","content":"# Reporting\\nUse facts."}]}}}
                """);
        PlatformAgentResources resources = resources(mapper);

        String instructions = resources.skillInstructions(List.of(
                new Mount("SKILL", "101", 10, 256L, "ALWAYS", null)));

        assertThat(instructions).isEqualTo("# Reporting\nUse facts.");
    }

    @ParameterizedTest
    @CsvSource({"ALWAYS,ON_DEMAND", "ON_DEMAND,ALWAYS", "ALWAYS,", "ON_DEMAND,"})
    void skillModeComesFromPublicationForDraftPublicationAndExecution(String publishedMode, String requestedMode) {
        PlatformAgentResourceMapper mapper = mock(PlatformAgentResourceMapper.class);
        when(mapper.currentCapability("SKILL", "101")).thenReturn("""
                {"id":"201","definition_id":"101","name":"reporting","descriptor":{
                  "activation_mode":"%s","package":{"files":[
                  {"path":"SKILL.md","encoding":"UTF8","content":"# Reporting\\nUse facts."}]}}}
                """.formatted(publishedMode));
        var resources = resources(mapper);
        var requested = new Mount("SKILL", "101", 7, 512L, requestedMode, null);
        var expected = new Mount("SKILL", "101", 7, 512L, publishedMode, null);

        assertThat(resources.validateDraft(List.of(requested))).containsExactly(expected);
        assertThat(resources.validate(List.of(requested), false)).containsExactly(expected);
        var frozen = resources.resolveForExecution(List.of(requested), true).getFirst();

        assertThat(frozen.mount()).isEqualTo(expected);
        assertThat(frozen.capabilityId()).isEqualTo("101");
        assertThat(frozen.capabilityVersionId()).isEqualTo("201");
        assertThat(frozen.descriptor()).containsEntry("activation_mode", publishedMode);
        assertThat(frozen.skillInstructions()).isEqualTo("# Reporting\nUse facts.");
        assertThat(requested.activationMode()).isEqualTo(requestedMode);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(3)).currentCapability("SKILL", "101");
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

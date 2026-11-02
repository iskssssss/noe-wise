package com.skillforge.institution.team;

import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.institution.chat.ChatTeamController;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class TeamMemberProfilesTest {
    private final AgentPublicationService publications = mock(AgentPublicationService.class);
    private final CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
    private final TeamMemberProfiles profiles = new TeamMemberProfiles(publications, catalog);
    private final ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void capturesOnlyActiveExactPublishedCapabilitySummariesAndRole() throws Exception {
        AgentDO agent = agent();
        var mounted = new CapabilityMountSetResult(7L, 11L, 1, List.of(
                mount("1", "101", true), mount("2", "102", false), mount("3", "103", true),
                mount("4", "104", true), mount("5", "105", true)));
        when(catalog.publishedVersions(any(), anyList())).thenReturn(List.of(
                version(101L, 21L, CapabilityType.TOOL, "PUBLISHED"),
                version(102L, 21L, CapabilityType.TOOL, "PUBLISHED"),
                version(103L, 22L, CapabilityType.TOOL, "PUBLISHED"),
                version(104L, 21L, CapabilityType.SKILL, "PUBLISHED"),
                version(105L, 21L, CapabilityType.TOOL, "DRAFT")));
        var result = profiles.member(agent, "frozen", mounted);
        assertEquals("真实角色", result.roleDescription());
        assertEquals("frozen", result.agentVersion());
        assertEquals(List.of(new TeamDefinition.CapabilitySummary("TOOL", "发布名称", "发布说明")), result.capabilities());
        verify(catalog).publishedVersions(CapabilityCatalogScope.tenant(7L), List.of(101L, 103L, 104L, 105L));
        String wire = json.writeValueAsString(result);
        assertFalse(wire.contains("private prompt"));
        assertFalse(wire.contains("credential"));
        assertFalse(wire.contains("descriptor"));
    }

    @Test
    void missingMountSetDoesNotInitializePersistenceAndEmptyRoleIsAllowed() {
        AgentDO agent = agent(); agent.setRoleDescription(null);
        when(publications.requirePublished(7L, 11L)).thenReturn(publication(agent, null));
        when(catalog.publishedVersions(any(), anyList())).thenReturn(List.of());
        var result = profiles.member(agent, "");
        assertEquals("", result.roleDescription());
        assertTrue(result.capabilities().isEmpty());
        verify(publications).requirePublished(7L, 11L);
        verifyNoMoreInteractions(publications);
    }

    @Test
    void catalogFailureIsNotSilentlyConvertedToEmptyCapabilities() {
        when(catalog.publishedVersions(any(), anyList())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> profiles.member(agent(), "", null));
    }

    @Test
    void writeDtosAcceptOnlyAgentReferencesAndIgnoreForgedMemberMetadata() throws Exception {
        String payload = """
                {"name":"团队","description":"","revision":0,"primary_agent_id":"11",
                 "members":[{"agent_id":"11","name":"伪造","agent_version":"伪造",
                   "role_description":"伪造","capabilities":[{"name":"伪造"}],"system_prompt":"伪造"}]}
                """;
        var admin = json.readValue(payload, TeamController.SaveRequest.class);
        var chat = json.readValue(payload, ChatTeamController.SaveRequest.class);
        assertEquals("11", admin.members().getFirst().agentId());
        assertEquals("11", chat.members().getFirst().agentId());
        assertEquals("", admin.definition().members().getFirst().roleDescription());
        assertTrue(admin.definition().members().getFirst().capabilities().isEmpty());
        assertEquals("{\"agent_id\":\"11\"}", json.writeValueAsString(chat.members().getFirst()));
        String wire = json.writeValueAsString(admin.definition());
        assertFalse(wire.contains("responsibility"));
        assertFalse(wire.contains("capacity"));
        assertFalse(wire.contains("token_budget"));
    }

    @Test
    void batchProfilesReadMountsAndVersionsOnceAndPreserveSingleItemProjection() {
        AgentDO first = agent();
        AgentDO second = agent(); second.setId(12L); second.setName("第二成员");
        var set = new CapabilityMountSetResult(7L, 11L, 1, List.of(mount("1", "101", true), mount("2", "102", false)));
        when(publications.currentPublishedAll(7L, List.of(11L, 12L))).thenReturn(Map.of(11L, publication(first, set), 12L, publication(second, null)));
        when(catalog.publishedVersions(any(), eq(List.of(101L)))).thenReturn(List.of(version(101L, 21L, CapabilityType.TOOL, "PUBLISHED")));
        var result = profiles.members(7L, List.of(first, second), "");
        assertEquals(List.of(new TeamDefinition.CapabilitySummary("TOOL", "发布名称", "发布说明")), result.get(11L).capabilities());
        assertTrue(result.get(12L).capabilities().isEmpty());
        verify(publications).currentPublishedAll(7L, List.of(11L, 12L));
        verifyNoMoreInteractions(publications);
        verify(catalog, times(1)).publishedVersions(CapabilityCatalogScope.tenant(7L), List.of(101L));
    }

    @Test
    void teamMutationsUseStableCommandEndpointsAndKeepRevisionInPayload() throws Exception {
        var update = TeamController.class.getMethod("update", TeamController.UpdateCommand.class);
        var publish = TeamController.class.getMethod("publish", TeamController.PublishCommand.class);
        assertEquals("/update", update.getAnnotation(PutMapping.class).value()[0]);
        assertEquals("/publish", publish.getAnnotation(PostMapping.class).value()[0]);

        var command = json.readValue("""
                {"id":"team-1","payload":{"name":"团队","description":"","status":"draft","revision":7,
                 "primary_agent_id":"11","members":[{"agent_id":"11"}]}}
                """, TeamController.UpdateCommand.class);
        assertEquals("team-1", command.id());
        assertEquals(7, command.payload().revision());
        assertEquals("11", command.payload().definition().primaryAgentId());
        assertEquals("{\"id\":\"team-1\"}", json.writeValueAsString(new TeamController.PublishCommand("team-1")));
    }

    private AgentPublicationService.Publication publication(AgentDO agent, CapabilityMountSetResult mounts) {
        return new AgentPublicationService.Publication(agent.getId()+100,1,agent,
                mounts == null ? new CapabilityMountSetResult(agent.getTenantId(),agent.getId(),1,List.of()) : mounts,
                java.time.Instant.EPOCH);
    }
    private AgentDO agent() {
        AgentDO agent = new AgentDO(); agent.setId(11L); agent.setTenantId(7L);
        agent.setName("真实成员"); agent.setRoleDescription("真实角色"); agent.setSystemPrompt("private prompt");
        return agent;
    }
    private CapabilityMount mount(String id, String version, boolean active) {
        return new CapabilityMount(id, 7L, 11L, new CapabilityRef(CapabilityType.TOOL, "21", version),
                new CapabilityMount.ToolMountConfig("deployment", 1), 0, 1, active);
    }
    private CapabilityVersionView version(long id, long definition, CapabilityType type, String status) {
        return new CapabilityVersionView(id, definition, 7L, type, "v1", "发布名称", "发布说明", "TENANT",
                Map.of("credential", "private"), "hash", status, null, null);
    }
}

package com.skillforge.institution.team;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;



class TeamServiceProfilesTest {
    private final TeamDefinitionMapper mapper = mock(TeamDefinitionMapper.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final CapabilityMountPersistenceService mounts = mock(CapabilityMountPersistenceService.class);
    private final CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
    private final PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentPublicationService publications = mock(AgentPublicationService.class);
    private final CapabilityMountResolver resolver = mock(CapabilityMountResolver.class);
    private final TeamService service = new TeamService(mapper, json, agents, publications, resolver, models,
            new TeamMemberProfiles(publications, catalog));

    @Test
    void saveReplacesForgedMetadataWithAgentOwnedRoleAndCapabilities() {
        var agent = agent();
        when(agents.selectById(11L)).thenReturn(agent);
        when(publications.requirePublished(7L, 11L)).thenReturn(publication(agent));
        when(mounts.readIfPresent(7L, 11L)).thenReturn(Optional.empty());
        when(catalog.publishedVersions(any(), anyList())).thenReturn(List.of());
        var result = service.save("7", null, draft());
        var member = result.members().getFirst();
        assertEquals("服务端成员", member.name());
        assertEquals("服务端人设", member.roleDescription());
        assertEquals("", member.agentVersion());
        assertTrue(member.capabilities().isEmpty());
    }

    @Test
    void publishFreezesServerRoleWhilePrivatePromptStaysInInternalSnapshot() throws Exception {
        var agent = agent();
        when(agents.selectById(11L)).thenReturn(agent);
        when(publications.requirePublished(7L, 11L)).thenReturn(publication(agent));
        when(mapper.definition(7L,"team",true)).thenReturn(List.of(writeDraft()));
        var model = mock(PlatformModelConfig.class);
        when(model.id()).thenReturn("model"); when(model.revision()).thenReturn(1L);
        when(models.resolveDefault("7", "LLM")).thenReturn(model);
        when(resolver.freeze(anyString(), anyString(), eq(7L), eq(11L), eq(1L), anyList())).thenAnswer(call ->
                new CapabilityMountSnapshot(call.getArgument(0), call.getArgument(1), 7L, 11L, 1, List.of(), java.time.Instant.EPOCH));
        when(mounts.read(7L, 11L)).thenReturn(new CapabilityMountSetResult(7L, 11L, 1, List.of()));
        when(catalog.publishedVersions(any(), anyList())).thenReturn(List.of());
        var result = service.publish("7", "team");
        var payload = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertVersion( eq(7L), eq("team"), eq(2L), payload.capture());
        var frozen = json.readTree(payload.getValue());
        assertEquals("服务端人设", frozen.path("team").path("members").get(0).path("role_description").asText());
        assertEquals("private prompt", frozen.path("members").path("11").path("systemPrompt").asText());
        agent.setRoleDescription("后续修改");
        assertEquals("服务端人设", result.members().getFirst().roleDescription());
        assertFalse(json.writeValueAsString(result).contains("private prompt"));
        assertFalse(result.members().getFirst().agentVersion().isBlank());
        verify(publications, times(2)).requirePublished(7L,11L); // 发布读取当前发布快照，不改写 Agent 挂载。
    }

    @Test
    void savedPublicationRejectsStaleRevisionBeforeFreezingOrWriting() {
        when(mapper.definition(7L,"team",true)).thenReturn(List.of(writeDraft()));
        assertThrows(com.skillforge.core.shared.exception.ApiException.class, () -> service.publish("7", "team", 2L));
        verify(mapper,never()).insertVersion(anyLong(),anyString(),anyLong(),anyString());
        verifyNoInteractions(publications, agents, models, resolver);
    }

    @Test
    void nextRunUsesLatestPublishedAgentAndKeepsTeamHistoryUnchanged() throws Exception {
        var source=agent();
        when(agents.selectById(11L)).thenReturn(source);
        var oldMember=new TeamService.FrozenMember("11","旧名称","旧提示","旧人设",
                com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(),"model",1,
                new CapabilityMountSetResult(7L,11L,1,List.of()),101L,1,source.getDelegationConfig());
        var oldTeam=new TeamService.FrozenTeam(draft(),java.util.Map.of("11",oldMember));
        when(mapper.published(7L,"team")).thenReturn(List.of(json.writeValueAsString(oldTeam)));
        when(publications.requirePublished(7L,11L)).thenReturn(publication(source));
        var model=mock(PlatformModelConfig.class);
        when(model.id()).thenReturn("model");when(model.revision()).thenReturn(2L);
        when(models.resolveDefault("7","LLM")).thenReturn(model);
        when(resolver.freeze(anyString(),anyString(),eq(7L),eq(11L),eq(1L),anyList())).thenAnswer(call ->
                new CapabilityMountSnapshot(call.getArgument(0),call.getArgument(1),7L,11L,1,List.of(),java.time.Instant.EPOCH));
        var next=service.freezePublished("7","team");
        assertEquals("private prompt",next.members().get("11").systemPrompt());
        assertEquals(2,next.members().get("11").modelRevision());
        assertEquals("旧提示",service.published("7","team").members().get("11").systemPrompt());
        verify(mapper,never()).insertVersion(anyLong(),anyString(),anyLong(),anyString());
    }

    @Test
    void disablingTeamDoesNotRequireUnavailableMemberToResolveAgain() {
        when(mapper.definition(7L,"team",true)).thenReturn(List.of(writeDraft()));
        var result = service.disable("7", "team", 1L);
        assertEquals("disabled", result.status());
        assertEquals(2L, result.revision());
        verifyNoInteractions(agents, mounts, catalog, models, publications, resolver);
    }

    /** 缺失线程上下文时使用可信调用租户，异常后也不能泄漏到后续任务。 */
    @Test
    void trustedTenantScopeRestoresAfterMapperFailure() {
        com.skillforge.common.context.TenantContext.clear();
        when(mapper.definitions(7L)).thenAnswer(invocation -> {
            assertEquals("7", com.skillforge.common.context.TenantContext.getTenantId());
            throw new IllegalStateException("storage_unavailable");
        });
        assertThrows(IllegalStateException.class, () -> service.list("7"));
        assertNull(com.skillforge.common.context.TenantContext.getTenantId());
    }

    /** 已有认证租户不得被方法参数改写，且拒绝发生在数据库访问之前。 */
    @Test
    void conflictingTenantIsRejectedBeforeDatabaseAccess() {
        com.skillforge.common.context.TenantContext.setTenantId("8");
        try {
            assertThrows(SecurityException.class, () -> service.list("7"));
            assertEquals("8", com.skillforge.common.context.TenantContext.getTenantId());
            verifyNoInteractions(mapper);
        } finally {
            com.skillforge.common.context.TenantContext.clear();
        }
    }

    private AgentPublicationService.Publication publication(AgentDO agent) {
        return new AgentPublicationService.Publication(101L, 1, agent,
                new CapabilityMountSetResult(7L, 11L, 1, List.of()), java.time.Instant.EPOCH);
    }
    private String writeDraft() { try { return json.writeValueAsString(draft()); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private TeamDefinition draft() {
        return new TeamDefinition("team", "团队", "", "draft", 1,
                List.of(new TeamDefinition.Member("11", "forged", "伪造", "伪造",
                        List.of(new TeamDefinition.CapabilitySummary("TOOL", "伪造", "伪造")))), "11");
    }
    private AgentDO agent() {
        var agent = new AgentDO(); agent.setId(11L); agent.setTenantId(7L); agent.setStatus("published");
        agent.setName("服务端成员"); agent.setRoleDescription("服务端人设"); agent.setSystemPrompt("private prompt");
        return agent;
    }
}

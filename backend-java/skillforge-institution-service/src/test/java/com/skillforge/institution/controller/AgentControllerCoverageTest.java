package com.skillforge.institution.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.dto.request.CreateAgentRequest;
import com.skillforge.core.agent.dto.request.UpdateAgentRequest;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMount.SkillMountConfig;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillActivationMode;
import com.skillforge.core.agent.constant.AgentConstants;
import com.skillforge.core.agent.enums.AgentStatus;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.agent.management.AgentManagementApplicationService;
import com.skillforge.core.agent.management.AgentConfigurationPolicy;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.institution.service.InstitutionResourceAccess;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Agent 控制面覆盖：本体 CRUD 与 CapabilityMount 装配投影。 */
class AgentControllerCoverageTest {

    @BeforeAll
    static void initTableMetadata() {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, AgentDO.class);
    }

    private AgentTemplateService templates;
    private AgentService agents;
    private CapabilityMountPersistenceService capabilityMounts;
    private PlatformModelCatalogService modelCatalog;
    private com.skillforge.core.agent.AgentPublicationService publications;
    private InstitutionResourceAccess resourceAccess;
    private FoundationAccess foundation;
    private FoundationOrganizationService organization;
    private ResourceQuota quota;
    private FeatureGate features;
    private AgentManagementApplicationService application;
    private AgentDO agent;
    private com.skillforge.institution.service.AgentManagementService controller;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("7");
        templates = mock(AgentTemplateService.class);
        agents = mock(AgentService.class);
        capabilityMounts = mock(CapabilityMountPersistenceService.class);
        modelCatalog = mock(PlatformModelCatalogService.class);
        publications = mock(com.skillforge.core.agent.AgentPublicationService.class);
        resourceAccess = mock(InstitutionResourceAccess.class);
        foundation = mock(FoundationAccess.class);
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "70"));
        organization = mock(FoundationOrganizationService.class);
        quota = mock(ResourceQuota.class);
        features = mock(FeatureGate.class);
        when(foundation.resourceFilter("agent", "read")).thenReturn(new FoundationAccess.ResourceFilter(true, List.of()));
        agent = agent(9L);
        when(agents.getOne(any(Wrapper.class))).thenReturn(agent);
        when(agents.lockVersion(7L, 9L)).thenReturn(agent);
        org.mockito.Mockito.doCallRealMethod().when(agents).requireEditable(any(AgentDO.class));
        when(agents.list(any(Wrapper.class))).thenReturn(List.of(agent));
        when(templates.getById(any())).thenReturn(template(55L));
        when(templates.currentPublished(any())).thenReturn(template(55L));
        when(capabilityMounts.read(7L, 9L)).thenReturn(mountSet());
        when(modelCatalog.requireEnabled(anyString(), eq("LLM"))).thenReturn(llmModel("88"));
        when(modelCatalog.resolveDefault(anyString(), eq("LLM"))).thenReturn(llmModel("88"));
        var settings=mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class);
        when(settings.read("calls")).thenReturn(new com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot(
                1,Map.of("chat_timeout_seconds",60)));
        when(publications.publish(any())).thenAnswer(invocation -> {
            AgentDO current = invocation.getArgument(0);
            current.setStatus("published"); current.setPublishedAt(java.time.Instant.EPOCH);
            current.setRevision(current.getRevision() + 1); current.setPublishedRevisionId(100L);
            current.setHasDraftChanges(false);
            return new com.skillforge.core.agent.AgentPublicationService.Publication(100L,
                    current.getRevision(), current, mountSet(), java.time.Instant.EPOCH);
        });
        application = new AgentManagementApplicationService(templates, agents, capabilityMounts,
                mock(CapabilityCatalogDatabaseContext.class), mock(com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry.class),
                mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class), foundation, new AgentConfigurationPolicy(agents, modelCatalog, settings), publications,
                organization, quota, features);
        controller = new com.skillforge.institution.service.AgentManagementService(application, resourceAccess);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 宿主传递可信身份后由领域拒绝read/manage，任何读取、编辑和发布均不能先行。 */
    @Test
    void currentResourceReadAndManageDenialStopsBeforeDomainSideEffects() {
        var denied = new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "resource_denied", "资源未授权", false);
        org.mockito.Mockito.doThrow(denied).when(foundation).requireResource("agent", "9", "read");
        org.mockito.Mockito.doThrow(denied).when(foundation).requireResource("agent", "9", "manage");
        var secured = new com.skillforge.institution.service.AgentManagementService(application,
                new InstitutionResourceAccess(foundation, org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class)));

        assertThatThrownBy(() -> secured.getAgent("9")).isSameAs(denied);
        assertThatThrownBy(() -> secured.updateAgent("9", new UpdateAgentRequest("forbidden", null, null, null, null, null, null, null, null))).isSameAs(denied);
        assertThatThrownBy(() -> secured.publish("9")).isSameAs(denied);
        org.mockito.Mockito.verifyNoInteractions(agents, templates, capabilityMounts, publications, organization, quota, features);
        assertThat(agent.getName()).isEqualTo("Agent 9");
    }

    @Test
    void templatesAgentsCreateAndDetailProjectMounts() {
        AgentTemplateDO template = template(55L);
        when(templates.currentPublishedLibrary()).thenReturn(List.of(template));
        when(templates.listByIds(List.of(55L))).thenReturn(List.of(template));
        assertThat(controller.listTemplates().total()).isEqualTo(1);
        assertThat(controller.listAgents().total()).isEqualTo(1);

        when(agents.count(any(Wrapper.class))).thenReturn(0L);
        var created = controller.createAgent(new CreateAgentRequest("support", "Support",
                null, null, Map.of("locale", "zh")));
        assertThat(created.agentKey()).isEqualTo("support");
        verify(agents).save(any(AgentDO.class));

        var detail = controller.getAgent("9");
        assertThat(detail.mountSetGeneration()).isEqualTo(4L);
        assertThat(detail.mounts()).hasSize(1);
        assertThat(detail.mounts().getFirst().capabilityType()).isEqualTo(CapabilityType.SKILL);
    }

    /** 已签名集成调用不伪造成员；删除仍在同机构清理正式资源共享登记。 */
    @Test
    void authenticatedExternalCreateAndDeleteDoNotRequireAnInteractiveMember() {
        when(resourceAccess.external()).thenReturn(true);
        when(agents.count(any(Wrapper.class))).thenReturn(0L);
        controller.createAgent(new CreateAgentRequest("external", "External", null, null, Map.of()));
        verify(agents).save(any(AgentDO.class));
        verify(organization, never()).registerResource(anyString(), anyString());
        agent.setStatus(AgentConstants.STATUS_DRAFT);
        agent.setPublishedAt(null);
        controller.deleteAgent("9");
        verify(organization).forgetResourceForTenant("7", "agent", "9");
        verify(organization, never()).forgetResource(anyString(), anyString());
    }

    @Test
    void memoryAutomaticExtractionRequiresBooleanAndSurvivesDraftSaveAndPublish() {
        var config=Map.<String,Object>of("memory",Map.of("automatic_extraction",true),"locale","zh");
        controller.createAgent(new CreateAgentRequest("memory","Memory","系统提示词",null,config));
        assertThat(agentSaved().getConfig()).isEqualTo(config);
        controller.updateAgent("9",new UpdateAgentRequest(null,null,null,null,null,null,config,null,null));
        assertThat(agent.getConfig()).isEqualTo(config);
        controller.publish("9");
        assertThat(agent.getConfig()).isEqualTo(config);
    }

    @Test
    void memoryAutomaticExtractionRejectsStringNumberAndNonObjectConfiguration() {
        for(Object value:List.of("true",1,List.of(true))) {
            assertThatThrownBy(()->controller.createAgent(new CreateAgentRequest("invalid-memory","Memory",null,null,
                    Map.of("memory",Map.of("automatic_extraction",value)))))
                    .isInstanceOf(ApiException.class).hasMessageContaining("必须是布尔值");
        }
        assertThatThrownBy(()->controller.updateAgent("9",new UpdateAgentRequest(null,null,null,null,null,null,
                Map.of("memory",true),null,null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("必须是对象");
        agent.setConfig(Map.of("memory",Map.of("automatic_extraction","true")));
        assertThatThrownBy(()->controller.publish("9")).isInstanceOf(ApiException.class).hasMessageContaining("必须是布尔值");
    }

    @Test
    void listAgentsIncludesDirectlyCreatedAgentWithoutTemplate() {
        agent.setTemplateId(null);

        var result = controller.listAgents();

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().getFirst().templateId()).isNull();
        assertThat(result.items().getFirst().templateName()).isNull();
        verify(templates, never()).listByIds(any());
    }

    @Test
    void updatePublishAndDeleteHonorConfiguration() {
        controller.updateAgent("9", new UpdateAgentRequest("Renamed", null,
                "系统提示词", "", com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(),
                null, Map.of("x", 1), null, null));
        assertThat(agent.getName()).isEqualTo("Renamed");
        verify(publications, never()).publish(any());
        verify(agents).update(any(Wrapper.class));
        assertThat(controller.publish("9").status()).isEqualTo(AgentConstants.ACTION_PUBLISHED);

        agent.setSystemPrompt(null);
        assertThatThrownBy(() -> controller.publish("9")).isInstanceOf(ApiException.class);
        agent.setSystemPrompt("恢复系统提示词");
        controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null, null, null));
        assertThatThrownBy(() -> controller.deleteAgent("9")).isInstanceOf(ApiException.class);
        agent.setPublishedAt(null); agent.setPublishedRevisionId(null); agent.setStatus("draft");
        assertThat(controller.deleteAgent("9").status()).isEqualTo(AgentConstants.STATUS_DELETED);
        verify(agents).removeById(9L);
    }

    @Test
    void publishedIdentityKeepsCurrentPublicationWhileDraftConfigurationCanChange() {
        agent.setStatus("published"); agent.setPublishedAt(java.time.Instant.EPOCH);
        agent.setPublishedRevisionId(100L);
        controller.updateAgent("9", new UpdateAgentRequest(
                "changed", null, null, null, null, null, null, null, null));
        assertThat(agent.getName()).isEqualTo("changed");
        assertThat(agent.getPublishedRevisionId()).isEqualTo(100L);
        assertThat(agent.getHasDraftChanges()).isTrue();
        assertThat(controller.revise("9").id()).isEqualTo(9L);
        assertThatThrownBy(() -> controller.updateAgent("9", new UpdateAgentRequest(
                null, AgentStatus.DRAFT, null, null, null, null, null, null, null))).isInstanceOf(ApiException.class);
        controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null, AgentDelegationConfig.disabled(), null));
        verify(modelCatalog, never()).resolveDefault(anyString(), anyString());
        controller.updateAgent("9", new UpdateAgentRequest(
                null, AgentStatus.DISABLED, null, null, null, null, null, AgentDelegationConfig.disabled(), null));
        assertThat(agent.getStatus()).isEqualTo("disabled");
        assertThat(agent.getDelegationConfig().enabled()).isFalse();
        controller.updateAgent("9", new UpdateAgentRequest(
                null, null, "replacement", null, null, null, null, null, null));
        assertThat(agent.getSystemPrompt()).isEqualTo("replacement");
        verify(publications, never()).publish(any());
    }

    @Test
    void invalidAgentIdsFailClosed() {
        assertThatThrownBy(() -> controller.getAgent("bad")).isInstanceOf(ApiException.class);
        when(agents.getOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> controller.getAgent("9")).isInstanceOf(ApiException.class);
    }

    @Test
    void directCreationUsesOnlyAnEnabledPlatformModelAndStaysDraft() {
        when(agents.count(any(Wrapper.class))).thenReturn(0L);
        controller.createAgent(new CreateAgentRequest("secured", "Secured", "系统提示词", "88", Map.of()));
        assertThat(agentSaved().getModelConfigId()).isEqualTo(88L);
        assertThat(agentSaved().getStatus()).isEqualTo("draft");
        verify(modelCatalog).requireEnabled("88", "LLM");
    }

    @Test
    void updateNullLeavesModelUnchangedAndEmptyClearsToTenantDefault() {
        agent.setModelConfigId(88L);

        controller.updateAgent("9", new UpdateAgentRequest(
                "renamed", null, null, null, null, null, null, null, null));
        assertThat(agent.getModelConfigId()).isEqualTo(88L);

        controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, "", null, null, null));
        assertThat(agent.getModelConfigId()).isNull();
        verify(modelCatalog, never()).requireEnabled("", "LLM");
    }

    @Test
    void delegationConfigRequiresOtherPublishedTenantAgentsAndPreservesDisabledTargets() {
        AgentDO target = agent(10L);
        target.setStatus(AgentConstants.STATUS_PUBLISHED);
        when(agents.list(any(Wrapper.class))).thenReturn(List.of(target));

        AgentDelegationConfig disabledWithTarget = new AgentDelegationConfig(false, List.of("10"));
        controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null, disabledWithTarget, null));

        assertThat(agent.getDelegationConfig()).isEqualTo(disabledWithTarget);
        assertThat(controller.getAgent("9").delegationConfig()).isEqualTo(disabledWithTarget);
        verify(agents).update(any(Wrapper.class));
        controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null, null, null));
        assertThat(agent.getDelegationConfig()).isEqualTo(disabledWithTarget);

        assertThatThrownBy(() -> controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null,
                new AgentDelegationConfig(true, List.of("9")), null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null,
                new AgentDelegationConfig(true, List.of("10", "10")), null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null,
                new AgentDelegationConfig(true, List.of("not-an-id")), null)))
                .isInstanceOf(ApiException.class);

        when(agents.list(any(Wrapper.class))).thenReturn(List.of());
        assertThatThrownBy(() -> controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null,
                new AgentDelegationConfig(true, List.of("10")), null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void disablingDelegationRetainsRevokedTargetsButRejectsNewUnknownTargets() {
        agent.setStatus(AgentConstants.STATUS_PUBLISHED);
        agent.setPublishedAt(java.time.Instant.EPOCH);
        agent.setDelegationConfig(new AgentDelegationConfig(true, List.of("10")));
        when(agents.list(any(Wrapper.class))).thenReturn(List.of());

        var disabled = new AgentDelegationConfig(false, List.of("10"));
        controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null, disabled, null));
        assertThat(agent.getDelegationConfig()).isEqualTo(disabled);
        verify(agents, never()).list(any(Wrapper.class));
        verify(modelCatalog, never()).resolveDefault(anyString(), anyString());

        assertThatThrownBy(() -> controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null,
                new AgentDelegationConfig(false, List.of("10", "11")), null)))
                .isInstanceOf(ApiException.class);
        assertThat(agent.getDelegationConfig()).isEqualTo(disabled);
        assertThatThrownBy(() -> controller.updateAgent("9", new UpdateAgentRequest(
                null, null, null, null, null, null, null,
                new AgentDelegationConfig(true, List.of("10")), null)))
                .isInstanceOf(ApiException.class);
        assertThat(agent.getDelegationConfig()).isEqualTo(disabled);
    }

    private AgentDO agentSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(AgentDO.class);
        verify(agents).save(captor.capture());
        return captor.getValue();
    }

    private AgentDO agent(Long id) {
        AgentDO value = new AgentDO();
        value.setId(id);
        value.setTenantId(7L);
        value.setAgentKey("agent-" + id);
        value.setName("Agent " + id);
        value.setTemplateId(55L);
        value.setTemplateVersion("1.0.0");
        value.setSystemPrompt("系统提示词");
        value.setStatus(AgentConstants.STATUS_DRAFT);
        value.setConfig(Map.of());
        return value;
    }

    private AgentTemplateDO template(Long id) {
        AgentTemplateDO value = new AgentTemplateDO();
        value.setId(id);
        value.setTemplateKey("support");
        value.setName("Support template");
        value.setVersion("1.0.0");
        value.setStatus("published");
        value.setDefinition(new com.skillforge.core.agent.model.AgentTemplateDefinition("模板系统提示词", "助手", "88", null, null, null, null));
        return value;
    }

    private CapabilityMountSetResult mountSet() {
        CapabilityMount mount = new CapabilityMount("8001", 7L, 9L,
                new CapabilityRef(CapabilityType.SKILL, "1001", "1002"),
                new SkillMountConfig(SkillActivationMode.ALWAYS, 2_000L), 10, 2L, true);
        return new CapabilityMountSetResult(7L, 9L, 4L, List.of(mount));
    }

    private PlatformModelConfig llmModel(String id) {
        return new PlatformModelConfig(id, 1L, "LLM", "LLM", true,
                "custom", "custom-openai-compatible", "custom-v1",
                "openai-chat-completions", "bearer-api-key", "v1",
                "https://model.example/v1", "model", "encrypted",
                4096, 8192, 1024, 0.2, "DISABLED", 30, null, null,
                null, null, null, "CNY");
    }
}

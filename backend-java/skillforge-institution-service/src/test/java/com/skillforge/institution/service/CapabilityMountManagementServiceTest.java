package com.skillforge.institution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.capabilitymount.CapabilityMountStatus;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.agent.dto.request.KnowledgeMountConfigRequest;
import com.skillforge.core.agent.dto.request.SkillMountConfigRequest;
import com.skillforge.core.agent.dto.request.ToolMountConfigRequest;
import com.skillforge.core.agent.dto.request.WorkflowMountConfigRequest;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityDefinitionView;
import com.skillforge.core.capability.CapabilityDeploymentView;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.capability.SkillActivationMode;
import com.skillforge.core.knowledge.KnowledgeReferenceService;
import com.skillforge.core.workflow.WorkflowReferenceService;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.model.KnowledgeBaseDO;
import com.skillforge.core.knowledge.model.KnowledgeBaseRevisionDO;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.mapper.WorkflowInstanceMapper;
import com.skillforge.core.workflow.model.WorkflowDefinitionVersionDO;
import com.skillforge.core.workflow.model.WorkflowInstanceDO;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** CapabilityMount 管理边界的类型、引用、租户与 CAS 契约测试。 */
class CapabilityMountManagementServiceTest {

    private CapabilityMountPersistenceService persistence;
    private CapabilityCatalogService catalog;
    private CapabilityCatalogDatabaseContext databaseContext;
    private WorkflowInstanceMapper workflowInstances;
    private WorkflowDefinitionVersionMapper workflowVersions;
    private KnowledgeBaseMapper knowledgeBases;
    private KnowledgeBaseRevisionMapper knowledgeRevisions;
    private InstitutionResourceAccess access;
    private CapabilityMountManagementService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("7");
        persistence = mock(CapabilityMountPersistenceService.class);
        catalog = mock(CapabilityCatalogService.class);
        databaseContext = mock(CapabilityCatalogDatabaseContext.class);
        workflowInstances = mock(WorkflowInstanceMapper.class);
        workflowVersions = mock(WorkflowDefinitionVersionMapper.class);
        knowledgeBases = mock(KnowledgeBaseMapper.class);
        knowledgeRevisions = mock(KnowledgeBaseRevisionMapper.class);
        access = mock(InstitutionResourceAccess.class);
        service = new CapabilityMountManagementService(
                new WorkflowReferenceService(workflowInstances, workflowVersions, mock(com.skillforge.core.workflow.mapper.WorkflowContainerMapper.class)),
                new KnowledgeReferenceService(knowledgeBases, knowledgeRevisions), persistence, catalog, databaseContext, mock(com.skillforge.core.agent.AgentService.class), access);
        when(persistence.readIfPresent(7L, 9L)).thenReturn(Optional.empty());
        when(persistence.create(any())).thenReturn(emptyMountSet(1L));
        when(persistence.update(any())).thenReturn(emptyMountSet(2L));
        when(persistence.delete(any())).thenReturn(emptyMountSet(2L));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void acceptsAllFourTypedMountsAndPassesNoMapConfigToPersistence() {
        configureCatalog(CapabilityType.SKILL, 100L, 101L);
        service.create(7L, 9L, skillRequest(100L, 101L));
        ArgumentCaptor<com.skillforge.core.agent.capabilitymount.CreateCapabilityMountCommand> skill =
                ArgumentCaptor.forClass(com.skillforge.core.agent.capabilitymount.CreateCapabilityMountCommand.class);
        verify(persistence).create(skill.capture());
        assertThat(skill.getValue().config()).isInstanceOf(CapabilityMount.SkillMountConfig.class);

        configureCatalog(CapabilityType.TOOL, 200L, 201L);
        when(catalog.getDeployment(any(CapabilityCatalogScope.class), eq("deploy-1")))
                .thenReturn(deployment(201L, 2L, "ACTIVE"));
        service.create(7L, 9L, toolRequest(200L, 201L));

        configureCatalog(CapabilityType.WORKFLOW, 300L, 301L);
        WorkflowInstanceDO instance = new WorkflowInstanceDO();
        instance.setId(401L);
        instance.setTenantId(7L);
        instance.setStatus("published");
        instance.setDefinitionVersionId(402L);
        instance.setInstallationRevision(1L);
        when(workflowInstances.selectOne(any())).thenReturn(instance);
        WorkflowDefinitionVersionDO workflowVersion = new WorkflowDefinitionVersionDO();
        workflowVersion.setId(402L);
        workflowVersion.setTenantId(7L);
        workflowVersion.setContainerId(403L);
        workflowVersion.setCapabilityVersionId(301L);
        when(workflowVersions.selectOne(any())).thenReturn(workflowVersion);
        service.create(7L, 9L, workflowRequest(300L, 301L, "401", 402L));

        configureCatalog(CapabilityType.KNOWLEDGE_SOURCE, 500L, 501L);
        KnowledgeBaseRevisionDO revision = new KnowledgeBaseRevisionDO();
        revision.setId(502L);
        revision.setTenantId(7L);
        revision.setKbId(503L);
        revision.setCapabilityVersionId(501L);
        revision.setStatus("published");
        when(knowledgeRevisions.selectOne(any())).thenReturn(revision);
        when(knowledgeRevisions.selectList(any())).thenReturn(List.of(revision));
        KnowledgeBaseDO base = new KnowledgeBaseDO();
        base.setId(503L);
        base.setTenantId(7L);
        base.setActiveRevisionId(502L);
        when(knowledgeBases.selectOne(any())).thenReturn(base);
        when(access.allowed("knowledge", "503", "execute")).thenReturn(true);
        service.create(7L, 9L, knowledgeRequest(500L, 501L, "502"));

        verify(persistence, times(4)).create(any());
        verify(databaseContext, org.mockito.Mockito.atLeastOnce()).bindTenant(7L);
    }

    @Test
    void rejectsWrongTypeMissingOrMultipleConfigAndDoesNotMutate() {
        configureCatalog(CapabilityType.SKILL, 100L, 101L);
        CapabilityMountRequest noConfig = new CapabilityMountRequest(
                CapabilityType.SKILL, 100L, 101L, null, null, null, null, 0);
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.create(7L, 9L, noConfig));

        CapabilityMountRequest multiple = new CapabilityMountRequest(
                CapabilityType.SKILL, 100L, 101L,
                new SkillMountConfigRequest(SkillActivationMode.ALWAYS, 1000L),
                new ToolMountConfigRequest("deploy-1", 1L), null, null, 0);
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.create(7L, 9L, multiple));

        CapabilityMountRequest wrong = new CapabilityMountRequest(
                CapabilityType.TOOL, 100L, 101L,
                new SkillMountConfigRequest(SkillActivationMode.ALWAYS, 1000L),
                null, null, null, 0);
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.create(7L, 9L, wrong));
        verify(persistence, never()).create(any());
    }

    @Test
    void freezesCurrentPublishedSkillActivationRatherThanStaleRequestedMode() {
        configureCatalog(CapabilityType.SKILL, 100L, 101L, "ON_DEMAND");
        service.create(7L, 9L, skillRequest(100L, 101L));
        ArgumentCaptor<com.skillforge.core.agent.capabilitymount.CreateCapabilityMountCommand> command =
                ArgumentCaptor.forClass(com.skillforge.core.agent.capabilitymount.CreateCapabilityMountCommand.class);
        verify(persistence).create(command.capture());
        assertThat(command.getValue().config()).isEqualTo(
                new CapabilityMount.SkillMountConfig(SkillActivationMode.ON_DEMAND, 1000L));
    }

    @Test
    void derivesSkillActivationWhenRequestOmitsIt() {
        configureCatalog(CapabilityType.SKILL, 100L, 101L, "ALWAYS");
        var request = new CapabilityMountRequest(CapabilityType.SKILL, 100L, 101L,
                new SkillMountConfigRequest(null, 1000L), null, null, null, 0);
        service.create(7L, 9L, request);
        var command = ArgumentCaptor.forClass(com.skillforge.core.agent.capabilitymount.CreateCapabilityMountCommand.class);
        verify(persistence).create(command.capture());
        assertThat(command.getValue().config()).isEqualTo(
                new CapabilityMount.SkillMountConfig(SkillActivationMode.ALWAYS, 1000L));
    }

    @Test
    void rejectsInvalidPublishedSkillActivationWithoutCreatingMount() {
        configureCatalog(CapabilityType.SKILL, 100L, 101L, "INVALID_MODE");
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.create(7L, 9L, skillRequest(100L, 101L)));
        verify(persistence, never()).create(any());
    }

    @Test
    void validatesToolWorkflowKnowledgeStateAndVersionExactly() {
        configureCatalog(CapabilityType.TOOL, 200L, 201L);
        when(catalog.getDeployment(any(CapabilityCatalogScope.class), eq("deploy-1")))
                .thenReturn(deployment(201L, 2L, "DISABLED"));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.create(7L, 9L, toolRequest(200L, 201L)));

        configureCatalog(CapabilityType.WORKFLOW, 300L, 301L);
        WorkflowInstanceDO instance = new WorkflowInstanceDO();
        instance.setId(401L);
        instance.setTenantId(7L);
        instance.setStatus("published");
        instance.setDefinitionVersionId(402L);
        instance.setInstallationRevision(1L);
        when(workflowInstances.selectOne(any())).thenReturn(instance);
        WorkflowDefinitionVersionDO definitionVersion = new WorkflowDefinitionVersionDO();
        definitionVersion.setId(402L);
        definitionVersion.setTenantId(7L);
        definitionVersion.setContainerId(403L);
        definitionVersion.setCapabilityVersionId(999L);
        when(workflowVersions.selectOne(any())).thenReturn(definitionVersion);
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.create(7L, 9L, workflowRequest(300L, 301L, "401", 999L)));

        configureCatalog(CapabilityType.KNOWLEDGE_SOURCE, 500L, 501L);
        KnowledgeBaseRevisionDO revision = new KnowledgeBaseRevisionDO();
        revision.setId(502L);
        revision.setTenantId(7L);
        revision.setKbId(503L);
        revision.setCapabilityVersionId(999L);
        revision.setStatus("published");
        when(knowledgeRevisions.selectOne(any())).thenReturn(revision);
        // 当前发布版本没有匹配的 active Knowledge 修订，不得生成挂载。
        when(knowledgeRevisions.selectList(any())).thenReturn(List.of());
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.create(7L, 9L, knowledgeRequest(500L, 501L, "502")));
        verify(persistence, never()).create(any());
    }

    @Test
    void crossTenantCatalogAndDeploymentAreHiddenAsNotFound() {
        CapabilityDefinitionView foreignDefinition = new CapabilityDefinitionView(
                200L, 8L, CapabilityType.TOOL, "foreign-tool", "Foreign", "", "TENANT",
                "PUBLISHED", null, 1L, null, null);
        CapabilityVersionView foreignVersion = new CapabilityVersionView(
                201L, 200L, 8L, CapabilityType.TOOL, "v1", "Foreign", "", "TENANT",
                Map.of(), "hash-201", "PUBLISHED", null, null);
        when(catalog.getDefinition(any(CapabilityCatalogScope.class), eq(200L)))
                .thenReturn(foreignDefinition);
        when(catalog.getVersion(any(CapabilityCatalogScope.class), eq(201L)))
                .thenReturn(foreignVersion);
        when(catalog.getCurrentPublishedVersion(any(CapabilityCatalogScope.class), eq(200L)))
                .thenReturn(foreignVersion);
        assertStatus(HttpStatus.NOT_FOUND,
                () -> service.create(7L, 9L, toolRequest(200L, 201L)));

        configureCatalog(CapabilityType.TOOL, 200L, 201L);
        CapabilityDeploymentView foreignDeployment = new CapabilityDeploymentView(
                600L, 8L, "deploy-1", 201L,
                ToolDeployment.ProviderType.TEST_MOCK, "fixture",
                ToolDeployment.Environment.TEST, "ACTIVE", 2L, null, null);
        when(catalog.getDeployment(any(CapabilityCatalogScope.class), eq("deploy-1")))
                .thenReturn(foreignDeployment);
        assertStatus(HttpStatus.NOT_FOUND,
                () -> service.create(7L, 9L, toolRequest(200L, 201L)));
        verify(persistence, never()).create(any());
    }

    @Test
    void followActiveRequiresCurrentActivePublishedRevision() {
        configureCatalog(CapabilityType.KNOWLEDGE_SOURCE, 500L, 501L);
        KnowledgeBaseRevisionDO revision = new KnowledgeBaseRevisionDO();
        revision.setId(502L);
        revision.setKbId(503L);
        revision.setTenantId(7L);
        revision.setCapabilityVersionId(501L);
        revision.setStatus("published");
        when(knowledgeRevisions.selectList(any())).thenReturn(List.of(revision));
        KnowledgeBaseDO base = new KnowledgeBaseDO();
        base.setId(503L);
        base.setTenantId(7L);
        base.setActiveRevisionId(502L);
        when(knowledgeBases.selectOne(any())).thenReturn(base);
        when(access.allowed("knowledge", "503", "execute")).thenReturn(true);

        CapabilityMountRequest request = new CapabilityMountRequest(
                CapabilityType.KNOWLEDGE_SOURCE, 500L, 501L,
                null, null, null,
                new KnowledgeMountConfigRequest(
                        CapabilityMount.KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE, null),
                0);
        service.create(7L, 9L, request);
        verify(persistence).create(any());

        base.setActiveRevisionId(504L);
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> service.create(7L, 9L, request));
    }

    @Test
    void duplicateDefinitionAndStaleUpdateAreConflictsWithoutMutation() {
        configureCatalog(CapabilityType.SKILL, 100L, 101L);
        CapabilityMount existing = new CapabilityMount("8001", 7L, 9L,
                new CapabilityRef(CapabilityType.SKILL, "100", "101"),
                new CapabilityMount.SkillMountConfig(SkillActivationMode.ALWAYS, 1000L),
                0, 1L, true);
        when(persistence.readIfPresent(7L, 9L)).thenReturn(
                Optional.of(new CapabilityMountSetResult(7L, 9L, 1L, List.of(existing))));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.create(7L, 9L, skillRequest(100L, 101L)));
        verify(persistence, never()).create(any());

        CapabilityMountSetResult current = new CapabilityMountSetResult(
                7L, 9L, 3L, List.of(existing));
        when(persistence.readIfPresent(7L, 9L)).thenReturn(Optional.of(current));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.update(7L, 9L, "8001", 2L, skillRequest(100L, 101L)));
        verify(persistence, never()).update(any());
    }

    private void configureCatalog(CapabilityType type, long definitionId, long versionId) {
        configureCatalog(type, definitionId, versionId, "ALWAYS");
    }

    private void configureCatalog(
            CapabilityType type, long definitionId, long versionId, String skillActivationMode) {
        CapabilityDefinitionView definition = new CapabilityDefinitionView(
                definitionId, 7L, type, "key-" + definitionId, "Definition", "", "TENANT",
                "PUBLISHED", null, 1L, null, null);
        Map<String, Object> descriptor = type == CapabilityType.SKILL
                ? Map.of("activation_mode", skillActivationMode) : Map.of();
        CapabilityVersionView version = new CapabilityVersionView(
                versionId, definitionId, 7L, type, "v1", "Version", "", "TENANT",
                descriptor, "hash-" + versionId, "PUBLISHED", null, null);
        when(catalog.getDefinition(any(CapabilityCatalogScope.class), eq(definitionId)))
                .thenReturn(definition);
        when(catalog.getVersion(any(CapabilityCatalogScope.class), eq(versionId)))
                .thenReturn(version);
        when(catalog.getCurrentPublishedVersion(any(CapabilityCatalogScope.class), eq(definitionId)))
                .thenReturn(version);
    }

    private CapabilityMountRequest skillRequest(long definitionId, long versionId) {
        return new CapabilityMountRequest(CapabilityType.SKILL, definitionId, versionId,
                new SkillMountConfigRequest(SkillActivationMode.ALWAYS, 1000L),
                null, null, null, 0, CapabilityMountStatus.ACTIVE);
    }

    private CapabilityMountRequest toolRequest(long definitionId, long versionId) {
        return new CapabilityMountRequest(CapabilityType.TOOL, definitionId, versionId,
                null, new ToolMountConfigRequest("deploy-1", 2L), null, null, 0,
                CapabilityMountStatus.ACTIVE);
    }

    private CapabilityMountRequest workflowRequest(
            long definitionId, long versionId, String instanceId, long revision) {
        return new CapabilityMountRequest(CapabilityType.WORKFLOW, definitionId, versionId,
                null, null, new WorkflowMountConfigRequest(instanceId, revision), null, 0,
                CapabilityMountStatus.ACTIVE);
    }

    private CapabilityMountRequest knowledgeRequest(
            long definitionId, long versionId, String revisionId) {
        return new CapabilityMountRequest(CapabilityType.KNOWLEDGE_SOURCE, definitionId, versionId,
                null, null, null,
                new KnowledgeMountConfigRequest(
                        CapabilityMount.KnowledgeMountConfig.BindingMode.PINNED_REVISION,
                        revisionId),
                0, CapabilityMountStatus.ACTIVE);
    }

    private CapabilityDeploymentView deployment(long versionId, long revision, String status) {
        return new CapabilityDeploymentView(600L, 7L, "deploy-1", versionId,
                ToolDeployment.ProviderType.TEST_MOCK, "fixture", ToolDeployment.Environment.TEST,
                status, revision, null, null);
    }

    private CapabilityMountSetResult emptyMountSet(long generation) {
        return new CapabilityMountSetResult(7L, 9L, generation, List.of());
    }

    private void assertStatus(HttpStatus status, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getStatus())
                .isEqualTo(status);
    }
}

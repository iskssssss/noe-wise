package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.skillforge.core.knowledge.KnowledgeReferenceService;
import com.skillforge.core.workflow.WorkflowReferenceService;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.model.KnowledgeBaseRevisionDO;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.knowledge.constant.KnowledgeConstants;
import com.skillforge.core.workflow.constant.WorkflowConstants;
import com.skillforge.core.capability.persistence.CapabilityVersionMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionDO;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.mapper.WorkflowInstanceMapper;
import com.skillforge.core.workflow.model.WorkflowDefinitionVersionDO;
import com.skillforge.core.workflow.model.WorkflowInstanceDO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CapabilityMountReferenceResolversTest {

    private static final long TENANT_ID = 10L;
    private static final long OTHER_TENANT_ID = 99L;

    @Mock
    private WorkflowInstanceMapper workflowInstances;

    @Mock
    private WorkflowDefinitionVersionMapper workflowVersions;

    @Mock
    private KnowledgeBaseMapper knowledgeBases;

    @Mock
    private KnowledgeBaseRevisionMapper knowledgeRevisions;

    @Mock
    private CapabilityVersionMapper capabilityVersions;

    private CapabilityMountReferenceResolvers resolvers;

    @org.junit.jupiter.api.BeforeAll
    static void modelMetadata() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, CapabilityVersionDO.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, KnowledgeBaseRevisionDO.class);
    }

    @BeforeEach
    void setUp() {
        resolvers = new CapabilityMountReferenceResolvers(
                capabilityVersions, new WorkflowReferenceService(workflowInstances, workflowVersions,
                org.mockito.Mockito.mock(com.skillforge.core.workflow.mapper.WorkflowContainerMapper.class)),
                new KnowledgeReferenceService(knowledgeBases, knowledgeRevisions));
    }

    @Test
    void workflowResolutionRequiresPublishedTenantInstanceAndExactDefinitionVersion() {
        WorkflowInstanceDO instance = workflowInstance(300L, TENANT_ID,
                WorkflowConstants.INSTANCE_STATUS_PUBLISHED);
        WorkflowDefinitionVersionDO definitionVersion = workflowVersion(
                300L, TENANT_ID, 400L);
        when(workflowInstances.selectOne(any())).thenReturn(instance);
        when(workflowVersions.selectOne(any())).thenReturn(definitionVersion);
        when(capabilityVersions.selectOne(any())).thenReturn(
                capabilityVersion(400L, 200L, TENANT_ID, CapabilityType.WORKFLOW,
                        CAPABILITY_PUBLISHED));

        assertTrue(resolvers.workflowInstanceMatches(
                TENANT_ID, "30", 300L,
                new CapabilityRef(CapabilityType.WORKFLOW, "200", "400")));
        assertFalse(resolvers.workflowInstanceMatches(
                TENANT_ID, "30", 301L,
                new CapabilityRef(CapabilityType.WORKFLOW, "200", "400")));

        instance.setTenantId(OTHER_TENANT_ID);
        assertFalse(resolvers.workflowInstanceMatches(
                TENANT_ID, "30", 300L,
                new CapabilityRef(CapabilityType.WORKFLOW, "200", "400")));

        instance.setTenantId(TENANT_ID);
        instance.setStatus(WorkflowConstants.STATUS_DRAFT);
        assertFalse(resolvers.workflowInstanceMatches(
                TENANT_ID, "30", 300L,
                new CapabilityRef(CapabilityType.WORKFLOW, "200", "400")));
    }

    @Test
    void workflowResolutionRejectsWrongCapabilityVersionAndMalformedIds() {
        when(capabilityVersions.selectOne(any())).thenReturn(
                capabilityVersion(401L, 200L, TENANT_ID, CapabilityType.WORKFLOW,
                        CAPABILITY_PUBLISHED));

        assertFalse(resolvers.workflowInstanceMatches(
                TENANT_ID, "30", 300L,
                new CapabilityRef(CapabilityType.WORKFLOW, "200", "400")));
        assertFalse(resolvers.workflowInstanceMatches(
                TENANT_ID, "not-a-number", 300L,
                new CapabilityRef(CapabilityType.WORKFLOW, "200", "400")));
        assertFalse(resolvers.workflowInstanceMatches(
                TENANT_ID, "30", 300L,
                new CapabilityRef(CapabilityType.TOOL, "200", "400")));
    }

    @Test
    void pinnedKnowledgeRequiresTenantPublishedRevisionAndExactCapabilityVersion() {
        CapabilityMount mount = knowledgeMount(
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "500", "501"),
                new KnowledgeMountConfig(
                        KnowledgeMountConfig.BindingMode.PINNED_REVISION, "601"));
        when(knowledgeRevisions.selectOne(any())).thenReturn(
                knowledgeRevision(601L, 700L, TENANT_ID, 501L,
                        KnowledgeConstants.REVISION_STATUS_PUBLISHED));
        when(capabilityVersions.selectOne(any())).thenReturn(
                knowledgeVersion(501L, 500L, TENANT_ID, CAPABILITY_PUBLISHED));

        assertEquals(Optional.of(mount), resolvers.resolveKnowledgeMount(TENANT_ID, mount));

        KnowledgeBaseRevisionDO wrongTenant = knowledgeRevision(
                601L, 700L, OTHER_TENANT_ID, 501L, KnowledgeConstants.REVISION_STATUS_PUBLISHED);
        when(knowledgeRevisions.selectOne(any())).thenReturn(wrongTenant);
        assertTrue(resolvers.resolveKnowledgeMount(TENANT_ID, mount).isEmpty());

        when(knowledgeRevisions.selectOne(any())).thenReturn(
                knowledgeRevision(601L, 700L, TENANT_ID, 502L,
                        KnowledgeConstants.REVISION_STATUS_PUBLISHED));
        assertTrue(resolvers.resolveKnowledgeMount(TENANT_ID, mount).isEmpty());
    }

    @Test
    void followActiveReturnsTheActualPublishedVersionAndPinsItsRevision() {
        CapabilityMount mount = knowledgeMount(
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "500", "501"),
                new KnowledgeMountConfig(KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE, null));
        CapabilityVersionDO configuredVersion = knowledgeVersion(
                501L, 500L, TENANT_ID, CAPABILITY_PUBLISHED);
        CapabilityVersionDO activeVersion = knowledgeVersion(
                502L, 500L, TENANT_ID, CAPABILITY_PUBLISHED);
        KnowledgeBaseRevisionDO activeRevision = knowledgeRevision(
                702L, 700L, TENANT_ID, 502L,
                KnowledgeConstants.REVISION_STATUS_PUBLISHED);
        when(capabilityVersions.selectList(any())).thenReturn(
                List.of(configuredVersion, activeVersion));
        when(knowledgeRevisions.activePublishedForCapabilityVersions(TENANT_ID, List.of(501L, 502L)))
                .thenReturn(List.of(activeRevision));

        CapabilityMount resolved = resolvers.resolveKnowledgeMount(TENANT_ID, mount).orElseThrow();

        assertEquals(new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "500", "502"),
                resolved.capability());
        assertEquals(new KnowledgeMountConfig(
                KnowledgeMountConfig.BindingMode.PINNED_REVISION, "702"), resolved.config());
        assertEquals(mount.mountId(), resolved.mountId());
        assertEquals(mount.revision(), resolved.revision());
    }

    @Test
    void followActiveRejectsMissingOrAmbiguousActivePublishedRevision() {
        CapabilityMount mount = knowledgeMount(
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "500", "501"),
                new KnowledgeMountConfig(KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE, null));
        CapabilityVersionDO first = knowledgeVersion(501L, 500L, TENANT_ID, CAPABILITY_PUBLISHED);
        CapabilityVersionDO second = knowledgeVersion(502L, 500L, TENANT_ID, CAPABILITY_PUBLISHED);
        when(capabilityVersions.selectList(any())).thenReturn(List.of(first, second));
        when(knowledgeRevisions.activePublishedForCapabilityVersions(TENANT_ID, List.of(501L, 502L))).thenReturn(List.of(
                knowledgeRevision(701L, 700L, TENANT_ID, 501L,
                        KnowledgeConstants.REVISION_STATUS_PUBLISHED),
                knowledgeRevision(702L, 701L, TENANT_ID, 502L,
                        KnowledgeConstants.REVISION_STATUS_PUBLISHED)));

        assertTrue(resolvers.resolveKnowledgeMount(TENANT_ID, mount).isEmpty());

        when(knowledgeRevisions.activePublishedForCapabilityVersions(TENANT_ID, List.of(501L, 502L)))
                .thenReturn(List.of());
        assertTrue(resolvers.resolveKnowledgeMount(TENANT_ID, mount).isEmpty());
    }

    @Test
    void followActiveDoesNotReadKnowledgeWhenNoPublishedCapabilityMatches() {
        var mount = knowledgeMount(new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "500", "501"),
                new KnowledgeMountConfig(KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE, null));
        when(capabilityVersions.selectList(any())).thenReturn(List.of());
        assertTrue(resolvers.resolveKnowledgeMount(TENANT_ID, mount).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(knowledgeBases, knowledgeRevisions);
    }

    @Test
    void followActiveSelectsOnlyPublishedCapabilityIdentityBeforeTargetedKnowledgeRead() {
        var mount = knowledgeMount(new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "500", "501"),
                new KnowledgeMountConfig(KnowledgeMountConfig.BindingMode.FOLLOW_ACTIVE, null));
        when(capabilityVersions.selectList(any())).thenAnswer(call -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) call.getArgument(0);
            assertEquals("id,definition_id,tenant_id,capability_type,status", query.getSqlSelect());
            return List.of(knowledgeVersion(502L, 500L, TENANT_ID, CAPABILITY_PUBLISHED));
        });
        when(knowledgeRevisions.activePublishedForCapabilityVersions(TENANT_ID, List.of(502L)))
                .thenReturn(List.of(knowledgeRevision(702L, 700L, TENANT_ID, 502L,
                        KnowledgeConstants.REVISION_STATUS_PUBLISHED)));
        assertEquals("502", resolvers.resolveKnowledgeMount(TENANT_ID, mount).orElseThrow().capability().versionId());
        org.mockito.Mockito.verify(knowledgeRevisions).activePublishedForCapabilityVersions(TENANT_ID, List.of(502L));
        org.mockito.Mockito.verifyNoMoreInteractions(knowledgeRevisions);
        org.mockito.Mockito.verifyNoInteractions(knowledgeBases);
    }

    private WorkflowInstanceDO workflowInstance(
            long definitionVersionId, long tenantId, String status) {
        WorkflowInstanceDO instance = new WorkflowInstanceDO();
        instance.setId(30L);
        instance.setTenantId(tenantId);
        instance.setDefinitionVersionId(definitionVersionId);
        instance.setStatus(status);
        return instance;
    }

    private WorkflowDefinitionVersionDO workflowVersion(
            long id, long tenantId, long capabilityVersionId) {
        WorkflowDefinitionVersionDO version = new WorkflowDefinitionVersionDO();
        version.setId(id);
        version.setTenantId(tenantId);
        version.setCapabilityVersionId(capabilityVersionId);
        return version;
    }

    private CapabilityMount knowledgeMount(CapabilityRef ref, KnowledgeMountConfig config) {
        return new CapabilityMount("knowledge-mount", TENANT_ID, 20L, ref, config, 3, 4L, true);
    }

    private KnowledgeBaseRevisionDO knowledgeRevision(
            long id, long kbId, long tenantId, long capabilityVersionId, String status) {
        KnowledgeBaseRevisionDO revision = new KnowledgeBaseRevisionDO();
        revision.setId(id);
        revision.setKbId(kbId);
        revision.setTenantId(tenantId);
        revision.setCapabilityVersionId(capabilityVersionId);
        revision.setStatus(status);
        return revision;
    }

    private CapabilityVersionDO knowledgeVersion(
            long id, long definitionId, long tenantId, String status) {
        return capabilityVersion(id, definitionId, tenantId,
                CapabilityType.KNOWLEDGE_SOURCE, status);
    }

    private CapabilityVersionDO capabilityVersion(
            long id, long definitionId, long tenantId, CapabilityType type, String status) {
        CapabilityVersionDO version = new CapabilityVersionDO();
        version.setId(id);
        version.setDefinitionId(definitionId);
        version.setTenantId(tenantId);
        version.setCapabilityType(type.name());
        version.setStatus(status);
        return version;
    }

    private static final String CAPABILITY_PUBLISHED = "PUBLISHED";
}

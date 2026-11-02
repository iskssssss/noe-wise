package com.skillforge.institution.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.knowledge.KnowledgeReferenceService;
import com.skillforge.core.workflow.WorkflowReferenceService;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.capability.CapabilityDescriptorCodec;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.PersistentCapabilityRegistry;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.capability.tool.DefaultToolCatalog;
import com.skillforge.core.capability.tool.DefaultToolResolver;
import com.skillforge.core.capability.tool.DefaultToolSchemaValidator;
import com.skillforge.core.capability.tool.PersistentToolDeploymentRegistry;
import com.skillforge.core.capability.tool.PersistentToolRegistry;
import com.skillforge.core.capability.tool.ToolDeploymentRegistry;
import com.skillforge.core.capability.tool.ToolCatalog;
import com.skillforge.core.capability.tool.ToolRegistry;
import com.skillforge.core.capability.tool.ToolResolver;
import com.skillforge.core.capability.tool.ToolSchemaValidator;
import com.skillforge.core.capability.connector.JsonSchemaValidateToolHandler;
import com.skillforge.core.capability.persistence.CapabilityDefinitionMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionDO;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordDO;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.mapper.WorkflowInstanceMapper;
import com.skillforge.institution.service.CapabilityMountReferenceResolvers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityMountRuntimeConfigurationTest {

    @Test
    void exposesTheProductionRegistryAndResolverWithExactToolDeploymentLookup() {
        CapabilityDefinitionMapper definitions = mock(CapabilityDefinitionMapper.class);
        CapabilityVersionMapper versions = mock(CapabilityVersionMapper.class);
        ToolDeploymentRecordMapper deployments = mock(ToolDeploymentRecordMapper.class);
        WorkflowInstanceMapper workflowInstances = mock(WorkflowInstanceMapper.class);
        WorkflowDefinitionVersionMapper workflowVersions = mock(WorkflowDefinitionVersionMapper.class);
        KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseRevisionMapper knowledgeRevisions = mock(KnowledgeBaseRevisionMapper.class);

        CapabilityMountRuntimeConfiguration configuration =
                new CapabilityMountRuntimeConfiguration();
        CapabilityMountReferenceResolvers references = configuration.capabilityMountReferenceResolvers(
                new WorkflowReferenceService(workflowInstances, workflowVersions, mock(com.skillforge.core.workflow.mapper.WorkflowContainerMapper.class)),
                new KnowledgeReferenceService(knowledgeBases, knowledgeRevisions), versions);
        PersistentCapabilityRegistry registry = configuration.persistentCapabilityRegistry(
                definitions, versions, deployments,
                new CapabilityDescriptorCodec(new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)),
                references);
        CapabilityMountResolver resolver = configuration.capabilityMountResolver(
                registry, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        ToolDeploymentRecordDO deployment = new ToolDeploymentRecordDO();
        deployment.setTenantId(10L);
        deployment.setDeploymentKey("calendar-production");
        deployment.setToolVersionId(200L);
        deployment.setProviderType("PLATFORM");
        deployment.setProviderRef("calendar");
        deployment.setEnvironment("PRODUCTION");
        deployment.setStatus("ACTIVE");
        deployment.setRevision(7L);
        when(deployments.selectOne(any())).thenReturn(deployment);
        when(versions.selectOne(any())).thenReturn(toolVersion(200L, 100L, 10L));
        var definition = new com.skillforge.core.capability.persistence.CapabilityDefinitionDO();
        definition.setId(100L); definition.setTenantId(10L); definition.setCapabilityType("TOOL");
        definition.setStatus("PUBLISHED"); definition.setDefaultVersionId(200L);
        when(definitions.selectOne(any())).thenReturn(definition);

        ToolDeployment resolved = registry.findToolDeployment(10L, "calendar-production")
                .orElseThrow();

        assertNotNull(resolver);
        assertEquals(new ToolDeployment(
                "calendar-production", 10L,
                new com.skillforge.core.capability.CapabilityRef(
                        com.skillforge.core.capability.CapabilityType.TOOL, "100", "200"),
                ToolDeployment.ProviderType.PLATFORM, "calendar", 7L,
                ToolDeployment.Environment.PRODUCTION, true), resolved);
    }

    @Test
    void exposesOneConcreteBeanForEachToolRuntimeBoundary() {
        CapabilityMountRuntimeConfiguration configuration =
                new CapabilityMountRuntimeConfiguration();
        PersistentCapabilityRegistry persistentRegistry = mock(PersistentCapabilityRegistry.class);
        ToolRegistry tools = configuration.toolRegistry(persistentRegistry);
        ToolDeploymentRegistry deployments = configuration.toolDeploymentRegistry(
                persistentRegistry);
        ToolResolver resolver = configuration.toolResolver(tools, deployments);
        ToolCatalog catalog = configuration.toolCatalog(resolver);
        ToolSchemaValidator schema = configuration.toolSchemaValidator(
                new JsonSchemaValidateToolHandler(new ObjectMapper()));

        assertInstanceOf(PersistentToolRegistry.class, tools);
        assertInstanceOf(PersistentToolDeploymentRegistry.class, deployments);
        assertInstanceOf(DefaultToolResolver.class, resolver);
        assertInstanceOf(DefaultToolCatalog.class, catalog);
        assertInstanceOf(DefaultToolSchemaValidator.class, schema);
    }

    private CapabilityVersionDO toolVersion(long id, long definitionId, long tenantId) {
        CapabilityVersionDO version = new CapabilityVersionDO();
        version.setId(id);
        version.setDefinitionId(definitionId);
        version.setTenantId(tenantId);
        version.setCapabilityType("TOOL");
        version.setName("Calendar");
        version.setDescription("Calendar tool");
        version.setTrustLevel("TENANT");
        version.setStatus("PUBLISHED");
        version.setContentHash("hash");
        version.setDescriptor(Map.ofEntries(
                Map.entry("model_invocable", true),
                Map.entry("user_invocable", false),
                Map.entry("tool_name", "calendar"),
                Map.entry("input_schema", Map.of()),
                Map.entry("output_schema", Map.of()),
                Map.entry("effect", "READ_ONLY"),
                Map.entry("idempotent", true),
                Map.entry("confirmation_policy", "NEVER"),
                Map.entry("timeout_ms", 1_000),
                Map.entry("sensitive_fields", Set.of()),
                Map.entry("provider_requirements", Set.of())));
        return version;
    }
}

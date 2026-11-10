package com.skillforge.core.capability;

import com.skillforge.core.shared.exception.ApiException;
import static org.mockito.Mockito.never;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.persistence.CapabilityDefinitionMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.capability.persistence.CapabilityDefinitionDO;
import com.skillforge.core.capability.persistence.CapabilityVersionDO;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordDO;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Catalog 生命周期服务的严格类型、Draft 编辑、校验与发布 CAS 契约。 */
class CapabilityCatalogServiceTest {

    @Test
    void catalogValidatesSkillWithIndependentUpstreamVersion() {
        var definition = definition("SKILL");
        var version = version(definition, "DRAFT");
        var skill = new SkillPackageValidator().fromDirectory(Map.of("SKILL.md",
                "---\nname: calendar\ndescription: Calendar skill\nmetadata:\n  version: v2\n---\nInstructions"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var descriptor = SkillPackageDescriptorMapper.descriptor(skill, true, true,
                SkillActivationMode.ON_DEMAND, null, List.of());
        version.setDescriptor(descriptor);
        version.setContentHash(new SkillPackageValidator().packageSha256(skill));
        when(definitions.selectOne(any())).thenReturn(definition);
        when(versions.selectOne(any())).thenReturn(version);
        when(versions.updateById(any(CapabilityVersionDO.class))).thenReturn(1);
        when(definitions.update(any(), any())).thenReturn(1);
        var result = service.validateVersion(CapabilityCatalogScope.tenant(7L), 1L, 2L);
        assertEquals(true, result.valid());
        var created = service.createDraftVersion(CapabilityCatalogScope.tenant(7L),
                1L, "platform-v1", "calendar", "Calendar skill", version.getContentHash(), descriptor);
        assertEquals("platform-v1", created.versionKey());
        assertEquals(descriptor, created.descriptor());
    }

    private final CapabilityDefinitionMapper definitions = mock(CapabilityDefinitionMapper.class);
    private final CapabilityVersionMapper versions = mock(CapabilityVersionMapper.class);
    private final ToolDeploymentRecordMapper deployments = mock(ToolDeploymentRecordMapper.class);
    private final CapabilityCatalogDatabaseContext databaseContext =
            mock(CapabilityCatalogDatabaseContext.class);
    private final CapabilityCatalogService service = new CapabilityCatalogService(
            definitions, versions, deployments,
            new CapabilityDescriptorCodec(new ObjectMapper()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)), databaseContext,org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));

    @BeforeAll
    static void initMybatisLambdaMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CapabilityDefinitionDO.class);
        TableInfoHelper.initTableInfo(assistant, CapabilityVersionDO.class);
        TableInfoHelper.initTableInfo(assistant, ToolDeploymentRecordDO.class);
    }

    @BeforeEach
    void setTenant() {
        TenantContext.setTenantId("7");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void deploymentDirectoryReadsOnlyOneBatchOfVersionLabels() {
        var rows = java.util.stream.LongStream.rangeClosed(1, 3).mapToObj(id -> {
            var row = new ToolDeploymentRecordDO();
            row.setId(id); row.setTenantId(7L); row.setDeploymentKey("deployment-" + id);
            row.setToolVersionId(id == 3 ? 3L : 2L);
            row.setProviderType("PLATFORM"); row.setProviderRef("fixture");
            row.setEnvironment("TEST"); row.setStatus("ACTIVE"); row.setRevision(1L);
            return row;
        }).toList();
        when(deployments.selectList(any())).thenReturn(rows);
        var label = new CapabilityVersionDO();
        label.setId(2L); label.setName("工具"); label.setVersionKey("v1");
        when(versions.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<CapabilityVersionDO> query = invocation.getArgument(0);
            assertEquals("id,name,version_key", query.getSqlSelect());
            return List.of(label);
        });
        var result = service.listDeployments(CapabilityCatalogScope.tenant(7L), null);
        assertEquals(List.of("deployment-1", "deployment-2", "deployment-3"),
                result.stream().map(CapabilityDeploymentView::deploymentKey).toList());
        assertEquals("工具", result.get(0).toolName());
        assertEquals("v1", result.get(1).toolVersionKey());
        assertEquals(null, result.get(2).toolName());
        assertEquals(null, result.get(2).toolVersionKey());
        verify(versions).selectList(any());
        verify(versions, never()).selectOne(any());
    }

    @Test
    void emptyDeploymentDirectoryDoesNotReadAllVersions() {
        when(deployments.selectList(any())).thenReturn(List.of());
        assertEquals(List.of(), service.listDeployments(CapabilityCatalogScope.tenant(7L), null));
        verify(versions, never()).selectList(any());
    }

    @Test
    void availableToolDirectoryBindsTenantAndRejectsPlatformScope() {
        var row = new AvailableToolDeployment(1L, 2L, "工具", "说明", "lookup", "deployment",
                com.skillforge.core.capability.tool.ToolDeployment.ProviderType.MCP, "connector",
                com.skillforge.core.capability.tool.ToolDeployment.Environment.TEST);
        when(deployments.listAvailableTools(7L)).thenReturn(List.of(row));
        assertEquals(List.of(row), service.availableToolDeployments(CapabilityCatalogScope.tenant(7L)));
        verify(databaseContext).bindTenant(7L);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> service.availableToolDeployments(CapabilityCatalogScope.platform())).getStatus());
        verify(versions, never()).selectOne(any());
    }

    @Test
    void platformSkillSummaryBindsPlatformAndDoesNotReadVersionBodies() {
        TenantContext.clear();
        var expected = List.of(new SkillCatalogSummary(1L, "report", "报告技能", "生成报告", "PUBLISHED", 2L, "v1"));
        when(definitions.listPlatformSkills()).thenReturn(expected);
        assertEquals(expected, service.listPlatformSkills());
        verify(databaseContext).bindPlatform();
        org.mockito.Mockito.verifyNoInteractions(versions, deployments);
    }

    @Test
    void requirementOptionsBindRequestedScopeAndTypeWithoutVersionBodies() {
        TenantContext.clear();
        var platform = List.of(new CapabilityRequirementOption(1L, "公共技能", 2L, "v1", 31L));
        var tenant = List.of(new CapabilityRequirementOption(3L, "机构工具", 4L, "v2", null));
        when(definitions.listRequirementOptions(null, CapabilityType.SKILL)).thenReturn(platform);
        when(definitions.listRequirementOptions(7L, CapabilityType.TOOL)).thenReturn(tenant);
        assertEquals(platform, service.listRequirementOptions(CapabilityCatalogScope.platform(), CapabilityType.SKILL));
        TenantContext.setTenantId("7");
        assertEquals(tenant, service.listRequirementOptions(CapabilityCatalogScope.tenant(7L), CapabilityType.TOOL));
        verify(databaseContext).bindPlatform(); verify(databaseContext).bindTenant(7L);
        org.mockito.Mockito.verifyNoInteractions(versions, deployments);
    }

    @Test
    void skillHistoryRequiresVisibleSkillDefinitionBeforeReadingScalarVersions() {
        var skill = definition("SKILL");
        when(definitions.selectOne(any())).thenReturn(skill);
        var expected = List.of(new SkillVersionSummary(2L, "v1", "PUBLISHED"));
        when(versions.listSkillVersionSummaries(7L, 1L)).thenReturn(expected);
        assertEquals(expected, service.listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 1L));
        verify(databaseContext).bindTenant(7L);
        verify(versions, never()).selectList(any());
        verify(versions, never()).selectOne(any());
    }

    @Test
    void skillHistoryRejectsNonSkillAndMissingDefinitions() {
        when(definitions.selectOne(any())).thenReturn(definition("TOOL")).thenReturn(null);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 1L)).getStatus());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 99L)).getStatus());
        org.mockito.Mockito.verifyNoInteractions(versions);
    }

    @Test
    void versionCardSummaryAcceptsAllCapabilityTypesWithoutReadingBodies() {
        var expected = List.of(new CapabilityVersionSummary(2L, "v1", "卡片名称", "卡片说明", "ARCHIVED"));
        when(versions.listVersionSummaries(7L, 1L)).thenReturn(expected);
        for (var type : CapabilityType.values()) {
            when(definitions.selectOne(any())).thenReturn(definition(type.name()));
            assertEquals(expected, service.listVersionSummaries(CapabilityCatalogScope.tenant(7L), 1L));
        }
        verify(versions, never()).selectList(any());
        verify(versions, never()).selectOne(any());
    }

    @Test
    void versionCardSummaryRejectsInvisibleDefinitionBeforeReadingVersions() {
        when(definitions.selectOne(any())).thenReturn(null);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.listVersionSummaries(CapabilityCatalogScope.tenant(7L), 99L)).getStatus());
        org.mockito.Mockito.verifyNoInteractions(versions);
    }

    @Test
    void batchPublishedVersionsRetainsScopeAndDefinitionIdentity() {
        var definition = definition("TOOL");
        definition.setStatus("PUBLISHED");
        var valid = version(definition, "PUBLISHED");
        var wrongDefinition = version(definition, "PUBLISHED");
        wrongDefinition.setId(3L); wrongDefinition.setDefinitionId(99L);
        var wrongType = version(definition, "PUBLISHED");
        wrongType.setId(4L); wrongType.setCapabilityType("SKILL");
        when(versions.selectList(any())).thenReturn(List.of(valid, wrongDefinition, wrongType));
        when(definitions.selectList(any())).thenReturn(List.of(definition));
        var result = service.publishedVersions(CapabilityCatalogScope.tenant(7L), List.of(2L, 3L, 4L));
        assertEquals(List.of(2L), result.stream().map(CapabilityVersionView::id).toList());
        verify(databaseContext).bindTenant(7L);
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<CapabilityVersionDO>> query =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(versions).selectList(query.capture());
        org.junit.jupiter.api.Assertions.assertTrue(query.getValue().getSqlSegment().contains("tenant_id"));
        org.junit.jupiter.api.Assertions.assertTrue(query.getValue().getSqlSegment().contains("status"));
        verify(versions, never()).selectOne(any());
        verify(definitions, never()).selectOne(any());
    }

    @Test
    void rejectsMcpWriteReplayClaimBeforeCreatingDeployment() {
        var published = version(definition("TOOL"), "PUBLISHED");
        published.setDescriptor(Map.of("effect", "IDEMPOTENT_WRITE", "idempotent", true));
        when(versions.selectOne(any())).thenReturn(published);
        var failure = assertThrows(ApiException.class, () -> service.createDeployment(
                CapabilityCatalogScope.tenant(7L), "write", 2L,
                com.skillforge.core.capability.tool.ToolDeployment.ProviderType.MCP, "fixture",
                com.skillforge.core.capability.tool.ToolDeployment.Environment.TEST, "ACTIVE"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getStatus());
        verify(deployments, never()).insert(any(ToolDeploymentRecordDO.class));
    }

    @Test
    void acceptsMcpMediaWriteWithoutClaimingRuntimeReplay() {
        var published = version(definition("TOOL"), "PUBLISHED");
        var media = new java.util.LinkedHashMap<>(published.getDescriptor());
        media.put("tool_name", "media_synthesize");
        media.put("effect", "NON_IDEMPOTENT_WRITE");
        media.put("idempotent", false);
        media.put("input_schema", Map.of("type", "object", "properties", Map.of(
                "request_id", Map.of("type", "string"), "text", Map.of("type", "string")),
                "required", List.of("request_id", "text"), "additionalProperties", false));
        // 接入示例的真实 descriptor 必须先通过严格 Codec，再进入已发布版本的部署契约。
        new CapabilityDescriptorCodec(new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)).decode(
                        new CapabilityRef(CapabilityType.TOOL, "1", "2"), published.getTenantId(),
                        published.getName(), published.getDescription(), published.getTrustLevel(),
                        published.getStatus(), published.getContentHash(), media);
        published.setDescriptor(media);
        when(versions.selectOne(any())).thenReturn(published);
        when(deployments.insert(any(ToolDeploymentRecordDO.class))).thenReturn(1);

        var deployment = service.createDeployment(CapabilityCatalogScope.tenant(7L), "media-synthesize", 2L,
                com.skillforge.core.capability.tool.ToolDeployment.ProviderType.MCP, "media-connector",
                com.skillforge.core.capability.tool.ToolDeployment.Environment.PRODUCTION, "ACTIVE");

        assertEquals("media-synthesize", deployment.deploymentKey());
        assertEquals("media-connector", deployment.providerRef());
        assertEquals("ACTIVE", deployment.status());
        assertEquals(published.getName(), deployment.toolName());
        assertEquals(published.getVersionKey(), deployment.toolVersionKey());
        assertEquals(com.skillforge.core.capability.tool.ToolDeployment.Environment.PRODUCTION, deployment.environment());
        verify(versions).selectOne(any());
        verify(deployments).insert(any(ToolDeploymentRecordDO.class));
    }

    @Test
    void platformDeploymentRejectsDifferentHandlerBeforeWriting() {
        var published = version(definition("TOOL"), "PUBLISHED");
        published.setDescriptor(Map.of("tool_name", "get_current_time"));
        when(versions.selectOne(any())).thenReturn(published);

        var failure = assertThrows(ApiException.class, () -> service.createDeployment(
                CapabilityCatalogScope.tenant(7L), "wrong-clock", published.getId(),
                com.skillforge.core.capability.tool.ToolDeployment.ProviderType.PLATFORM, "template_render",
                com.skillforge.core.capability.tool.ToolDeployment.Environment.DEVELOPMENT, "ACTIVE"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getStatus());
        assertEquals(CapabilityCatalogErrors.DEPLOYMENT_INVALID, failure.getCode());
        verify(deployments, never()).insert(any(ToolDeploymentRecordDO.class));
    }

    @Test
    void createsDefinitionAndStrictlyDecodesEveryClosedDescriptor() {
        when(definitions.insert(any(CapabilityDefinitionDO.class))).thenReturn(1);
        CapabilityCatalogScope scope = CapabilityCatalogScope.tenant(7L);
        CapabilityDefinitionView definition = service.createDefinition(
                scope, CapabilityType.TOOL, "calendar", "Calendar", "Calendar tool");

        assertEquals("TENANT", definition.trustLevel());
        CapabilityDefinitionDO stored = new CapabilityDefinitionDO();
        stored.setId(definition.id());
        stored.setTenantId(7L);
        stored.setCapabilityType("TOOL");
        stored.setCapabilityKey("calendar");
        stored.setName("Calendar");
        stored.setDescription("Calendar tool");
        stored.setTrustLevel("TENANT");
        stored.setStatus("DRAFT");
        stored.setRevision(1L);
        when(definitions.selectOne(any())).thenReturn(stored);
        when(versions.insert(any(CapabilityVersionDO.class))).thenReturn(1);

        for (CapabilityType type : CapabilityType.values()) {
            CapabilityDefinitionDO typeDefinition = stored;
            typeDefinition.setCapabilityType(type.name());
            Map<String, Object> descriptor = descriptor(type);
            CapabilityVersionView version = service.createDraftVersion(
                    scope, definition.id(), type.name().toLowerCase() + "-v1",
                    "Version", "Version description", "sha256:" + type.name(), descriptor);
            assertEquals(type, version.capabilityType());
        }
    }

    @Test
    void rejectsUnknownDescriptorFieldsBeforeInsert() {
        when(definitions.selectOne(any())).thenReturn(definition("TOOL"));
        assertThrows(com.skillforge.core.shared.exception.ApiException.class, () ->
                service.createDraftVersion(
                        CapabilityCatalogScope.tenant(7L), 1L, "v1", "Tool", "",
                        "sha256:bad", Map.of(
                                "model_invocable", true,
                                "user_invocable", false,
                                "unknown", true)));
    }

    @Test
    void publishUsesDefinitionRevisionCasAndDoesNotUpdateVersionContent() {
        CapabilityDefinitionDO definition = definition("TOOL");
        definition.setRevision(4L);
        CapabilityVersionDO version = version(definition, "DRAFT");
        when(definitions.selectOne(any())).thenReturn(definition);
        when(versions.selectOne(any())).thenReturn(version);
        when(definitions.update(any(), any())).thenReturn(1);
        when(versions.update(any(), any())).thenReturn(1);

        service.publishVersion(CapabilityCatalogScope.tenant(7L), 1L, 2L, 4L);

        verify(definitions, org.mockito.Mockito.times(2)).update(any(), any());
        verify(versions).update(any(), any());
    }

    @Test
    void stalePublishIsRejectedWithoutTouchingVersion() {
        CapabilityDefinitionDO definition = definition("TOOL");
        definition.setRevision(4L);
        when(definitions.selectOne(any())).thenReturn(definition);
        when(versions.selectOne(any())).thenReturn(version(definition, "DRAFT"));
        when(definitions.update(any(), any())).thenReturn(0);

        var exception = assertThrows(
                com.skillforge.core.shared.exception.ApiException.class,
                () -> service.publishVersion(CapabilityCatalogScope.tenant(7L), 1L, 2L, 3L));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void validationReturnsStableDescriptorErrorWithoutPersistence() {
        CapabilityDefinitionDO definition = definition("TOOL");
        CapabilityVersionDO version = version(definition, "DRAFT");
        version.setDescriptor(Map.of("model_invocable", true, "user_invocable", false,
                "unknown", true));
        when(definitions.selectOne(any())).thenReturn(definition);
        when(versions.selectOne(any())).thenReturn(version);

        CapabilityValidationView result = service.validateVersion(
                CapabilityCatalogScope.tenant(7L), definition.getId(), version.getId());

        assertEquals(false, result.valid());
        assertEquals(CapabilityCatalogErrors.DESCRIPTOR_INVALID, result.errors().get(0).code());
    }

    @Test
    void draftUpdateUsesDefinitionRevisionCas() {
        CapabilityDefinitionDO definition = definition("TOOL");
        definition.setRevision(2L);
        CapabilityVersionDO version = version(definition, "DRAFT");
        when(definitions.selectOne(any())).thenReturn(definition);
        when(versions.selectOne(any())).thenReturn(version);
        when(definitions.update(any(), any())).thenReturn(1);
        when(versions.update(any(), any())).thenReturn(1);

        service.updateDraftVersion(CapabilityCatalogScope.tenant(7L), 1L, 2L, 2L,
                "Updated", "Updated tool", "sha256:updated",
                descriptor(CapabilityType.TOOL));

        verify(definitions).update(any(), any());
        verify(versions).update(any(), any());
    }

    private CapabilityDefinitionDO definition(String type) {
        CapabilityDefinitionDO value = new CapabilityDefinitionDO();
        value.setId(1L);
        value.setTenantId(7L);
        value.setCapabilityType(type);
        value.setCapabilityKey("catalog-key");
        value.setName("Catalog");
        value.setDescription("Catalog description");
        value.setTrustLevel("TENANT");
        value.setStatus("DRAFT");
        value.setRevision(1L);
        return value;
    }

    private CapabilityVersionDO version(CapabilityDefinitionDO definition, String status) {
        CapabilityVersionDO value = new CapabilityVersionDO();
        value.setId(2L);
        value.setDefinitionId(definition.getId());
        value.setTenantId(definition.getTenantId());
        value.setCapabilityType(definition.getCapabilityType());
        value.setVersionKey("v1");
        value.setName("Version");
        value.setDescription("Version description");
        value.setTrustLevel("TENANT");
        value.setStatus(status);
        value.setContentHash("sha256:v1");
        value.setDescriptor(descriptor(CapabilityType.TOOL));
        return value;
    }

    private Map<String, Object> descriptor(CapabilityType type) {
        return switch (type) {
            case SKILL -> SkillPackageDescriptorMapper.descriptor(
                    new SkillPackageValidator().fromDirectory(Map.of("SKILL.md",
                            "---\nname: calendar\ndescription: Calendar skill\n---\nDo the thing"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    true, true, SkillActivationMode.ALWAYS,
                    "calendar", List.of(), 20);
            case TOOL -> Map.ofEntries(
                    Map.entry("model_invocable", true),
                    Map.entry("user_invocable", false),
                    Map.entry("tool_name", "calendar.lookup"),
                    Map.entry("input_schema", Map.of()),
                    Map.entry("output_schema", Map.of()),
                    Map.entry("effect", "READ_ONLY"),
                    Map.entry("idempotent", true),
                    Map.entry("confirmation_policy", "NEVER"),
                    Map.entry("timeout_ms", 1000),
                    Map.entry("sensitive_fields", List.of()),
                    Map.entry("provider_requirements", List.of()));
            case WORKFLOW -> Map.of(
                    "model_invocable", true, "user_invocable", false,
                    "input_schema", Map.of(), "output_schema", Map.of(),
                    "supports_hitl", false, "export_fields", List.of(),
                    "timeout_ms", 1000, "concurrency_safe", true);
            case KNOWLEDGE_SOURCE -> Map.of(
                    "model_invocable", true, "user_invocable", true,
                    "source_id", "knowledge-source",
                    "source_versions", List.of("source-v1"),
                    "allowed_operators", List.of("search"),
                    "freshness_policy", "daily", "citation_required", true);
        };
    }
}

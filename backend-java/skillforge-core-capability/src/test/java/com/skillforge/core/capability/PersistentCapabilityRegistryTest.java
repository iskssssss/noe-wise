package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.capability.persistence.CapabilityDefinitionMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.capability.persistence.CapabilityDefinitionDO;
import com.skillforge.core.capability.persistence.CapabilityVersionDO;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordDO;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PersistentCapabilityRegistryTest {

    private final CapabilityDefinitionMapper definitions = mock(CapabilityDefinitionMapper.class);
    private final CapabilityVersionMapper versions = mock(CapabilityVersionMapper.class);
    private final ToolDeploymentRecordMapper deployments = mock(ToolDeploymentRecordMapper.class);
    private final PersistentCapabilityRegistry registry = new PersistentCapabilityRegistry(
            definitions, versions, deployments,
            new CapabilityDescriptorCodec(new ObjectMapper()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)),
            (tenant, instance, revision, ref) -> true,
            (tenant, mount) -> Optional.of(mount));

    @Test
    void resolvesPublishedToolByImmutableIds() {
        when(versions.selectOne(any())).thenReturn(version(2L, 1L, 10L));

        when(definitions.selectOne(any())).thenReturn(definition(1L, 10L));

        Optional<CapabilityDescriptor> result = registry.find(
                10L, new CapabilityRef(CapabilityType.TOOL, "1", "2"));

        assertInstanceOf(ToolVersion.class, result.orElseThrow());
    }

    @Test
    void malformedReferenceNeverQueriesTheDatabase() {
        assertTrue(registry.find(10L,
                new CapabilityRef(CapabilityType.TOOL, "calendar", "latest")).isEmpty());
        verify(versions, never()).selectOne(any());
    }

    @Test
    void resolvesTenantDeploymentToTheExactToolVersion() {
        ToolDeploymentRecordDO record = new ToolDeploymentRecordDO();
        record.setTenantId(10L);
        record.setDeploymentKey("calendar-prod");
        record.setToolVersionId(2L);
        record.setProviderType("CONNECTOR");
        record.setProviderRef("connector-1");
        record.setEnvironment("PRODUCTION");
        record.setStatus("ACTIVE");
        record.setRevision(3L);
        when(deployments.selectOne(any())).thenReturn(record);
        when(versions.selectOne(any())).thenReturn(version(2L, 1L, 10L));
        when(definitions.selectOne(any())).thenReturn(definition(1L, 10L));

        ToolDeployment result = registry.findToolDeployment(10L, "calendar-prod").orElseThrow();

        assertEquals(new CapabilityRef(CapabilityType.TOOL, "1", "2"), result.toolVersion());
        assertEquals(3L, result.revision());
    }

    @Test
    void newExecutionResolvesCurrentToolPublicationFromStableDefinitionAndDeployment() {
        // 此用例实际渲染 Lambda SQL，独立运行时显式初始化对应实体元数据。
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "registry-test"), CapabilityVersionDO.class);
        var definition = definition(1L, 10L);
        definition.setDefaultVersionId(3L);
        when(definitions.selectOne(any())).thenReturn(definition);
        when(versions.selectOne(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?> query = invocation.getArgument(0);
            query.getSqlSegment();
            return version(query.getParamNameValuePairs().containsValue(3L) ? 3L : 2L, 1L, 10L);
        });
        var deployment = new ToolDeploymentRecordDO();
        deployment.setTenantId(10L); deployment.setDeploymentKey("calendar-prod"); deployment.setToolVersionId(2L);
        deployment.setProviderType("MCP"); deployment.setProviderRef("calendar"); deployment.setEnvironment("TEST");
        deployment.setStatus("ACTIVE"); deployment.setRevision(4L);
        when(deployments.selectOne(any())).thenReturn(deployment);
        var requested = new CapabilityMount("mount-1", 10L, 20L,
                new CapabilityRef(CapabilityType.TOOL, "1", "2"),
                new CapabilityMount.ToolMountConfig("calendar-prod", 3L), 0, 1L, true);

        var selected = registry.resolveCurrentMount(10L, requested).orElseThrow();

        assertEquals(new CapabilityRef(CapabilityType.TOOL, "1", "3"), selected.capability());
        assertEquals(new CapabilityMount.ToolMountConfig("calendar-prod", 4L), selected.config());
        assertEquals(requested.mountId(), selected.mountId());
    }

    @Test
    void skillUsesItsExactPublishedVersionAndModeRatherThanCurrentDefault() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "skill-registry-test"),
                CapabilityVersionDO.class);
        var definition = definition(1L, 10L); definition.setCapabilityType("SKILL"); definition.setDefaultVersionId(3L);
        when(definitions.selectOne(any())).thenReturn(definition);
        when(versions.selectOne(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?> query = invocation.getArgument(0);
            query.getSqlSegment();
            return skillVersion(query.getParamNameValuePairs().containsValue(3L) ? 3L : 2L,
                    query.getParamNameValuePairs().containsValue(3L) ? SkillActivationMode.ALWAYS : SkillActivationMode.ON_DEMAND);
        });
        var mounted = new CapabilityMount("skill-mount", 10L, 20L,
                new CapabilityRef(CapabilityType.SKILL, "1", "2"),
                new CapabilityMount.SkillMountConfig(SkillActivationMode.ON_DEMAND, 3000L), 0, 1L, true);
        assertEquals(mounted, registry.resolveCurrentMount(10L, mounted).orElseThrow());
        // 默认版本存在也不能恢复一个不存在、失活或激活配置不匹配的精确挂载。
        doReturn(null).when(versions).selectOne(any());
        assertTrue(registry.resolveCurrentMount(10L, mounted).isEmpty());
        doReturn(skillVersion(2L, SkillActivationMode.ALWAYS)).when(versions).selectOne(any());
        assertTrue(registry.resolveCurrentMount(10L, mounted).isEmpty());
    }

    private CapabilityVersionDO skillVersion(long id, SkillActivationMode mode) {
        var packages = new SkillPackageValidator();
        var skill = packages.fromDirectory(Map.of("SKILL.md", ("---\nname: pinned-skill\ndescription: Exact version fixture\n---\nVersion " + id)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var value = version(id, 1L, 10L); value.setCapabilityType("SKILL");
        value.setContentHash(packages.packageSha256(skill));
        value.setDescriptor(SkillPackageDescriptorMapper.descriptor(skill, true, false, mode, null, List.of()));
        return value;
    }

    private CapabilityDefinitionDO definition(Long id, Long tenantId) {
        CapabilityDefinitionDO value = new CapabilityDefinitionDO();
        value.setId(id);
        value.setTenantId(tenantId);
        value.setCapabilityType("TOOL");
        value.setStatus("PUBLISHED");
        value.setDefaultVersionId(2L);
        value.setName("Calendar");
        value.setDescription("Calendar tool");
        value.setTrustLevel("TENANT");
        return value;
    }

    private CapabilityVersionDO version(Long id, Long definitionId, Long tenantId) {
        CapabilityVersionDO value = new CapabilityVersionDO();
        value.setId(id);
        value.setDefinitionId(definitionId);
        value.setTenantId(tenantId);
        value.setCapabilityType("TOOL");
        value.setName("Calendar v1");
        value.setDescription("Calendar tool");
        value.setTrustLevel("TENANT");
        value.setStatus("PUBLISHED");
        value.setContentHash("sha256:test");
        value.setDescriptor(Map.ofEntries(
                Map.entry("model_invocable", true),
                Map.entry("user_invocable", false),
                Map.entry("tool_name", "calendar.query"),
                Map.entry("input_schema", Map.of()),
                Map.entry("output_schema", Map.of()),
                Map.entry("effect", "READ_ONLY"),
                Map.entry("idempotent", true),
                Map.entry("confirmation_policy", "NEVER"),
                Map.entry("timeout_ms", 1_000),
                Map.entry("sensitive_fields", Set.of()),
                Map.entry("provider_requirements", Set.of())));
        return value;
    }
}

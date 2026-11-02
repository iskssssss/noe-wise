package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityDefinitionView;
import com.skillforge.core.capability.CapabilityMetadata;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.capability.InvocationPolicy;
import com.skillforge.core.capability.KnowledgeSourceDescriptor;
import com.skillforge.core.capability.ToolDefinition;
import com.skillforge.core.capability.ToolExecutionPolicy;
import com.skillforge.core.capability.ToolVersion;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper;
import com.skillforge.core.workflow.model.WorkflowInstallationDO;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 安装绑定必须完整、精确，并锁定可执行的租户目标和子图修订。 */
class WorkflowInstallationValidatorTest {

    @Test
    void stageUnavailableNodesCannotBePublishedInstalledOrFrozen() {
        for (String type : List.of("parallel", "subgraph", "agentic")) {
            Map<String, Object> definition = Map.of("nodes", List.of(Map.of("id", "pending", "type", type)));
            var rejected = assertThrows(ApiException.class, () -> validator.validateForExecution(
                    TENANT, 11L, definition, WorkflowInstallationBindings.empty()));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
            org.junit.jupiter.api.Assertions.assertTrue(rejected.getMessage().contains(type));
            org.junit.jupiter.api.Assertions.assertTrue(new WorkflowDraftValidator().validate(definition).stream()
                    .anyMatch(problem -> "WF_NODE_UNAVAILABLE".equals(problem.code())));
        }
        org.mockito.Mockito.verifyNoInteractions(catalog, registry, targets, installations);
    }

    private static final long TENANT = 7L;
    private static final String TOOL_KEY = "public-http-get";
    private static final String KNOWLEDGE_KEY = "knowledge-source-43";
    private static final CapabilityRef TOOL_REF = new CapabilityRef(CapabilityType.TOOL, "42", "100");
    private static final CapabilityRef KNOWLEDGE_REF = new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "43", "101");

    private final CapabilityCatalogService catalog = org.mockito.Mockito.mock(CapabilityCatalogService.class);
    private final CapabilityMountResolver.Registry registry = org.mockito.Mockito.mock(CapabilityMountResolver.Registry.class);
    private final WorkflowInstallationTargetAccess targets = org.mockito.Mockito.mock(WorkflowInstallationTargetAccess.class);
    private final WorkflowInstallationMapper installations = org.mockito.Mockito.mock(WorkflowInstallationMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final WorkflowInstallationValidator validator = new WorkflowInstallationValidator(
            catalog, registry, targets, installations, json);

    @BeforeEach
    void configurePublishedToolVersion() {
        when(catalog.getDefinition(any(), eq(42L))).thenReturn(definition(42L, CapabilityType.TOOL, TOOL_KEY));
        when(catalog.publishedVersions(any(), eq(List.of(100L))))
                .thenReturn(List.of(new CapabilityVersionView(100L, 42L, TENANT, CapabilityType.TOOL,
                        "1.2.0", "tool", "", "TENANT", Map.of(), "hash", "PUBLISHED", null, null)));
    }

    @Test
    void rejectsMissingDuplicateAndWrongTypeCapabilitySlots() {
        Map<String, Object> definition = capabilityDefinition("tool", TOOL_KEY, "*", true);
        assertRejected(definition, WorkflowInstallationBindings.empty());

        var duplicate = toolBinding("search", TOOL_REF, "deployment", 3L);
        assertRejected(definition, new WorkflowInstallationBindings(List.of(duplicate, duplicate), List.of(), List.of()));

        var wrongType = new WorkflowInstallationBindings.CapabilityBinding("search", KNOWLEDGE_REF,
                null, null, 9L);
        assertRejected(definition, new WorkflowInstallationBindings(List.of(wrongType), List.of(), List.of()));
    }

    @Test
    void rejectsVersionsOutsideTheDeclaredConstraint() {
        Map<String, Object> definition = capabilityDefinition("tool", TOOL_KEY, "2.x", true);
        when(registry.find(TENANT, TOOL_REF)).thenReturn(Optional.of(tool(TOOL_REF, Set.of())));

        assertRejected(definition, toolBindings(TOOL_REF, deployment(TENANT, TOOL_REF,
                ToolDeployment.ProviderType.MCP, "mcp-server", 3L, true)));
    }

    @Test
    void rejectsCrossTenantDisabledAndRevisionMismatchedDeployments() {
        Map<String, Object> definition = capabilityDefinition("tool", TOOL_KEY, "*", true);
        when(registry.find(TENANT, TOOL_REF)).thenReturn(Optional.of(tool(TOOL_REF, Set.of())));

        assertRejectedWithDeployment(definition, deployment(8L, TOOL_REF,
                ToolDeployment.ProviderType.MCP, "mcp-server", 3L, true));
        assertRejectedWithDeployment(definition, deployment(TENANT, TOOL_REF,
                ToolDeployment.ProviderType.MCP, "mcp-server", 3L, false));
        assertRejectedWithBindingAndDeployment(definition, toolBinding("search", TOOL_REF, "deployment", 4L),
                deployment(TENANT, TOOL_REF, ToolDeployment.ProviderType.MCP, "mcp-server", 3L, true));
    }

    @Test
    void acceptsOnlyTheProviderRequirementMatchingMcpOrPlatformTarget() {
        Map<String, Object> definition = capabilityDefinition("tool", TOOL_KEY, "*", true);
        ToolDeployment mcp = deployment(TENANT, TOOL_REF, ToolDeployment.ProviderType.MCP, "mcp-server", 3L, true);
        when(registry.find(TENANT, TOOL_REF)).thenReturn(Optional.of(tool(TOOL_REF, Set.of())));
        when(registry.findToolDeployment(TENANT, "deployment")).thenReturn(Optional.of(mcp));

        validator.validate(TENANT, 11L, definition, toolBindings(TOOL_REF, mcp));
        verify(targets).requireProvider(TENANT, mcp);

        ToolDeployment platform = deployment(TENANT, TOOL_REF, ToolDeployment.ProviderType.PLATFORM,
                "get_time", 3L, true);
        when(registry.find(TENANT, TOOL_REF)).thenReturn(Optional.of(tool(TOOL_REF,
                Set.of("platform_handler:get_time"))));
        when(registry.findToolDeployment(TENANT, "deployment")).thenReturn(Optional.of(platform));
        validator.validate(TENANT, 11L, definition, toolBindings(TOOL_REF, platform));

        when(registry.find(TENANT, TOOL_REF)).thenReturn(Optional.of(tool(TOOL_REF,
                Set.of("platform_handler:other"))));
        assertRejected(definition, toolBindings(TOOL_REF, platform));
    }

    @Test
    void requiresKnowledgeToUseAnExactRevisionWithoutDeploymentFields() {
        Map<String, Object> definition = capabilityDefinition("knowledge_source", KNOWLEDGE_KEY, "*", true);
        KnowledgeSourceDescriptor source = knowledge(KNOWLEDGE_REF);
        when(catalog.getDefinition(any(), eq(43L))).thenReturn(definition(43L,
                CapabilityType.KNOWLEDGE_SOURCE, KNOWLEDGE_KEY));
        when(catalog.publishedVersions(any(), eq(List.of(101L))))
                .thenReturn(List.of(new CapabilityVersionView(101L, 43L, TENANT,
                        CapabilityType.KNOWLEDGE_SOURCE, "2026.1", "knowledge", "", "TENANT",
                        Map.of(), "hash", "PUBLISHED", null, null)));
        when(registry.find(TENANT, KNOWLEDGE_REF)).thenReturn(Optional.of(source));
        var exact = new WorkflowInstallationBindings.CapabilityBinding("search", KNOWLEDGE_REF,
                null, null, 9L);

        validator.validate(TENANT, 11L, definition,
                new WorkflowInstallationBindings(List.of(exact), List.of(), List.of()));
        verify(targets).requireKnowledge(TENANT, source, 9L);

        var mixedTarget = new WorkflowInstallationBindings.CapabilityBinding("search", KNOWLEDGE_REF,
                "deployment", 3L, 9L);
        assertRejected(definition, new WorkflowInstallationBindings(List.of(mixedTarget), List.of(), List.of()));
    }

    @Test
    void newExecutionResolvesStableSlotsToCurrentToolAndKnowledgePublications() {
        var currentTool = new CapabilityRef(CapabilityType.TOOL, "42", "110");
        var currentKnowledge = new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "43", "111");
        when(catalog.getCurrentPublishedVersion(any(), eq(42L))).thenReturn(
                new CapabilityVersionView(110L, 42L, TENANT, CapabilityType.TOOL, "2.0", "tool", "",
                        "TENANT", Map.of(), "hash", "PUBLISHED", null, null));
        when(catalog.getCurrentPublishedVersion(any(), eq(43L))).thenReturn(
                new CapabilityVersionView(111L, 43L, TENANT, CapabilityType.KNOWLEDGE_SOURCE, "revision:10", "knowledge", "",
                        "TENANT", Map.of(), "hash", "PUBLISHED", null, null));
        var source = knowledge(currentKnowledge);
        when(registry.find(TENANT, currentTool)).thenReturn(Optional.of(tool(currentTool, Set.of())));
        when(registry.find(TENANT, currentKnowledge)).thenReturn(Optional.of(source));
        when(registry.findToolDeployment(TENANT, "deployment")).thenReturn(Optional.of(
                deployment(TENANT, currentTool, ToolDeployment.ProviderType.MCP, "mcp-server", 4L, true)));
        when(targets.currentKnowledgeRevision(TENANT, source)).thenReturn(10L);
        var configured = new WorkflowInstallationBindings(List.of(
                toolBinding("search", TOOL_REF, "deployment", 3L),
                new WorkflowInstallationBindings.CapabilityBinding("policy", KNOWLEDGE_REF, null, null, 9L)),
                List.of(), List.of());

        var frozen = validator.currentBindings(TENANT, configured);

        assertEquals(currentTool, frozen.capabilities().getFirst().capability());
        assertEquals(4L, frozen.capabilities().getFirst().toolDeploymentRevision());
        assertEquals(currentKnowledge, frozen.capabilities().getLast().capability());
        assertEquals(10L, frozen.capabilities().getLast().knowledgeRevision());
        assertEquals(TOOL_REF, configured.capabilities().getFirst().capability());
        assertEquals(9L, configured.capabilities().getLast().knowledgeRevision());
    }

    @Test
    void rejectsExactDefinitionIdentityWhenItsStableKeyDiffersFromTheDeclaredRequirement() {
        Map<String, Object> definition = capabilityDefinition("tool", "another-public-tool", "*", true);

        assertRejected(definition, toolBindings(TOOL_REF, deployment(TENANT, TOOL_REF,
                ToolDeployment.ProviderType.MCP, "mcp-server", 3L, true)));
    }

    @Test
    void requiresPublishedSubgraphIdentityAndRejectsIndirectCycles() {
        Map<String, Object> definition = subgraphDefinition("child");
        var child = new WorkflowInstallationBindings.WorkflowBinding("child", 22L, 91L, 3L);
        WorkflowInstallationDO stored = installation(22L, 3L, 91L, WorkflowInstallationBindings.empty());
        when(installations.find(TENANT, 22L, 3L)).thenReturn(stored);
        when(installations.publishedTarget(TENANT, 22L, 3L, "child")).thenReturn(false);
        assertRejected(definition, new WorkflowInstallationBindings(List.of(), List.of(child), List.of()));

        var grandchild = new WorkflowInstallationBindings.WorkflowBinding("grand", 33L, 92L, 4L);
        stored.setBindings(bindings(new WorkflowInstallationBindings(List.of(), List.of(grandchild), List.of())));
        WorkflowInstallationDO nested = installation(33L, 4L, 92L,
                new WorkflowInstallationBindings(List.of(), List.of(child), List.of()));
        when(installations.publishedTarget(TENANT, 22L, 3L, "child")).thenReturn(true);
        when(installations.find(TENANT, 33L, 4L)).thenReturn(nested);
        when(installations.publishedTarget(TENANT, 33L, 4L, "grand")).thenReturn(true);
        assertRejected(definition, new WorkflowInstallationBindings(List.of(), List.of(child), List.of()));
    }

    @Test
    void requiresAgentBindingsToExactlyCoverAgenticSlots() {
        Map<String, Object> definition = agentDefinition("planner");
        var agent = new WorkflowInstallationBindings.AgentBinding("planner", "agent-1");
        validator.validate(TENANT, 11L, definition,
                new WorkflowInstallationBindings(List.of(), List.of(), List.of(agent)));
        verify(targets).requireAgent(TENANT, "agent-1");

        assertRejected(definition, WorkflowInstallationBindings.empty());
        assertRejected(definition, new WorkflowInstallationBindings(List.of(), List.of(),
                List.of(new WorkflowInstallationBindings.AgentBinding("other", "agent-1"))));
    }

    private void assertRejectedWithDeployment(Map<String, Object> definition, ToolDeployment deployment) {
        assertRejectedWithBindingAndDeployment(definition, toolBinding("search", TOOL_REF, "deployment", 3L), deployment);
    }

    private void assertRejectedWithBindingAndDeployment(Map<String, Object> definition,
            WorkflowInstallationBindings.CapabilityBinding binding, ToolDeployment deployment) {
        when(registry.findToolDeployment(TENANT, "deployment")).thenReturn(Optional.of(deployment));
        assertRejected(definition, new WorkflowInstallationBindings(List.of(binding), List.of(), List.of()));
    }

    private void assertRejected(Map<String, Object> definition, WorkflowInstallationBindings bindings) {
        ApiException error = assertThrows(ApiException.class,
                () -> validator.validate(TENANT, 11L, definition, bindings));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
    }

    private static WorkflowInstallationBindings toolBindings(CapabilityRef ref, ToolDeployment deployment) {
        return new WorkflowInstallationBindings(List.of(toolBinding("search", ref,
                deployment.deploymentId(), deployment.revision())), List.of(), List.of());
    }

    private static WorkflowInstallationBindings.CapabilityBinding toolBinding(String slot, CapabilityRef ref,
            String deploymentId, long revision) {
        return new WorkflowInstallationBindings.CapabilityBinding(slot, ref, deploymentId, revision, null);
    }

    private static ToolDeployment deployment(long tenant, CapabilityRef ref, ToolDeployment.ProviderType provider,
            String providerRef, long revision, boolean active) {
        return new ToolDeployment("deployment", tenant, ref, provider, providerRef, revision,
                ToolDeployment.Environment.PRODUCTION, active);
    }

    private static ToolVersion tool(CapabilityRef ref, Set<String> providerRequirements) {
        return new ToolVersion(new CapabilityMetadata(ref, TENANT, "tool", "",
                CapabilityMetadata.Trust.TENANT, CapabilityMetadata.Status.PUBLISHED, "hash",
                new InvocationPolicy(true, false)), new ToolDefinition("search", Optional.empty(), "search",
                Map.of("type", "object"), Optional.empty(), List.of(), Optional.empty()),
                new ToolExecutionPolicy(ToolExecutionPolicy.Effect.READ_ONLY, false,
                        ToolExecutionPolicy.ConfirmationPolicy.NEVER, 1_000L, Set.of(), providerRequirements));
    }

    private static KnowledgeSourceDescriptor knowledge(CapabilityRef ref) {
        return new KnowledgeSourceDescriptor(new CapabilityMetadata(ref, TENANT, "knowledge", "",
                CapabilityMetadata.Trust.TENANT, CapabilityMetadata.Status.PUBLISHED, "hash",
                new InvocationPolicy(true, false)), "source-43", Set.of("source-v1"), Set.of(), "active", true);
    }

    private static CapabilityDefinitionView definition(long id, CapabilityType type, String capabilityKey) {
        return new CapabilityDefinitionView(id, TENANT, type, capabilityKey, capabilityKey, "", "TENANT",
                "PUBLISHED", null, 1L, null, null);
    }

    private WorkflowInstallationDO installation(long instanceId, long revision, long definitionVersion,
            WorkflowInstallationBindings bindings) {
        WorkflowInstallationDO value = new WorkflowInstallationDO();
        value.setTenantId(TENANT); value.setInstanceId(instanceId); value.setInstallationRevision(revision);
        value.setDefinitionVersionId(definitionVersion); value.setBindings(bindings(bindings));
        return value;
    }

    private Map<String, Object> bindings(WorkflowInstallationBindings value) {
        return json.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private static Map<String, Object> capabilityDefinition(String type, String capabilityId,
            String versionConstraint, boolean required) {
        return Map.of("nodes", List.of(), "meta", Map.of("capability_requirements", List.of(Map.of(
                "slot", "search", "type", type, "capability_id", capabilityId,
                "version_constraint", versionConstraint, "required", required))));
    }

    private static Map<String, Object> subgraphDefinition(String workflow) {
        return Map.of("nodes", List.of(Map.of("type", "subgraph", "workflow", workflow)), "meta", Map.of());
    }

    private static Map<String, Object> agentDefinition(String slot) {
        return Map.of("nodes", List.of(Map.of("type", "agentic", "agent_slot", slot,
                "result_to", "result", "task", Map.of("objective", "plan", "expected_output", "plan"))),
                "meta", Map.of());
    }
}

package com.skillforge.institution.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityMetadata;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.InvocationPolicy;
import com.skillforge.core.capability.KnowledgeSourceDescriptor;
import com.skillforge.core.capability.ToolDefinition;
import com.skillforge.core.capability.ToolExecutionPolicy;
import com.skillforge.core.capability.ToolVersion;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import com.skillforge.core.workflow.WorkflowInstallationValidator;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 工作流安装 binding 在 Run 准入时冻结其能力槽和子图闭包。 */
class AgentRuntimeWorkflowBindingPublisherTest {

    private static final TenantId TENANT = new TenantId("1");
    private static final long TENANT_ID = 1L;

    @Test
    void repeatedAdmissionKeepsThePreviouslyFrozenDependencyClosure() throws Exception {
        Fixture fixture = new Fixture();
        var frozen = fixture.binding(fixture.rootTarget, WorkflowInstallationBindings.empty());
        when(fixture.workflows.find(TENANT, fixture.rootTarget)).thenReturn(Optional.of(frozen));

        assertThat(fixture.publisher.publish(TENANT, fixture.rootTarget)).isSameAs(frozen);

        verify(fixture.workflows, never()).publish(eq(TENANT), any(), any(), any());
        verify(fixture.validator, never()).currentBindings(eq(TENANT_ID), any());
    }

    @Test
    void freezesExactToolAndKnowledgeSlotsWithoutRewritingModelInvocable() throws Exception {
        Fixture fixture = new Fixture();
        WorkflowInstallationBindings selected = new WorkflowInstallationBindings(List.of(
                new WorkflowInstallationBindings.CapabilityBinding("search", fixture.toolRef,
                        "deployment-7", 7L, null),
                new WorkflowInstallationBindings.CapabilityBinding("policy", fixture.knowledgeRef,
                        null, null, 5L)), List.of(), List.of());
        JdbcWorkflowBindings.Binding root = fixture.binding(fixture.rootTarget, selected);
        AtomicReference<String> frozen = fixture.publish(fixture.rootTarget, root);
        when(fixture.registry.find(TENANT_ID, fixture.toolRef)).thenReturn(Optional.of(fixture.tool));
        when(fixture.registry.find(TENANT_ID, fixture.knowledgeRef)).thenReturn(Optional.of(fixture.knowledgeSource));
        when(fixture.registry.findToolDeployment(TENANT_ID, "deployment-7"))
                .thenReturn(Optional.of(fixture.activeDeployment));

        fixture.publisher.publish(TENANT, fixture.rootTarget);

        JsonNode bindings = fixture.json.readTree(frozen.get());
        assertThat(bindings.at("/tools/0/slot").asText()).isEqualTo("search");
        assertThat(bindings.at("/tools/0/target/handle/value").asText())
                .isEqualTo("tool:workflow:100:9:search");
        assertThat(bindings.at("/tools/0/target/capability/capabilityId").asText())
                .isEqualTo("tool-definition");
        assertThat(bindings.at("/tools/0/target/capability/versionId").asText())
                .isEqualTo("tool-version");
        assertThat(bindings.at("/tools/0/target/deploymentId").asText()).isEqualTo("deployment-7");
        assertThat(bindings.at("/tools/0/target/deploymentRevision").asLong()).isEqualTo(7L);
        assertThat(bindings.at("/tools/0/target/modelInvocable").asBoolean()).isFalse();
        assertThat(bindings.at("/tools/0/binding/providerType").asText()).isEqualTo("MCP");
        assertThat(bindings.at("/tools/0/binding/providerRef").asText()).isEqualTo("server-a");
        assertThat(bindings.at("/tools/0/binding/toolName").asText()).isEqualTo("search");
        assertThat(bindings.at("/knowledge/0/slot").asText()).isEqualTo("policy");
        assertThat(bindings.at("/knowledge/0/target/handle/value").asText())
                .isEqualTo("knowledge:workflow:100:9:policy");
        assertThat(bindings.at("/knowledge/0/target/descriptorId").asText()).isEqualTo("knowledge-source");
        assertThat(bindings.at("/knowledge/0/target/descriptorRevision").asLong()).isEqualTo(5L);
        assertThat(bindings.at("/knowledge/0/target/capability/capabilityId").asText())
                .isEqualTo("knowledge-definition");
        assertThat(bindings.at("/knowledge/0/target/capability/versionId").asText())
                .isEqualTo("knowledge-version");
        assertThat(bindings.at("/knowledge/0/target/sourceVersions/0/sourceId").asText())
                .isEqualTo("knowledge-source");
        assertThat(bindings.at("/knowledge/0/target/sourceVersions/0/sourceVersion").asText())
                .isEqualTo("revision-3");
        assertThat(bindings.at("/knowledge/0/target/allowedOperators/0").asText()).isEqualTo("VECTOR");
        verify(fixture.tools).publish(eq(TENANT), any(),
                eq(new JdbcMcpBindings.Profile("search", JdbcMcpBindings.PROTOCOL_VERSION)));
        verify(fixture.knowledge).publish(eq(TENANT), any());
    }

    @Test
    void publishesTheFrozenSubgraphClosure() throws Exception {
        Fixture fixture = new Fixture();
        WorkflowInstallationBindings.WorkflowBinding child =
                new WorkflowInstallationBindings.WorkflowBinding("child", 200L, 4L, 3L);
        JdbcWorkflowBindings.Binding root = fixture.binding(fixture.rootTarget,
                new WorkflowInstallationBindings(List.of(), List.of(child), List.of()));
        FrozenWorkflowTarget childTarget = new FrozenWorkflowTarget(new WorkflowHandle("workflow:root:child:200"),
                "200", 4L, fixture.workflowRuntimeRef("child-capability", "child-version"),
                Duration.ofSeconds(15), 3L);
        JdbcWorkflowBindings.Binding childBinding = fixture.binding(childTarget,
                WorkflowInstallationBindings.empty());
        AtomicReference<String> frozenRoot = fixture.publish(fixture.rootTarget, root);
        fixture.publish(childTarget, childBinding);
        when(fixture.validator.target(TENANT_ID, child)).thenReturn(childTarget);

        fixture.publisher.publish(TENANT, fixture.rootTarget);

        JsonNode bindings = fixture.json.readTree(frozenRoot.get());
        assertThat(bindings.at("/workflows/0/workflow").asText()).isEqualTo("child");
        assertThat(bindings.at("/workflows/0/target/handle/value").asText())
                .isEqualTo("workflow:root:child:200");
        verify(fixture.workflows).publish(eq(TENANT), eq(fixture.rootTarget), any(), any());
        verify(fixture.workflows).publish(eq(TENANT), eq(childTarget), any(), any());
    }

    @Test
    void rejectsSubgraphCyclesBeforePublishingTheSameTargetAgain() throws Exception {
        Fixture fixture = new Fixture();
        WorkflowInstallationBindings.WorkflowBinding child =
                new WorkflowInstallationBindings.WorkflowBinding("loop", 100L, 5L, 9L);
        JdbcWorkflowBindings.Binding root = fixture.binding(fixture.rootTarget,
                new WorkflowInstallationBindings(List.of(), List.of(child), List.of()));
        fixture.publish(fixture.rootTarget, root);
        when(fixture.validator.target(TENANT_ID, child)).thenReturn(fixture.rootTarget);

        assertThatThrownBy(() -> fixture.publisher.publish(TENANT, fixture.rootTarget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workflow_subgraph_cycle");
        verify(fixture.workflows).publish(eq(TENANT), eq(fixture.rootTarget), any(), any());
    }

    @Test
    void rejectsDisabledToolDeploymentBeforePublishingItsMcpBinding() throws Exception {
        Fixture fixture = new Fixture();
        WorkflowInstallationBindings selected = new WorkflowInstallationBindings(List.of(
                new WorkflowInstallationBindings.CapabilityBinding("search", fixture.toolRef,
                        "deployment-7", 7L, null)), List.of(), List.of());
        JdbcWorkflowBindings.Binding root = fixture.binding(fixture.rootTarget, selected);
        fixture.publish(fixture.rootTarget, root);
        when(fixture.registry.find(TENANT_ID, fixture.toolRef)).thenReturn(Optional.of(fixture.tool));
        when(fixture.registry.findToolDeployment(TENANT_ID, "deployment-7"))
                .thenReturn(Optional.of(fixture.disabledDeployment));

        assertThatThrownBy(() -> fixture.publisher.publish(TENANT, fixture.rootTarget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workflow_tool_deployment_drift");
        verify(fixture.tools, never()).publish(eq(TENANT), any(), any());
    }

    private static final class Fixture {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final JdbcWorkflowBindings workflows = mock(JdbcWorkflowBindings.class);
        private final JdbcMcpBindings tools = mock(JdbcMcpBindings.class);
        private final JdbcKnowledgeBindings knowledge = mock(JdbcKnowledgeBindings.class);
        private final CapabilityMountResolver.Registry registry = mock(CapabilityMountResolver.Registry.class);
        private final WorkflowInstallationValidator validator = mock(WorkflowInstallationValidator.class);
        private final AgentRuntimeWorkflowBindingPublisher publisher =
                new AgentRuntimeWorkflowBindingPublisher(workflows, tools, knowledge, registry, validator, json, org.mockito.Mockito.mock(com.skillforge.institution.runtime.WorkflowTestRuntimePolicy.class));
        private final com.skillforge.core.capability.CapabilityRef toolRef =
                new com.skillforge.core.capability.CapabilityRef(
                        com.skillforge.core.capability.CapabilityType.TOOL,
                        "tool-definition", "tool-version");
        private final com.skillforge.core.capability.CapabilityRef knowledgeRef =
                new com.skillforge.core.capability.CapabilityRef(
                        com.skillforge.core.capability.CapabilityType.KNOWLEDGE_SOURCE,
                        "knowledge-definition", "knowledge-version");
        private final ToolVersion tool = new ToolVersion(new CapabilityMetadata(toolRef, TENANT_ID,
                "search", "Search", CapabilityMetadata.Trust.TENANT,
                CapabilityMetadata.Status.PUBLISHED, "tool-hash", new InvocationPolicy(false, true)),
                new ToolDefinition("search", Optional.empty(), "Search", Map.of("type", "object"),
                        Optional.of(Map.of("type", "object")), List.of(), Optional.empty()),
                new ToolExecutionPolicy(ToolExecutionPolicy.Effect.READ_ONLY, true,
                        ToolExecutionPolicy.ConfirmationPolicy.NEVER, 4_000L, Set.of(), Set.of()));
        private final KnowledgeSourceDescriptor knowledgeSource = new KnowledgeSourceDescriptor(
                new CapabilityMetadata(knowledgeRef, TENANT_ID, "policy", "Policy",
                        CapabilityMetadata.Trust.TENANT, CapabilityMetadata.Status.PUBLISHED,
                        "knowledge-hash", new InvocationPolicy(true, true)),
                "knowledge-source", Set.of("revision-3"), Set.of("VECTOR"), "current", true);
        private final ToolDeployment activeDeployment = deployment(true);
        private final ToolDeployment disabledDeployment = deployment(false);
        private final FrozenWorkflowTarget rootTarget = new FrozenWorkflowTarget(
                new WorkflowHandle("workflow:root"), "100", 5L,
                workflowRuntimeRef("root-capability", "root-version"), Duration.ofSeconds(30), 9L);

        private Fixture() {
            when(validator.currentBindings(eq(TENANT_ID), any())).thenAnswer(call -> call.getArgument(1));
        }

        private AtomicReference<String> publish(FrozenWorkflowTarget target,
                JdbcWorkflowBindings.Binding binding) {
            AtomicReference<String> frozen = new AtomicReference<>();
            when(workflows.publish(eq(TENANT), eq(target), any(), any())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Function<JdbcWorkflowBindings.Binding, String> resolver = invocation.getArgument(2);
                Function<String, String> canonical = invocation.getArgument(3);
                frozen.set(canonical.apply(resolver.apply(binding)));
                return binding;
            });
            return frozen;
        }

        private JdbcWorkflowBindings.Binding binding(FrozenWorkflowTarget target,
                WorkflowInstallationBindings selected) throws Exception {
            return new JdbcWorkflowBindings.Binding(target, "workflow-key", "{\"nodes\":[]}", "{}",
                    Optional.empty(), json.writeValueAsString(selected), "{}", "{}");
        }

        private ToolDeployment deployment(boolean active) {
            return new ToolDeployment("deployment-7", TENANT_ID, toolRef, ToolDeployment.ProviderType.MCP,
                    "server-a", 7L, ToolDeployment.Environment.TEST, active);
        }

        private com.skillforge.runtime.api.CapabilityRef workflowRuntimeRef(String capabilityId,
                String versionId) {
            return new com.skillforge.runtime.api.CapabilityRef(
                    com.skillforge.runtime.api.CapabilityType.WORKFLOW, capabilityId, versionId);
        }
    }
}

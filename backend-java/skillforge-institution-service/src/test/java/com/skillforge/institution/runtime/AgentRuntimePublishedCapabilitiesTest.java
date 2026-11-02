package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.capability.CapabilityMetadata;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.capability.InvocationPolicy;
import com.skillforge.core.capability.ToolDefinition;
import com.skillforge.core.capability.ToolExecutionPolicy;
import com.skillforge.core.capability.ToolVersion;
import com.skillforge.core.capability.tool.ToolCatalog;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.capability.tool.ToolResolver;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.core.agent.integration.runtime.session.JdbcSessionCapabilities;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolHandle;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.spi.SessionCapabilities;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ToolCatalog 唯一投影、原冻结目录与精确执行授权。 */
class AgentRuntimePublishedCapabilitiesTest {

    @Test
    void onlyFrozenPlatformClockQualifiesForCurrentReadAndPreservesOriginalOperation() {
        var fixture = new Fixture();
        var catalog = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor());
        var clock = catalog.entries().stream().filter(entry -> entry.name().equals("get_current_time")).findFirst().orElseThrow();
        var json = new ObjectMapper().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        var version = (ToolVersion) new com.skillforge.core.capability.CapabilityDescriptorCodec(json).decode(fixture.catalogRef,
                null, clock.name(), clock.description(), "PLATFORM", "PUBLISHED", clock.contentHash(), clock.descriptor());
        var binding = new ToolBinding(ToolBinding.ProviderType.PLATFORM, "get_current_time", "get_current_time");
        var deployment = new ToolDeployment("deployment", 1L, fixture.catalogRef,
                ToolDeployment.ProviderType.PLATFORM, "get_current_time", 7, ToolDeployment.Environment.TEST, true);
        fixture.current(9);
        when(fixture.catalog.list(fixture.mountSnapshot)).thenReturn(List.of(new ToolCatalog.Entry(binding.serverIdentity(),
                binding.serverIdentity() + "/get_current_time", new ToolResolver.Resolved(fixture.mount, version, deployment))));
        var entry = fixture.subject.list(fixture.owner, fixture.owner.target().requireAgent()).stream()
                .filter(value -> value.binding().equals(binding)).findFirst().orElseThrow();
        var target = entry.target();
        var arguments = new TypedPayload("application/json", "{}");
        assertThat(fixture.subject.currentRead(entry, arguments)).isTrue();
        var other = new ToolBinding(ToolBinding.ProviderType.MCP, "get_current_time", "get_current_time");
        assertThat(fixture.subject.currentRead(new SessionToolDirectory.Entry(target, other,
                new SessionToolDirectory.Discovery(other.serverIdentity(), other.serverIdentity() + "/get_current_time", "get_current_time", Optional.empty(), "clock")), arguments)).isFalse();
        var namedOther = new ToolBinding(ToolBinding.ProviderType.PLATFORM, "calculate_expression", "calculate_expression");
        assertThat(fixture.subject.currentRead(new SessionToolDirectory.Entry(target, namedOther,
                new SessionToolDirectory.Discovery(namedOther.serverIdentity(), namedOther.serverIdentity() + "/calculate_expression", "calculate_expression", Optional.empty(), "calculation")), arguments)).isFalse();
        var untrusted = new FrozenToolTarget(target.handle(), target.capability(), target.deploymentId(), target.deploymentRevision(),
                target.inputSchema(), target.outputSchema(), ToolEffect.READ_ONLY, true, target.timeout(), true,
                ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
        assertThat(fixture.subject.currentRead(new SessionToolDirectory.Entry(untrusted, binding, entry.discovery()), arguments)).isFalse();
        var nonIdempotent = new FrozenToolTarget(target.handle(), target.capability(), target.deploymentId(), target.deploymentRevision(),
                target.inputSchema(), target.outputSchema(), ToolEffect.READ_ONLY, false, target.timeout(), true,
                ToolConfirmationPolicy.NEVER, Set.of(), target.providerRequirements(), true);
        assertThat(fixture.subject.currentRead(new SessionToolDirectory.Entry(nonIdempotent, binding, entry.discovery()), arguments)).isFalse();

        var loop = new com.skillforge.runtime.core.AgentExecutionLoop(new com.skillforge.runtime.json.CodecLimits(1048576, 100000, 128));
        var loaded = new com.skillforge.runtime.core.AgentExecutionLoop.LoadedTool(
                new com.skillforge.runtime.core.ToolCallRouting.Binding(target, binding, true), "sf_clock");
        var call = new com.skillforge.runtime.core.AgentAction.ToolCall(target.handle().value(), arguments);
        var handler = new com.skillforge.core.capability.connector.GetCurrentTimeToolHandler();
        var result = handler.execute(Map.of(), null);
        assertThat(result.get("success")).isEqualTo(true);
        var source = new com.skillforge.runtime.core.AgentExecutionLoop.ToolSource(
                new com.skillforge.runtime.api.port.ActionRef(fixture.tenant, "clock-action"), "clock-attempt", new ResultRef("clock-request"), new ResultRef("clock-result"));
        var original = new com.skillforge.runtime.core.AgentExecutionLoop.ToolObservation(call, loaded,
                com.skillforge.runtime.core.AgentExecutionLoop.ToolResolution.SUCCEEDED, Optional.of(new ResultRef("clock-feedback")),
                List.of(new com.skillforge.runtime.api.media.ModelContent.Text(result.toString())), source, true, 1, 1);
        var same = loop.resolveTool(call, List.of(loaded), List.of(original), true, 1, 1);
        assertThat(same).isInstanceOf(com.skillforge.runtime.core.AgentExecutionLoop.LoadedToolReuse.class);
        assertThat(((com.skillforge.runtime.core.AgentExecutionLoop.LoadedToolReuse) same).original()).isSameAs(original);
        assertThat(loop.resolveTool(call, List.of(loaded), List.of(original), true, 2, 1))
                .isInstanceOf(com.skillforge.runtime.core.AgentExecutionLoop.LoadedSelected.class);
        assertThat(handler.execute(Map.of(), null)).containsEntry("success", true).containsKey("epoch_millis");
        var old = new com.skillforge.runtime.core.AgentExecutionLoop.ToolObservation(call, loaded,
                com.skillforge.runtime.core.AgentExecutionLoop.ToolResolution.SUCCEEDED, Optional.of(new ResultRef("old-feedback")),
                List.of(), source, false, 1, 1);
        assertThat(loop.resolveTool(call, List.of(loaded), List.of(old), true, 2, 1))
                .isInstanceOf(com.skillforge.runtime.core.AgentExecutionLoop.LoadedToolReuse.class);
        var unknown = new com.skillforge.runtime.core.AgentExecutionLoop.ToolObservation(call, loaded,
                com.skillforge.runtime.core.AgentExecutionLoop.ToolResolution.UNKNOWN, Optional.empty(), List.of(), source, true, 1, 1);
        assertThat(loop.resolveTool(call, List.of(loaded), List.of(unknown), true, 2, 1))
                .isEqualTo(new com.skillforge.runtime.core.AgentExecutionLoop.LoadedRejected(com.skillforge.runtime.core.AgentExecutionLoop.LoadedRejection.TOOL_RESULT_UNRESOLVED));
    }

    @Test
    void runBoundFrozenDirectoryMapsConfirmedDeploymentInactivityToExecutionRevocation() {
        var fixture = new Fixture();
        var run = fixture.frozenRun("run:revoked-tool", List.of(fixture.mount));
        var registry = mock(CapabilityMountResolver.Registry.class);
        when(registry.find(1L, fixture.catalogRef)).thenReturn(Optional.of(fixture.version));
        when(registry.findToolDeployment(1L, "deployment")).thenReturn(Optional.of(
                new ToolDeployment("deployment", 1L, fixture.catalogRef, ToolDeployment.ProviderType.MCP,
                        "server-a", 8, ToolDeployment.Environment.TEST, false)));
        var resolver = new CapabilityMountResolver(registry,
                java.time.Clock.fixed(Fixture.NOW, java.time.ZoneOffset.UTC));
        when(fixture.mountResolver.restore(any(CapabilityMountSnapshot.class)))
                .thenAnswer(invocation -> resolver.restore(invocation.getArgument(0)));

        assertThatThrownBy(() -> fixture.subject.list(run))
                .isInstanceOf(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class)
                .hasMessage("tool_deployment_revoked")
                .hasCauseInstanceOf(CapabilityMountResolver.InactiveToolDeploymentException.class);
        verify(registry).findToolDeployment(1L, "deployment");
        verifyNoInteractions(fixture.mounts, fixture.catalog, fixture.capabilities, fixture.tools);
    }

    @ParameterizedTest
    @ValueSource(strings = {"storage", "ordinary", "same_text", "security"})
    void runBoundRestoreDoesNotTranslateUnconfirmedFailuresIntoRevocation(String variant) {
        var fixture = new Fixture();
        var run = fixture.frozenRun("run:ordinary-failure", List.of(fixture.mount));
        RuntimeException failure = switch (variant) {
            case "storage" -> new IllegalStateException("storage_unavailable", new java.sql.SQLException("offline"));
            case "same_text" -> new IllegalArgumentException("tool_deployment_inactive");
            case "security" -> new SecurityException("tool_deployment_revoked");
            default -> new IllegalArgumentException("tool deployment does not match mounted version");
        };
        when(fixture.mountResolver.restore(any(CapabilityMountSnapshot.class))).thenThrow(failure);

        assertThatThrownBy(() -> fixture.subject.list(run)).isSameAs(failure);
        verifyNoInteractions(fixture.catalog, fixture.capabilities, fixture.tools);
    }

    @Test
    void freezesOwnerTitleForPlatformToolsWithoutGuessingMcpNamesOrReplacingPublishedTitles() {
        for (var provider : List.of(ToolBinding.ProviderType.PLATFORM, ToolBinding.ProviderType.MCP)) {
            for (var declared : List.of(Optional.<String>empty(), Optional.of("已经发布的业务标题"))) {
                var fixture = new Fixture();
                fixture.current(9);
                String name = "list_artifacts";
                var definition = new ToolDefinition(name, declared, "读取当前会话的成果",
                        fixture.inputSchema, Optional.of(fixture.outputSchema), List.of(), Optional.empty());
                var version = new ToolVersion(fixture.version.metadata(), definition, fixture.version.policy());
                var binding = new ToolBinding(provider, name, name);
                var deployment = new ToolDeployment("deployment", 1L, fixture.catalogRef,
                        ToolDeployment.ProviderType.valueOf(provider.name()), name, 7, ToolDeployment.Environment.TEST, true);
                var entry = new ToolCatalog.Entry(binding.serverIdentity(), binding.serverIdentity() + "/" + name,
                        new ToolResolver.Resolved(fixture.mount, version, deployment));
                when(fixture.catalog.list(fixture.mountSnapshot)).thenReturn(List.of(entry));
                when(fixture.tools.find(any(), any())).thenAnswer(invocation -> Optional.of(new JdbcMcpBindings.Binding(
                        invocation.getArgument(1), "http://localhost/mcp", Optional.empty(), name,
                        JdbcMcpBindings.PROTOCOL_VERSION, name)));

                var frozen = fixture.subject.list(fixture.owner, fixture.owner.target().requireAgent()).getFirst();
                assertThat(frozen.discovery().title()).isEqualTo(declared.isPresent() ? declared
                        : provider == ToolBinding.ProviderType.PLATFORM ? Optional.of("查找已有成果") : Optional.empty());
            }
        }
    }

    @Test
    void projectsCatalogEntriesOnceAndPreservesDiscoveryTimeMcpPublication() {
        var fixture = new Fixture();
        fixture.current(9);
        when(fixture.tools.find(fixture.tenant, fixture.runtimeTarget))
                .thenReturn(Optional.empty(), Optional.of(fixture.binding));

        var targets = fixture.subject.resolve(fixture.owner, fixture.owner.target().requireAgent());
        var tools = fixture.subject.list(fixture.owner, fixture.owner.target().requireAgent());

        assertThat(targets.generation()).isEqualTo(9);
        assertDirectoryContainsMountedToolAndHostOutputControls(fixture, tools);
        assertThat(targets.workflows()).isEmpty();
        assertThat(targets.knowledge()).isEmpty();
        verify(fixture.catalog).list(fixture.mountSnapshot);
        verify(fixture.catalog, never()).resolve(any(), any());
        verify(fixture.capabilities).catalog(fixture.owner, fixture.owner.target().requireAgent());
        verify(fixture.tools).publish(fixture.tenant, fixture.runtimeTarget, fixture.profile);
        verify(fixture.tools, times(2)).find(fixture.tenant, fixture.runtimeTarget);
    }

    @Test
    void runDirectoryAndExactExecutionShareProjectionWithoutJdbcDirectoryOrRepeatedResolution() {
        var fixture = new Fixture();
        var run = fixture.frozenRun("run:shared", List.of(fixture.mount));
        var expected = fixture.scopedTool("tool:101:input:run:shared");
        assertThat(fixture.subject.list(run)).contains(expected);
        var authorized = fixture.subject.requireCurrent(run, expected.target());
        assertThat(authorized).isEqualTo(expected);
        assertThat(authorized.target()).isSameAs(expected.target());
        verify(fixture.catalog).list(any());
        verify(fixture.catalog).resolve(any(), org.mockito.ArgumentMatchers.eq(fixture.mount.handle()));
        verifyNoInteractions(fixture.capabilities, fixture.mounts);
        verify(fixture.tools, never()).publish(any(), any(), any());
        verify(fixture.studioRuns, times(2)).authorize(any());
    }

    @Test
    void catalogProjectionPreservesOriginalDeploymentAndAllExecutionPolicyFields() {
        var fixture = new Fixture();
        var policy = new ToolExecutionPolicy(ToolExecutionPolicy.Effect.READ_ONLY, true,
                ToolExecutionPolicy.ConfirmationPolicy.NEVER, 4_567, Set.of("private"),
                Set.of("platform_handler:search"), true);
        var definition = new ToolDefinition("search", Optional.of("Exact tool"), "Exact policy",
                fixture.inputSchema, Optional.empty(), List.of(), Optional.empty());
        var version = new ToolVersion(fixture.version.metadata(), definition, policy);
        var deployment = new ToolDeployment("deployment", 1L, fixture.catalogRef,
                ToolDeployment.ProviderType.PLATFORM, "search", 7, ToolDeployment.Environment.TEST, true);
        var entry = new ToolCatalog.Entry("platform:search", "platform:search/search",
                new ToolResolver.Resolved(fixture.mount, version, deployment));
        when(fixture.catalog.list(any())).thenReturn(List.of(entry));
        when(fixture.catalog.resolve(any(), any())).thenReturn(Optional.of(entry));
        var run = fixture.frozenRun("run:policy", List.of(fixture.mount));
        var projected = fixture.subject.list(run).getFirst();
        var target = projected.target();
        assertThat(target.handle().value()).isEqualTo("tool:101:input:run:policy");
        assertThat(target.capability()).isEqualTo(fixture.runtimeRef);
        assertThat(target.deploymentId()).isEqualTo("deployment");
        assertThat(target.deploymentRevision()).isEqualTo(((CapabilityMount.ToolMountConfig) fixture.mount.config()).toolDeploymentRevision());
        assertThat(target.effect()).isEqualTo(ToolEffect.READ_ONLY);
        assertThat(target.idempotent()).isTrue();
        assertThat(target.timeout()).isEqualTo(Duration.ofMillis(4_567));
        assertThat(target.modelInvocable()).isTrue();
        assertThat(target.confirmation()).isEqualTo(ToolConfirmationPolicy.NEVER);
        assertThat(target.sensitiveFields()).containsExactly("private");
        assertThat(target.providerRequirements()).containsExactly("platform_handler:search");
        assertThat(target.outputSchemaDeclared()).isFalse();
        assertThat(target.outputSchema()).isEqualTo(new TypedPayload("json-schema", "{}"));
        assertThat(projected.parallelSafe()).isTrue();
        assertThat(fixture.subject.requireCurrent(run, target)).isEqualTo(projected);
        verifyNoInteractions(fixture.capabilities, fixture.tools);
    }

    @Test
    void exactExecutionRestoresOnlySelectedMountWhileFullDirectoryRejectsAnyInactiveDeployment() {
        var fixture = new Fixture();
        var inactive = new CapabilityMount("102", 1L, 2L, fixture.catalogRef,
                new CapabilityMount.ToolMountConfig("inactive", 7), 2, 3, true);
        var run = fixture.frozenRun("run:two", List.of(fixture.mount, inactive));
        var registry = mock(CapabilityMountResolver.Registry.class);
        when(registry.find(1L, fixture.catalogRef)).thenReturn(Optional.of(fixture.version));
        when(registry.findToolDeployment(1L, "deployment")).thenReturn(Optional.of(fixture.deployment));
        when(registry.findToolDeployment(1L, "inactive")).thenReturn(Optional.of(new ToolDeployment(
                "inactive", 1L, fixture.catalogRef, ToolDeployment.ProviderType.MCP, "server-a", 7,
                ToolDeployment.Environment.TEST, false)));
        var resolver = new CapabilityMountResolver(registry, java.time.Clock.fixed(Fixture.NOW, java.time.ZoneOffset.UTC));
        when(fixture.mountResolver.restore(any())).thenAnswer(invocation -> resolver.restore(invocation.getArgument(0)));
        var selected = fixture.scopedTool("tool:101:input:run:two").target();
        assertThat(fixture.subject.requireCurrent(run, selected).target()).isSameAs(selected);
        assertThatThrownBy(() -> fixture.subject.list(run))
                .isInstanceOf(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class)
                .hasCauseInstanceOf(CapabilityMountResolver.InactiveToolDeploymentException.class);
        var unavailable = new FrozenToolTarget(new ToolHandle("tool:102:input:run:two"), selected.capability(),
                "inactive", 7, selected.inputSchema(), selected.outputSchema(), selected.effect(), selected.idempotent(),
                selected.timeout(), selected.modelInvocable(), selected.confirmation(), selected.sensitiveFields(),
                selected.providerRequirements(), selected.outputSchemaDeclared());
        assertThatThrownBy(() -> fixture.subject.requireCurrent(run, unavailable))
                .isInstanceOf(com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException.class)
                .hasCauseInstanceOf(CapabilityMountResolver.InactiveToolDeploymentException.class);
        verify(fixture.catalog).resolve(any(), org.mockito.ArgumentMatchers.eq(fixture.mount.handle()));
        verify(fixture.catalog, never()).list(any());
        verify(fixture.catalog, never()).resolve(any(), org.mockito.ArgumentMatchers.eq(inactive.handle()));
        verifyNoInteractions(fixture.capabilities, fixture.mounts);
    }

    @Test
    void exactMcpExecutionKeepsOriginalSchemaBytesAndNeverPublishesMissingOrChangedBinding() throws Exception {
        for (String change : List.of("none", "missing", "provider", "tool")) {
            var fixture = new Fixture();
            var run = fixture.frozenRun("run:mcp", List.of(fixture.mount));
            var target = fixture.scopedTool("tool:101:input:run:mcp").target();
            var original = withSchemas(target, reordered(target.inputSchema()), reordered(target.outputSchema()));
            var binding = new JdbcMcpBindings.Binding(original, "http://localhost/frozen", Optional.empty(),
                    change.equals("tool") ? "another-tool" : "search", JdbcMcpBindings.PROTOCOL_VERSION,
                    change.equals("provider") ? "another-provider" : "server-a");
            when(fixture.tools.find(fixture.tenant, original)).thenReturn(change.equals("missing")
                    ? Optional.empty() : Optional.of(binding));
            if (change.equals("none")) assertThat(fixture.subject.requireCurrent(run, original).target()).isSameAs(original);
            else assertThatThrownBy(() -> fixture.subject.requireCurrent(run, original))
                    .isInstanceOf(IllegalStateException.class).hasMessage(change.equals("missing")
                            ? "agent_runtime_tool_binding_missing" : "agent_runtime_tool_binding_mismatch");
            verify(fixture.tools).find(fixture.tenant, original);
            verify(fixture.tools, never()).find(fixture.tenant, target);
            verify(fixture.tools, never()).publish(any(), any(), any());
            verify(fixture.catalog).resolve(any(), org.mockito.ArgumentMatchers.eq(fixture.mount.handle()));
            verify(fixture.catalog, never()).list(any());
            verifyNoInteractions(fixture.capabilities, fixture.mounts);
        }
    }

    @Test
    void exactExecutionCannotUseHiddenOrHostShadowedBusinessTools() {
        for (String name : List.of("submit_team_plan", "submit_task_result", "finalize_task_outputs")) {
            var fixture = new Fixture();
            var run = fixture.frozenRun("run:hidden", List.of(fixture.mount));
            var definition = new ToolDefinition(name, Optional.empty(), "Hidden business tool", fixture.inputSchema,
                    Optional.of(fixture.outputSchema), List.of(), Optional.empty());
            var version = new ToolVersion(fixture.version.metadata(), definition, fixture.version.policy());
            when(fixture.catalog.resolve(any(), any())).thenReturn(Optional.of(new ToolCatalog.Entry(
                    "mcp:server-a", "mcp:server-a/" + name, new ToolResolver.Resolved(fixture.mount, version, fixture.deployment))));
            assertThatThrownBy(() -> fixture.subject.requireCurrent(run,
                    fixture.scopedTool("tool:101:input:run:hidden").target()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("studio_tool_no_longer_authorized");
            verifyNoInteractions(fixture.tools, fixture.capabilities);
        }
    }

    @Test
    void exactExecutionRequiresOriginalOwnerActorAndCurrentIamBeforeCatalogReads() {
        for (String change : List.of("owner", "actor", "iam")) {
            var fixture = new Fixture();
            var run = fixture.frozenRun("run:identity", List.of(fixture.mount));
            var binding = fixture.studioRuns.find(run.runRef()).orElseThrow();
            if (change.equals("owner")) when(binding.owner()).thenReturn(SessionOwner.user(fixture.owner.sessionRef(),
                    fixture.owner.target(), new EndUserId(99)));
            if (change.equals("actor")) when(binding.actor()).thenReturn(new AgentId("99"));
            if (change.equals("iam")) org.mockito.Mockito.doThrow(new SecurityException("revoked"))
                    .when(fixture.studioRuns).authorize(binding);
            assertThatThrownBy(() -> fixture.subject.requireCurrent(run,
                    fixture.scopedTool("tool:101:input:run:identity").target())).isInstanceOf(RuntimeException.class);
            verifyNoInteractions(fixture.mountResolver, fixture.catalog, fixture.tools, fixture.capabilities);
        }
    }

    @Test
    void exactExecutionRejectsUnknownAliasesOtherInputAndDuplicateOriginalMountsBeforeResolution() {
        var fixture = new Fixture();
        var run = fixture.frozenRun("run:exact", List.of(fixture.mount));
        for (String handle : List.of("tool:101", "tool:999:input:run:exact", "tool:101:input:another"))
            assertThatThrownBy(() -> fixture.subject.requireCurrent(run, fixture.scopedTool(handle).target()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("studio_tool_no_longer_authorized");
        var duplicate = fixture.frozenRun("run:duplicate", List.of(fixture.mount, fixture.mount));
        assertThatThrownBy(() -> fixture.subject.requireCurrent(duplicate,
                fixture.scopedTool("tool:101:input:run:duplicate").target()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("studio_tool_no_longer_authorized");
        verifyNoInteractions(fixture.mountResolver, fixture.catalog, fixture.tools, fixture.capabilities);
    }

    @Test
    void ignoresNonToolMountsWhenBuildingTheCurrentToolDirectory() {
        var fixture = new Fixture();
        var invalidWorkflow = new CapabilityMount("broken-workflow", 1L, 2L,
                new com.skillforge.core.capability.CapabilityRef(
                        com.skillforge.core.capability.CapabilityType.WORKFLOW,
                        "missing-workflow", "missing-version"),
                new CapabilityMount.WorkflowMountConfig("missing-instance", 1L, 1L),
                2, 3, true);
        fixture.current(9, List.of(invalidWorkflow, fixture.mount));

        assertDirectoryContainsMountedToolAndHostOutputControls(fixture,
                fixture.subject.list(fixture.owner, fixture.owner.target().requireAgent()));

        verify(fixture.mountResolver).freeze(fixture.mountSnapshot.snapshotId(), "tool-directory:9",
                1L, 2L, 9, List.of(fixture.mount));
    }

    @Test
    void publishesWorkflowBindingsThroughTheWorkflowPublisherBeforeReadingThem() {
        var fixture = new Fixture();
        fixture.current(9);
        when(fixture.capabilities.catalog(fixture.owner, fixture.owner.target().requireAgent())).thenReturn(
                new JdbcSessionCapabilities.Catalog(9, List.of(fixture.workflowTarget), List.of()));
        when(fixture.workflows.find(fixture.tenant, fixture.workflowTarget))
                .thenReturn(Optional.of(mock(JdbcWorkflowBindings.Binding.class)));

        var targets = fixture.subject.resolve(fixture.owner, fixture.owner.target().requireAgent());

        assertThat(targets.workflows()).containsExactly(fixture.workflowTarget);
        verify(fixture.workflowPublisher).publish(fixture.tenant, fixture.workflowTarget);
        verify(fixture.workflows).find(fixture.tenant, fixture.workflowTarget);
    }

    @Test
    void publishesWorkflowTargetsForTheActualAdmissionInputInsteadOfASyntheticRunInput() {
        var fixture = new Fixture();
        var input = new InputId("client-input");
        var run = new com.skillforge.runtime.api.RunRef(fixture.tenant, "run:client-input");
        var target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:101:" + input.value()),
                fixture.workflowTarget.instanceId(), fixture.workflowTarget.instanceRevision(),
                fixture.workflowTarget.capability(), fixture.workflowTarget.timeout(),
                fixture.workflowTarget.installationRevision());
        when(fixture.capabilities.catalog(fixture.owner, fixture.owner.target().requireAgent(), input))
                .thenReturn(new JdbcSessionCapabilities.Catalog(9, List.of(target), List.of()));
        when(fixture.workflows.find(fixture.tenant, target))
                .thenReturn(Optional.of(mock(JdbcWorkflowBindings.Binding.class)));

        var targets = fixture.subject.resolve(fixture.owner, fixture.owner.target().requireAgent(), run, input);

        assertThat(targets.workflows()).containsExactly(target);
        assertThat(targets.workflows().getFirst().handle().value()).endsWith(":" + input.value());
        verify(fixture.workflowPublisher).publish(fixture.tenant, target);
        verify(fixture.capabilities, never()).catalog(fixture.owner, fixture.owner.target().requireAgent(),
                new InputId("input:" + run.runId()));
    }

    @Test
    void frozenMountModelIdentityStaysStableAcrossRunsAndKeepsExactTargetsUntouched() {
        var fixture = new Fixture();
        var firstRun = fixture.frozenRun("run:first", List.of(fixture.mount));
        var secondRun = fixture.frozenRun("run:second", List.of(fixture.mount));
        var first = fixture.scopedTool("tool:101:input:run:first");
        var second = fixture.scopedTool("tool:101:input:run:second");

        assertThat(fixture.subject.modelIdentities(firstRun, List.of(first)))
                .containsExactlyEntriesOf(Map.of("tool:101:input:run:first", "tool:101"));
        assertThat(fixture.subject.modelIdentities(secondRun, List.of(second)))
                .containsExactlyEntriesOf(Map.of("tool:101:input:run:second", "tool:101"));
        assertThat(first.target().handle().value()).isEqualTo("tool:101:input:run:first");
        assertThat(second.target().handle().value()).isEqualTo("tool:101:input:run:second");
        verifyNoInteractions(fixture.mounts, fixture.mountResolver, fixture.capabilities, fixture.tools);
    }

    @Test
    void sameNameMountsHaveUniqueStableIdentitiesAndHostControlsUseTheirDeclaredIdentity() {
        var fixture = new Fixture();
        var secondMount = new CapabilityMount("102", 1L, 2L, fixture.catalogRef,
                fixture.mount.config(), 2, 3, true);
        var run = fixture.frozenRun("run:two-mounts", List.of(fixture.mount, secondMount));
        var first = fixture.scopedTool("tool:101:input:run:two-mounts");
        var second = fixture.scopedTool("tool:102:input:run:two-mounts");
        var entries = OutputControlTools.append(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()), List.of(first, second));

        var identities = fixture.subject.modelIdentities(run, entries);

        assertThat(first.discovery().qualifiedName()).isEqualTo(second.discovery().qualifiedName());
        assertThat(identities).containsExactlyInAnyOrderEntriesOf(Map.of(
                "tool:101:input:run:two-mounts", "tool:101",
                "tool:102:input:run:two-mounts", "tool:102",
                "tool:host-output:finalize_task_outputs", "tool:host-output:finalize_task_outputs"));
        assertThat(identities.values()).doesNotHaveDuplicates();
    }

    @Test
    void modelIdentityRejectsOtherInputsUnknownHandlesAndChangedMountBindings() {
        var fixture = new Fixture();
        var run = fixture.frozenRun("run:original", List.of(fixture.mount));
        var original = fixture.scopedTool("tool:101:input:run:original");
        var changedDeployment = new FrozenToolTarget(original.target().handle(), original.target().capability(),
                original.target().deploymentId(), 8, original.target().inputSchema(), original.target().outputSchema(),
                original.target().effect(), original.target().idempotent(), original.target().timeout(),
                original.target().modelInvocable(), original.target().confirmation(), original.target().sensitiveFields(),
                original.target().providerRequirements(), original.target().outputSchemaDeclared());
        var changedCapability = new FrozenToolTarget(original.target().handle(),
                new CapabilityRef(CapabilityType.TOOL, "tool-capability", "v2"),
                original.target().deploymentId(), original.target().deploymentRevision(),
                original.target().inputSchema(), original.target().outputSchema(), original.target().effect(),
                original.target().idempotent(), original.target().timeout(), original.target().modelInvocable(),
                original.target().confirmation(), original.target().sensitiveFields(),
                original.target().providerRequirements(), original.target().outputSchemaDeclared());

        for (var entry : List.of(fixture.scopedTool("tool:101:input:run:other"),
                fixture.scopedTool("tool:999:input:run:original"),
                new SessionToolDirectory.Entry(changedDeployment, fixture.toolBinding, fixture.discovery),
                new SessionToolDirectory.Entry(changedCapability, fixture.toolBinding, fixture.discovery))) {
            assertThatThrownBy(() -> fixture.subject.modelIdentities(run, List.of(entry)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("agent_runtime_tool_model_identity_rejected");
        }
    }

    @Test
    void modelIdentityRejectsDuplicateMountsAndCannotUseStableAliasAsExecutionHandle() {
        var fixture = new Fixture();
        var run = fixture.frozenRun("run:original", List.of(fixture.mount));
        assertThatThrownBy(() -> fixture.subject.modelIdentities(run, List.of(fixture.scopedTool("tool:101"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("agent_runtime_tool_model_identity_rejected");
        var duplicateRun = fixture.frozenRun("run:duplicate", List.of(fixture.mount, fixture.mount));
        assertThatThrownBy(() -> fixture.subject.modelIdentities(duplicateRun,
                List.of(fixture.scopedTool("tool:101:input:run:duplicate"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("agent_runtime_tool_model_identity_rejected");
    }

    /** 跨进程只改变对象键序时重新授权原目标；后续模型身份检查也不得替换原冻结字节。 */
    @Test
    void reorderedHostControlSchemasKeepOriginalTargetThroughAuthorizationAndModelIdentity() throws Exception {
        var fixture=new Fixture();
        var run=fixture.outputOnlyRun();
        for(var current:OutputControlTools.append(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()), List.of())) {
            var frozen=withSchemas(current.target(),reordered(current.target().inputSchema()),reordered(current.target().outputSchema()));
            assertThat(frozen).isNotEqualTo(current.target());
            var authorized=fixture.subject.requireCurrent(run,frozen);
            assertThat(authorized.target()).isSameAs(frozen);
            assertThat(authorized.binding()).isEqualTo(current.binding());
            assertThat(authorized.discovery()).isEqualTo(current.discovery());
            assertThat(fixture.subject.modelIdentities(run,List.of(authorized)))
                    .containsExactlyEntriesOf(Map.of(frozen.handle().value(),frozen.handle().value()));
        }
        verify(fixture.studioRuns,org.mockito.Mockito.atLeastOnce()).authorize(any());
        verifyNoInteractions(fixture.mounts,fixture.tools);
    }

    /** 仅对象键序可忽略，所有发布身份与执行策略仍是不可变授权事实。 */
    @ParameterizedTest
    @ValueSource(strings={"handle","capability","version","deployment","revision","effect","idempotent",
            "timeout","modelInvocable","confirmation","sensitiveFields","providerRequirements","outputSchemaDeclared"})
    void hostControlAuthorizationRejectsEveryExecutionFieldDrift(String field) {
        var fixture=new Fixture();
        var run=fixture.outputOnlyRun();
        var current=OutputControlTools.append(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()), List.of()).getFirst();
        var target=current.target();
        var changed=new FrozenToolTarget(
                "handle".equals(field)?new ToolHandle("tool:host-output:other"):target.handle(),
                "capability".equals(field)?new CapabilityRef(CapabilityType.TOOL,"other",target.capability().versionId())
                        :"version".equals(field)?new CapabilityRef(CapabilityType.TOOL,target.capability().capabilityId(),"other-version"):target.capability(),
                "deployment".equals(field)?"other-deployment":target.deploymentId(),
                "revision".equals(field)?target.deploymentRevision()+1:target.deploymentRevision(),
                target.inputSchema(),"outputSchemaDeclared".equals(field)?new TypedPayload("json-schema","{}"):target.outputSchema(),
                "effect".equals(field)?ToolEffect.DESTRUCTIVE:"idempotent".equals(field)?ToolEffect.NON_IDEMPOTENT_WRITE:target.effect(),
                !"idempotent".equals(field)&&target.idempotent(),
                "timeout".equals(field)?target.timeout().plusMillis(1):target.timeout(),
                !"modelInvocable".equals(field)&&target.modelInvocable(),
                "confirmation".equals(field)?ToolConfirmationPolicy.POLICY:target.confirmation(),
                "sensitiveFields".equals(field)?Set.of("private"):target.sensitiveFields(),
                "providerRequirements".equals(field)?Set.of("other-handler"):target.providerRequirements(),
                !"outputSchemaDeclared".equals(field)&&target.outputSchemaDeclared());
        assertThatThrownBy(()->fixture.subject.requireCurrent(run,changed))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("studio_tool_no_longer_authorized");
        var entry=new SessionToolDirectory.Entry(changed,current.binding(),current.discovery(),current.parallelSafe());
        assertThatThrownBy(()->fixture.subject.modelIdentities(run,List.of(entry)))
                .isInstanceOf(IllegalStateException.class).hasMessage("agent_runtime_tool_model_identity_rejected");
    }

    /** 格式值、数组顺序、schemaId 与完整语法均参与比较；宽松解析不能抹掉差异。 */
    @Test
    void hostControlAuthorizationRejectsSchemaMeaningAndMalformedJson() throws Exception {
        var fixture=new Fixture();
        var run=fixture.outputOnlyRun();
        var current=OutputControlTools.append(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()), List.of()).getFirst();
        var original=current.target().inputSchema();
        var json=new ObjectMapper();
        var changed=json.readTree(original.canonicalJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("additionalProperties",true);
        var arrays=json.readTree(original.canonicalJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)arrays.path("properties").path("deliverables").path("items"))
                .putArray("required").add("formats").add("kind").add("title").add("key");
        for(var input:List.of(new TypedPayload("other-schema",original.canonicalJson()),
                new TypedPayload("json-schema",json.writeValueAsString(changed)),
                new TypedPayload("json-schema",json.writeValueAsString(arrays)),
                new TypedPayload("json-schema","{\"type\":\"string\","+original.canonicalJson().substring(1)),
                new TypedPayload("json-schema",original.canonicalJson()+" {}"),
                new TypedPayload("json-schema",original.canonicalJson().substring(0,original.canonicalJson().length()-1)))) {
            var frozen=withSchemas(current.target(),input,current.target().outputSchema());
            assertThatThrownBy(()->fixture.subject.requireCurrent(run,frozen))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("studio_tool_no_longer_authorized");
        }
        var output=json.readTree(current.target().outputSchema().canonicalJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)output.path("properties").path("success")).put("type","string");
        assertThatThrownBy(()->fixture.subject.requireCurrent(run,withSchemas(current.target(),original,
                new TypedPayload("json-schema",json.writeValueAsString(output)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("studio_tool_no_longer_authorized");
    }

    /** 高精度十进制差异不能被 Double 舍入为相同 schema 值。 */
    @Test
    void schemaComparisonPreservesExactDecimalValues() {
        var fixture = new Fixture();
        var run = fixture.frozenRun("run:decimal", List.of(fixture.mount));
        var definition = new ToolDefinition("search", Optional.empty(), "Exact decimals",
                Map.of("enum", List.of(new java.math.BigDecimal("0.100000000000000001"))),
                Optional.of(fixture.outputSchema), List.of(), Optional.empty());
        var version = new ToolVersion(fixture.version.metadata(), definition, fixture.version.policy());
        when(fixture.catalog.resolve(any(), any())).thenReturn(Optional.of(new ToolCatalog.Entry(
                "mcp:server-a", "mcp:server-a/search", new ToolResolver.Resolved(fixture.mount, version, fixture.deployment))));
        var target = fixture.scopedTool("tool:101:input:run:decimal").target();
        var first = withSchemas(target, new TypedPayload("json-schema", "{\"enum\":[0.100000000000000001]}"), target.outputSchema());
        var second = withSchemas(target, new TypedPayload("json-schema", "{\"enum\":[0.100000000000000002]}"), target.outputSchema());
        assertThat(fixture.subject.requireCurrent(run, first).target()).isSameAs(first);
        assertThatThrownBy(() -> fixture.subject.requireCurrent(run, second))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("studio_tool_no_longer_authorized");
    }

    /** schema 排序容差不能放宽控制能力的 provider 路由、发现契约或并行策略。 */
    @Test
    void modelIdentityStillRejectsChangedControlBindingDiscoveryAndParallelPolicy() {
        var fixture=new Fixture();
        var run=fixture.outputOnlyRun();
        var current=OutputControlTools.append(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()), List.of()).getFirst();
        var otherBinding=new ToolBinding(ToolBinding.ProviderType.PLATFORM,"other-handler",current.binding().toolName());
        var otherDiscovery=new SessionToolDirectory.Discovery(otherBinding.serverIdentity(),
                otherBinding.serverIdentity()+"/"+otherBinding.toolName(),otherBinding.toolName(),current.discovery().title(),current.discovery().description());
        for(var changed:List.of(
                new SessionToolDirectory.Entry(current.target(),otherBinding,otherDiscovery,false),
                new SessionToolDirectory.Entry(current.target(),current.binding(),new SessionToolDirectory.Discovery(
                        current.discovery().serverIdentity(),current.discovery().qualifiedName(),current.discovery().name(),Optional.of("other"),"other"),false),
                new SessionToolDirectory.Entry(current.target(),current.binding(),current.discovery(),true))) {
            assertThatThrownBy(()->fixture.subject.modelIdentities(run,List.of(changed)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("agent_runtime_tool_model_identity_rejected");
        }
    }

    /** 测试只更换原冻结 schema，全部执行字段仍取自同一已发布目标。 */
    private static FrozenToolTarget withSchemas(FrozenToolTarget target,TypedPayload input,TypedPayload output) {
        return new FrozenToolTarget(target.handle(),target.capability(),target.deploymentId(),target.deploymentRevision(),
                input,output,target.effect(),target.idempotent(),target.timeout(),target.modelInvocable(),target.confirmation(),
                target.sensitiveFields(),target.providerRequirements(),target.outputSchemaDeclared());
    }

    /** 只反转每层对象键序；数组与值完全保留，用于模拟另一个 JVM 的 Map.of 顺序。 */
    private static TypedPayload reordered(TypedPayload payload) throws Exception {
        var json=new ObjectMapper();
        return new TypedPayload(payload.schemaId(),json.writeValueAsString(reverseObjectKeys(json.readTree(payload.canonicalJson()),json)));
    }

    /** 递归反转对象字段，数组保持原位置，叶值直接复用。 */
    private static com.fasterxml.jackson.databind.JsonNode reverseObjectKeys(com.fasterxml.jackson.databind.JsonNode node,ObjectMapper json) {
        if(node.isObject()) {
            var names=new java.util.ArrayList<String>();node.fieldNames().forEachRemaining(names::add);
            java.util.Collections.reverse(names);
            var object=json.createObjectNode();
            for(var name:names)object.set(name,reverseObjectKeys(node.get(name),json));
            return object;
        }
        if(node.isArray()) {
            var array=json.createArrayNode();node.forEach(value->array.add(reverseObjectKeys(value,json)));return array;
        }
        return node;
    }

    private static void assertDirectoryContainsMountedToolAndHostOutputControls(Fixture fixture,
            List<SessionToolDirectory.Entry> tools) {
        assertThat(tools).extracting(entry -> entry.target().handle().value()).containsExactly(
                "tool:101", "tool:host-output:finalize_task_outputs");
        assertThat(tools).extracting(SessionToolDirectory.Entry::binding).containsExactly(
                fixture.toolBinding,
                new ToolBinding(ToolBinding.ProviderType.PLATFORM,
                        "finalize_task_outputs", "finalize_task_outputs"));
        assertThat(tools).filteredOn(entry -> entry.target().handle().equals(fixture.runtimeTarget.handle()))
                .containsExactly(new SessionToolDirectory.Entry(
                        fixture.runtimeTarget, fixture.toolBinding, fixture.discovery));
        assertThat(tools).filteredOn(entry -> OutputControlTools.control(entry.discovery().name()))
                .extracting(entry -> entry.discovery().title().orElseThrow())
                .containsExactly("确认交付清单");
    }

    private static final class Fixture {
        private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
        private final TenantId tenant = new TenantId("1");
        private final SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "session"),
                new AgentId("2"), new EndUserId(3));
        private final InputId input = new InputId("input");
        private final JdbcSessionCapabilities capabilities = mock(JdbcSessionCapabilities.class);
        private final JdbcMcpBindings tools = mock(JdbcMcpBindings.class);
        private final JdbcWorkflowBindings workflows = mock(JdbcWorkflowBindings.class);
        private final AgentRuntimeWorkflowBindingPublisher workflowPublisher =
                mock(AgentRuntimeWorkflowBindingPublisher.class);
        private final JdbcKnowledgeBindings knowledge = mock(JdbcKnowledgeBindings.class);
        private final CapabilityMountPersistenceService mounts = mock(CapabilityMountPersistenceService.class);
        private final CapabilityMountResolver mountResolver = mock(CapabilityMountResolver.class);
        private final ToolCatalog catalog = mock(ToolCatalog.class);
        private final com.skillforge.institution.runtime.AgentRunBindings studioRuns =
                mock(com.skillforge.institution.runtime.AgentRunBindings.class);
        private final AgentRuntimePublishedCapabilities subject = new AgentRuntimePublishedCapabilities(
                capabilities, tools, workflows, knowledge, mounts, mountResolver, catalog,
                new ObjectMapper(), studioRuns, workflowPublisher, mock(AgentRuntimeExecutionMounts.Mapper.class), new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()));

        private final com.skillforge.core.capability.CapabilityRef catalogRef =
                new com.skillforge.core.capability.CapabilityRef(
                        com.skillforge.core.capability.CapabilityType.TOOL, "tool-capability", "v1");
        private final CapabilityMount mount = new CapabilityMount("101", 1L, 2L, catalogRef,
                new CapabilityMount.ToolMountConfig("deployment", 7), 1, 3, true);
        private final CapabilityMountSnapshot mountSnapshot = new CapabilityMountSnapshot(
                "tool-directory:9:capability-mount-snapshot", "tool-directory:9", 1L, 2L, 9,
                List.of(mount), NOW);
        private final Map<String, Object> inputSchema = Map.of(
                "type", "object", "required", List.of("query"),
                "properties", Map.of("query", Map.of("type", "string")),
                "additionalProperties", false);
        private final Map<String, Object> outputSchema = Map.of(
                "type", "object", "properties", Map.of("answer", Map.of("type", "string")));
        private final ToolDefinition definition = new ToolDefinition("search",
                Optional.of("Search documents"), "Search the frozen server",
                inputSchema, Optional.of(outputSchema), List.of(), Optional.empty());
        private final ToolVersion version = new ToolVersion(new CapabilityMetadata(
                catalogRef, 1L, "search", "Search the frozen server",
                CapabilityMetadata.Trust.TENANT, CapabilityMetadata.Status.PUBLISHED,
                "content-hash", new InvocationPolicy(true, false)), definition,
                new ToolExecutionPolicy(ToolExecutionPolicy.Effect.READ_ONLY, false,
                        ToolExecutionPolicy.ConfirmationPolicy.NEVER, 3_000,
                        Set.of(), Set.of()));
        private final ToolDeployment deployment = new ToolDeployment("deployment", 1L,
                catalogRef, ToolDeployment.ProviderType.MCP, "server-a", 7,
                ToolDeployment.Environment.TEST, true);
        private final ToolResolver.Resolved resolved = new ToolResolver.Resolved(
                mount, version, deployment);
        private final ToolCatalog.Entry entry = new ToolCatalog.Entry(
                "mcp:server-a", "mcp:server-a/search", resolved);
        private final CapabilityRef runtimeRef = new CapabilityRef(
                CapabilityType.TOOL, "tool-capability", "v1");
        private final FrozenToolTarget runtimeTarget = new FrozenToolTarget(
                new ToolHandle("tool:101"), runtimeRef, "deployment", 7,
                new TypedPayload("json-schema", "{\"additionalProperties\":false,\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"],\"type\":\"object\"}"),
                new TypedPayload("json-schema", "{\"properties\":{\"answer\":{\"type\":\"string\"}},\"type\":\"object\"}"),
                ToolEffect.READ_ONLY, false, Duration.ofSeconds(3), true,
                ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
        private final FrozenWorkflowTarget workflowTarget = new FrozenWorkflowTarget(
                new WorkflowHandle("workflow:101"), "workflow-instance", 4,
                new CapabilityRef(CapabilityType.WORKFLOW, "workflow-capability", "workflow-v1"),
                Duration.ofSeconds(30), 6L);
        private final ToolBinding toolBinding = new ToolBinding(
                        ToolBinding.ProviderType.MCP, "server-a", "search");
        private final JdbcMcpBindings.Profile profile = new JdbcMcpBindings.Profile(
                "search", JdbcMcpBindings.PROTOCOL_VERSION);
        private final JdbcMcpBindings.Binding binding = new JdbcMcpBindings.Binding(
                runtimeTarget, "http://localhost/mcp", Optional.empty(), "search",
                JdbcMcpBindings.PROTOCOL_VERSION, "server-a");
        private final SessionToolDirectory.Discovery discovery =
                new SessionToolDirectory.Discovery("mcp:server-a", "mcp:server-a/search",
                        "search", Optional.of("Search documents"), "Search the frozen server");

        private Fixture() {
            when(mountResolver.restore(any(CapabilityMountSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(catalog.list(any())).thenReturn(List.of(entry));
            when(catalog.resolve(any(), any())).thenReturn(Optional.of(entry));
            when(tools.find(any(), any())).thenAnswer(invocation -> invocation.getArgument(1) == null
                    ? Optional.empty() : Optional.of(bindingFor(invocation.getArgument(1))));
        }

        private JdbcMcpBindings.Binding bindingFor(FrozenToolTarget target) {
            return new JdbcMcpBindings.Binding(target, "http://localhost/mcp", Optional.empty(), "search",
                    JdbcMcpBindings.PROTOCOL_VERSION, "server-a");
        }

        /** 映射读取原冻结成员；本测试不允许通过当前挂载或 JDBC 目录反推预期。 */
        private RunIdentity frozenRun(String id, List<CapabilityMount> frozenMounts) {
            var admission = new InputId("admission:" + id);
            var run = new RunIdentity(owner, new AgentId((owner).target().id()), admission, new RunRef(tenant, id), new TraceEnvelope("trace", "span", admission, Optional.empty()), new ResultRef("identity:" + id), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (admission).value()));
            var member = mock(com.skillforge.institution.team.TeamService.FrozenMember.class);
            when(member.mounts()).thenReturn(new CapabilityMountSetResult(1L, 2L, 9, frozenMounts));
            var binding = mock(AgentRunBindings.Binding.class);
            when(binding.owner()).thenReturn(owner);
            when(binding.actor()).thenReturn(owner.target().requireAgent());
            when(binding.member()).thenReturn(member);
            when(studioRuns.find(run.runRef())).thenReturn(Optional.of(binding));
            return run;
        }

        /** 实际 list(Run) 走原冻结挂载和授权入口，空业务挂载仅产生宿主控制。 */
        private RunIdentity outputOnlyRun() {
            var run=frozenRun("run:controls",List.of());
            var directoryInput=new InputId("input:"+run.runRef().runId());
            var snapshot=new CapabilityMountSnapshot(CapabilityMountSnapshot.snapshotIdForInput(directoryInput.value()),
                    directoryInput.value(),1L,2L,9,List.of(),NOW);
            when(mountResolver.restore(any(CapabilityMountSnapshot.class))).thenReturn(snapshot);
            when(catalog.list(snapshot)).thenReturn(List.of());
            return run;
        }

        /** 只更换测试的精确输入绑定，保留全部执行字段用于拒绝目标漂移。 */
        private SessionToolDirectory.Entry scopedTool(String handle) {
            var target = new FrozenToolTarget(new ToolHandle(handle), runtimeTarget.capability(),
                    runtimeTarget.deploymentId(), runtimeTarget.deploymentRevision(), runtimeTarget.inputSchema(),
                    runtimeTarget.outputSchema(), runtimeTarget.effect(), runtimeTarget.idempotent(), runtimeTarget.timeout(),
                    runtimeTarget.modelInvocable(), runtimeTarget.confirmation(), runtimeTarget.sensitiveFields(),
                    runtimeTarget.providerRequirements(), runtimeTarget.outputSchemaDeclared());
            return new SessionToolDirectory.Entry(target, toolBinding, discovery);
        }

        private void current(long generation) {
            current(generation, List.of(mount));
        }

        private void current(long generation, List<CapabilityMount> currentMounts) {
            when(mounts.read(1L, 2L)).thenReturn(new CapabilityMountSetResult(1L, 2L, 9, currentMounts));
            when(mountResolver.freeze(mountSnapshot.snapshotId(), "tool-directory:9", 1L, 2L,
                    9, List.of(mount))).thenReturn(mountSnapshot);
            when(capabilities.catalog(owner, owner.target().requireAgent())).thenReturn(
                    new JdbcSessionCapabilities.Catalog(generation, List.of(), List.of()));
            when(tools.publish(tenant, runtimeTarget, profile)).thenReturn(binding);
        }
    }
}

package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.chat.ChatRuntimeGateway.Submission;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation;
import com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import com.skillforge.core.workflow.runtime.WorkflowNodeSource;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.SessionExecutionWorkflows;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 验证真实发送前的用途策略；端口目录受控，不替代数据库、普通IAM或真实MCP验收。 */
class WorkflowTestRuntimePolicyTest {
    @Test void exactReadOnlyTestMcpDeploymentPassesTheProviderGuard() {
        var f = new Fixture();
        assertDoesNotThrow(() -> f.policy.tool(f.nodeRun(), f.tool(f.toolTarget, ToolBinding.ProviderType.MCP)));
        verify(f.registry).findToolDeployment(7L, "test-mcp");
    }

    @Test void twoInstalledSlotsMayReferenceTheSameExactLegalToolTarget() {
        var f = new Fixture();
        when(f.testRegistry.bindings(7, 41)).thenReturn(List.of(f.binding("lookup-primary"), f.binding("lookup-secondary")));
        var configured = new WorkflowInstallationBindings(List.of(
                new WorkflowInstallationBindings.CapabilityBinding("lookup-primary", f.toolVersion, "test-mcp", 3L, null),
                new WorkflowInstallationBindings.CapabilityBinding("lookup-secondary", f.toolVersion, "test-mcp", 3L, null)), List.of(), List.of());
        assertDoesNotThrow(() -> f.policy.installation(7, 91, configured, configured));
        assertDoesNotThrow(() -> f.policy.tool(f.nodeRun(), f.tool(f.toolTarget, ToolBinding.ProviderType.MCP)));
    }

    @Test void writeEffectsNonMcpBindingsAndOrdinaryAgentRunsCannotSendTestTools() {
        var f = new Fixture();
        for (var effect : List.of(ToolEffect.IDEMPOTENT_WRITE, ToolEffect.NON_IDEMPOTENT_WRITE, ToolEffect.DESTRUCTIVE))
            assertDenied("workflow_test_tool_effect_rejected", () -> f.policy.tool(f.nodeRun(),
                    f.tool(f.toolTarget(effect, "221", 3), ToolBinding.ProviderType.MCP)));
        for (var provider : List.of(ToolBinding.ProviderType.PLATFORM, ToolBinding.ProviderType.CONNECTOR, ToolBinding.ProviderType.TEST_MOCK))
            assertDenied("workflow_test_tool_effect_rejected", () -> f.policy.tool(f.nodeRun(), f.tool(f.toolTarget, provider)));
        assertDenied("workflow_test_tool_effect_rejected", () -> f.policy.tool(f.rootRun(), f.tool(f.toolTarget, ToolBinding.ProviderType.MCP)));
        verifyNoInteractions(f.registry);
    }

    @Test void currentDeploymentMustRemainActiveTestMcpWithTheSameVersionRevisionAndProvider() {
        var f = new Fixture();
        var request = f.tool(f.toolTarget, ToolBinding.ProviderType.MCP);
        var changed = List.of(
                f.deployment(ToolDeployment.Environment.PRODUCTION, ToolDeployment.ProviderType.MCP, "221", 3, "test-server", true),
                f.deployment(ToolDeployment.Environment.DEVELOPMENT, ToolDeployment.ProviderType.MCP, "221", 3, "test-server", true),
                f.deployment(ToolDeployment.Environment.TEST, ToolDeployment.ProviderType.CONNECTOR, "221", 3, "test-server", true),
                f.deployment(ToolDeployment.Environment.TEST, ToolDeployment.ProviderType.MCP, "222", 3, "test-server", true),
                f.deployment(ToolDeployment.Environment.TEST, ToolDeployment.ProviderType.MCP, "221", 4, "test-server", true),
                f.deployment(ToolDeployment.Environment.TEST, ToolDeployment.ProviderType.MCP, "221", 3, "another-server", true),
                f.deployment(ToolDeployment.Environment.TEST, ToolDeployment.ProviderType.MCP, "221", 3, "test-server", false));
        for (var deployment : changed) {
            when(f.registry.findToolDeployment(7L, "test-mcp")).thenReturn(Optional.of(deployment));
            assertDenied("workflow_test_tool_target_rejected", () -> f.policy.tool(f.nodeRun(), request));
        }
    }

    @Test void requestedToolVersionOrDeploymentRevisionCannotDriftFromTheInstalledManifest() {
        var f = new Fixture();
        for (var target : List.of(f.toolTarget(ToolEffect.READ_ONLY, "222", 3), f.toolTarget(ToolEffect.READ_ONLY, "221", 4)))
            assertDenied("workflow_test_tool_target_rejected", () -> f.policy.tool(f.nodeRun(), f.tool(target, ToolBinding.ProviderType.MCP)));
        verifyNoInteractions(f.registry);
    }

    @Test void initialInvocationUsesTheOnlyFrozenWorkflowAndOriginalInputParameters() throws Exception {
        var f = new Fixture();
        assertEquals(Optional.of(new AgentExecutionProgram.AuthorizedWorkflowInvocation("workflow-test:41", f.workflow)),
                f.policy.initial(f.identity, List.of(f.workflow)));
        var parameters = f.json.readTree(f.policy.parameters(f.identity).canonicalJson());
        assertFalse(parameters.get("accepted").booleanValue());
        assertEquals(0, parameters.get("count").intValue());
        assertDenied("workflow_test_target_mismatch", () -> f.policy.initial(f.identity, List.of(f.workflow, f.workflow)));
        assertDenied("workflow_test_target_mismatch", () -> f.policy.initial(f.identity, List.of(f.driftedWorkflow())));
        assertDenied("workflow_test_target_mismatch", () -> f.policy.initial(f.identity("run:another-input", "another-input"), List.of(f.workflow)));
    }

    @Test void onlyOriginalInitialContentOrTheOriginalRunPendingResponseCanEnterTheTestSession() {
        var f = new Fixture();
        var original = f.submission(f.test.getInitialMessage(), Map.of("end_user_id", 51L,
                "root_input_id", "initial-request", "trace_id", "trace", "span_id", "span", "event_id", "event", "delegation_requester_id", "51"));
        assertDoesNotThrow(() -> f.policy.input(original, "initial-request", Optional.empty(), false));
        var reply = f.submission("已核对当前待办", Map.of("end_user_id", 51L), "reply-input");
        assertDoesNotThrow(() -> f.policy.input(reply, "reply-input", Optional.of(f.identity.runRef()), false));
        assertDenied("workflow_test_initial_input_required", () -> f.policy.input(reply, "initial-request", Optional.empty(), false));
        assertDenied("workflow_test_initial_input_required", () -> f.policy.input(original, "different-input", Optional.empty(), false));
        assertDenied("workflow_test_initial_input_required", () -> f.policy.input(
                f.submission(f.test.getInitialMessage(), Map.of("end_user_id", 51L), "different-client-message"),
                "initial-request", Optional.empty(), false));
        assertDenied("workflow_test_pending_owner_mismatch", () -> f.policy.input(reply, "reply-input", Optional.of(new RunRef(Fixture.TENANT, "run:other")), false));
        assertDenied("workflow_test_identity_mismatch", () -> f.policy.input(original, "initial-request", Optional.empty(), true));
        assertDenied("workflow_test_input_scope_rejected", () -> f.policy.input(f.submission(f.test.getInitialMessage(),
                Map.of("end_user_id", 51L, "workflow_test", true)), "initial-request", Optional.empty(), false));
        when(f.access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "62"));
        assertDenied("workflow_test_owner_mismatch", () -> f.policy.input(original, "initial-request", Optional.empty(), false));
    }

    @Test void rootDispatchAndResumeRequireTheTrustedOriginAndUnchangedParameters() {
        var f = new Fixture();
        var dispatch = f.dispatch(f.origin, f.parameters);
        var resume = f.resume(f.origin);
        assertTrue(f.allowed(f.prepared(dispatch)));
        assertTrue(f.allowed(f.prepared(resume)));
        var model = new SessionExecutionWorkflows.ModelOrigin("model-call", "dispatch_workflow", new TypedPayload("application/json", "{}"));
        assertFalse(f.allowed(f.prepared(f.dispatch(model, f.parameters))));
        assertFalse(f.allowed(f.prepared(f.resume(model))));
        assertFalse(f.allowed(f.prepared(f.dispatch(new SessionExecutionWorkflows.AuthorizedInputOrigin("workflow-test:other"), f.parameters))));
        assertFalse(f.allowed(f.prepared(f.dispatch(f.origin, new TypedPayload("application/json", "{\"count\":1}")))));
    }

    @Test void closingClosedAndExpiredStopNewDispatchButKeepOriginalCancelAndQueryAvailable() {
        for (var state : List.of("closing", "closed", "expired")) {
            var f = new Fixture();
            f.state(state);
            for (var request : List.of(f.dispatch(f.origin, f.parameters), f.resume(f.origin))) {
                var prepared = f.prepared(request);
                assertFalse(f.allowed(prepared), state);
                assertDenied("workflow_test_not_live", () -> f.policy.provider(f.rootRun(), prepared));
            }
            for (var request : List.of(
                    new SessionExecutionWorkflows.Cancel(f.snapshot, f.workflow, f.execution, "cancel-original", 5, "user_cancelled"),
                    new SessionExecutionWorkflows.Query(f.snapshot, f.workflow, f.execution))) {
                var prepared = f.prepared(request);
                assertTrue(f.allowed(prepared), state);
                assertDoesNotThrow(() -> f.policy.provider(f.rootRun(), prepared));
                var anotherRun = f.rootRun(f.identity("run:another", "initial-request"));
                assertFalse(f.policy.allowed(f.sql, anotherRun, prepared, f.codec, f.workflows));
                assertDenied("workflow_test_not_live", () -> f.policy.provider(anotherRun, prepared));
            }
            var wrongTarget = f.prepared(new SessionExecutionWorkflows.Query(f.snapshot, f.driftedWorkflow(), f.execution));
            assertFalse(f.allowed(wrongTarget));
            assertDenied("workflow_test_not_live", () -> f.policy.provider(f.rootRun(), wrongTarget));
            assertDenied("workflow_test_not_live", () -> f.policy.tool(f.nodeRun(), f.tool(f.toolTarget, ToolBinding.ProviderType.MCP)));
        }
    }

    private static void assertDenied(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(ApiException.class, operation).getCode());
    }

    /** 复用正式登记回执与节点身份工厂；仅替换公开Registry端口，不模拟策略规则。 */
    private static final class Fixture {
        private static final TenantId TENANT = new TenantId("7");
        private final WorkflowTestRegistry testRegistry = mock(WorkflowTestRegistry.class);
        private final CapabilityMountResolver.Registry registry = mock(CapabilityMountResolver.Registry.class);
        private final FoundationAccess access = mock(FoundationAccess.class);
        private final SqlSession sql = mock(SqlSession.class);
        private final ObjectMapper json = new ObjectMapper();
        private final SessionExecutionWorkflows workflows = new SessionExecutionWorkflows(new CodecLimits(100_000, 10_000, 100));
        private final AgentExecutionCodec codec = new AgentExecutionCodec(new CodecLimits(100_000, 10_000, 100));
        private WorkflowTestRegistry.Snapshot test = snapshot("ready", Instant.now().plus(Duration.ofDays(1)));
        private final RunIdentity identity = identity("run:initial-request", "initial-request");
        private final VerifiedIdentity actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, new EndUserId(51)),
                identity.owner(), Set.of("sessions.write"), 1, new ResultRef("verified-test-member"));
        private final CapabilitySnapshotRef snapshot = new CapabilitySnapshotRef(identity.owner().sessionRef(), new InputId("initial-request"), "snapshot:test", 1);
        private final ExecutionRef execution = new ExecutionRef(TENANT, "original-workflow-execution");
        private final FrozenWorkflowTarget workflow = new FrozenWorkflowTarget(new WorkflowHandle("workflow:test"), "91", 101,
                new CapabilityRef(CapabilityType.WORKFLOW, "111", "121"), Duration.ofSeconds(30), 131);
        private final com.skillforge.core.capability.CapabilityRef toolVersion = new com.skillforge.core.capability.CapabilityRef(
                com.skillforge.core.capability.CapabilityType.TOOL, "211", "221");
        private final FrozenToolTarget toolTarget = toolTarget(ToolEffect.READ_ONLY, "221", 3);
        private final TypedPayload parameters = new TypedPayload("application/json", "{\"accepted\":false,\"count\":0}");
        private final SessionExecutionWorkflows.AuthorizedInputOrigin origin = new SessionExecutionWorkflows.AuthorizedInputOrigin("workflow-test:41");
        private final WorkflowTestRuntimePolicy policy = new WorkflowTestRuntimePolicy(new WorkflowTestIsolation(testRegistry, access), testRegistry, registry, json, workflows);

        private Fixture() {
            when(testRegistry.bySession(7, "test-session")).thenAnswer(ignored -> test);
            when(testRegistry.byInstance(7, 91)).thenAnswer(ignored -> test);
            when(testRegistry.bindings(7, 41)).thenReturn(List.of(binding("lookup")));
            when(testRegistry.session(sql, 7, "test-session")).thenAnswer(ignored -> test);
            when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "61"));
            when(registry.findToolDeployment(7L, "test-mcp")).thenReturn(Optional.of(
                    deployment(ToolDeployment.Environment.TEST, ToolDeployment.ProviderType.MCP, "221", 3, "test-server", true)));
        }

        private void state(String state) {
            test = snapshot(state.equals("expired") ? "ready" : state,
                    state.equals("expired") ? Instant.EPOCH : Instant.now().plus(Duration.ofDays(1)));
        }

        private WorkflowTestRegistry.Snapshot snapshot(String status, Instant expiresAt) {
            return new WorkflowTestRegistry.Snapshot(41L, 7L, 61L, "test-request", "test-request-fingerprint",
                    "definition", 71L, "source-workflow", null, 81L, "source-hash", 71L,
                    101L, 111L, 121L, 91L, 131L, 31L, 51L, "test-session", "initial-request", "测试原始输入",
                    new java.util.TreeMap<>(Map.of("accepted", false, "count", 0)), 4, 8, 4, 10_000L, 300L,
                    status, expiresAt, Instant.EPOCH, Instant.EPOCH, status.equals("closed") ? Instant.EPOCH : null);
        }

        private RunIdentity identity(String run, String input) {
            var owner = SessionOwner.user(new SessionRef(TENANT, "test-session"), new AgentId("31"), new EndUserId(51));
            return new RunIdentity(owner, new AgentId("31"), new InputId(input), new RunRef(TENANT, run),
                    new TraceEnvelope("trace", "span", new InputId(input), Optional.empty()), new ResultRef("verified-test-member"),
                    new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(TENANT, "input:" + input));
        }

        private WorkflowTestRegistry.Binding binding(String slot) {
            return new WorkflowTestRegistry.Binding(7, 41, slot, "TOOL", 211, 221, 301L, "test-mcp", 3L, null);
        }

        private ToolDeployment deployment(ToolDeployment.Environment environment, ToolDeployment.ProviderType provider,
                String version, long revision, String providerRef, boolean active) {
            return new ToolDeployment("test-mcp", 7L, new com.skillforge.core.capability.CapabilityRef(
                    com.skillforge.core.capability.CapabilityType.TOOL, "211", version), provider, providerRef, revision, environment, active);
        }

        private FrozenToolTarget toolTarget(ToolEffect effect, String version, long revision) {
            var schema = new TypedPayload("json-schema", "{\"type\":\"object\"}");
            return new FrozenToolTarget(new ToolHandle("tool:test"), new CapabilityRef(CapabilityType.TOOL, "211", version), "test-mcp", revision,
                    schema, schema, effect, true, Duration.ofSeconds(5), true, ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
        }

        private AgentExecutionCodec.ToolRequest tool(FrozenToolTarget target, ToolBinding.ProviderType provider) {
            return new AgentExecutionCodec.ToolRequest(target, new ToolBinding(provider, "test-server", "lookup"),
                    new TypedPayload("application/json", "{}"), "call:test", "lookup");
        }

        private SessionExecutionWorkflows.Dispatch dispatch(SessionExecutionWorkflows.InvocationOrigin source, TypedPayload input) {
            return new SessionExecutionWorkflows.Dispatch(snapshot, workflow, execution, input, List.of(), source);
        }

        private SessionExecutionWorkflows.Resume resume(SessionExecutionWorkflows.InvocationOrigin source) {
            return new SessionExecutionWorkflows.Resume(snapshot, workflow, execution, "original-suspend", "original-wait-action", 5,
                    new TypedPayload("application/json", "{\"confirmed\":false}"), source, new ResultRef("original-response"), new com.skillforge.runtime.api.InputId("reply-input"));
        }

        private ExecutionStore.Prepared prepared(SessionExecutionWorkflows.Request request) {
            return RegisteredProviderFixture.create(identity, actor, ExecutionStore.Kind.WORKFLOW, request.callKind(),
                    request.target().handle().value(), workflows.encode(request)).prepared();
        }

        private ExecutionStore.Run rootRun() {
            return rootRun(identity);
        }

        private ExecutionStore.Run rootRun(RunIdentity current) {
            var request = dispatch(origin, parameters);
            return RegisteredProviderFixture.create(current, actor, ExecutionStore.Kind.WORKFLOW, request.callKind(),
                    workflow.handle().value(), workflows.encode(request)).registered().run();
        }

        private ExecutionStore.Run nodeRun() {
            var node = WorkflowNodeSource.runIdentity(new WorkflowNodeCallIdentity(identity, execution, "lookup", 1, 1));
            var original = RegisteredProviderFixture.create(node, actor, ExecutionStore.Kind.TOOL, PortalCallKind.TOOL_INVOKE,
                    toolTarget.handle().value(), new TypedPayload("application/json", "{}")).registered().run();
            return new ExecutionStore.Run(node, WorkflowNodeRequests.reference(node.runRef()), original.status(), original.revision(), original.fence(),
                    original.cancelled(), original.phase(), original.progress(), original.lease(), original.currentAction(), original.currentBatch(),
                    original.waiting(), original.actorVerificationRef(), original.nextDueAt(), original.createdAt(), original.updatedAt());
        }

        private boolean allowed(ExecutionStore.Prepared prepared) { return policy.allowed(sql, rootRun(), prepared, codec, workflows); }

        private FrozenWorkflowTarget driftedWorkflow() {
            return new FrozenWorkflowTarget(workflow.handle(), workflow.instanceId(), workflow.instanceRevision(), workflow.capability(),
                    workflow.timeout(), workflow.installationRevision() + 1);
        }

        private Submission submission(String text, Map<String, Object> context) {
            return submission(text, context, test.getInitialInputId());
        }

        private Submission submission(String text, Map<String, Object> context, String clientMessageId) {
            var session = SessionView.builder().sessionId("test-session").tenantId(7L).agentId(31L).endUserId(51L).build();
            var request = new ChatRequest(text, "test-session", "31", null, null, null, null, "zh-CN", clientMessageId, null, context);
            return new Submission(request, session, "31", 51L, null);
        }
    }
}

package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.workflow.execution.WorkflowModels.Workflow;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import com.skillforge.core.workflow.execution.node.NodeCallIdentity;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.WorkflowNodeFailure;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.execution.ExecutionAdmissionRejection;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.ExecutionPolicy;
import com.skillforge.institution.execution.ExecutionSubmissionRejectedException;
import com.skillforge.institution.execution.WorkflowScriptSandboxClient;
import com.skillforge.institution.execution.WorkflowScriptSandboxRequestFactory;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/** 宿主真实执行路径的持久端口采用验证；数据库提交由原 JDBC journal 组合测试证明。 */
class AgentRuntimeWorkflowScriptsAdmissionTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String ENVIRONMENT = "workflow-python-v1";
    private static final String SCRIPT_PATH = "scripts/main.py";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final JdbcWorkflowScriptCalls journal = mock(JdbcWorkflowScriptCalls.class);
    private final WorkflowScriptSandboxClient sandbox = mock(WorkflowScriptSandboxClient.class);
    private final RunSettingsService settings = mock(RunSettingsService.class);
    private final AgentRuntimePayloadValidation payloads = mock(AgentRuntimePayloadValidation.class);
    private final TenantId tenant = new TenantId("tenant");
    private final InputId input = new InputId("input");
    private final SessionOwner session = SessionOwner.user(new SessionRef(tenant, "session"),
            new AgentId("agent"), new EndUserId(1));
    private final RunIdentity owner = new RunIdentity(session, new AgentId((session).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((session).requireUser()), java.util.Optional.empty(), new RunOriginRef((session).sessionRef().tenantId(), "input:" + (input).value()));
    private final VerifiedIdentity identity = new VerifiedIdentity(
            new PrincipalRef.EndUserPrincipal(tenant, session.requireUser()), session,
            Set.of("runtime:invoke"), 1, new ResultRef("verified"));
    private final ExecutionRef executionRef = new ExecutionRef(tenant, "execution");
    private final LeaseFence fence = new LeaseFence(tenant, "claim", "worker", 1, NOW.plusSeconds(60));
    private final ExecutionLease lease = new ExecutionLease(executionRef, fence, 2);
    private final ExecutionRecord execution = new ExecutionRecord(executionRef, owner, "workflow", "definition",
            ExecutionMode.FOREGROUND, Optional.empty(), Optional.empty(), WorkflowExecutionState.RUNNING, 2,
            new TypedPayload("workflow.checkpoint.v1", "{}"), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(fence), Optional.empty(), Optional.empty(), NOW, NOW);
    private final InvocationControl control = new InvocationControl(NOW.plusSeconds(30), () -> false);

    @ParameterizedTest
    @EnumSource(ExecutionAdmissionRejection.Code.class)
    void recordsConfirmedNonSubmissionBeforeFailingAndNeverQueriesOrResubmits(
            ExecutionAdmissionRejection.Code code) throws Exception {
        var workflowNode = new WorkflowNode("script", "script", null, null, null,
                Map.of("script", SCRIPT_PATH, "input", Map.of("value", 7)), null, null,
                Map.of(), List.of(), null, List.of(), null, List.of(), Map.of(), null, null, Map.of(), null);
        var node = new NodeExecutionContext(new Workflow("workflow", workflowNode.id(), List.of(workflowNode), List.of()),
                workflowNode, Map.of(), Map.of(), Map.of(), "input", List.of(), "instance", tenant.value(), List.of(),
                executionRef.executionId(), null, session.sessionRef().sessionId(), Map.of(), null,
                new NodeCallIdentity(executionRef.executionId(), workflowNode.id(), 1, 1));
        var key = new WorkflowNodeCallIdentity(owner, executionRef, workflowNode.id(), 1, 1);
        var target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:root"), "instance", 4,
                new CapabilityRef(CapabilityType.WORKFLOW, "workflow", "v1"), Duration.ofSeconds(30), 9);
        var definition = definition();
        var binding = new JdbcWorkflowBindings.Binding(target, "workflow", json.writeValueAsString(definition),
                "{}", Optional.empty(), "{}", "{}", "{}");
        var inputs = new SdkWorkflowPortal.NodeInputs(target,
                new CapabilitySnapshotRef(session.sessionRef(), input, "snapshot", 1),
                new TypedPayload("application/json", "{}"), List.of(), Optional.empty(), binding);
        var frozenSettings = new RunSettingsService.FrozenConfig(null, 0, Map.of("sandbox", 1L), null, com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(), "profile");
        var policy = policy();
        when(settings.captureOperation(eq(tenant.value()), eq(owner.runRef().runId()), anyString(), isNull(), eq(0L)))
                .thenReturn(frozenSettings);
        when(settings.read(frozenSettings, "sandbox")).thenReturn(snapshot(policy));
        var request = WorkflowScriptSandboxRequestFactory.prepare(definition, workflowNode, Map.of("value", 7),
                new WorkflowScriptSandboxRequestFactory.TaskIdentity(tenant.value(), executionRef.executionId(),
                        workflowNode.id(), 1, 1), control.deadlineAt(), policy, ENVIRONMENT);
        var preparation = new JdbcWorkflowScriptCalls.Preparation(key, target.instanceRevision(),
                target.installationRevision(), execution.checkpoint(), request.taskId(),
                new TypedPayload("application/json", request.canonicalJson()), control.deadlineAt());
        var saved = new AtomicReference<>(new JdbcWorkflowScriptCalls.Entry(preparation,
                JdbcWorkflowScriptCalls.State.PREPARED, Optional.empty()));
        when(journal.find(identity, key)).thenAnswer(ignored -> Optional.of(saved.get()));
        when(journal.current(identity, lease, execution.executionRevision(), key)).thenAnswer(ignored -> saved.get());
        when(journal.currentWaiting(identity, lease, execution.executionRevision(), key)).thenAnswer(ignored -> saved.get());
        when(journal.dispatch(identity, lease, execution.executionRevision(), key)).thenAnswer(ignored -> {
            assertEquals(JdbcWorkflowScriptCalls.State.PREPARED, saved.get().state());
            saved.set(new JdbcWorkflowScriptCalls.Entry(preparation, JdbcWorkflowScriptCalls.State.DISPATCHED,
                    Optional.empty()));
            return true;
        });
        when(journal.record(eq(identity), eq(lease), eq(execution.executionRevision()), eq(key),
                eq(JdbcWorkflowScriptCalls.State.FAILED), any(TypedPayload.class))).thenAnswer(call -> {
                    assertEquals(JdbcWorkflowScriptCalls.State.DISPATCHED, saved.get().state());
                    saved.set(new JdbcWorkflowScriptCalls.Entry(preparation, JdbcWorkflowScriptCalls.State.FAILED,
                            Optional.of(call.getArgument(5, TypedPayload.class))));
                    return saved.get();
                });
        var rejection = ExecutionAdmissionRejection.notSubmitted(code);
        when(sandbox.submit(any(), any())).thenThrow(new ExecutionSubmissionRejectedException(rejection));
        when(sandbox.query(anyString(), any())).thenThrow(new AssertionError("确定未提交不能查询不存在的任务"));

        var failure = assertThrows(WorkflowNodeFailure.class,
                () -> subject().execute(identity, lease, execution, control, inputs, node));

        assertEquals("workflow_script_failed", failure.reason());
        assertEquals(JdbcWorkflowScriptCalls.State.FAILED, saved.get().state());
        var result = saved.get().result().orElseThrow();
        assertEquals("application/json", result.schemaId());
        assertEquals(rejection, json.readValue(result.canonicalJson(), ExecutionAdmissionRejection.class));
        var ordered = inOrder(journal, sandbox);
        ordered.verify(journal).dispatch(identity, lease, execution.executionRevision(), key);
        ordered.verify(sandbox).submit(any(), any());
        ordered.verify(journal).record(identity, lease, execution.executionRevision(), key,
                JdbcWorkflowScriptCalls.State.FAILED, result);

        var rebuilt = subject();
        assertEquals("workflow_script_failed", assertThrows(WorkflowNodeFailure.class,
                () -> rebuilt.execute(identity, lease, execution, control, inputs, node)).reason());
        var signal = rebuilt.observe(identity, lease, execution, control, inputs, node);
        assertTrue(signal.isPresent());
        rebuilt.requireSignal(identity, execution, signal.orElseThrow(), inputs, node);
        verify(journal, times(1)).dispatch(identity, lease, execution.executionRevision(), key);
        verify(journal, times(1)).record(identity, lease, execution.executionRevision(), key,
                JdbcWorkflowScriptCalls.State.FAILED, result);
        verify(journal, never()).prepare(any(), any(), anyLong(), any());
        verify(sandbox, times(1)).submit(any(), any());
        verifyNoMoreInteractions(sandbox);
        verifyNoInteractions(payloads);
    }

    @Test
    void recoveryQueriesOriginalExpiredTaskAndRecordsRealCompletionWithoutSubmitOrCancel() throws Exception {
        var source=querySource(JdbcWorkflowScriptCalls.State.DISPATCHED);
        var output=new TypedPayload("application/json","{\"value\":7}");
        var actual=new WorkflowScriptSandboxClient.Status(source.entry().preparation().taskId(),WorkflowScriptSandboxClient.State.COMPLETED,
                Optional.of(output),Optional.empty());
        when(sandbox.query(source.entry().preparation().taskId(),control)).thenReturn(Optional.of(actual));
        when(journal.record(eq(identity),eq(lease),eq(execution.executionRevision()),eq(source.key()),
                eq(JdbcWorkflowScriptCalls.State.COMPLETED),any(TypedPayload.class))).thenAnswer(call->
                new JdbcWorkflowScriptCalls.Entry(source.entry().preparation(),JdbcWorkflowScriptCalls.State.COMPLETED,Optional.of(call.getArgument(5))));

        var observed=subject().queryOriginal(identity,lease,execution,control,source.inputs(),source.node()).orElseThrow();

        assertTrue(source.entry().preparation().deadlineAt().isBefore(NOW));
        assertEquals(actual,json.readValue(observed.result().orElseThrow().canonicalJson(),WorkflowScriptSandboxClient.Status.class));
        var ordered=inOrder(journal,sandbox);
        ordered.verify(journal).current(identity,lease,execution.executionRevision(),source.key());
        ordered.verify(sandbox).query(source.entry().preparation().taskId(),control);
        ordered.verify(journal).record(eq(identity),eq(lease),eq(execution.executionRevision()),eq(source.key()),
                eq(JdbcWorkflowScriptCalls.State.COMPLETED),any(TypedPayload.class));
        verifyNoMoreInteractions(sandbox);
        verifyNoInteractions(settings,payloads);
    }

    @Test
    void recoveryKeepsMissingOrUncertainOriginalResultUnresolvedWithoutSubmitOrCancel() throws Exception {
        var source=querySource(JdbcWorkflowScriptCalls.State.DISPATCHED);
        when(sandbox.query(source.entry().preparation().taskId(),control)).thenReturn(Optional.empty());

        assertTrue(subject().queryOriginal(identity,lease,execution,control,source.inputs(),source.node()).isEmpty());

        verify(sandbox).query(source.entry().preparation().taskId(),control);
        verifyNoMoreInteractions(sandbox);
        verify(journal,never()).record(any(),any(),anyLong(),any(),any(),any());
    }

    @Test
    void recoveryCannotQueryWhenCurrentAuthorizationRejectsOrOriginalWasNeverDispatched() throws Exception {
        var source=querySource(JdbcWorkflowScriptCalls.State.PREPARED);
        assertTrue(subject().queryOriginal(identity,lease,execution,control,source.inputs(),source.node()).isEmpty());
        when(journal.current(identity,lease,execution.executionRevision(),source.key())).thenThrow(new SecurityException("workflow_script_access_rejected"));

        assertThrows(SecurityException.class,()->subject().queryOriginal(identity,lease,execution,control,source.inputs(),source.node()));

        verifyNoInteractions(sandbox);
        verify(journal,never()).dispatch(any(),any(),anyLong(),any());
    }

    private QuerySource querySource(JdbcWorkflowScriptCalls.State state) throws Exception {
        var workflowNode=new WorkflowNode("script","script",null,null,null,Map.of("script",SCRIPT_PATH,"input",Map.of("value",7)),null,null,
                Map.of(),List.of(),null,List.of(),null,List.of(),Map.of(),null,null,Map.of(),null);
        var node=new NodeExecutionContext(new Workflow("workflow",workflowNode.id(),List.of(workflowNode),List.of()),workflowNode,
                Map.of(),Map.of(),Map.of(),"input",List.of(),"instance",tenant.value(),List.of(),executionRef.executionId(),null,
                session.sessionRef().sessionId(),Map.of(),null,new NodeCallIdentity(executionRef.executionId(),workflowNode.id(),1,1));
        var key=new WorkflowNodeCallIdentity(owner,executionRef,workflowNode.id(),1,1);
        var target=new FrozenWorkflowTarget(new WorkflowHandle("workflow:root"),"instance",4,new CapabilityRef(CapabilityType.WORKFLOW,"workflow","v1"),Duration.ofSeconds(30),9);
        var definition=definition();
        var binding=new JdbcWorkflowBindings.Binding(target,"workflow",json.writeValueAsString(definition),"{}",Optional.empty(),"{}","{}","{}");
        var inputs=new SdkWorkflowPortal.NodeInputs(target,new CapabilitySnapshotRef(session.sessionRef(),input,"snapshot",1),
                new TypedPayload("application/json","{}"),List.of(),Optional.empty(),binding);
        var deadline=NOW.minusSeconds(1);
        var request=WorkflowScriptSandboxRequestFactory.prepare(definition,workflowNode,Map.of("value",7),
                new WorkflowScriptSandboxRequestFactory.TaskIdentity(tenant.value(),executionRef.executionId(),workflowNode.id(),1,1),deadline,policy(),ENVIRONMENT);
        var preparation=new JdbcWorkflowScriptCalls.Preparation(key,target.instanceRevision(),target.installationRevision(),execution.checkpoint(),request.taskId(),
                new TypedPayload("application/json",request.canonicalJson()),deadline);
        var entry=new JdbcWorkflowScriptCalls.Entry(preparation,state,Optional.empty());
        when(journal.find(identity,key)).thenReturn(Optional.of(entry));
        when(journal.current(identity,lease,execution.executionRevision(),key)).thenReturn(entry);
        return new QuerySource(key,node,inputs,entry);
    }

    private record QuerySource(WorkflowNodeCallIdentity key,NodeExecutionContext node,SdkWorkflowPortal.NodeInputs inputs,JdbcWorkflowScriptCalls.Entry entry) { }

    private AgentRuntimeWorkflowScripts subject() {
        return new AgentRuntimeWorkflowScripts(journal, sandbox, settings, payloads, json,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Map<String, Object> definition() throws Exception {
        byte[] script = "print('test')".getBytes(StandardCharsets.UTF_8);
        return Map.of("resources", List.of(Map.of("path", SCRIPT_PATH,
                "content_base64", Base64.getEncoder().encodeToString(script),
                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script)))));
    }

    private static ExecutionPolicy policy() {
        return new ExecutionPolicy(1, ExecutionLimits.DEFAULT_TIMEOUT_SECONDS, 15, 24, 60,
                ExecutionLimits.OUTPUT_CAPTURE_BYTES, ExecutionLimits.MAX_FILES, ExecutionLimits.CONCURRENT_EXECUTIONS,
                ExecutionLimits.MEMORY_LIMIT, ExecutionLimits.CPU_LIMIT, ExecutionLimits.PIDS_LIMIT);
    }

    private static OperationalSettingsService.Snapshot snapshot(ExecutionPolicy policy) {
        return new OperationalSettingsService.Snapshot(policy.revision(), Map.ofEntries(
                Map.entry("enabled", true), Map.entry("execution_timeout_seconds", policy.executionTimeoutSeconds()),
                Map.entry("workspace_retention_hours", policy.workspaceRetentionHours()),
                Map.entry("output_capture_bytes", policy.outputCaptureBytes()), Map.entry("max_artifact_files", policy.maxArtifactFiles()),
                Map.entry("max_concurrent_executions", policy.maxConcurrentExecutions()), Map.entry("memory_limit", policy.memoryLimit()),
                Map.entry("cpu_limit", policy.cpuLimit()), Map.entry("pids_limit", policy.pidsLimit())));
    }
}

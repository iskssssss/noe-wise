package com.skillforge.institution.team;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.institution.runtime.AgentRunBindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.artifact.ArtifactStorage;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;


class TeamResultServiceTest {

    @Test void completionCapturesAuthoritativeTextUsingCurrentExecutionStateEvidence() throws Exception {
        var fixture = new Fixture();
        var execution = fixture.completion("准确成果正文", Set.of("answer"));
        fixture.service.captureCompletion(fixture.work, execution, new ResultRef("execution-state:run-1:7"));
        var payloads = fixture.capturedPayloads();
        assertEquals(1, payloads.size());
        var result = new ObjectMapper().readTree(payloads.getFirst());
        assertEquals("准确成果正文", result.path("content").asText());
        assertEquals("execution-state:run-1:7", result.path("source").path("event_ref").asText());
    }

    @Test void completionCapturesEachNamedOutputFromAuthoritativeRunRatherThanDelegationSummary() throws Exception {
        var fixture = new Fixture();
        var execution = fixture.completion("{\"outputs\":{\"answer\":\"正文\",\"sources\":\"依据\"}}", Set.of("answer", "sources"));
        fixture.service.captureCompletion(fixture.work, execution, new ResultRef("execution-state:run-1:7"));
        var values = fixture.capturedPayloads().stream().map(value -> {
            try { return new ObjectMapper().readTree(value).path("content").asText(); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
        }).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("正文", "依据"), values);
    }

    @Test void completionRejectsWrongEvidencePrefixAndStaleRevisionWithoutPersistingResults() {
        var fixture = new Fixture();
        var execution = fixture.completion("准确成果正文", Set.of("answer"));
        for (String evidence : List.of("execution-run-state:run-1:7", "execution-state:run-1:6"))
            assertRejected("task_result_run_evidence_mismatch", () -> fixture.service.captureCompletion(fixture.work, execution, new ResultRef(evidence)));
        assertTrue(fixture.capturedPayloads().isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=DagWork.TaskStatus.class,names={"FAILED","CANCELLED"})
    void unsuccessfulPrimaryNamedOutputsHaveDistinctDisplayIdsWithoutSuccessfulOutputs(DagWork.TaskStatus status) throws Exception {
        var fixture=new Fixture();
        var task=new DagPlan.Task("primary-step","主步骤",fixture.agent,DagPlan.ExecutorType.PRIMARY,"核对结果",List.of(),Set.of("报告","来源/限制"),Set.of());
        var source=new ResultRef("plan-finish:primary-step");
        var execution=new DagWork.Execution(new DagWork.Binding("primary-admission",1,task,fixture.parentRunRef,Map.of()),
                status,Optional.empty(),false,0,Map.of(),"任务未完成",Optional.of(source));
        when(fixture.work.plan()).thenReturn(new DagPlan(1,false,List.of(task)));
        when(fixture.work.executions()).thenReturn(Map.of(task.id(),execution));
        when(fixture.work.coordinator()).thenReturn(fixture.agent);
        var bindings=(AgentRunBindings)org.springframework.test.util.ReflectionTestUtils.getField(fixture.service,"bindings");
        var binding=mock(AgentRunBindings.Binding.class);when(binding.owner()).thenReturn(fixture.parentOwner);
        when(bindings.find(fixture.parentRunRef)).thenReturn(Optional.of(binding));
        var event=mock(com.skillforge.runtime.api.port.CommittedSessionEvent.class);
        when(event.owner()).thenReturn(fixture.parentOwner);when(event.runRef()).thenReturn(Optional.of(fixture.parentRunRef));
        when(event.agentId()).thenReturn(fixture.agent);when(event.inputId()).thenReturn(fixture.rootInput);
        when(event.eventType()).thenReturn("plan.step.finished");when(event.occurredAt()).thenReturn(Instant.EPOCH);
        when(event.payload()).thenReturn(new TypedPayload("skillforge.plan.step.finished.v1",new ObjectMapper().writeValueAsString(Map.of(
                "work_id",fixture.rootInput.value(),"task_id",task.id(),"admission_id","primary-admission","outcome",status.name(),"summary","任务未完成","result_refs",List.of(),"outputs",Map.of()))));
        var log=mock(com.skillforge.runtime.api.port.SessionLogPort.class);
        when(log.findByEvent(fixture.parentOwner,new com.skillforge.runtime.api.EventId(source.value()))).thenReturn(Optional.of(event));
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service,"planEvents",log);
        var displayed=fixture.service.report(fixture.work,execution);
        assertEquals(2,displayed.size());
        assertEquals(2,displayed.stream().map(row->row.get("result_id")).distinct().count());
        assertTrue(displayed.stream().allMatch(row->row.get("result_id").toString().startsWith("step-status:"+source.value()+"#")));
        assertTrue(displayed.stream().allMatch(row->row.get("status").equals("failed")));
        assertTrue(execution.outputs().isEmpty(),"展示身份不能制造已履约输出");
    }

    @Test
    void acceptsSucceededActionWithMatchingReceiptAndPublicObservation() {
        Fixture fixture = new Fixture();

        TeamResultService.VerifiedOperation verified = fixture.verify("succeeded");

        assertEquals("calendar.send", verified.receipt().sourceTool());
        assertEquals(ExecutionStore.ActionStatus.SUCCEEDED.name(), verified.receipt().actionState());
        assertEquals(fixture.factRef.value(), verified.source().eventRef());
        assertEquals(Set.of("https://cdn.example.org/result", "https://docs.example.org/result"),
                verified.publicUrls());
        assertFalse(verified.publicUrls().contains("https://model.example.org/result"));
    }

    @Test
    void acceptsFailedActionOnlyAsFailedSubmission() {
        Fixture fixture = new Fixture();
        fixture.terminal(ExecutionStore.ActionStatus.FAILED,
                ExecutionStore.ResultKind.FAILED, Optional.empty(), Optional.of("provider_rejected"));

        TeamResultService.VerifiedOperation verified = fixture.verify("failed");

        assertEquals(ExecutionStore.ActionStatus.FAILED.name(), verified.receipt().actionState());
        assertEquals(com.skillforge.runtime.session.AgentExecutionCodec.TOOL_FAILURE_SCHEMA, verified.receipt().observationSchema());
        assertTrue(verified.receipt().observationJson().contains("\"reasonCode\":\"provider_rejected\""));
        assertTrue(verified.publicUrls().isEmpty());
    }

    @Test
    void rejectsOutcomeUnknownAction() {
        Fixture fixture = new Fixture();
        fixture.terminal(ExecutionStore.ActionStatus.UNKNOWN,
                ExecutionStore.ResultKind.UNKNOWN, Optional.empty(), Optional.of("timeout"));

        assertRejected("operation_result_action_not_determinate", () -> fixture.verify("failed"));
    }

    @Test
    void rejectsActionFromAnotherRun() {
        Fixture fixture = new Fixture();
        RunIdentity anotherRun = new RunIdentity(fixture.childOwner, fixture.agent, fixture.rootInput, new RunRef(fixture.tenant, "run-2"), new TraceEnvelope("trace-2", "span-2", fixture.rootInput, Optional.empty()), new ResultRef("identity:other-causation"), new RunRequester.EndUser((fixture.childOwner).requireUser()), java.util.Optional.empty(), new RunOriginRef((fixture.childOwner).sessionRef().tenantId(), "input:" + (fixture.rootInput).value()));
        when(fixture.action.run()).thenReturn(anotherRun.runRef());
        when(fixture.fact.run()).thenReturn(anotherRun.runRef());

        assertRejected("operation_result_receipt_mismatch", () -> fixture.verify("succeeded"));
    }

    @Test
    void rejectsSelectedAttemptBoundToAnotherAction() {
        Fixture fixture = new Fixture();
        when(fixture.fact.action()).thenReturn(new ActionRef(fixture.tenant,"another-action"));

        assertRejected("operation_result_receipt_mismatch", () -> fixture.verify("succeeded"));
    }

    @Test
    void rejectsCurrentSubmitAttemptBeforeReadingLedger() {
        Fixture fixture = new Fixture();
        TeamResultService.Submission submission = fixture.submission("succeeded",
                fixture.context.invocationId(), fixture.context.attemptId(), fixture.factRef.value());

        assertRejected("operation_result_current_attempt_forbidden",
                () -> fixture.service.verifyOperationReceipt(fixture.work, fixture.execution, submission, fixture.context));
        verifyNoInteractions(fixture.store);
    }

    @Test
    void rejectsSubmissionStatusThatDiffersFromActionState() {
        Fixture fixture = new Fixture();

        assertRejected("operation_result_status_mismatch", () -> fixture.verify("failed"));
    }

    @Test
    void rejectsExternalAddressThatWasNotExplicitlyPublishedByObservation() {
        Fixture fixture = new Fixture();
        TeamResultService.VerifiedOperation verified = fixture.verify("succeeded");
        var published = new TeamResultService.RepresentationInput("公开结果", "text/html", "",
                "https://cdn.example.org/result", "external");
        var modelSupplied = new TeamResultService.RepresentationInput("模型提供地址", "text/html", "",
                "https://model.example.org/result", "external");

        fixture.service.verifyExternalRepresentation(verified, published);
        assertRejected("task_result_external_public_url_unverified",
                () -> fixture.service.verifyExternalRepresentation(verified, modelSupplied));
    }

    @Test
    void rejectsPublicObservationAddressWithLocalhostTrailingDot() {
        Fixture fixture = new Fixture();
        fixture.terminal(ExecutionStore.ActionStatus.SUCCEEDED,
                ExecutionStore.ResultKind.SUCCEEDED,
                Optional.of(new TypedPayload("calendar-send-result.v1",
                        "{\"public_url\":\"https://localhost./hidden\"}")), Optional.empty());

        assertRejected("operation_result_public_url_invalid", () -> fixture.verify("succeeded"));
    }

    private static void assertRejected(String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(code, failure.getMessage());
    }

    private static final class Fixture {
        private final TenantId tenant = new TenantId("tenant-1");
        private final AgentId agent = new AgentId("agent-1");
        private final InputId rootInput = new InputId("input-1");
        private final RunRef runRef = new RunRef(tenant, "run-1");
        private final RunRef parentRunRef = new RunRef(tenant, "parent-run-1");
        private final SessionOwner parentOwner = SessionOwner.user(new SessionRef(tenant, "session-1"),SessionTarget.team("team-1"), new EndUserId(1L));
        private final SessionOwner childOwner = SessionOwner.user(new SessionRef(tenant, "delegation:admission-1"),SessionTarget.team("team-1"), new EndUserId(1L));
        private final RunIdentity run = new RunIdentity(childOwner, agent, rootInput, runRef, new TraceEnvelope("trace-1", "span-1", rootInput, Optional.empty()), new ResultRef("identity:causation"), new RunRequester.EndUser((childOwner).requireUser()), java.util.Optional.empty(), new RunOriginRef((childOwner).sessionRef().tenantId(), "input:" + (rootInput).value()));
        private final VerifiedIdentity identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, childOwner.requireUser()), childOwner,Set.of("tool.invoke"), 1, new ResultRef("identity:verified"));
        private final PlatformToolExecutionContext context = new PlatformToolExecutionContext(new PortCallContext(run, identity,new InvocationControl(Instant.MAX, () -> false)),"submit-invocation", "submit-attempt");
        private final DagPlan.Task task = new DagPlan.Task("task-1", "发送日程", agent, "发送已批准日程",List.of(), Set.of("answer"), Set.of());
        private final DagWork.Execution execution = new DagWork.Execution(new DagWork.Binding("admission-1", 1, task, runRef, Map.of()),DagWork.TaskStatus.COMPLETED, Optional.empty(), false, 0, Map.of(), "", Optional.empty());
        private final DagWork work = mock(DagWork.class);
        private final ExecutionStore store=mock(ExecutionStore.class);
        private final ExecutionStore.Action action=mock(ExecutionStore.Action.class);
        private final ExecutionStore.Attempt fact=mock(ExecutionStore.Attempt.class);
        private final ActionRef actionRef=new ActionRef(tenant,"action-1");
        private final ResultRef factRef=new ResultRef("action-result:1");
        private final com.skillforge.runtime.json.CodecLimits limits=new com.skillforge.runtime.json.CodecLimits(1_000_000,100_000,128);
        private final com.skillforge.runtime.session.AgentExecutionCodec codec=new com.skillforge.runtime.session.AgentExecutionCodec(limits);
        private final com.skillforge.runtime.session.SessionRuntimePayloads payloads=new com.skillforge.runtime.session.SessionRuntimePayloads();
        private final com.skillforge.runtime.core.CoreRuntime core=new com.skillforge.runtime.core.CoreRuntime(limits,payloads);
        private final com.skillforge.runtime.session.AgentExecutionCodec.ToolRequest request;
        private final TeamResultService service;

        private Fixture() {
            when(work.key()).thenReturn(new DagWork.Key(parentOwner,rootInput));
            var workRequest=mock(DagWork.Request.class);when(workRequest.controlRun()).thenReturn(parentRunRef);when(work.request()).thenReturn(workRequest);
            var actualRun=mock(ExecutionStore.Run.class);when(actualRun.identity()).thenReturn(run);when(store.load(runRef)).thenReturn(Optional.of(actualRun));
            when(action.reference()).thenReturn(actionRef);when(action.run()).thenReturn(runRef);when(action.kind()).thenReturn(ExecutionStore.Kind.TOOL);
            when(action.selectedAttempt()).thenReturn(Optional.of("upstream-attempt"));when(action.currentAttempt()).thenReturn("upstream-attempt");
            when(fact.id()).thenReturn("upstream-attempt");when(fact.action()).thenReturn(actionRef);when(fact.run()).thenReturn(runRef);when(fact.adopted()).thenReturn(true);
            when(fact.result()).thenReturn(Optional.of(factRef));when(fact.feedback()).thenReturn(Optional.of(new ResultRef("safe-feedback")));
            when(store.action(actionRef)).thenReturn(Optional.of(action));when(store.attempt(runRef,"upstream-attempt")).thenReturn(Optional.of(fact));
            var target=new com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget(new com.skillforge.runtime.api.port.ToolPortal.ToolHandle("tool:calendar"),
                    new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.TOOL,"calendar","1"),"deployment",1,
                    new TypedPayload("json-schema","{}"),new TypedPayload("json-schema","{}"),com.skillforge.runtime.api.ToolEffect.READ_ONLY,true,
                    java.time.Duration.ofSeconds(30),true,com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER,Set.of(),Set.of(),false);
            var binding=new com.skillforge.runtime.api.port.ToolPortal.ToolBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM,"calendar.send","calendar.send");
            request=new com.skillforge.runtime.session.AgentExecutionCodec.ToolRequest(target,binding,new TypedPayload("application/json","{}"),"call","wire");
            var requestRef=new ResultRef("actual-request");when(action.request()).thenReturn(requestRef);when(fact.request()).thenReturn(requestRef);
            when(store.material(tenant,requestRef)).thenReturn(Optional.of(new ExecutionStore.Material(requestRef,codec.encodeTool(request))));
            terminal(ExecutionStore.ActionStatus.SUCCEEDED,ExecutionStore.ResultKind.SUCCEEDED,
                    Optional.of(new TypedPayload("calendar-send-result.v1","{\"public_url\":\"https://cdn.example.org/result\",\"public_urls\":[\"https://docs.example.org/result\"],\"url\":\"https://model.example.org/result\"}")),Optional.empty());
            var studioRuns=mock(AgentRunBindings.class);var frozen=mock(AgentRunBindings.Binding.class);
            when(frozen.role()).thenReturn(AgentRunBindings.Role.DELEGATE);when(frozen.parentRunRef()).thenReturn(Optional.of(parentRunRef));when(frozen.actor()).thenReturn(agent);when(frozen.owner()).thenReturn(childOwner);when(studioRuns.find(runRef)).thenReturn(Optional.of(frozen));
            var properties=new com.skillforge.runtime.starter.AgentRuntimeProperties();
            service=new TeamResultService(mock(TeamResultMapper.class),new ObjectMapper(),mock(ArtifactStorage.class),mock(ArtifactAccess.class),mock(TeamService.class),
                    new TeamRuntimeFacts(store,codec,new com.fasterxml.jackson.databind.ObjectMapper()),mock(com.skillforge.runtime.api.port.SessionLogPort.class),studioRuns,mock(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.class));
        }

        private DagWork.Execution completion(String finalText, Set<String> outputs) {
            var task = new DagPlan.Task("task-1", "成果", agent, "完成任务", List.of(), outputs, Set.of());
            var execution = new DagWork.Execution(new DagWork.Binding("admission-1", 1, task, runRef, Map.of()),
                    DagWork.TaskStatus.RUNNING, Optional.of(new DagWork.Permit(1, Instant.MAX)), false, 0, Map.of(), "", Optional.empty());
            when(work.plan()).thenReturn(new DagPlan(1, false, List.of(task)));
            when(work.executions()).thenReturn(Map.of(task.id(), execution));
            var completed = store.load(runRef).orElseThrow();
            when(completed.status()).thenReturn(com.skillforge.runtime.api.RunStatus.COMPLETED);
            when(completed.revision()).thenReturn(7L);
            var facts = mock(TeamRuntimeFacts.class);
            when(facts.run(childOwner, runRef)).thenReturn(completed);
            when(facts.completedText(completed)).thenReturn(finalText);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "runtimeFacts", facts);
            var mapper = (TeamResultMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "mapper");
            when(mapper.workStatus(any(), any(), any(), any(), any(), any())).thenReturn(List.of("ACTIVE"));
            var guard = (com.skillforge.core.chat.integration.publicread.PublicOutputGuard)
                    org.springframework.test.util.ReflectionTestUtils.getField(service, "publicOutputs");
            when(guard.capture(childOwner)).thenReturn(mock(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.Scope.class));
            return execution;
        }

        private List<String> capturedPayloads() {
            var mapper = (TeamResultMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "mapper");
            return org.mockito.Mockito.mockingDetails(mapper).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("insert"))
                    .map(call -> (String) call.getArgument(10)).toList();
        }

        private void terminal(ExecutionStore.ActionStatus state,ExecutionStore.ResultKind status,Optional<TypedPayload> observation,Optional<String> reason) {
            when(action.status()).thenReturn(state);when(fact.resultKind()).thenReturn(Optional.of(status));
            com.skillforge.runtime.api.port.ToolPortal.ToolOutcome outcome;
            if(status==ExecutionStore.ResultKind.FAILED)outcome=new com.skillforge.runtime.api.port.ToolPortal.ToolOutcome.Failed(
                    new com.skillforge.runtime.api.port.PortFailure(reason.orElseThrow(),com.skillforge.runtime.api.port.RetryMode.NONE,new ResultRef("provider-result")));
            else if(status==ExecutionStore.ResultKind.UNKNOWN)outcome=new com.skillforge.runtime.api.port.ToolPortal.ToolOutcome.OutcomeUnknown(
                    new com.skillforge.runtime.api.port.PortFailure(reason.orElse("timeout"),com.skillforge.runtime.api.port.RetryMode.NONE,new ResultRef("provider-result")));
            else outcome=new com.skillforge.runtime.api.port.ToolPortal.ToolOutcome.Succeeded(new TypedPayload(payloads.TOOL_RESULT_SCHEMA,
                    "{\"content\":[],\"structuredContent\":"+observation.orElse(new TypedPayload("json","{}")).canonicalJson()+"}"),new ResultRef("provider-result"),List.of());
            var raw=codec.encodeOutcome(core.portalResults(payloads,payloads,payloads).encodeTool(PortalCallKind.TOOL_INVOKE,outcome));
            when(store.material(tenant,factRef)).thenReturn(Optional.of(new ExecutionStore.Material(factRef,raw)));
            var safe=codec.safeToolFeedback(request,outcome);var safeRef=new ResultRef("safe-feedback");
            when(fact.feedback()).thenReturn(safe.map(ignored->safeRef));
            safe.ifPresent(value->when(store.material(tenant,safeRef)).thenReturn(Optional.of(new ExecutionStore.Material(safeRef,value))));
        }
        private TeamResultService.VerifiedOperation verify(String status){return service.verifyOperationReceipt(work,execution,submission(status,actionRef.actionId(),"upstream-attempt",factRef.value()),context);}
        private TeamResultService.Submission submission(String status,String invocationId,String attemptId,String receiptFactRef) {
            return new TeamResultService.Submission("answer","发送日程","operation",status,"日程已处理","",List.of(),List.of(),new TeamResultService.OperationReceiptInput(actionRef.actionId(),invocationId,attemptId,receiptFactRef));
        }
    }
}

package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.skillforge.core.capability.integration.runtime.AgentRuntimeMcpToolProvider;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgePortal;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.SessionExecutionWorkflows;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.WorkflowInteractionView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 已登记Attempt的明确前置拒绝与真实外调异常必须保持不同结果事实。 */
class RegisteredWorkflowTestRejectionTest {
    @Test void registeredDispatchAndResumeGuardRejectionsProduceOriginalExecutionFailureWithoutCallingPortal() {
        for (boolean resume : List.of(false, true)) {
            var f = new Fixture();
            var call = f.call(resume);
            doThrow(new ApiException(HttpStatus.CONFLICT, "workflow_test_not_live", "测试会话已关闭", false))
                    .when(f.policy).provider(call.registration().registered().run(), call.registration().prepared());

            var result = f.invoke(call);

            assertEquals(ExecutionStore.ResultKind.FAILED, result.kind());
            assertEquals(Optional.of(f.execution.executionId()), result.externalOperation());
            var reference = new ResultRef("execution-result:" + call.registration().registered().attempt().id());
            assertEquals(reference, result.result().reference());
            var envelope = f.protocol.decodeResult(result.result().content());
            assertTrue(envelope.waiting().isEmpty());
            assertTrue(envelope.human().isEmpty());
            var actual = assertInstanceOf(WorkflowResult.Failed.class, f.outcomes.decodeWorkflow(
                    f.codec.decodeOutcome(envelope.outcome()), call.request().callKind()));
            assertEquals(f.execution, actual.executionRef());
            assertEquals(OptionalLong.empty(), actual.revision());
            assertEquals(new PortFailure("workflow_test_not_live", RetryMode.NONE, reference), actual.failure());
            assertTrue(result.feedback().isPresent());
            verify(f.policy).provider(call.registration().registered().run(), call.registration().prepared());
            verifyNoInteractions(f.portal, f.executions, f.interactions);
        }
    }

    @Test void exceptionsFromTheActualPortalAreNotReclassifiedAsAnUnsentGuardRejection() {
        for (var failure : List.of(
                new ApiException(HttpStatus.FORBIDDEN, "provider_failure_after_send", "供应商发送后返回异常", false),
                new IllegalStateException("transport_lost_after_send"))) {
            var f = new Fixture();
            var call = f.call(false);
            when(f.portal.dispatchRegistered(any(), any(), any())).thenThrow(failure);

            assertSame(failure, assertThrows(RuntimeException.class, () -> f.invoke(call)));

            verify(f.policy).provider(call.registration().registered().run(), call.registration().prepared());
            verify(f.portal).dispatchRegistered(any(), any(), any());
            verifyNoMoreInteractions(f.portal);
            verifyNoInteractions(f.executions, f.interactions);
        }
    }

    @Test void actualPortalUnknownRetainsItsOriginalReceiptAndNeverBecomesAKnownFailure() {
        var f = new Fixture();
        var call = f.call(false);
        var unknown = new WorkflowResult.OutcomeUnknown(f.execution, OptionalLong.of(8),
                new PortFailure("provider_outcome_unknown", RetryMode.RESULT_QUERYABLE, new ResultRef("original-provider-receipt")));
        when(f.portal.dispatchRegistered(any(), any(), any())).thenReturn(unknown);

        var result = f.invoke(call);

        assertEquals(ExecutionStore.ResultKind.UNKNOWN, result.kind());
        var envelope = f.protocol.decodeResult(result.result().content());
        assertEquals(unknown, f.outcomes.decodeWorkflow(f.codec.decodeOutcome(envelope.outcome()), call.request().callKind()));
        assertTrue(result.feedback().isEmpty());
        verify(f.portal).dispatchRegistered(any(), any(), any());
        verifyNoMoreInteractions(f.portal);
    }

    private record Call(RegisteredProviderFixture registration, SessionExecutionWorkflows.Request request) { }

    /** 使用已有正式登记夹具与实际等待记录，避免靠未登记的伪造调用触发预期分支。 */
    private static final class Fixture {
        private static final TenantId TENANT = new TenantId("7");
        private final ExecutionStore store = mock(ExecutionStore.class);
        private final WorkflowTestRuntimePolicy policy = mock(WorkflowTestRuntimePolicy.class);
        private final SdkWorkflowPortal portal = mock(SdkWorkflowPortal.class);
        private final JdbcExecutionLedgerPort executions = mock(JdbcExecutionLedgerPort.class);
        private final WorkflowInteractionView interactions = mock(WorkflowInteractionView.class);
        private final CodecLimits limits = new CodecLimits(100_000, 10_000, 100);
        private final SessionRuntimePayloads payloads = new SessionRuntimePayloads();
        private final CoreRuntime core = new CoreRuntime(limits, payloads);
        private final CoreRuntime.PortalResults outcomes = core.portalResults(payloads, payloads, payloads);
        private final AgentExecutionCodec codec = new AgentExecutionCodec(limits);
        private final SessionExecutionWorkflows protocol = new SessionExecutionWorkflows(limits);
        private final SessionOwner owner = SessionOwner.user(new SessionRef(TENANT, "original-test-session"), new AgentId("31"), new EndUserId(51));
        private final InputId input = new InputId("original-input");
        private final RunIdentity identity = new RunIdentity(owner, new AgentId("31"), input, new RunRef(TENANT, "run:original-input"),
                new TraceEnvelope("original-trace", "original-span", input, Optional.empty()), new ResultRef("verified-original"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(TENANT, "input:original-input"));
        private final VerifiedIdentity actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, owner.requireUser()), owner,
                Set.of("sessions.write"), 1, identity.causationIdentityRef());
        private final CapabilitySnapshotRef snapshot = new CapabilitySnapshotRef(owner.sessionRef(), input, "original-snapshot", 1);
        private final FrozenWorkflowTarget target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:original"), "91", 101,
                new CapabilityRef(CapabilityType.WORKFLOW, "111", "121"), Duration.ofSeconds(30), 131);
        private final ExecutionRef execution = new ExecutionRef(TENANT, "original-workflow-execution");
        private final SessionExecutionWorkflows.AuthorizedInputOrigin origin = new SessionExecutionWorkflows.AuthorizedInputOrigin("workflow-test:41");
        private final RegisteredExecutionProviderCalls providers = new RegisteredExecutionProviderCalls(mock(OpenAiModelPort.class),
                mock(AgentRuntimeModelInvocationAudit.class), mock(AgentRuntimeToolInvocationAudit.class), mock(AgentRuntimePlatformToolProvider.class),
                mock(AgentRuntimeMcpToolProvider.class), mock(JdbcKnowledgePortal.class), store, core, limits, policy);

        private Call call(boolean resume) {
            var request = new SessionExecutionWorkflows.Dispatch(snapshot, target, execution, new TypedPayload("application/json", "{}"), List.of(), origin);
            var original = RegisteredProviderFixture.create(identity, actor, ExecutionStore.Kind.WORKFLOW, PortalCallKind.WORKFLOW_DISPATCH,
                    target.handle().value(), protocol.encode(request));
            if (!resume) return new Call(original, request);
            var response = new ExecutionStore.Material(new ResultRef("accepted-response"), new TypedPayload("application/json", "{\"confirmed\":false}"));
            var resumed = new SessionExecutionWorkflows.Resume(snapshot, target, execution, "original-suspend", "original-wait-action", 5,
                    response.content(), origin, response.reference(), new InputId("reply-input"));
            var registration = RegisteredProviderFixture.resume(original, protocol.encode(resumed));
            var reference = registration.registered().attempt().continuation().orElseThrow();
            var accepted = new ExecutionStore.AcceptedResponse(new InputId("reply-input"), response.reference(), "original-response-fingerprint",
                    actor.verificationRef(), 5);
            var waiting = new ExecutionStore.Waiting(reference, ExecutionStore.WaitKind.WORKFLOW, identity.runRef(), original.registered().attempt().id(),
                    Map.of(), Optional.of(new ExecutionStore.WorkflowWait(execution, resumed.suspendRef(), resumed.actionKey(), resumed.expectedRevision())),
                    Optional.of(accepted), Optional.of(registration.registered().attempt().id()), OptionalLong.empty());
            when(store.waiting(identity.runRef(), reference)).thenReturn(Optional.of(waiting));
            when(store.material(TENANT, response.reference())).thenReturn(Optional.of(response));
            return new Call(registration, resumed);
        }

        private ExecutionCoordinator.CapabilityResult invoke(Call call) {
            return providers.workflow(call.registration().registered(), call.registration().prepared(), call.request(), portal, executions, interactions,
                    new InvocationControl(Instant.now().plusSeconds(30), () -> false));
        }
    }
}

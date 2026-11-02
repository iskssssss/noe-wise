package com.skillforge.runtime.adapter.jdbc.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** IAM 资源只验证明确动作与完整规范身份；不把构造资源当成来源或消费许可证明。 */
class WorkflowPolicyResourceTest {
    @Test void everyExplicitResourceHasExactOwnerAndRejectsWrongSchemaOrExtraFields() {
        var owner = SessionOwner.user(new SessionRef(new TenantId("1"), "session"), new AgentId("10"), new EndUserId(20));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(owner.sessionRef().tenantId(), owner.requireUser()), owner, Set.of("sessions.write"), 6, new ResultRef("verified"));
        var resources = resources(identity);
        var target=new SessionPolicyResource.ExecutionTarget(new RunRef(owner.sessionRef().tenantId(),"run"),new AgentId("10"));
        assertEquals(Optional.of(target),WorkflowPolicyResource.executionTarget("workflow.dispatch",resources.get("workflow.dispatch")));
        assertEquals(Optional.of(target),WorkflowPolicyResource.executionTarget("workflow.resume",resources.get("workflow.resume")));
        for(String action:List.of("workflow.cancel","workflow.recover","workflow.query","workflow.execute"))
            assertTrue(WorkflowPolicyResource.executionTarget(action,resources.get(action)).isEmpty(),"清理与查询不冒充新Run派发");
        for (var entry : resources.entrySet()) {
            assertEquals(Optional.of(owner), WorkflowPolicyResource.owner(entry.getKey(), entry.getValue()));
            assertTrue(WorkflowPolicyResource.owner("workflow.*", entry.getValue()).isEmpty());
            assertTrue(WorkflowPolicyResource.owner(entry.getKey(), new TypedPayload("unknown.v1", entry.getValue().canonicalJson())).isEmpty());
            String json = entry.getValue().canonicalJson();
            for (String malformed : List.of(json + " ", json.substring(0, json.length() - 1) + ",\"extra\"]", json.replace("\"20\"", "\"020\"")))
                assertTrue(WorkflowPolicyResource.owner(entry.getKey(), new TypedPayload(entry.getValue().schemaId(), malformed)).isEmpty());
        }
    }

    @Test void resourcePreservesArgumentsEvidenceControlAndCompleteRunChanges() {
        var owner = SessionOwner.user(new SessionRef(new TenantId("1"), "session"), new AgentId("10"), new EndUserId(20));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(owner.sessionRef().tenantId(), owner.requireUser()), owner, Set.of("sessions.write"), 6, new ResultRef("verified"));
        var calls = calls(identity); var dispatch = calls.dispatch();
        var original = WorkflowPolicyResource.dispatch(dispatch);
        assertNotEquals(original, WorkflowPolicyResource.dispatch(new WorkflowDispatch(dispatch.context(), dispatch.snapshot(), dispatch.target(), dispatch.executionRef(),
                new TypedPayload("input.v1", "{\"changed\":true}"), dispatch.evidenceRefs(), dispatch.idempotencyKey())));
        assertNotEquals(original, WorkflowPolicyResource.dispatch(new WorkflowDispatch(dispatch.context(), dispatch.snapshot(), dispatch.target(), dispatch.executionRef(), dispatch.parameters(),
                List.of(new EvidenceRef(owner.sessionRef(), "bundle", List.of("item"), 1, new EventId("evidence"))), dispatch.idempotencyKey())));
        var resume = calls.resume();
        assertNotEquals(WorkflowPolicyResource.resume(resume), WorkflowPolicyResource.resume(new WorkflowResume(resume.context(), resume.snapshot(), resume.target(), resume.executionRef(), resume.suspendRef(), resume.actionKey(), resume.response(), 2, resume.idempotencyKey())));
        var cancel = calls.cancel();
        assertNotEquals(WorkflowPolicyResource.cancel(cancel), WorkflowPolicyResource.cancel(new WorkflowCancel(cancel.context(), cancel.snapshot(), cancel.target(), cancel.executionRef(), cancel.actionKey(), cancel.expectedRevision(), "other", cancel.idempotencyKey())));
    }

    /** 供真实IAM测试重用结构合法候选；这里不实现生产membership或permit guard。 */
    static Map<String, TypedPayload> resources(VerifiedIdentity identity) {
        var calls = calls(identity);
        return Map.of("workflow.dispatch", WorkflowPolicyResource.dispatch(calls.dispatch()), "workflow.resume", WorkflowPolicyResource.resume(calls.resume()),
                "workflow.recover", WorkflowPolicyResource.recovery(calls.recovery()), "workflow.cancel", WorkflowPolicyResource.cancel(calls.cancel()),
                "workflow.query", WorkflowPolicyResource.query(new WorkflowQuery(identity, calls.dispatch().executionRef()), calls.dispatch().context().callContext().runIdentity().agentId()),
                "workflow.execute", WorkflowPolicyResource.execution(new WorkflowQuery(identity, calls.dispatch().executionRef()), calls.dispatch().context().callContext().runIdentity().agentId()));
    }
    private static Calls calls(VerifiedIdentity identity) {
        var owner = identity.owner(); var tenant = owner.sessionRef().tenantId(); var input = new InputId("input");
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), identity.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(),"execution-input:"+(new RunRef(tenant, "run")).runId()));
        var snapshot = new CapabilitySnapshotRef(owner.sessionRef(), input, "snapshot", 1);
        var target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:mount"), "instance", 1, new CapabilityRef(CapabilityType.WORKFLOW, "capability", "version"), Duration.ofSeconds(30), 1L);
        var execution = new ExecutionRef(tenant, "execution"); var payload = new TypedPayload("input.v1", "{}"); var key = new IdempotencyKey("key");
        return new Calls(new WorkflowDispatch(provider(run, identity, PortalCallKind.WORKFLOW_DISPATCH), snapshot, target, execution, payload, List.of(), key),
                new WorkflowResume(provider(run, identity, PortalCallKind.WORKFLOW_RESUME), snapshot, target, execution, "suspend", "action", payload, 1, key),
                new WorkflowResultQuery(provider(run, identity, PortalCallKind.WORKFLOW_RECOVER_RESULT), snapshot, target, execution),
                new WorkflowCancel(provider(run, identity, PortalCallKind.WORKFLOW_CANCEL), snapshot, target, execution, "action", 1, "cancelled", key));
    }
    private static ProviderCallContext provider(RunIdentity run, VerifiedIdentity identity, PortalCallKind kind) {
        var action = new ActionRef(run.runRef().tenantId(), "action"); var now = Instant.now();
        var fence = new LeaseFence(action.tenantId(), "claim", "worker", 1, now.plusSeconds(60));
        var dispatch = new ProviderDispatch(run.runRef(),action,action.actionId(),"attempt",kind,new ResultRef("invocation-snapshot"),
                new ResultRef("capability:workflow:version"),"0".repeat(64),fence.fencingToken());
        return ProviderCallContext.dispatched(new PortCallContext(run, identity, new InvocationControl(now.plusSeconds(30), () -> false)),dispatch);
    }
    private record Calls(WorkflowDispatch dispatch, WorkflowResume resume, WorkflowResultQuery recovery, WorkflowCancel cancel) { }
}

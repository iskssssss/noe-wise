package com.skillforge.core.foundation.integration.runtime.identity;

import com.skillforge.runtime.adapter.jdbc.identity.KnowledgePolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.WorkflowPolicyResource;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.KnowledgePortal.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** IAM 测试通过 SDK 正式值对象构造合法候选，授权仍由真实身份与数据库事实决定。 */
final class IdentityPolicyResources {
    private IdentityPolicyResources() { }

    static TypedPayload knowledge(VerifiedIdentity identity) {
        var owner = identity.owner();
        var tenant = owner.sessionRef().tenantId();
        var trace = new TraceEnvelope("trace", "span", new InputId("root"), Optional.empty());
        var run = new RunIdentity(owner, owner.target().requireAgent(), new InputId("input"),
                new RunRef(tenant, "run"), trace, new ResultRef("cause"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(),
                new RunOriginRef(tenant, "execution-input:run"));
        var snapshot = new CapabilitySnapshotRef(owner.sessionRef(), run.causationInputId(), "snapshot", 1);
        var versions = Set.of(new KnowledgeSourceVersion("source", "v1"));
        var operators = Set.of(KnowledgeOperatorType.KEYWORD);
        var target = new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:docs"),
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "capability", "version"),
                "descriptor", 1, versions, operators, Duration.ofSeconds(2));
        var request = new KnowledgeRequest("request", tenant, run.requester(), run.agentId(),
                owner.sessionRef(), trace, KnowledgeMode.GROUNDING, "answer", new com.skillforge.runtime.api.KnowledgeOperation.Search("question🚀"),
                Set.of("source"), versions, operators, new KnowledgeBudget(2, 3, 40, 500, 2000),
                Instant.parse("2026-09-10T00:00:00Z"), Optional.empty(), true);
        return KnowledgePolicyResource.dispatch(run, snapshot, target, request, new EventId("source-event"),
                new IdempotencyKey("key"), new ActionRef(tenant, "action"),
                new ResultRef("invocation-ref"), "fingerprint");
    }

    static Map<String, TypedPayload> workflows(VerifiedIdentity identity) {
        var owner = identity.owner();
        var tenant = owner.sessionRef().tenantId();
        var input = new InputId("input");
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(tenant, "run"),
                new TraceEnvelope("trace", "span", input, Optional.empty()), identity.verificationRef(),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(),
                new RunOriginRef(tenant, "execution-input:run"));
        var snapshot = new CapabilitySnapshotRef(owner.sessionRef(), input, "snapshot", 1);
        var target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:mount"), "instance", 1,
                new CapabilityRef(CapabilityType.WORKFLOW, "capability", "version"), Duration.ofSeconds(30), 1L);
        var execution = new ExecutionRef(tenant, "execution");
        var action = new ActionRef(tenant, "action");
        var request = new ResultRef("invocation-snapshot");
        var payload = new TypedPayload("input.v1", "{}");
        var fingerprint = "0".repeat(64);
        var query = new WorkflowQuery(identity, execution);
        return Map.of(
                "workflow.dispatch", WorkflowPolicyResource.dispatchResource(run, snapshot, target,
                        execution, payload, List.of(), action, request, fingerprint),
                "workflow.resume", WorkflowPolicyResource.resumeResource(run, snapshot, target,
                        execution, "suspend", "action", 1, payload, action, request, fingerprint),
                "workflow.recover", WorkflowPolicyResource.recoveryResource(run, snapshot, target,
                        execution, action, request, fingerprint),
                "workflow.cancel", WorkflowPolicyResource.cancelResource(run, snapshot, target,
                        execution, "action", 1, "cancelled", action, request, fingerprint),
                "workflow.query", WorkflowPolicyResource.query(query, run.agentId()),
                "workflow.execute", WorkflowPolicyResource.execution(query, run.agentId()));
    }
}

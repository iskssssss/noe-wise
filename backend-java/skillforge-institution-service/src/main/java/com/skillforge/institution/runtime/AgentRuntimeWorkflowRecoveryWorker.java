package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.identity.WorkflowPolicyResource;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 只观察真实外部等待并接受精确响应；Engine继续执行仍由同一新Run协调器拥有。 */
@Component
public final class AgentRuntimeWorkflowRecoveryWorker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentRuntimeWorkflowRecoveryWorker.class);
    private final JdbcExecutionStore store;
    private final JdbcExecutionLedgerPort executions;
    private final JdbcIdentityPolicyPort identities;
    private final AgentRuntimeProperties properties;
    private final AgentRuntimeHostProvisioner provisioner;
    private final SdkWorkflowPortal workflows;
    private final ObjectProvider<AgentRuntimeExecutionWorker> worker;
    private final ObjectProvider<AgentRuntimeWorkflowCancellations> cancellations;
    private final AgentRuntimeWorkflowNodeExecutions nodes;
    private final Clock clock;
    private final ObjectMapper json;

    public AgentRuntimeWorkflowRecoveryWorker(JdbcExecutionStore store, JdbcExecutionLedgerPort executions,
            JdbcIdentityPolicyPort identities, AgentRuntimeProperties properties, AgentRuntimeHostProvisioner provisioner,
            SdkWorkflowPortal workflows, AgentRuntimeWorkflowNodeExecutions nodes, WorkflowNodeRecoveryMapper recovery,
            ObjectProvider<AgentRuntimeExecutionWorker> worker, ObjectProvider<AgentRuntimeWorkflowCancellations> cancellations, Clock clock, ObjectMapper json) {
        this.store = Objects.requireNonNull(store); this.executions = Objects.requireNonNull(executions);
        this.identities = Objects.requireNonNull(identities); this.properties = Objects.requireNonNull(properties);
        this.provisioner = Objects.requireNonNull(provisioner); this.workflows = Objects.requireNonNull(workflows);
        this.nodes = Objects.requireNonNull(nodes); this.worker = Objects.requireNonNull(worker);
        this.cancellations = Objects.requireNonNull(cancellations);
        this.clock = Objects.requireNonNull(clock); this.json = Objects.requireNonNull(json);
        nodes.bindParentWake(this::wake);
        nodes.bindRecovery((tenant, limit) -> recovery.pendingParents(tenant.value(), limit).stream().map(id -> new RunRef(tenant, id)).toList());
    }

    /** 节点提交仅提供原父地址；唤醒必须重新证明当前精确Workflow等待。 */
    public void wake(RunRef reference) {
        try {
            var found = store.load(reference); if (found.isEmpty()) return;
            var parent = found.orElseThrow();
            if (parent.cancelled()) { cancellations.getObject().changed(reference);return; }
            if (parent.phase() != ExecutionStore.Phase.WAITING || parent.waiting().isEmpty()) return;
            var waiting = store.waiting(reference, parent.waiting().orElseThrow()).orElseThrow(AgentRuntimeWorkflowRecoveryWorker::rejected);
            if (waiting.kind() != ExecutionStore.WaitKind.WORKFLOW || !(waiting.internal().orElseThrow() instanceof ExecutionStore.WorkflowWait exact)) return;
            var input = observationInput(reference, waiting.reference(), exact); var actor = authenticate(parent.identity(), input);
            var execution = executions.find(new ExecutionLookup(actor, exact.execution())).orElseThrow(AgentRuntimeWorkflowRecoveryWorker::rejected);
            requireWaiting(parent, waiting, execution);
            if (execution.state() != WorkflowExecutionState.WAITING_EXTERNAL) return;
            var signal = workflows.observeExternal(actor, exact.execution(), new InvocationControl(clock.instant().plusSeconds(10), () -> Thread.currentThread().isInterrupted()));
            if (signal.isEmpty()) return;
            respond(parent, actor, input, waiting.reference(), parent.revision(), signal.orElseThrow());
        } catch (RuntimeException unavailable) {
            LOG.warn("Workflow等待观察暂缓 tenant={} run={} failureType={}", reference.tenantId().value(), reference.runId(), unavailable.getClass().getSimpleName(), unavailable);
        }
    }

    /** 可信人类、系统或HMAC回调入口共同使用本端口；普通Store.resume不能解除内部Workflow等待。 */
    public ExecutionStore.ResumeResult respond(ExecutionStore.Run parent, VerifiedIdentity actor, InputId input,
            ExecutionStore.WaitRef waiting, long revision, TypedPayload response) {
        return respond(parent, actor, input, waiting, revision, response, (session, execution) -> { });
    }

    /** callback交付确认在同一个Store接受事务内完成，不伪造旧InputReceipt。 */
    ExecutionStore.ResumeResult respond(ExecutionStore.Run parent, VerifiedIdentity actor, InputId input,
            ExecutionStore.WaitRef waiting, long revision, TypedPayload response, ResponseCommit commit) {
        if (!parent.identity().owner().equals(actor.owner())) throw rejected();
        var material = new ExecutionStore.Material(new ResultRef("execution-response:" + AgentExecutionCodec.digest(parent.identity().runRef() + "\0" + input.value())), response);
        var result = store.acceptDependency(new ExecutionStore.Resume(actor, parent.identity().runRef(), waiting, revision, input, material,
                AgentExecutionCodec.digest(response.schemaId() + "\0" + response.canonicalJson()), Map.of(), List.of(resolved(parent, input, waiting))),
                (session, current, actualWait, children, actualResponse) -> {
                    if (!current.identity().equals(parent.identity()) || !actualWait.reference().equals(waiting) || !children.isEmpty()
                            || !actualResponse.equals(material)) throw rejected();
                    var exact = (ExecutionStore.WorkflowWait) actualWait.internal().filter(value -> value instanceof ExecutionStore.WorkflowWait).orElseThrow(AgentRuntimeWorkflowRecoveryWorker::rejected);
                    var execution = JdbcExecutionLedgerPort.lockBorrowed(session, exact.execution()).execution();
                    requireWaiting(current, actualWait, execution);
                    if (!(identities.authorize(session, new IdentityPolicyPort.AuthorizationRequest(actor, "workflow.execute",
                            WorkflowPolicyResource.execution(new WorkflowPortal.WorkflowQuery(actor, execution.executionRef()), current.identity().agentId()),
                            current.identity().trace().traceId(), current.identity().trace().spanId())) instanceof IdentityPolicyPort.AuthorizationResult.Allowed)) throw rejected();
                    workflows.requireResumeResponse(session, actor, execution, actualResponse.content());
                    commit.confirm(session, execution);
                });
        worker.getObject().wake(parent.identity().runRef()); return result;
    }

    @FunctionalInterface interface ResponseCommit { void confirm(SqlSession session, ExecutionRecord execution); }

    static void requireWaiting(ExecutionStore.Run parent, ExecutionStore.Waiting waiting, ExecutionRecord execution) {
        if (parent.cancelled() || parent.phase() != ExecutionStore.Phase.WAITING || !parent.waiting().equals(Optional.of(waiting.reference()))
                || !waiting.run().equals(parent.identity().runRef()) || waiting.kind() != ExecutionStore.WaitKind.WORKFLOW
                || !(waiting.internal().orElse(null) instanceof ExecutionStore.WorkflowWait exact)
                || !execution.owner().equals(parent.identity()) || !execution.executionRef().equals(exact.execution())
                || !execution.state().isWaiting() || execution.cancelRequestId().isPresent() || execution.executionRevision() != exact.executionRevision()
                || execution.waiting().filter(value -> value.suspendRef().equals(exact.suspendRef()) && value.actionKey().equals(exact.actionKey())).isEmpty()) throw rejected();
    }

    /** 当前真实Worker只取得新接续主体；原HMAC envelope和其认证证明保持原事实。 */
    VerifiedIdentity currentActor(RunIdentity parent, InputId input) { return authenticate(parent,input); }

    private VerifiedIdentity authenticate(RunIdentity parent, InputId input) {
        provisioner.provisionAccess(parent.runRef().tenantId().value());
        var authentication = identities.registerTrusted(new PrincipalRef.SystemPrincipal(parent.runRef().tenantId(), properties.getWorkerId()),
                parent.owner(), "chat", "workflow", Duration.ofMinutes(5));
        return switch (identities.verify(new IdentityPolicyPort.IdentityRequest(authentication, parent.owner(), "chat", "workflow", new RequestId(input.value()),
                Optional.of(input), parent.trace().traceId(), parent.trace().spanId()))) {
            case IdentityPolicyPort.IdentityResult.Verified verified -> verified.identity();
            case IdentityPolicyPort.IdentityResult.Rejected denied -> throw rejected();
        };
    }
    private ExecutionStore.Event resolved(ExecutionStore.Run parent, InputId input, ExecutionStore.WaitRef wait) {
        var original = AgentExecutionProgram.resolved(parent, input, wait);
        try {
            var value = (ObjectNode) json.readTree(original.data().canonicalJson()); value.put("dependencyKind", "workflow");
            return new ExecutionStore.Event(original.id(), original.type(), new TypedPayload(original.data().schemaId(), json.writeValueAsString(value)));
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("workflow_resolution_invalid", failure); }
    }
    static InputId observationInput(RunRef parent, ExecutionStore.WaitRef waiting, ExecutionStore.WorkflowWait exact) {
        return new InputId("workflow-response:" + AgentExecutionCodec.digest(parent + "\0" + waiting.action().actionId() + "\0" + waiting.generation()
                + "\0" + exact.execution().executionId() + "\0" + exact.executionRevision()));
    }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("workflow_recovery_source_rejected"); }
}

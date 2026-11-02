package com.skillforge.core.workflow.runtime;

import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.execution.node.NodeCallIdentity;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.WorkflowNodeCallIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 从真实Engine checkpoint及原不可变安装重建单节点，不查询当前配置或复制来源树。 */
public final class WorkflowNodeSource {
    private WorkflowNodeSource() { }

    public record Source(ExecutionRecord execution, WorkflowNodeCallIdentity identity,
            SdkWorkflowPortal.NodeInputs inputs, NodeExecutionContext node) {
        public Source { Objects.requireNonNull(execution); Objects.requireNonNull(identity); Objects.requireNonNull(inputs); Objects.requireNonNull(node); }
    }

    public static Source rebuild(ExecutionRecord execution, WorkflowNodeCallIdentity key,
            JdbcWorkflowBindings.Binding binding, WorkflowParser parser) {
        Objects.requireNonNull(execution); Objects.requireNonNull(key); Objects.requireNonNull(binding); Objects.requireNonNull(parser);
        var stored = WorkflowCheckpointCodec.decode(execution.checkpoint());
        var checkpoint = stored.engine().orElseThrow(WorkflowNodeSource::rejected);
        var cursor = checkpoint.cursor();
        if (!key.owner().equals(execution.owner()) || !key.executionRef().equals(execution.executionRef())
                || !key.nodeId().equals(cursor.activeNode()) || !Integer.valueOf(key.visit()).equals(cursor.visits().get(key.nodeId()))
                || key.call() != 1 || !binding.target().equals(stored.target())
                || !execution.executionUnitId().equals(stored.target().instanceId())
                || !execution.definitionVersion().equals(Long.toString(stored.target().instanceRevision()))
                || !stored.snapshot().sessionRef().equals(execution.owner().owner().sessionRef())) throw rejected();
        var workflow = parser.parse(binding.workflowKey(), WorkflowCheckpointCodec.object(binding.definitionJson()));
        var node = workflow.nodes().stream().filter(value -> value.id().equals(key.nodeId())).findFirst().orElseThrow(WorkflowNodeSource::rejected);
        var parameters = WorkflowCheckpointCodec.object(stored.parameters());
        var businessState = new LinkedHashMap<String,Object>(checkpoint.state());
        var visits = StateMachineEngine.restoreActionVisits(businessState);
        var variables = StateMachineEngine.buildExtraVars(requesterVariables(execution.owner()),
                execution.owner().owner().sessionRef().sessionId(), execution.executionRef().executionId(), stored.target().instanceId(), visits);
        var context = new NodeExecutionContext(workflow, node, WorkflowCheckpointCodec.object(binding.configJson()), businessState,
                new LinkedHashMap<>(checkpoint.extracted()), parameters.get("user_input") instanceof String text ? text : "",
                new java.util.ArrayList<>(checkpoint.messages()), stored.target().instanceId(), execution.executionRef().tenantId().value(), List.of(),
                execution.executionRef().executionId(), null, execution.owner().owner().sessionRef().sessionId(), variables,
                checkpoint.cursor().incomingEdge(), new NodeCallIdentity(key.executionRef().executionId(), key.nodeId(), key.visit(), key.call()));
        return new Source(execution, key, new SdkWorkflowPortal.NodeInputs(stored.target(), stored.snapshot(), stored.parameters(),
                stored.evidenceRefs(), binding.customPrompt(), binding), context);
    }

    /** 节点拥有独立input唯一键，根输入和trace仍指向原始用户请求。 */
    public static RunIdentity runIdentity(WorkflowNodeCallIdentity key) {
        Objects.requireNonNull(key); var parent = key.owner(); String id = identityDigest(key);
        var trace = new TraceEnvelope(parent.trace().traceId(), "workflow-node-span:" + id,
                parent.trace().rootInputId(), parent.trace().causedByEventId());
        return new RunIdentity(parent.owner(), parent.agentId(), new InputId("workflow-node-input:" + id),
                new RunRef(parent.runRef().tenantId(), "workflow-node-run:" + id), trace, parent.causationIdentityRef(), parent.requester(), parent.sourceMessageRef(), parent.originRef());
    }

    public static String identityDigest(WorkflowNodeCallIdentity key) {
        return digest(key.executionRef().tenantId().value() + "\0" + key.executionRef().executionId()
                + "\0" + key.nodeId() + "\0" + key.visit() + "\0" + key.call());
    }
    public static String checkpointFingerprint(TypedPayload checkpoint) {
        return digest(checkpoint.schemaId() + "\0" + checkpoint.canonicalJson());
    }
    public static com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget target(TypedPayload checkpoint) {
        return WorkflowCheckpointCodec.decode(checkpoint).target();
    }
    public static java.util.Optional<WorkflowNodeCallIdentity> activeIdentity(ExecutionRecord execution) {
        return WorkflowCheckpointCodec.decode(execution.checkpoint()).engine().flatMap(checkpoint->{
            var cursor=checkpoint.cursor();if(cursor.activeNode()==null)return java.util.Optional.empty();
            return java.util.Optional.of(new WorkflowNodeCallIdentity(execution.owner(),execution.executionRef(),cursor.activeNode(),cursor.visits().get(cursor.activeNode()),1));
        });
    }
    public static boolean referencesToolWait(ExecutionRecord execution,RunRef run,com.skillforge.runtime.api.port.ActionRef action,long generation,com.skillforge.runtime.api.ResultRef result) {
        return WorkflowCheckpointCodec.decode(execution.checkpoint()).toolWait().filter(wait->wait.nodeRun().equals(run)
                &&wait.actionRef().equals(action)&&wait.generation()==generation&&wait.sourceResult().equals(result)).isPresent();
    }
    /** 工作流只暴露真实请求者，不把群归属伪装为用户。 */
    static Map<String, Object> requesterVariables(RunIdentity identity) {
        var variables = new LinkedHashMap<String, Object>();
        switch (identity.owner().subject()) {
            case com.skillforge.runtime.api.SessionSubject.User user -> variables.put("session_subject", Map.of("kind", "USER", "end_user_id", user.endUserId().value()));
            case com.skillforge.runtime.api.SessionSubject.Group group -> variables.put("session_subject", Map.of("kind", "GROUP", "group_binding_id", group.groupBindingRef().groupBindingId()));
        }
        switch (identity.requester()) {
            case com.skillforge.runtime.api.RunRequester.EndUser user -> variables.put("end_user_id", user.endUserId().value());
            case com.skillforge.runtime.api.RunRequester.System system -> variables.put("system_principal_id", system.principal().principalId());
        }
        return Map.copyOf(variables);
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("workflow_node_digest_unavailable", impossible); }
    }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("workflow_node_source_rejected"); }
}

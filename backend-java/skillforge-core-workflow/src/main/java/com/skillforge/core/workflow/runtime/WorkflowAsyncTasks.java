package com.skillforge.core.workflow.runtime;

import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.NodeOutcome;
import com.skillforge.core.workflow.execution.node.WorkflowNodeFailure;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.spi.SessionWorkflowWakeups;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 异步节点只保存等待边界；回调和超时均由已受理的恢复输入继续原节点。 */
final class WorkflowAsyncTasks {
    private WorkflowAsyncTasks() { }

    static WorkflowCheckpointCodec.AsyncWait waiting(NodeExecutionContext node, Instant now) {
        Integer seconds=node.node().timeoutSeconds();
        if(seconds!=null&&seconds<=0)throw rejected();
        return new WorkflowCheckpointCodec.AsyncWait(node.node().id(),node.callIdentity().visit(),
                node.callIdentity().call(),seconds==null?Optional.empty():Optional.of(now.plusSeconds(seconds)));
    }

    static Optional<TypedPayload> timeout(WorkflowCheckpointCodec.State state,Instant now) {
        return state.asyncWait().filter(wait->wait.deadlineAt().filter(deadline->!now.isBefore(deadline)).isPresent())
                .map(wait->new TypedPayload(SessionWorkflowWakeups.SIGNAL_SCHEMA,
                        "{\"deadlineAt\":\""+wait.deadlineAt().orElseThrow()+"\",\"kind\":\"async-task-timeout\"}"));
    }

    static void requireCallback(WorkflowCheckpointCodec.State state,TypedPayload response,Instant now) {
        if(!SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA.equals(response.schemaId())||state.asyncWait().isEmpty()
                ||timeout(state,now).isPresent())throw rejected();
        requireResult(WorkflowCheckpointCodec.object(response));
    }

    static NodeOutcome execute(NodeExecutionContext node,WorkflowCheckpointCodec.State state) {
        if(state.asyncWait().isEmpty()||state.response().isEmpty())return NodeOutcome.waiting(NodeOutcome.WaitReason.CALLBACK);
        var wait=state.asyncWait().orElseThrow();
        if(!wait.nodeId().equals(node.node().id())||wait.visit()!=node.callIdentity().visit()
                ||wait.call()!=node.callIdentity().call())throw rejected();
        var response=state.response();
        if("async-task-timeout".equals(response.get("kind")))return failure(node,"workflow_async_timed_out");
        requireResult(response);
        if("failed".equals(response.get("status")))return failure(node,(String)response.get("reason_code"));
        String to=Objects.requireNonNullElse(node.node().resultTo(),"state");
        var destination=to.startsWith("extracted")?node.extracted():node.state();
        if(to.equals("state")||to.equals("extracted"))destination.put(node.node().id(),response.get("result"));
        else if(to.matches("(?:state|extracted)\\.[A-Za-z][A-Za-z0-9_]*"))destination.put(to.substring(to.indexOf('.')+1),response.get("result"));
        else throw new WorkflowNodeFailure("workflow_result_destination_rejected");
        return NodeOutcome.continueFlow();
    }

    private static void requireResult(Map<String,Object> response) {
        if("completed".equals(response.get("status"))&&response.keySet().equals(Set.of("status","result"))
                &&response.get("result") instanceof Map<?,?>)return;
        if("failed".equals(response.get("status"))&&response.keySet().equals(Set.of("status","reason_code"))
                &&response.get("reason_code") instanceof String reason&&reason.matches("[a-z][a-z0-9_]{0,127}"))return;
        throw rejected();
    }

    private static NodeOutcome failure(NodeExecutionContext node,String reason) {
        node.state().put("last_error",Map.of("node_id",node.node().id(),"reason",reason));
        if(node.node().onError()!=null&&node.node().onError().startsWith("goto:"))return NodeOutcome.jump(node.node().onError().substring(5));
        if("message".equals(node.node().onError()))node.messages().add(Map.of("role","assistant","content",reason,"node_id",node.node().id()));
        throw new WorkflowNodeFailure(reason);
    }

    private static IllegalArgumentException rejected() {return new IllegalArgumentException("workflow_async_response_rejected");}
}

package com.skillforge.institution.runtime;

import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.NodeOutcome;
import com.skillforge.core.workflow.execution.node.WorkflowNodeFailure;
import com.skillforge.core.workflow.execution.node.WorkflowNodeWait;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.core.workflow.runtime.WorkflowNodeSource;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 外部节点只创建或采纳真实typed节点Run；执行全部进入共享Coordinator及五十槽Worker。 */
@Component
public final class AgentRuntimeWorkflowNodes implements SdkWorkflowPortal.ExternalNodes {
    private final AgentRuntimeWorkflowNodeExecutions nodes;
    private final ExecutionStore store;
    private final ObjectProvider<AgentRuntimeExecutionWorker> workers;
    private final PortalOutcomeCodec outcomes;
    private final AgentExecutionCodec codec;
    private final ObjectMapper json;
    private final AgentRuntimeWorkflowScripts scripts;
    private final AgentRuntimeExecutionSources parents;
    private final WorkflowTestRuntimePolicy tests;
    public AgentRuntimeWorkflowNodes(AgentRuntimeWorkflowNodeExecutions nodes,ExecutionStore store,
            ObjectProvider<AgentRuntimeExecutionWorker> workers,PortalOutcomeCodec outcomes,AgentExecutionCodec codec,ObjectMapper json,
            AgentRuntimeWorkflowScripts scripts,AgentRuntimeExecutionSources parents,WorkflowTestRuntimePolicy tests) {
        this.nodes=nodes;this.store=store;this.workers=workers;this.outcomes=outcomes;this.codec=codec;this.json=json;
        this.scripts=scripts;this.parents=parents;this.tests=tests;
    }
    @Override public NodeOutcome execute(VerifiedIdentity identity,ExecutionLease lease,ExecutionRecord execution,
            InvocationControl control,SdkWorkflowPortal.NodeInputs inputs,NodeExecutionContext node) {
        var registered=inputs.registered().orElseThrow(()->new WorkflowNodeFailure("workflow_registered_source_required"));
        tests.node(identity.owner().sessionRef(),node.node().type());
        if("script".equals(node.node().type()))scripts.prepareOnly(identity,lease,execution,control,inputs,node);
        var source=new WorkflowNodeSource.Source(execution,key(execution,node),inputs,node);
        workers.getObject().observeReady(WorkflowNodeSource.runIdentity(source.identity()).runRef());
        var admitted=nodes.admit(registered.registered(),registered.prepared(),lease,source,parents.extraParameters(registered.registered().run()));var run=admitted.run();
        if(run.phase()==ExecutionStore.Phase.READY||run.phase()==ExecutionStore.Phase.AWAITING_RESULT)workers.getObject().wake(run.identity().runRef());
        return apply(node,run);
    }
    @Override public Optional<TypedPayload> observeExternal(VerifiedIdentity identity,ExecutionLease lease,
            ExecutionRecord execution,InvocationControl control,SdkWorkflowPortal.NodeInputs inputs,NodeExecutionContext node) {return resolvedSignal(identity,execution,node);}
    @Override public void requireExternalSignal(VerifiedIdentity identity,ExecutionRecord execution,TypedPayload signal,
            SdkWorkflowPortal.NodeInputs inputs,NodeExecutionContext node) {if(!resolvedSignal(identity,execution,node).equals(Optional.of(signal)))throw rejected();}
    @Override public void requireExternalSignalBorrowed(org.apache.ibatis.session.SqlSession session,VerifiedIdentity identity,ExecutionRecord execution,
            TypedPayload signal,SdkWorkflowPortal.NodeInputs inputs,NodeExecutionContext node) {
        if(!(store instanceof com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore jdbc))throw new IllegalStateException("workflow_transactional_store_required");
        jdbc.participate(session,execution.executionRef().tenantId(),()->{requireExternalSignal(identity,execution,signal,inputs,node);return null;});
    }
    private Optional<TypedPayload> resolvedSignal(VerifiedIdentity identity,ExecutionRecord execution,NodeExecutionContext node) {
        var reference=WorkflowNodeSource.runIdentity(key(execution,node)).runRef();
        return store.load(reference).filter(run->run.identity().owner().equals(identity.owner())&&Set.of(ExecutionStore.Phase.WAITING,ExecutionStore.Phase.FINISHED).contains(run.phase())).flatMap(run->{
            var attempt=run.phase()==ExecutionStore.Phase.WAITING?store.attempt(reference,store.waiting(reference,run.waiting().orElseThrow()).orElseThrow().sourceAttempt()).orElseThrow():selected(run);
            if(run.phase()==ExecutionStore.Phase.WAITING) {
                var wait=run.waiting().orElseThrow();if(WorkflowNodeSource.referencesToolWait(execution,reference,wait.action(),wait.generation(),attempt.result().orElseThrow()))return Optional.empty();
            }
            try{return Optional.of(new TypedPayload(SdkWorkflowPortal.EXTERNAL_SIGNAL_SCHEMA,json.writeValueAsString(new TreeMap<>(Map.of(
                    "kind","node-result","nodeRun",reference.runId(),"resultRef",attempt.result().orElseThrow().value())))));}
            catch(java.io.IOException invalid){throw rejected();}
        });
    }
    @Override public void requireScriptSource(ExecutionRecord execution,SdkWorkflowPortal.NodeInputs inputs,
            com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls.Preparation preparation,NodeExecutionContext node) {
        scripts.requireFrozenSource(execution,inputs,preparation,node);
    }
    @Override public boolean cancel(VerifiedIdentity identity,ExecutionLease lease,ExecutionRecord execution,InvocationControl control) {
        boolean nodeQuiet=true;var target=WorkflowNodeSource.activeIdentity(execution);
        if(target.isPresent()) {
            var reference=WorkflowNodeSource.runIdentity(target.orElseThrow()).runRef();var candidate=store.load(reference);
            if(candidate.isPresent()) {
                var actual=candidate.orElseThrow();
                if(actual.phase()!=ExecutionStore.Phase.FINISHED)actual=store.cancel(new ExecutionStore.Cancel(identity,reference,new InputId("workflow-node-cancel:"+WorkflowNodeSource.identityDigest(target.orElseThrow())),List.of()));
                workers.getObject().cancelled(reference);nodeQuiet=quiescent(actual);
            }
        }
        // 先传播已持久取消与内存信号，再清理原脚本；未知取消响应不能使节点继续发后续效果。
        boolean scriptsQuiet=scripts.cancel(identity,lease,execution,control);return nodeQuiet&&scriptsQuiet;
    }
    private boolean quiescent(ExecutionStore.Run run) {
        var actions=store.actions(run.identity().runRef(),0,2);if(actions.isEmpty())return true;
        var action=actions.getFirst();var attempt=store.attempt(run.identity().runRef(),action.currentAttempt()).orElseThrow();
        return attempt.resultKind().filter(kind->kind!=ExecutionStore.ResultKind.UNKNOWN).isPresent();
    }
    NodeOutcome apply(NodeExecutionContext node,ExecutionStore.Run run) {
        if(run.phase()==ExecutionStore.Phase.WAITING) {
            var waiting=store.waiting(run.identity().runRef(),run.waiting().orElseThrow()).orElseThrow(AgentRuntimeWorkflowNodes::rejected);
            if(waiting.kind()!=ExecutionStore.WaitKind.TOOL)throw rejected();
            var source=store.attempt(run.identity().runRef(),waiting.sourceAttempt()).orElseThrow(AgentRuntimeWorkflowNodes::rejected);
            return NodeOutcome.toolInput(new WorkflowNodeWait(run.identity().runRef(),waiting.reference().action(),waiting.reference().generation(),source.result().orElseThrow()));
        }
        if(run.phase()!=ExecutionStore.Phase.FINISHED)return NodeOutcome.waiting(NodeOutcome.WaitReason.CALLBACK);
        if(run.status()!=RunStatus.COMPLETED)return failure(node,"workflow_node_execution_failed");
        var attempt=selected(run);var material=store.material(run.identity().runRef().tenantId(),attempt.result().orElseThrow()).orElseThrow(AgentRuntimeWorkflowNodes::rejected);
        var outcome=codec.decodeOutcome(material.content());var semantics=outcomes.semantics(outcome,outcome.callKind());
        if(semantics.failure().isPresent())return failure(node,semantics.failure().orElseThrow().reasonCode());
        if(outcome.callKind()==PortalCallKind.MODEL_GENERATE) {
            var model=(ModelResult.Completed)outcomes.decodeModel(outcome,outcome.callKind());
            if("length".equals(model.finishReasonCode()))return failure(node,"model_output_length");
            if("content_filter".equals(model.finishReasonCode()))return failure(node,"model_content_filtered");
            write(node,object(model.output().canonicalJson()));return NodeOutcome.continueFlow();
        }
        Object value=switch(outcome.callKind()) {
            case TOOL_INVOKE -> {
                var actual=((ToolOutcome.Succeeded)outcomes.decodeTool(outcome,outcome.callKind())).observation();
                yield "script".equals(node.node().type())?object(store.material(run.identity().runRef().tenantId(),attempt.feedback().orElseThrow()).orElseThrow().content().canonicalJson()):object(actual.canonicalJson());
            }
            case KNOWLEDGE_EXECUTE -> json.convertValue(((KnowledgePortal.KnowledgeOutcome.Retrieved)outcomes.decodeKnowledge(outcome,outcome.callKind())).bundle(),Map.class);
            default -> throw rejected();
        };write(node,value);return NodeOutcome.continueFlow();
    }
    private ExecutionStore.Attempt selected(ExecutionStore.Run run) {
        var actions=store.actions(run.identity().runRef(),0,2);if(actions.size()!=1)throw rejected();
        return store.attempt(run.identity().runRef(),actions.getFirst().selectedAttempt().orElseThrow()).orElseThrow(AgentRuntimeWorkflowNodes::rejected);
    }
    private static WorkflowNodeCallIdentity key(ExecutionRecord execution,NodeExecutionContext node) {
        var identity=node.callIdentity();return new WorkflowNodeCallIdentity(execution.owner(),execution.executionRef(),identity.nodeId(),identity.visit(),identity.call());
    }
    private Object object(String value){try{return json.readValue(value,Object.class);}catch(java.io.IOException invalid){throw rejected();}}
    private void write(NodeExecutionContext node,Object result) {
        String to=Objects.requireNonNullElse(node.node().resultTo(),"state");var destination=to.startsWith("extracted")?node.extracted():node.state();
        if(to.equals("state")||to.equals("extracted")) {
            if(node.node().extract()!=null&&!node.node().extract().isEmpty()) {
                if(!(result instanceof Map<?,?> fields))throw new WorkflowNodeFailure("workflow_model_extract_rejected");
                var extracted=new LinkedHashMap<String,Object>();for(String key:node.node().extract()){if(!fields.containsKey(key))throw new WorkflowNodeFailure("workflow_model_extract_rejected");extracted.put(key,fields.get(key));}destination.putAll(extracted);
            } else destination.put(node.node().id(),result);
        } else if(to.matches("(?:state|extracted)\\.[A-Za-z][A-Za-z0-9_]*"))destination.put(to.substring(to.indexOf('.')+1),result);
        else throw new WorkflowNodeFailure("workflow_result_destination_rejected");
    }
    private static NodeOutcome failure(NodeExecutionContext node,String reason) {
        node.state().put("last_error",Map.of("node_id",node.node().id(),"reason",reason));String policy=node.node().onError();
        if(policy!=null&&policy.startsWith("goto:"))return NodeOutcome.jump(policy.substring(5));
        if("message".equals(policy))node.messages().add(Map.of("role","assistant","content",reason,"node_id",node.node().id()));throw new WorkflowNodeFailure(reason);
    }
    private static IllegalArgumentException rejected(){return new IllegalArgumentException("workflow_node_execution_rejected");}
}

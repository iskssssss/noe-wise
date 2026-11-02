package com.skillforge.core.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.*;
import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.WorkflowExpressions;
import com.skillforge.runtime.adapter.jdbc.execution.ExecutionCommands;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowChildExecutions.*;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings.Binding;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.WorkflowNodeCallIdentity;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** 从已冻结的父节点生成完整子执行命令；本类不查找最新安装，也不创建或调度执行。 */
final class WorkflowChildPlans {
    private static final ObjectMapper JSON = new ObjectMapper();
    private WorkflowChildPlans() { }

    static Preparation prepare(ExecutionRecord parent, NodeExecutionContext node,
            WorkflowRuntimeBindings slots, Function<FrozenWorkflowTarget, Binding> frozenBinding,
            WorkflowParser parser) {
        var source=WorkflowCheckpointCodec.decode(parent.checkpoint());
        var checkpoint=source.engine().orElseThrow(WorkflowChildPlans::invalid);
        var call=node.callIdentity();
        if(!parent.executionRef().executionId().equals(call.executionId())
                ||!parent.executionRef().tenantId().value().equals(node.tenantId())
                ||!node.node().id().equals(call.nodeId())
                ||!node.node().id().equals(checkpoint.cursor().activeNode())
                ||!Integer.valueOf(call.visit()).equals(checkpoint.cursor().visits().get(node.node().id()))
                ||!Objects.equals(node.incomingEdge(),checkpoint.cursor().incomingEdge()))throw invalid();
        var key=new WorkflowNodeCallIdentity(parent.owner(),parent.executionRef(),node.node().id(),call.visit(),call.call());
        var children=new ArrayList<Child>();
        if("parallel".equals(node.node().type())) {
            var branches=node.node().branches();
            if(branches==null||branches.isEmpty()||branches.size()>32
                    ||new HashSet<>(branches).size()!=branches.size())throw invalid();
            for(String branch:branches) {
                if(node.workflow().nodes().stream().noneMatch(value->value.id().equals(branch)))throw invalid();
                var state=copy(checkpoint.state());
                StateMachineEngine.restoreActionVisits(state);
                var initial=new Checkpoint(Boundary.PROGRESS,branch,branch,state,copy(checkpoint.extracted()),
                        List.of(),null,null,Optional.empty(),Optional.empty(),Cursor.initial());
                var child=new WorkflowCheckpointCodec.State(source.target(),source.snapshot(),source.parameters(),
                        source.evidenceRefs(),Optional.of(initial),Map.of(),Optional.empty(),Optional.empty());
                children.add(child(parent,key,branch,child));
            }
        } else if("subgraph".equals(node.node().type())) {
            var target=slots.workflows().stream().filter(slot->slot.workflow().equals(node.node().workflow()))
                    .map(WorkflowRuntimeBindings.WorkflowSlot::target).findFirst().orElseThrow(WorkflowChildPlans::invalid);
            var binding=Objects.requireNonNull(frozenBinding.apply(target));
            if(!binding.target().equals(target))throw invalid();
            var workflow=parser.parse(binding.workflowKey(),WorkflowCheckpointCodec.object(binding.definitionJson()));
            var values=new LinkedHashMap<String,Object>();
            if(node.node().args()!=null)values.putAll(interpolate(node.node().args(),node));
            if(node.incomingEdge()!=null&&node.incomingEdge().transferContext()!=null)
                values.putAll(interpolate(node.incomingEdge().transferContext(),node));
            var initial=new Checkpoint(Boundary.PROGRESS,workflow.entry(),workflow.entry(),
                    WorkflowExecutionWorker.parameterState(values),Map.of(),List.of(),null,null,
                    Optional.empty(),Optional.empty(),Cursor.initial());
            var child=new WorkflowCheckpointCodec.State(target,source.snapshot(),payload(values),List.of(),
                    Optional.of(initial),Map.of(),Optional.empty(),Optional.empty());
            children.add(child(parent,key,"subgraph",child));
        } else throw invalid();
        return new Preparation(key,source.target().instanceRevision(),source.target().installationRevision(),
                parent.checkpoint(),children);
    }

    private static Child child(ExecutionRecord parent,WorkflowNodeCallIdentity key,String branch,
            WorkflowCheckpointCodec.State state) {
        var checkpoint=WorkflowCheckpointCodec.encode(state);
        String id="workflow-child:"+digest(List.of(key.executionRef().tenantId().value(),
                key.executionRef().executionId(),key.nodeId(),key.visit(),key.call(),branch));
        var ref=new ExecutionRef(parent.executionRef().tenantId(),id);
        var command=ExecutionCommands.create(ref,parent.owner(),state.target().instanceId(),
                Long.toString(state.target().instanceRevision()),parent.mode(),Optional.empty(),Optional.empty(),
                checkpoint,digest(List.of(id,checkpoint.schemaId(),checkpoint.canonicalJson())),
                new EventId(id+":queued"),parent.updatedAt());
        return new Child(branch,command);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> interpolate(Map<String,Object> values,NodeExecutionContext node) {
        return copy((Map<String,Object>)WorkflowExpressions.interpolateValue(values,node));
    }
    private static Map<String,Object> copy(Map<String,Object> values) {
        return WorkflowCheckpointCodec.object(payload(values));
    }
    private static TypedPayload payload(Map<String,Object> values) {
        try {return new TypedPayload("skillforge.runtime.workflow-child-parameters.v1",JSON.writeValueAsString(values));}
        catch(Exception failure) {throw invalid();}
    }
    private static String digest(Object value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure) {throw invalid();}
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("workflow_child_plan_invalid");}
}

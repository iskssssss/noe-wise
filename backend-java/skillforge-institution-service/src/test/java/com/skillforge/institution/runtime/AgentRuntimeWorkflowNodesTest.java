package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.workflow.execution.node.NodeCallIdentity;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.WorkflowNodeFailure;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution;
import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowModels;
import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.core.workflow.runtime.WorkflowNodeSource;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

class AgentRuntimeWorkflowNodesTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings={"fail","message","goto:recover"})
    void toolBusinessFailureHonorsConfiguredPolicyWithoutAdoptingFailedOutput(String policy) throws Exception {
        var fixture=toolFixture("{\"content\":[],\"isError\":true,\"resultType\":\"complete\",\"structuredContent\":{\"success\":false,\"error\":\"private-provider-error\"}}");
        var visited=new ArrayList<String>();var checkpoints=new ArrayList<ControlledWorkflowExecution.Checkpoint>();
        var workflow=toolGraph(policy);var request=new ControlledWorkflowExecution.Request(workflow,Map.of(),"","session",null,null,"instance","1",List.of(),"execution",Map.of("end_user_id",1L),null);
        var hooks=new ControlledWorkflowExecution.Hooks() {
            public void beforeNode(String id) {visited.add(id);}
            public void checkpoint(ControlledWorkflowExecution.Checkpoint value) {checkpoints.add(value);}
            public com.skillforge.core.workflow.execution.node.NodeOutcome executeExternalNode(NodeExecutionContext node) {
                return fixture.subject().apply(node,fixture.run());
            }
        };
        if(policy==null||policy.equals("fail")) {
            var failure=assertThrows(WorkflowNodeFailure.class,()->new StateMachineEngine().runControlled(request,hooks));
            assertEquals("workflow_tool_business_failed",failure.reason());
            assertEquals(List.of("calculate"),visited);
            assertTrue(checkpoints.stream().noneMatch(value->value.boundary()==ControlledWorkflowExecution.Boundary.COMPLETED));
        } else {
            var result=new StateMachineEngine().runControlled(request,hooks);
            assertEquals("completed",result.get("status"));
            assertEquals(policy.equals("message")?List.of("calculate"):List.of("calculate","recover","end"),visited);
            var state=(Map<?,?>)result.get("state");assertFalse(state.containsKey("tool_result"));
            assertEquals(Map.of("node_id","calculate","reason","workflow_tool_business_failed"),state.get("last_error"));
            var messages=(List<?>)result.get("messages");assertEquals(1,messages.size());
            assertEquals(policy.equals("message")?"节点执行失败，请检查输入或查看执行记录。":"workflow_tool_business_failed",((Map<?,?>)messages.getFirst()).get("content"));
            assertFalse(new ObjectMapper().writeValueAsString(result).contains("private-provider-error"));
            assertEquals(ControlledWorkflowExecution.Boundary.COMPLETED,checkpoints.getLast().boundary());
        }
        assertEquals(fixture.original(),fixture.store().material(new TenantId("1"),new ResultRef("result")).orElseThrow().content());
    }

    @Test void toolSuccessPreservesFullEnvelopeAndDoesNotInferFailureFromBusinessKeys() throws Exception {
        for(var envelope:List.of(
                "{\"content\":[],\"isError\":false,\"resultType\":\"complete\",\"structuredContent\":{\"success\":true,\"value\":\"888\"}}",
                "{\"content\":[],\"isError\":false,\"structuredContent\":{\"success\":false,\"value\":\"888\"}}",
                "{\"content\":[],\"structuredContent\":{\"success\":false,\"value\":\"888\"}}")) {
            var fixture=toolFixture(envelope);var visited=new ArrayList<String>();
            var workflow=toolGraph("fail");
            var request=new ControlledWorkflowExecution.Request(workflow,Map.of(),"","session",null,null,"instance","1",List.of(),"execution",Map.of("end_user_id",1L),null);
            var result=new StateMachineEngine().runControlled(request,new ControlledWorkflowExecution.Hooks() {
                public void beforeNode(String id) {visited.add(id);}
                public void checkpoint(ControlledWorkflowExecution.Checkpoint value) { }
                public com.skillforge.core.workflow.execution.node.NodeOutcome executeExternalNode(NodeExecutionContext node) {
                    return fixture.subject().apply(node,fixture.run());
                }
            });
            assertEquals("completed",result.get("status"));assertEquals(List.of("calculate","success","end"),visited);
            assertEquals(new ObjectMapper().readValue(envelope,Map.class),((Map<?,?>)result.get("state")).get("tool_result"));
            assertEquals("计算结果 888",((Map<?,?>)((List<?>)result.get("messages")).getFirst()).get("content"));
            assertFalse(((Map<?,?>)result.get("state")).containsKey("last_error"));
        }
    }

    private static WorkflowModels.Workflow toolGraph(String policy) {
        var tool=new LinkedHashMap<String,Object>(Map.of("id","calculate","type","tool","tool","calculate_expression","args",Map.of("expression","1/0"),"result_to","state.tool_result"));
        if(policy!=null)tool.put("on_error",policy);
        return new WorkflowParser().parse("business-error",Map.of("entry","calculate",
                "meta",Map.of("capability_requirements",List.of(Map.of("slot","calculator","type","tool","capability_id","calculate_expression","version_constraint","*","required",true)),
                        "tools",List.of(Map.of("name","calculate_expression","requirement","calculator"))),"nodes",List.of(tool,
                Map.of("id","success","type","output","message","计算结果 ${state.tool_result.structuredContent.value}"),
                Map.of("id","recover","type","output","message","${state.last_error.reason}"),Map.of("id","end","type","end")),
                "edges",List.of(Map.of("from","calculate","to","success"),Map.of("from","success","to","end"),Map.of("from","recover","to","end"))));
    }

    private static ToolFixture toolFixture(String envelope) {
        var store=mock(ExecutionStore.class);var limits=new CodecLimits(100_000,10_000,100);var payloads=new SessionRuntimePayloads();
        var outcomes=new CoreRuntime(limits,payloads).portalResults(payloads,payloads,payloads);var codec=new AgentExecutionCodec(limits);
        @SuppressWarnings("unchecked") ObjectProvider<AgentRuntimeExecutionWorker> workers=mock(ObjectProvider.class);
        var subject=new AgentRuntimeWorkflowNodes(mock(AgentRuntimeWorkflowNodeExecutions.class),store,workers,outcomes,codec,new ObjectMapper(),
                mock(AgentRuntimeWorkflowScripts.class),mock(AgentRuntimeExecutionSources.class),mock(WorkflowTestRuntimePolicy.class));
        var tenant=new TenantId("1");var runRef=new RunRef(tenant,"node-run");var identity=mock(RunIdentity.class);when(identity.runRef()).thenReturn(runRef);
        var run=mock(ExecutionStore.Run.class);when(run.phase()).thenReturn(ExecutionStore.Phase.FINISHED);when(run.status()).thenReturn(RunStatus.COMPLETED);when(run.identity()).thenReturn(identity);
        var action=mock(ExecutionStore.Action.class);when(action.selectedAttempt()).thenReturn(Optional.of("attempt"));
        var attempt=mock(ExecutionStore.Attempt.class);var result=new ResultRef("result");when(attempt.result()).thenReturn(Optional.of(result));
        when(store.actions(runRef,0,2)).thenReturn(List.of(action));when(store.attempt(runRef,"attempt")).thenReturn(Optional.of(attempt));
        var original=codec.encodeOutcome(outcomes.encodeTool(PortalCallKind.TOOL_INVOKE,
                new ToolPortal.ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,envelope),new ResultRef("provider-result"),List.of())));
        when(store.material(tenant,result)).thenReturn(Optional.of(new ExecutionStore.Material(result,original)));
        return new ToolFixture(subject,store,run,original);
    }

    private record ToolFixture(AgentRuntimeWorkflowNodes subject,ExecutionStore store,ExecutionStore.Run run,TypedPayload original) { }

    @Test void modelNodeStopsOnKnownIncompleteOutputAndAcceptsCompleteOutput() {
        for (var finish : List.of("length", "content_filter", "stop")) {
            var store=mock(ExecutionStore.class);var outcomes=mock(PortalOutcomeCodec.class);var codec=mock(AgentExecutionCodec.class);
            @SuppressWarnings("unchecked") ObjectProvider<AgentRuntimeExecutionWorker> workers=mock(ObjectProvider.class);
            var subject=new AgentRuntimeWorkflowNodes(mock(AgentRuntimeWorkflowNodeExecutions.class),store,workers,outcomes,codec,new ObjectMapper(),
                    mock(AgentRuntimeWorkflowScripts.class),mock(AgentRuntimeExecutionSources.class),mock(WorkflowTestRuntimePolicy.class));
            var tenant=new TenantId("1");var runRef=new RunRef(tenant,"node-run");var identity=mock(RunIdentity.class);
            when(identity.runRef()).thenReturn(runRef);
            var run=mock(ExecutionStore.Run.class);when(run.phase()).thenReturn(ExecutionStore.Phase.FINISHED);
            when(run.status()).thenReturn(RunStatus.COMPLETED);when(run.identity()).thenReturn(identity);
            var action=mock(ExecutionStore.Action.class);when(action.selectedAttempt()).thenReturn(Optional.of("attempt"));
            var attempt=mock(ExecutionStore.Attempt.class);var resultRef=new ResultRef("result");when(attempt.result()).thenReturn(Optional.of(resultRef));
            when(store.actions(runRef,0,2)).thenReturn(List.of(action));when(store.attempt(runRef,"attempt")).thenReturn(Optional.of(attempt));
            var content=new TypedPayload("application/json","{}");when(store.material(tenant,resultRef)).thenReturn(Optional.of(new ExecutionStore.Material(resultRef,content)));
            var outcome=mock(PortalOutcomeSnapshot.class);when(outcome.callKind()).thenReturn(PortalCallKind.MODEL_GENERATE);when(codec.decodeOutcome(content)).thenReturn(outcome);
            var semantics=mock(PortalOutcomeCodec.OutcomeSemantics.class);when(semantics.failure()).thenReturn(Optional.empty());
            when(outcomes.semantics(outcome,PortalCallKind.MODEL_GENERATE)).thenReturn(semantics);
            var model=mock(ModelPort.ModelResult.Completed.class);when(model.finishReasonCode()).thenReturn(finish);
            when(model.output()).thenReturn(new TypedPayload("application/json","\"部分结果\""));
            when(outcomes.decodeModel(outcome,PortalCallKind.MODEL_GENERATE)).thenReturn(model);
            var definition=mock(com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode.class);when(definition.id()).thenReturn("llm");
            var state=new LinkedHashMap<String,Object>();var node=mock(NodeExecutionContext.class);when(node.node()).thenReturn(definition);when(node.state()).thenReturn(state);
            if (finish.equals("stop")) {
                assertEquals(com.skillforge.core.workflow.execution.node.NodeOutcome.continueFlow(),subject.apply(node,run));
                assertEquals("部分结果",state.get("llm"));
            } else {
                var failure=assertThrows(WorkflowNodeFailure.class,()->subject.apply(node,run));
                assertEquals(finish.equals("length")?"model_output_length":"model_content_filtered",failure.reason());
                assertFalse(state.containsKey("llm"));
            }
        }
    }

    @Test void unresolvedNodeStillWaitsWithoutReadingOrAdoptingOutput() {
        var store=mock(ExecutionStore.class);var outcomes=mock(PortalOutcomeCodec.class);var codec=mock(AgentExecutionCodec.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentRuntimeExecutionWorker> workers=mock(ObjectProvider.class);
        var subject=new AgentRuntimeWorkflowNodes(mock(AgentRuntimeWorkflowNodeExecutions.class),store,workers,outcomes,codec,new ObjectMapper(),
                mock(AgentRuntimeWorkflowScripts.class),mock(AgentRuntimeExecutionSources.class),mock(WorkflowTestRuntimePolicy.class));
        var run=mock(ExecutionStore.Run.class);when(run.phase()).thenReturn(ExecutionStore.Phase.AWAITING_RESULT);
        assertEquals(com.skillforge.core.workflow.execution.node.NodeOutcome.waiting(com.skillforge.core.workflow.execution.node.NodeOutcome.WaitReason.CALLBACK),
                subject.apply(mock(NodeExecutionContext.class),run));
        verifyNoInteractions(store,outcomes,codec);
    }

    @Test void externalNodeCannotAdmitOrWakeWithoutActualParentRegistered() {
        var nodes=mock(AgentRuntimeWorkflowNodeExecutions.class);var store=mock(ExecutionStore.class);
        @SuppressWarnings("unchecked") ObjectProvider<AgentRuntimeExecutionWorker> workers=mock(ObjectProvider.class);
        var subject=new AgentRuntimeWorkflowNodes(nodes,store,workers,mock(PortalOutcomeCodec.class),mock(AgentExecutionCodec.class),new ObjectMapper(),
                mock(AgentRuntimeWorkflowScripts.class),mock(AgentRuntimeExecutionSources.class), org.mockito.Mockito.mock(com.skillforge.institution.runtime.WorkflowTestRuntimePolicy.class));
        var inputs=mock(SdkWorkflowPortal.NodeInputs.class);when(inputs.registered()).thenReturn(Optional.empty());
        var rejected=assertThrows(WorkflowNodeFailure.class,()->subject.execute(null,null,null,null,inputs,null));
        assertEquals("workflow_registered_source_required",rejected.reason());
        verifyNoInteractions(nodes,store,workers);
    }

    @Test void forgedNodeResultSignalCannotInventAnUncreatedNodeRun() {
        var tenant=new TenantId("1");var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("agent"),new EndUserId(1));
        var input=new InputId("input");var parent=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"parent"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("actor"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var actor=new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant,"host"),owner,Set.of("read"),1,new ResultRef("actor"));
        var execution=mock(ExecutionRecord.class);when(execution.owner()).thenReturn(parent);when(execution.executionRef()).thenReturn(new ExecutionRef(tenant,"execution"));
        var node=mock(NodeExecutionContext.class);when(node.callIdentity()).thenReturn(new NodeCallIdentity("execution","node",2,1));
        var store=mock(ExecutionStore.class);var child=WorkflowNodeSource.runIdentity(new WorkflowNodeCallIdentity(parent,execution.executionRef(),"node",2,1)).runRef();
        when(store.load(child)).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked") ObjectProvider<AgentRuntimeExecutionWorker> workers=mock(ObjectProvider.class);
        var subject=new AgentRuntimeWorkflowNodes(mock(AgentRuntimeWorkflowNodeExecutions.class),store,workers,mock(PortalOutcomeCodec.class),mock(AgentExecutionCodec.class),new ObjectMapper(),
                mock(AgentRuntimeWorkflowScripts.class),mock(AgentRuntimeExecutionSources.class), org.mockito.Mockito.mock(com.skillforge.institution.runtime.WorkflowTestRuntimePolicy.class));
        assertThrows(IllegalArgumentException.class,()->subject.requireExternalSignal(actor,execution,new TypedPayload(SdkWorkflowPortal.EXTERNAL_SIGNAL_SCHEMA,"{}"),null,node));
        verify(store).load(child);verifyNoInteractions(workers);
    }

    @Test void committedNodeCancellationSignalsBeforeUnknownScriptCleanupAndDoesNotInventQuiescence() {
        var tenant=new TenantId("1");var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("agent"),new EndUserId(1));
        var input=new InputId("input");var parent=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"parent"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("actor"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var key=new WorkflowNodeCallIdentity(parent,new ExecutionRef(tenant,"execution"),"script",1,1);
        var nodeIdentity=WorkflowNodeSource.runIdentity(key);var reference=nodeIdentity.runRef();var digest=WorkflowNodeSource.identityDigest(key);
        var actor=new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant,"host"),owner,Set.of("read"),1,new ResultRef("actor"));
        var store=mock(ExecutionStore.class);var scripts=mock(AgentRuntimeWorkflowScripts.class);
        var existing=nodeRun(nodeIdentity,false);var cancelled=nodeRun(nodeIdentity,true);
        when(store.load(reference)).thenReturn(Optional.of(existing));
        when(store.cancel(any())).thenReturn(cancelled);when(store.actions(reference,0,2)).thenReturn(List.of());
        var worker=mock(AgentRuntimeExecutionWorker.class);@SuppressWarnings("unchecked") ObjectProvider<AgentRuntimeExecutionWorker> workers=mock(ObjectProvider.class);when(workers.getObject()).thenReturn(worker);
        var execution=mock(ExecutionRecord.class);var lease=mock(com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease.class);var control=mock(InvocationControl.class);
        when(scripts.cancel(actor,lease,execution,control)).thenReturn(false);
        var subject=new AgentRuntimeWorkflowNodes(mock(AgentRuntimeWorkflowNodeExecutions.class),store,workers,mock(PortalOutcomeCodec.class),mock(AgentExecutionCodec.class),new ObjectMapper(),scripts,mock(AgentRuntimeExecutionSources.class), org.mockito.Mockito.mock(com.skillforge.institution.runtime.WorkflowTestRuntimePolicy.class));
        try(var source=mockStatic(WorkflowNodeSource.class)) {
            source.when(()->WorkflowNodeSource.activeIdentity(execution)).thenReturn(Optional.of(key));
            source.when(()->WorkflowNodeSource.runIdentity(key)).thenReturn(nodeIdentity);source.when(()->WorkflowNodeSource.identityDigest(key)).thenReturn(digest);
            assertFalse(subject.cancel(actor,lease,execution,control),"未知脚本取消不能冒充外部效果已经静止");
        }
        var ordered=inOrder(store,worker,scripts);ordered.verify(store).cancel(any());ordered.verify(worker).cancelled(reference);ordered.verify(scripts).cancel(actor,lease,execution,control);
    }

    /** 节点尚未创建Action；取消前后保持同一完整身份和材料，仅改变正式终态及取消事实。 */
    private static ExecutionStore.Run nodeRun(RunIdentity identity,boolean cancelled) {
        var now=Instant.EPOCH;
        return new ExecutionStore.Run(identity,new ResultRef("workflow-node-configuration"),cancelled?RunStatus.CANCELLED:RunStatus.RUNNING,
                cancelled?2:1,0,cancelled,cancelled?ExecutionStore.Phase.FINISHED:ExecutionStore.Phase.READY,
                new ExecutionStore.Progress(0,0,0,new ResultRef("workflow-node-context")),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),
                identity.causationIdentityRef(),Optional.empty(),now,cancelled?now.plusSeconds(1):now);
    }
}

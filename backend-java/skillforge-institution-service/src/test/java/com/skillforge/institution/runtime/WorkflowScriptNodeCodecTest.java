package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.institution.execution.WorkflowScriptSandboxClient;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkflowScriptNodeCodecTest {
    @Test void taskReferenceMatchesOnlyOriginalRequestAndSafeOutputKeepsRawStatus() {
        var payloads=mock(AgentRuntimePayloadValidation.class);when(payloads.accepts(any(TypedPayload.class),any(TypedPayload.class))).thenReturn(true);
        var codec=new WorkflowScriptNodeCodec(new ObjectMapper().findAndRegisterModules(),payloads);
        var prepared=preparation("{\"taskId\":\"original\"}");var reference=codec.reference(prepared,node());
        codec.require(reference,prepared);
        assertThrows(IllegalArgumentException.class,()->codec.require(reference,preparation("{\"taskId\":\"changed\"}")));
        var output=new TypedPayload("application/json","{\"answer\":27}");
        var status=new WorkflowScriptSandboxClient.Status(prepared.taskId(),WorkflowScriptSandboxClient.State.COMPLETED,Optional.of(output),Optional.empty());
        var raw=codec.status(status);var actual=new ToolOutcome.Succeeded(raw,new ResultRef("script-status"),List.of());
        assertEquals(Optional.of(output),codec.feedback(reference,actual));assertEquals(status,codec.status(raw));assertEquals(raw,actual.observation());
    }
    @Test void outputSchemaRejectionDoesNotReplaceActualCompletedTaskStatus() {
        var codec=new WorkflowScriptNodeCodec(new ObjectMapper().findAndRegisterModules(),mock(AgentRuntimePayloadValidation.class));
        var prepared=preparation("{}");var reference=codec.reference(prepared,node());
        var status=new WorkflowScriptSandboxClient.Status(prepared.taskId(),WorkflowScriptSandboxClient.State.COMPLETED,
                Optional.of(new TypedPayload("application/json","{\"wrong\":true}")),Optional.empty());
        var raw=codec.status(status);var actual=new ToolOutcome.Succeeded(raw,new ResultRef("script-status"),List.of());
        assertThrows(WorkflowScriptNodeCodec.OutputRejected.class,()->codec.feedback(reference,actual));
        assertEquals(WorkflowScriptSandboxClient.State.COMPLETED,codec.status(actual.observation()).state());
    }
    @Test void failedTaskOrDifferentTaskCannotInventSuccessfulScriptOutput() {
        var codec=new WorkflowScriptNodeCodec(new ObjectMapper().findAndRegisterModules(),mock(AgentRuntimePayloadValidation.class));
        var prepared=preparation("{}");var reference=codec.reference(prepared,node());
        var failed=new WorkflowScriptSandboxClient.Status(prepared.taskId(),WorkflowScriptSandboxClient.State.FAILED,Optional.empty(),Optional.of("actual-error"));
        assertThrows(WorkflowScriptNodeCodec.OutputRejected.class,()->codec.feedback(reference,new ToolOutcome.Succeeded(codec.status(failed),new ResultRef("failed"),List.of())));
        var other=new WorkflowScriptSandboxClient.Status("b".repeat(64),WorkflowScriptSandboxClient.State.COMPLETED,Optional.of(new TypedPayload("application/json","{}")),Optional.empty());
        assertThrows(IllegalArgumentException.class,()->codec.feedback(reference,new ToolOutcome.Succeeded(codec.status(other),new ResultRef("other"),List.of())));
    }
    private static NodeExecutionContext node() {
        var definition=mock(WorkflowNode.class);when(definition.args()).thenReturn(Map.of("output_schema",Map.of("type","object")));
        var node=mock(NodeExecutionContext.class);when(node.node()).thenReturn(definition);return node;
    }
    private static JdbcWorkflowScriptCalls.Preparation preparation(String body) {
        var tenant=new TenantId("1");var input=new InputId("input");var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("agent"),new EndUserId(1));
        var run=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"run"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("actor"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        return new JdbcWorkflowScriptCalls.Preparation(new WorkflowNodeCallIdentity(run,new ExecutionRef(tenant,"execution"),"script",1,1),1,1,
                new TypedPayload("checkpoint","{}"),"a".repeat(64),new TypedPayload("application/json",body),Instant.parse("2026-10-22T10:00:00Z"));
    }
}

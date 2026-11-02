package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.TenantId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 产品响应身份的拒绝边界：陈旧身份不能由宽松数值或未声明字段变成合法回复。 */
class AgentRuntimeExecutionResponsesTest {
    /** 提供一份合法外部回复，负向场景只改变需要拒绝的字段。 */
    private Map<String, Object> body() {
        return new HashMap<>(Map.of("execution_id", "run-1", "run_revision", "9007199254740993",
                "tool_input", Map.of("wait", Map.of("action_id", "action-1", "generation", "7"),
                        "responses", Map.of("quantity", Map.of("method", "elicitation/create",
                                "result", Map.of("schema_id", "application/json", "canonical_json", "{\"quantity\":27}"))))));
    }

    @Test void keepsExactHighRevisionAndTypedMethods() {
        var reply = AgentRuntimeExecutionResponses.tool(new TenantId("1"), body(), value -> "application/json".equals(value.schemaId()));
        assertEquals(9007199254740993L, reply.revision());
        assertEquals(7, reply.waiting().generation());
        assertEquals("action-1", reply.waiting().action().actionId());
        assertEquals("{\"quantity\":27}", reply.responses().get("quantity").result().canonicalJson());
    }

    @Test void rejectsNumericOrNonCanonicalRevisionWithoutValidation() {
        var body = body(); body.put("run_revision", 7L);
        assertThrows(ApiException.class, () -> AgentRuntimeExecutionResponses.tool(new TenantId("1"), body, value -> true));
        body.put("run_revision", "07");
        assertThrows(ApiException.class, () -> AgentRuntimeExecutionResponses.tool(new TenantId("1"), body, value -> true));
    }

    @Test void rejectsOldProofFieldsAndUnregisteredPayload() {
        var body = body(); body.put("checkpoint_revision", "7");
        assertThrows(ApiException.class, () -> AgentRuntimeExecutionResponses.tool(new TenantId("1"), body, value -> true));
        assertThrows(ApiException.class, () -> AgentRuntimeExecutionResponses.tool(new TenantId("1"), body(), value -> false));
    }

    @Test void humanReplyKeepsOriginalTextAndExactWaitWithoutToolArguments() {
        var body = Map.<String, Object>of("execution_id", "run-1", "run_revision", "9", "human_input",
                Map.of("wait", Map.of("action_id", "model-action-1", "generation", "2"), "text", "实际回复 27\n保留换行"));
        var reply = (AgentRuntimeExecutionResponses.HumanReply) AgentRuntimeExecutionResponses.decode(new TenantId("1"), body, value -> false);
        assertEquals("实际回复 27\n保留换行", reply.text()); assertEquals(2, reply.waiting().generation());
        assertEquals("model-action-1", reply.waiting().action().actionId());
    }

    @Test void mixedHumanAndToolReplyNeverChangesWaitKind() {
        var mixed = body();
        mixed.put("human_input", Map.of("wait", Map.of("action_id", "action-1", "generation", "7"), "text", "reply"));
        assertThrows(ApiException.class, () -> AgentRuntimeExecutionResponses.decode(new TenantId("1"), mixed, value -> true));
    }

    private Map<String, Object> workflowBody() {
        return new HashMap<>(Map.of("execution_id", "parent-run", "run_revision", "9007199254740993", "workflow_input",
                new HashMap<>(Map.of("wait", Map.of("action_id", "workflow-action", "generation", "2"),
                        "execution_ref", "sdk-execution", "suspend_ref", "sdk-suspend", "action_key", "human-node",
                        "execution_revision", "9007199254740994", "response", Map.of("schema_id", "application/json", "canonical_json", "{\"response\":\"实际确认\"}")))));
    }

    @Test void workflowHumanKeepsAllExactSdkAndRunWaitIdentities() {
        var reply = (AgentRuntimeExecutionResponses.WorkflowReply) AgentRuntimeExecutionResponses.decode(new TenantId("1"), workflowBody(), value -> true);
        assertEquals("parent-run", reply.run().runId());assertEquals(9007199254740993L,reply.revision());
        assertEquals("workflow-action",reply.waiting().action().actionId());assertEquals(2,reply.waiting().generation());
        assertEquals("sdk-execution",reply.execution().executionId());assertEquals("sdk-suspend",reply.suspendRef());
        assertEquals("human-node",reply.actionKey());assertEquals(9007199254740994L,reply.executionRevision());
        assertEquals("{\"response\":\"实际确认\"}",reply.response().canonicalJson());
    }

    @Test void workflowHumanRejectsOldFieldsNumericVersionsAndUnregisteredOrNonobjectResponse() {
        var mixed=workflowBody();mixed.put("suspend_ref","old");
        assertThrows(ApiException.class,()->AgentRuntimeExecutionResponses.decode(new TenantId("1"),mixed,value->true));
        for(var mutation:java.util.List.<Map.Entry<String,Object>>of(Map.entry("execution_revision",4L),
                Map.entry("execution_revision","04"),Map.entry("response",Map.of("schema_id","application/json","canonical_json","[]")),
                Map.entry("response",Map.of("schema_id","unregistered","canonical_json","{}")))) {
            var body=workflowBody();var input=new HashMap<>((Map<String,Object>)body.get("workflow_input"));
            input.put(mutation.getKey(),mutation.getValue());body.put("workflow_input",input);
            assertThrows(ApiException.class,()->AgentRuntimeExecutionResponses.decode(new TenantId("1"),body,value->true));
        }
        assertThrows(ApiException.class,()->AgentRuntimeExecutionResponses.decode(new TenantId("1"),workflowBody(),value->false));
    }

    @Test void workflowReplyCannotCrossPersistedSdkExecutionSuspendActionOrRevision() {
        var reply=(AgentRuntimeExecutionResponses.WorkflowReply)AgentRuntimeExecutionResponses.decode(new TenantId("1"),workflowBody(),value->true);
        var run=org.mockito.Mockito.mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
        var identity=org.mockito.Mockito.mock(com.skillforge.runtime.api.port.RunIdentity.class);
        org.mockito.Mockito.when(run.identity()).thenReturn(identity);org.mockito.Mockito.when(identity.runRef()).thenReturn(reply.run());
        var waiting=org.mockito.Mockito.mock(com.skillforge.runtime.session.spi.ExecutionStore.Waiting.class);
        org.mockito.Mockito.when(waiting.run()).thenReturn(reply.run());org.mockito.Mockito.when(waiting.reference()).thenReturn(reply.waiting());
        org.mockito.Mockito.when(waiting.kind()).thenReturn(com.skillforge.runtime.session.spi.ExecutionStore.WaitKind.WORKFLOW);
        var exact=new com.skillforge.runtime.session.spi.ExecutionStore.WorkflowWait(reply.execution(),reply.suspendRef(),reply.actionKey(),reply.executionRevision());
        org.mockito.Mockito.when(waiting.internal()).thenReturn(java.util.Optional.of(exact));
        assertDoesNotThrow(()->AgentRuntimeExecutionGateway.requireWorkflowIdentity(run,waiting,reply));
        for(var changed:java.util.List.of(
                new com.skillforge.runtime.session.spi.ExecutionStore.WorkflowWait(new com.skillforge.runtime.api.ExecutionRef(new TenantId("1"),"another-execution"),reply.suspendRef(),reply.actionKey(),reply.executionRevision()),
                new com.skillforge.runtime.session.spi.ExecutionStore.WorkflowWait(reply.execution(),"another-suspend",reply.actionKey(),reply.executionRevision()),
                new com.skillforge.runtime.session.spi.ExecutionStore.WorkflowWait(reply.execution(),reply.suspendRef(),"another-action",reply.executionRevision()),
                new com.skillforge.runtime.session.spi.ExecutionStore.WorkflowWait(reply.execution(),reply.suspendRef(),reply.actionKey(),reply.executionRevision()-1))) {
            org.mockito.Mockito.when(waiting.internal()).thenReturn(java.util.Optional.of(changed));
            assertThrows(ApiException.class,()->AgentRuntimeExecutionGateway.requireWorkflowIdentity(run,waiting,reply));
        }
        org.mockito.Mockito.when(waiting.internal()).thenReturn(java.util.Optional.of(exact));
        org.mockito.Mockito.when(waiting.kind()).thenReturn(com.skillforge.runtime.session.spi.ExecutionStore.WaitKind.HUMAN);
        assertThrows(ApiException.class,()->AgentRuntimeExecutionGateway.requireWorkflowIdentity(run,waiting,reply));
    }
}

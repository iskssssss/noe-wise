package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import com.skillforge.runtime.session.spi.ExecutionStore;
import org.junit.jupiter.api.Test;

final class SessionExecutionWorkflowsTest {
    private static final TenantId TENANT = new TenantId("7");
    private static final SessionRef SESSION = new SessionRef(TENANT,"session:source");
    private static final CapabilitySnapshotRef SNAPSHOT = new CapabilitySnapshotRef(SESSION,new InputId("input:source"),"snapshot:source",9007199254740993L);
    private static final FrozenWorkflowTarget TARGET = new FrozenWorkflowTarget(new WorkflowHandle("workflow:mount"),"instance:source",9007199254740993L,
            new CapabilityRef(CapabilityType.WORKFLOW,"capability","version:17"),Duration.ofSeconds(31),9007199254740995L);
    private static final ExecutionRef EXECUTION = new ExecutionRef(TENANT,"execution:original");
    private static final TypedPayload PARAMETERS = new TypedPayload("application/json","{\"input\":\"原始输入\"}");
    private static final TypedPayload ARGUMENTS = new TypedPayload("application/json","{\"workflow_handle\":\"workflow:mount\"}");
    private final SessionExecutionWorkflows codec = new SessionExecutionWorkflows(new CodecLimits(100_000,10_000,100));

    @Test void dispatchKeepsExactFrozenTargetEvidenceAndNativeOriginWithoutLegacySourceGraph() {
        var evidence = new EvidenceRef(SESSION,"bundle:real",List.of("item:1","item:2"),7,new EventId("event:real"));
        var request = new SessionExecutionWorkflows.Dispatch(SNAPSHOT,TARGET,EXECUTION,PARAMETERS,List.of(evidence),"call:source","dispatch_workflow",ARGUMENTS);
        var encoded = codec.encode(request);
        assertEquals(request,codec.decode(encoded)); assertEquals(PortalCallKind.WORKFLOW_DISPATCH,codec.decode(encoded).callKind());
        assertFalse(encoded.canonicalJson().contains("permit")); assertFalse(encoded.canonicalJson().contains("checkpoint"));
        assertTrue(encoded.canonicalJson().contains("\"9007199254740993\""));
        assertFalse(request.toString().contains("原始输入"));
    }
    @Test void resumeCancelAndOriginalExecutionQueryHaveClosedDistinctFields() {
        var resume = new SessionExecutionWorkflows.Resume(SNAPSHOT,TARGET,EXECUTION,"suspend:exact","action:exact",9007199254740997L,
                new TypedPayload("application/json","{\"answer\":\"实际回复\"}"),"call:source","dispatch_workflow",ARGUMENTS,new ResultRef("response:actual"),new InputId("reply:actual"));
        var cancel = new SessionExecutionWorkflows.Cancel(SNAPSHOT,TARGET,EXECUTION,"cancel:exact",17,"user_cancelled");
        var query = new SessionExecutionWorkflows.Query(SNAPSHOT,TARGET,EXECUTION);
        for (var request : List.of(resume,cancel,query)) assertEquals(request,codec.decode(codec.encode(request)));
        assertEquals(PortalCallKind.WORKFLOW_RESUME,resume.callKind()); assertEquals(PortalCallKind.WORKFLOW_CANCEL,cancel.callKind());
        assertEquals(PortalCallKind.WORKFLOW_RECOVER_RESULT,query.callKind());
    }
    @Test void authorizedOriginRoundTripsDispatchAndResumeWithoutInventingNativeModelCalls() {
        var origin = new SessionExecutionWorkflows.AuthorizedInputOrigin("intent:accepted-input");
        var evidence = new EvidenceRef(SESSION,"bundle:authorized",List.of("item:original"),7,new EventId("event:accepted"));
        var dispatch = new SessionExecutionWorkflows.Dispatch(SNAPSHOT,TARGET,EXECUTION,PARAMETERS,List.of(evidence),origin);
        var response = new TypedPayload("application/json","{\"accepted\":false,\"count\":0}");
        var resume = new SessionExecutionWorkflows.Resume(SNAPSHOT,TARGET,EXECUTION,"suspend:original","action:original",9007199254740997L,
                response,origin,new ResultRef("response:original"),new InputId("reply:original"));
        for (var request : List.of(dispatch,resume)) {
            var encoded = codec.encode(request);
            assertEquals("skillforge.runtime.execution.workflow-request.v3",encoded.schemaId());
            assertEquals(request,codec.decode(encoded));
            assertTrue(encoded.canonicalJson().contains("intent:accepted-input"));
            assertFalse(encoded.canonicalJson().contains("callId"));
            assertFalse(encoded.canonicalJson().contains("wireName"));
            assertFalse(encoded.canonicalJson().contains("arguments"));
        }
    }
    @Test void persistedOriginRejectsMixedMissingAndLegacyProvenanceForDispatchAndResume() {
        var origin = new SessionExecutionWorkflows.AuthorizedInputOrigin("intent:accepted-input");
        var dispatch = new SessionExecutionWorkflows.Dispatch(SNAPSHOT,TARGET,EXECUTION,PARAMETERS,List.of(),origin);
        var resume = new SessionExecutionWorkflows.Resume(SNAPSHOT,TARGET,EXECUTION,"suspend:original","action:original",5,
                PARAMETERS,origin,new ResultRef("response:original"),new InputId("reply:original"));
        for (var request : List.of(dispatch,resume)) {
            var encoded = codec.encode(request);
            assertThrows(IllegalArgumentException.class,()->codec.decode(new TypedPayload("skillforge.runtime.execution.workflow-request.v1",encoded.canonicalJson())));
            var documents = new AgentExecutionCodec(new CodecLimits(100_000,10_000,100));
            var missingOrigin = (com.fasterxml.jackson.databind.node.ObjectNode) documents.parse(encoded,encoded.schemaId());
            missingOrigin.remove("origin");
            var withoutOrigin = documents.encode(encoded.schemaId(),missingOrigin);
            assertThrows(IllegalArgumentException.class,()->codec.decode(withoutOrigin));
            for (var invalidOrigin : List.of(
                    "{}",
                    "{\"kind\":\"UNRECOGNIZED\",\"intentRef\":\"intent:accepted-input\"}",
                    "{\"kind\":\"AUTHORIZED_INPUT\"}",
                    "{\"kind\":\"MODEL\"}",
                    "{\"kind\":\"AUTHORIZED_INPUT\",\"intentRef\":\" \"}",
                    "{\"kind\":\"AUTHORIZED_INPUT\",\"intentRef\":7}",
                    "{\"kind\":\"AUTHORIZED_INPUT\",\"intentRef\":\"intent:accepted-input\",\"callId\":\"forged-model-call\"}",
                    "{\"kind\":\"AUTHORIZED_INPUT\",\"intentRef\":\"intent:accepted-input\",\"wireName\":\"dispatch_workflow\"}",
                    "{\"kind\":\"AUTHORIZED_INPUT\",\"intentRef\":\"intent:accepted-input\",\"arguments\":{\"schemaId\":\"application/json\",\"canonicalJson\":\"{}\"}}",
                    "{\"kind\":\"MODEL\",\"callId\":\"call:source\",\"wireName\":\"dispatch_workflow\",\"arguments\":{\"schemaId\":\"application/json\",\"canonicalJson\":\"{}\"},\"intentRef\":\"forged-authority\"}")) {
                var root = (com.fasterxml.jackson.databind.node.ObjectNode) documents.parse(encoded,encoded.schemaId());
                root.set("origin",documents.parse(new TypedPayload("application/json",invalidOrigin),"application/json"));
                var tampered = documents.encode(encoded.schemaId(),root);
                assertThrows(IllegalArgumentException.class,()->codec.decode(tampered),invalidOrigin);
            }
        }
    }
    @Test void invalidEvidenceAndCrossTenantExecutionCannotBeEncodedAsValidSource() {
        var evidence = new EvidenceRef(new SessionRef(TENANT,"different-session"),"bundle",List.of("item"),1,new EventId("event"));
        assertThrows(IllegalArgumentException.class,()->new SessionExecutionWorkflows.Dispatch(SNAPSHOT,TARGET,EXECUTION,PARAMETERS,List.of(evidence),"call","dispatch",ARGUMENTS));
        assertThrows(IllegalArgumentException.class,()->new SessionExecutionWorkflows.Query(SNAPSHOT,TARGET,new ExecutionRef(new TenantId("8"),"execution")));
        assertThrows(IllegalArgumentException.class,()->new SessionExecutionWorkflows.Resume(SNAPSHOT,TARGET,EXECUTION,"suspend","action",0,PARAMETERS,"call","resume",ARGUMENTS,new ResultRef("response:actual"),new InputId("reply:actual")));
    }
    @Test void unknownFieldsAndNoncanonicalNumericRevisionAreRejected() {
        var query = codec.encode(new SessionExecutionWorkflows.Query(SNAPSHOT,TARGET,EXECUTION));
        assertThrows(IllegalArgumentException.class,()->codec.decode(new TypedPayload(query.schemaId(),query.canonicalJson().replace("\"operation\":", "\"unknown\":true,\"operation\":"))));
        assertThrows(IllegalArgumentException.class,()->codec.decode(new TypedPayload(query.schemaId(),query.canonicalJson().replace("\"9007199254740993\"","9007199254740993"))));
        assertThrows(IllegalArgumentException.class,()->codec.decode(new TypedPayload("legacy-workflow-request",query.canonicalJson())));
    }
    @Test void nestedPayloadDocumentsShareTheActualParsingQuota() {
        var small = new SessionExecutionWorkflows(new CodecLimits(100_000,25,100));
        var many = "[" + String.join(",",java.util.Collections.nCopies(30,"true")) + "]";
        var request = new SessionExecutionWorkflows.Dispatch(SNAPSHOT,TARGET,EXECUTION,new TypedPayload("application/json",many),List.of(),"call","dispatch",ARGUMENTS);
        assertThrows(IllegalArgumentException.class,()->small.encode(request));
    }
    @Test void suspendedResultRetainsCompleteRawOutcomeAndExactHostWaitWithoutGuessingActionKey() {
        var modelCodec = new AgentExecutionCodec(new CodecLimits(100_000,10_000,100));
        var payloads = new SessionRuntimePayloads();
        var core = new CoreRuntime(new CodecLimits(100_000,10_000,100), payloads);
        var raw = core.portalResults(payloads, payloads, payloads).encodeWorkflow(PortalCallKind.WORKFLOW_DISPATCH,
                new WorkflowResult.Suspended(EXECUTION, "suspend:real", 9007199254740997L,
                        new ResultRef("raw:result"), LoopSuspendReason.WORKFLOW_EXTERNAL));
        var wait = new ExecutionStore.WorkflowWait(EXECUTION,"suspend:real","real-engine-action",9007199254740997L);
        var value = new SessionExecutionWorkflows.Result(modelCodec.encodeOutcome(raw),Optional.of(wait));
        assertEquals(value,codec.decodeResult(codec.encodeResult(value)));
        assertEquals(raw,modelCodec.decodeOutcome(codec.decodeResult(codec.encodeResult(value)).outcome()));
        assertThrows(IllegalArgumentException.class,()->codec.encodeResult(new SessionExecutionWorkflows.Result(value.outcome(),Optional.empty())));
        assertThrows(IllegalArgumentException.class,()->codec.encodeResult(new SessionExecutionWorkflows.Result(value.outcome(),Optional.of(
                new ExecutionStore.WorkflowWait(EXECUTION,"different-suspend","real-engine-action",wait.executionRevision())))));
    }
    @Test void completedFeedbackAcceptsRegisteredEngineOutputAndKeepsExactOriginalPayload() {
        var output = new TypedPayload("skillforge.runtime.workflow.engine-result.v1","{\"answer\":\"已完成\"}");
        var feedback = codec.feedback(new WorkflowResult.Completed(EXECUTION,output,9007199254740997L,new ResultRef("result:real"))).orElseThrow();
        assertTrue(feedback.canonicalJson().contains("9007199254740997"));
        assertTrue(feedback.canonicalJson().contains("skillforge.runtime.workflow.engine-result.v1"));
        assertTrue(feedback.canonicalJson().contains("已完成"));
        assertTrue(codec.feedback(new WorkflowResult.Suspended(EXECUTION,"suspend",1,new ResultRef("wait"),LoopSuspendReason.WORKFLOW_HITL)).isEmpty());
        assertThrows(IllegalArgumentException.class,()->codec.feedback(new WorkflowResult.Accepted(EXECUTION,1)));
    }
}

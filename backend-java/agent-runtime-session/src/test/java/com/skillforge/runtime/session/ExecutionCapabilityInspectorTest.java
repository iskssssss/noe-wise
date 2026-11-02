package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.EvidenceItem;
import com.skillforge.runtime.api.EvidenceSufficiency;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.KnowledgeBudget;
import com.skillforge.runtime.api.KnowledgeDocumentResult;
import com.skillforge.runtime.api.KnowledgeMode;
import com.skillforge.runtime.api.KnowledgeOperation;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.KnowledgePlan;
import com.skillforge.runtime.api.KnowledgeRequest;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.KnowledgeStep;
import com.skillforge.runtime.api.KnowledgeTraceStep;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.DelegationCommand;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeHandle;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolHandle;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 实际能力的安全诊断只读测试；输入/输出、来源授权与执行结果分别表达。 */
class ExecutionCapabilityInspectorTest {
    private static final TenantId TENANT = new TenantId("7");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session:source");
    private static final AgentId AGENT = new AgentId("agent:source");
    private static final InputId INPUT = new InputId("input:source");
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final CodecLimits LIMITS = new CodecLimits(100_000, 10_000, 100);
    private static final KnowledgeSourceVersion FIRST = new KnowledgeSourceVersion("source-1", "version-1");
    private static final KnowledgeSourceVersion SECOND = new KnowledgeSourceVersion("source-2", "version-2");
    private static final KnowledgeBudget BUDGET = new KnowledgeBudget(2, 10, 1_000, 10_000, 1_000);
    private static final CapabilitySnapshotRef SNAPSHOT = new CapabilitySnapshotRef(SESSION, INPUT, "snapshot:source", 1);
    private static final ExecutionRef EXECUTION = new ExecutionRef(TENANT, "execution:source");
    private static final FrozenWorkflowTarget WORKFLOW = new FrozenWorkflowTarget(new WorkflowHandle("workflow:mount"),
            "private-instance", 17, new CapabilityRef(CapabilityType.WORKFLOW, "private-capability", "private-version"),
            Duration.ofSeconds(30), 19);
    private final AgentExecutionCodec codec = new AgentExecutionCodec(LIMITS);
    private final SessionRuntimePayloads payloads = new SessionRuntimePayloads();
    private final CoreRuntime core = new CoreRuntime(LIMITS, payloads);
    private final SessionExecutionWorkflows workflows = new SessionExecutionWorkflows(LIMITS);
    private final ExecutionCapabilityInspector inspector = new ExecutionCapabilityInspector(100_000, 10_000, 100);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void toolInspectionKeepsExactFrozenBindingForHostPolicyWithoutSerializingIt() throws Exception {
        var schema = new TypedPayload("json-schema", "{\"type\":\"object\"}");
        var binding = new ToolBinding(ToolBinding.ProviderType.PLATFORM, "search_memory", "search_memory");
        var target = new FrozenToolTarget(new ToolHandle("tool:memory"),
                new CapabilityRef(CapabilityType.TOOL, "private-memory-capability", "private-memory-version"),
                "private-memory-deployment", 1, schema, schema, ToolEffect.READ_ONLY, true,
                Duration.ofSeconds(10), true, ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
        var request = codec.encodeTool(new AgentExecutionCodec.ToolRequest(target, binding,
                new TypedPayload("application/json", "{\"query\":\"当前受控查询\"}"), "call:memory", "aliased_wire_name"));

        var inspection = inspector.inspect(ExecutionStore.Kind.TOOL, request, Optional.empty());

        assertEquals(Optional.of(binding), inspection.toolBinding());
        assertEquals("aliased_wire_name", inspection.title());
        assertEquals("当前受控查询", json.readTree(inspection.input().canonicalJson()).path("query").textValue());
        var publicFields = json.getSerializationConfig().introspect(json.constructType(ExecutionCapabilityInspector.Inspection.class))
                .findProperties().stream().filter(property -> property.couldSerialize()).map(property -> property.getName()).toList();
        assertFalse(publicFields.contains("toolBinding"));
        assertFalse(publicFields.contains("knowledgeAccess"));
    }

    @Test
    void knowledgeShowsTheActualQueryAndSelectedEvidenceWithoutRawBundleOrUnselectedMaterial() throws Exception {
        var call = knowledgeCall(new KnowledgeOperation.Search("如何做评估"), Set.of(KnowledgeOperatorType.KEYWORD));
        var selected = item("selected", FIRST, "已选操作步骤", KnowledgeOperatorType.KEYWORD);
        var excluded = item("unselected", SECOND, "PRIVATE_UNSELECTED_TEXT", KnowledgeOperatorType.KEYWORD);
        var steps = List.of(step("first", FIRST, KnowledgeOperatorType.KEYWORD), step("second", SECOND, KnowledgeOperatorType.KEYWORD));
        var bundle = new EvidenceBundle("bundle:real", call.request().knowledgeRequestId(),
                new KnowledgePlan("plan:real", call.request().knowledgeRequestId(), steps, "done", "planner:real"),
                List.of(selected, excluded), List.of(), EvidenceSufficiency.PARTIAL,
                List.of(new KnowledgeTraceStep("first", "selection", List.of("selected"), List.of("selected"), 2, 1),
                        new KnowledgeTraceStep("second", "selection", List.of("unselected"), List.of(), 1, 1)), 7, NOW, Optional.empty());

        var inspection = inspector.inspect(ExecutionStore.Kind.KNOWLEDGE, codec.encodeKnowledgeRequest(call), Optional.of(knowledgeResult(bundle)));
        var input = json.readTree(inspection.input().canonicalJson());
        var output = json.readTree(inspection.output().orElseThrow().canonicalJson());

        assertEquals("如何做评估", input.path("query").asText());
        assertFalse(input.has("snapshot"));
        assertEquals("RETRIEVAL_SELECTED", output.path("selection_scope").asText());
        assertEquals(1, output.path("items").size());
        assertEquals("已选操作步骤", output.path("items").get(0).path("text").asText());
        assertFalse(output.toString().contains("PRIVATE_UNSELECTED_TEXT"));
        assertFalse(output.toString().contains("private-provenance"));
        assertFalse(output.has("plan"));
        assertFalse(output.has("trace"));
        var access = inspection.knowledgeAccess().orElseThrow();
        assertEquals(call.target(), access.target());
        assertEquals(SESSION, access.sessionRef());
        assertEquals(AGENT, access.agentId());
        assertEquals(Set.of(FIRST), access.sourceVersions());
        assertFalse(inspection.toString().contains("评估"));
    }

    @Test
    void knowledgeCannotReadAnotherRequestsResultOrFallbackToTheRawEnvelope() {
        var call = knowledgeCall(new KnowledgeOperation.Search("如何做评估"), Set.of(KnowledgeOperatorType.KEYWORD));
        var bundle = new EvidenceBundle("bundle:wrong", "other-request",
                new KnowledgePlan("plan:wrong", "other-request", List.of(step("first", FIRST, KnowledgeOperatorType.KEYWORD)), "done", "planner"),
                List.of(), List.of(), EvidenceSufficiency.INSUFFICIENT, List.of(), 1, NOW, Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspect(ExecutionStore.Kind.KNOWLEDGE, codec.encodeKnowledgeRequest(call), Optional.of(knowledgeResult(bundle))));
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspect(ExecutionStore.Kind.KNOWLEDGE, new TypedPayload("legacy-knowledge", "{}"), Optional.empty()));
    }

    @Test
    void documentCatalogAndSliceKeepExactSourceVersionsAndBoundedPublicContent() throws Exception {
        var document = new KnowledgeDocumentResult.Document(FIRST.sourceId(), "document-1", FIRST.sourceVersion(), "评估说明", 3);
        var catalog = knowledgeCall(new KnowledgeOperation.ListDocuments("评估", Optional.empty(), 10), Set.of(KnowledgeOperatorType.DOCUMENT_LIST));
        var catalogBundle = documentBundle(catalog, KnowledgeOperatorType.DOCUMENT_LIST,
                new KnowledgeDocumentResult.CatalogPage(List.of(document), Optional.empty()), List.of());
        var listed = inspector.inspect(ExecutionStore.Kind.KNOWLEDGE, codec.encodeKnowledgeRequest(catalog), Optional.of(knowledgeResult(catalogBundle)));
        assertEquals(Set.of(FIRST), listed.knowledgeAccess().orElseThrow().sourceVersions());
        assertEquals("评估说明", json.readTree(listed.output().orElseThrow().canonicalJson())
                .path("document_access").path("documents").get(0).path("title").asText());

        var read = knowledgeCall(new KnowledgeOperation.ReadDocument(FIRST.sourceId(), "document-1", FIRST.sourceVersion(), 0, 3), Set.of(KnowledgeOperatorType.DOCUMENT_READ));
        var text = new EvidenceItem("slice-1", FIRST.sourceId(), FIRST.sourceVersion(), KnowledgeOperatorType.DOCUMENT_READ,
                new TypedPayload("skillforge.runtime.knowledge.document-slice.v1", "{\"text\":\"操作一\"}"),
                List.of("knowledge-document:document-1"), List.of(), 1, "tenant");
        var sliceBundle = documentBundle(read, KnowledgeOperatorType.DOCUMENT_READ,
                new KnowledgeDocumentResult.DocumentSlice(document, 0, 3, OptionalInt.empty()), List.of(text));
        var inspected = inspector.inspect(ExecutionStore.Kind.KNOWLEDGE, codec.encodeKnowledgeRequest(read), Optional.of(knowledgeResult(sliceBundle)));
        var output = json.readTree(inspected.output().orElseThrow().canonicalJson());
        assertEquals("操作一", output.path("items").get(0).path("text").asText());
        assertEquals(3, output.path("document_access").path("end_offset").asInt());
        assertEquals(Set.of(FIRST), inspected.knowledgeAccess().orElseThrow().sourceVersions());
    }

    @Test
    void workflowAuthorizedInputShowsDeclaredParametersAndOutputWithoutInventingAModelCall() {
        var parameters = new TypedPayload("application/json", "{\"topic\":\"评估流程\"}");
        var output = new TypedPayload("application/json", "{\"answer\":\"已生成说明\"}");
        var call = new SessionExecutionWorkflows.Dispatch(SNAPSHOT, WORKFLOW, EXECUTION, parameters, List.of(),
                new SessionExecutionWorkflows.AuthorizedInputOrigin("private-intent"));
        var actual = workflowResult(new WorkflowResult.Completed(EXECUTION, output, 21, new ResultRef("private-result")));

        var inspected = inspector.inspect(ExecutionStore.Kind.WORKFLOW, workflows.encode(call), Optional.of(actual));

        assertEquals(parameters, inspected.input());
        assertEquals(Optional.of(output), inspected.output());
        assertEquals(null, inspected.callId());
        assertFalse(inspected.input().canonicalJson().contains("private-instance"));
        assertFalse(inspected.output().orElseThrow().canonicalJson().contains("private-result"));
        assertEquals(ExecutionCapabilityInspector.Availability.FULL, inspected.outputAvailability());
    }

    @Test
    void workflowUnknownAndMismatchedExecutionRemainDistinctFromSuccessfulOutput() throws Exception {
        var call = new SessionExecutionWorkflows.Dispatch(SNAPSHOT, WORKFLOW, EXECUTION,
                new TypedPayload("application/json", "{}"), List.of(), new SessionExecutionWorkflows.AuthorizedInputOrigin("intent"));
        var unknown = new WorkflowResult.OutcomeUnknown(EXECUTION, OptionalLong.empty(),
                new PortFailure("workflow_outcome_unknown", RetryMode.NONE, new ResultRef("private-reconciliation")));
        var inspected = inspector.inspect(ExecutionStore.Kind.WORKFLOW, workflows.encode(call), Optional.of(workflowResult(unknown)));
        var output = json.readTree(inspected.output().orElseThrow().canonicalJson());
        assertEquals("OUTCOME_UNKNOWN", output.path("status").asText());
        assertFalse(output.toString().contains("private-reconciliation"));
        var wrong = new WorkflowResult.Completed(new ExecutionRef(TENANT, "another-execution"),
                new TypedPayload("application/json", "{}"), 1, new ResultRef("wrong-result"));
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspect(ExecutionStore.Kind.WORKFLOW, workflows.encode(call), Optional.of(workflowResult(wrong))));
    }

    @Test
    void delegationExposesTheActualTaskAndWhitelistReturnWithoutInternalEvidenceOrArbitraryExtras() throws Exception {
        var boundary = new SessionExecutionDelegations(LIMITS);
        var call = boundary.encodeRequest(new SessionExecutionDelegations.Request(
                DelegationCommand.delegate("member:worker", "核对评估操作步骤", true), "call:real", "delegate"));
        var response = new TypedPayload("application/json", """
                {"results":[{"delegation_id":"delegation:real","agent_name":"核查Agent","state":"completed",
                  "objective":"核对评估操作步骤","summary":"已核对来源","used_tokens_reported":"9007199254740993",
                  "usage_complete":false,"evidence":["private-result-reference"],"credential":"PRIVATE_SECRET"}],
                 "checkpoint":"PRIVATE_CHECKPOINT","wait_id":"private-wait"}
                """);
        var inspected = inspector.inspect(ExecutionStore.Kind.DELEGATION, call,
                Optional.of(boundary.encodeResult(new SessionExecutionDelegations.Result(response, Optional.empty()))));
        var input = json.readTree(inspected.input().canonicalJson());
        var output = json.readTree(inspected.output().orElseThrow().canonicalJson());
        assertEquals("核对评估操作步骤", input.path("objective").asText());
        assertEquals("已核对来源", output.path("results").get(0).path("summary").asText());
        assertEquals("9007199254740993", output.path("results").get(0).path("used_tokens_reported").asText());
        assertFalse(output.toString().contains("private"));
        assertFalse(output.toString().contains("PRIVATE"));
        assertEquals(ExecutionCapabilityInspector.Availability.PARTIAL, inspected.outputAvailability());
    }

    @Test
    void missingResultIsExplicitAndModelContextCannotEnterThisPublicReader() {
        var call = knowledgeCall(new KnowledgeOperation.Search("如何做评估"), Set.of(KnowledgeOperatorType.KEYWORD));
        var inspected = inspector.inspect(ExecutionStore.Kind.KNOWLEDGE, codec.encodeKnowledgeRequest(call), Optional.empty());
        assertTrue(inspected.output().isEmpty());
        assertEquals(ExecutionCapabilityInspector.Availability.NOT_RECORDED, inspected.outputAvailability());
        assertThrows(IllegalArgumentException.class,
                () -> inspector.inspect(ExecutionStore.Kind.MODEL, new TypedPayload("application/json", "{\"reasoning\":\"private\"}"), Optional.empty()));
    }

    private AgentExecutionCodec.KnowledgeCallRequest knowledgeCall(KnowledgeOperation operation, Set<KnowledgeOperatorType> operators) {
        var target = new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:mount"),
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "private-capability", "private-version"),
                "private-descriptor", 17, Set.of(FIRST, SECOND), operators, Duration.ofSeconds(5));
        var request = new KnowledgeRequest("knowledge-request", TENANT, new RunRequester.EndUser(new EndUserId(23L)),
                AGENT, SESSION, new TraceEnvelope("trace:source", "span:source", INPUT, Optional.empty()),
                operation instanceof KnowledgeOperation.Search ? KnowledgeMode.RESEARCH : KnowledgeMode.GROUNDING,
                "private-intent", operation, Set.of(FIRST.sourceId(), SECOND.sourceId()), Set.of(FIRST, SECOND), operators,
                BUDGET, NOW, Optional.empty(), true);
        return new AgentExecutionCodec.KnowledgeCallRequest(target, request, SNAPSHOT, new EventId("event:source"),
                "call:knowledge", "knowledge_query", new TypedPayload("application/json", "{}"));
    }

    private static EvidenceItem item(String id, KnowledgeSourceVersion source, String text, KnowledgeOperatorType operator) {
        return new EvidenceItem(id, source.sourceId(), source.sourceVersion(), operator,
                new TypedPayload("skillforge.runtime.knowledge.chunk.v1", "{\"text\":\"" + text + "\"}"),
                List.of("private-provenance"), List.of("private-graph-path"), 0.8, "tenant");
    }

    private static KnowledgeStep step(String id, KnowledgeSourceVersion source, KnowledgeOperatorType operator) {
        return new KnowledgeStep(id, "评估流程", Set.of(source.sourceId()), operator, List.of(), BUDGET);
    }

    private static EvidenceBundle documentBundle(AgentExecutionCodec.KnowledgeCallRequest call, KnowledgeOperatorType operator,
            KnowledgeDocumentResult result, List<EvidenceItem> items) {
        var ids = items.stream().map(EvidenceItem::itemId).toList();
        return new EvidenceBundle("bundle:document", call.request().knowledgeRequestId(),
                new KnowledgePlan("plan:document", call.request().knowledgeRequestId(), List.of(step("document", FIRST, operator)), "done", "planner"),
                items, List.of(), EvidenceSufficiency.PARTIAL,
                List.of(new KnowledgeTraceStep("document", "selection", ids, ids, 2, 1)), 1, NOW, Optional.of(result));
    }

    private TypedPayload knowledgeResult(EvidenceBundle bundle) {
        return codec.encodeOutcome(core.portalResults(payloads, payloads, payloads).encodeKnowledge(PortalCallKind.KNOWLEDGE_EXECUTE,
                new KnowledgeOutcome.Retrieved(bundle, new ResultRef("private-business-result"))));
    }

    private TypedPayload workflowResult(WorkflowResult result) {
        var outcome = codec.encodeOutcome(core.portalResults(payloads, payloads, payloads).encodeWorkflow(PortalCallKind.WORKFLOW_DISPATCH, result));
        return workflows.encodeResult(new SessionExecutionWorkflows.Result(outcome, Optional.empty()));
    }
}

package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static com.skillforge.runtime.api.port.PortalCallKind.KNOWLEDGE_EXECUTE;
import static com.skillforge.runtime.api.port.PortalCallKind.MODEL_GENERATE;
import static com.skillforge.runtime.api.port.PortalCallKind.MODEL_STREAM;
import static com.skillforge.runtime.api.port.PortalCallKind.TOOL_INVOKE;
import static com.skillforge.runtime.api.port.PortalCallKind.WORKFLOW_CANCEL;
import static com.skillforge.runtime.api.port.PortalCallKind.WORKFLOW_DISPATCH;
import static com.skillforge.runtime.api.port.PortalCallKind.WORKFLOW_RECOVER_RESULT;
import static com.skillforge.runtime.api.port.PortalCallKind.WORKFLOW_RESUME;
import static com.skillforge.runtime.codec.spi.PortalOutcomeCodec.CanonicalObservationRule.FORBIDDEN;
import static com.skillforge.runtime.codec.spi.PortalOutcomeCodec.CanonicalObservationRule.REQUIRED;
import static com.skillforge.runtime.codec.spi.PortalOutcomeCodec.ResolutionRelation.COMPATIBLE;
import static com.skillforge.runtime.codec.spi.PortalOutcomeCodec.ResolutionRelation.CONFLICT;
import static com.skillforge.runtime.codec.spi.PortalOutcomeCodec.ResolutionRelation.EQUIVALENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.EvidenceItem;
import com.skillforge.runtime.api.EvidenceSufficiency;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.KnowledgePlan;
import com.skillforge.runtime.api.LoopTerminationReason;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.AssistantContent;
import com.skillforge.runtime.api.port.ModelPort.AssistantTurn;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.InvocationBarrierProof;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowControlResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowSnapshot;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowRecoveredResult;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec.CanonicalObservationRule;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec.FailureSemantics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 固定 Action resolved-index registry 的完整 subtype、投影与比较语义。 */
class PortalOutcomeResolutionRegistryTest {
    private static final CodecLimits LIMITS = new CodecLimits(20_000, 2_000, 40);
    private static final TenantId TENANT = new TenantId("tenant");
    private static final ExecutionRef EXECUTION = new ExecutionRef(TENANT, "execution");
    private static final TypedPayload MODEL = new TypedPayload("model.v1", "{\"value\":1}");
    private static final TypedPayload TOOL = new TypedPayload("tool.v1", "{\"value\":2}");
    private static final TypedPayload WORKFLOW = new TypedPayload("workflow.v1", "{\"value\":3}");
    private static final TypedPayload EVIDENCE = new TypedPayload("evidence.v1", "{\"value\":4}");

    private final PortalOutcomeCodec codec = codec();

    /** 每个已登记 subtype 都固定 eligibility、失败语义与 canonical observation presence。 */
    @Test
    void registersEveryPortalSubtype() {
        for (PortalCallKind kind : List.of(MODEL_GENERATE, MODEL_STREAM)) {
            assertRegistered(codec.encodeModel(kind, modelCompleted("model-result")), kind,
                    true, REQUIRED, null);
            assertRegistered(codec.encodeModel(kind,
                    new ModelResult.Failed(failure("model_failed", RetryMode.NONE))), kind,
                    true, FORBIDDEN, failure("model_failed", RetryMode.NONE));
            assertRegistered(codec.encodeModel(kind,
                    new ModelResult.OutcomeUnknown(failure("model_unknown", RetryMode.NONE))), kind,
                    false, FORBIDDEN, failure("model_unknown", RetryMode.NONE));
        }

        assertRegistered(codec.encodeTool(TOOL_INVOKE,
                new ToolOutcome.Succeeded(TOOL, new ResultRef("tool-result"), List.of())),
                TOOL_INVOKE, true, REQUIRED, null);
        assertRegistered(codec.encodeTool(TOOL_INVOKE,
                new ToolOutcome.InputRequired(Map.of(), Optional.of("tool-request-state"))),
                TOOL_INVOKE, false, FORBIDDEN, null);
        assertRegistered(codec.encodeTool(TOOL_INVOKE,
                new ToolOutcome.Failed(failure("tool_failed", RetryMode.NONE))),
                TOOL_INVOKE, true, FORBIDDEN, failure("tool_failed", RetryMode.NONE));
        assertRegistered(codec.encodeTool(TOOL_INVOKE,
                new ToolOutcome.OutcomeUnknown(failure(
                        "tool_unknown", RetryMode.IDEMPOTENT_BY_STABLE_KEY))),
                TOOL_INVOKE, false, FORBIDDEN,
                failure("tool_unknown", RetryMode.IDEMPOTENT_BY_STABLE_KEY));

        for (PortalCallKind kind : List.of(WORKFLOW_DISPATCH, WORKFLOW_RESUME)) {
            assertWorkflowInvokeFamily(kind);
        }

        var completed = workflowCompleted(5, "workflow-recovered");
        var failed = workflowFailed(OptionalLong.of(6), "workflow_recovered_failed", RetryMode.NONE);
        var terminated = workflowTerminated(7, "workflow-recovered-terminated");
        var cancelled = workflowCancelled(8, "workflow-recovered-cancelled");
        assertRegistered(codec.encodeWorkflowRecovery(WORKFLOW_RECOVER_RESULT,
                new WorkflowRecoveredResult.Completed(completed)),
                WORKFLOW_RECOVER_RESULT, true, REQUIRED, null);
        assertRegistered(codec.encodeWorkflowRecovery(WORKFLOW_RECOVER_RESULT,
                new WorkflowRecoveredResult.Failed(failed)),
                WORKFLOW_RECOVER_RESULT, true, FORBIDDEN, failed.failure());
        assertRegistered(codec.encodeWorkflowRecovery(WORKFLOW_RECOVER_RESULT,
                new WorkflowRecoveredResult.Terminated(terminated)),
                WORKFLOW_RECOVER_RESULT, true, FORBIDDEN, null);
        assertRegistered(codec.encodeWorkflowRecovery(WORKFLOW_RECOVER_RESULT,
                new WorkflowRecoveredResult.Cancelled(cancelled)),
                WORKFLOW_RECOVER_RESULT, true, FORBIDDEN, null);
        assertRegistered(codec.encodeWorkflowRecovery(WORKFLOW_RECOVER_RESULT, absent()),
                WORKFLOW_RECOVER_RESULT, false, FORBIDDEN, null);
        var rejected = failure("recovery_rejected", RetryMode.RESULT_QUERYABLE);
        assertRegistered(codec.encodeWorkflowRecovery(WORKFLOW_RECOVER_RESULT,
                new WorkflowRecoveredResult.Rejected(rejected)),
                WORKFLOW_RECOVER_RESULT, false, FORBIDDEN, rejected);

        assertRegistered(codec.encodeWorkflowControl(WORKFLOW_CANCEL,
                new WorkflowControlResult.Applied(workflowSnapshot(
                        WorkflowExecutionState.CANCELLED, 9))),
                WORKFLOW_CANCEL, true, FORBIDDEN, null);
        assertRegistered(codec.encodeWorkflowControl(WORKFLOW_CANCEL,
                new WorkflowControlResult.Replayed(workflowSnapshot(
                        WorkflowExecutionState.CANCELLED, 9))),
                WORKFLOW_CANCEL, true, FORBIDDEN, null);
        var conflict = failure("cancel_conflict", RetryMode.NONE);
        assertRegistered(codec.encodeWorkflowControl(WORKFLOW_CANCEL,
                new WorkflowControlResult.Conflict(workflowSnapshot(
                        WorkflowExecutionState.RUNNING, 8), conflict)),
                WORKFLOW_CANCEL, true, FORBIDDEN, conflict);
        var cancelRejected = failure("cancel_rejected", RetryMode.NONE);
        assertRegistered(codec.encodeWorkflowControl(WORKFLOW_CANCEL,
                new WorkflowControlResult.Rejected(cancelRejected)),
                WORKFLOW_CANCEL, false, FORBIDDEN, cancelRejected);
        var cancelUnknown = failure("cancel_unknown", RetryMode.RESULT_QUERYABLE);
        assertRegistered(codec.encodeWorkflowControl(WORKFLOW_CANCEL,
                new WorkflowControlResult.OutcomeUnknown(cancelUnknown)),
                WORKFLOW_CANCEL, false, FORBIDDEN, cancelUnknown);

        assertRegistered(codec.encodeKnowledge(KNOWLEDGE_EXECUTE, knowledge("knowledge-result")),
                KNOWLEDGE_EXECUTE, true, REQUIRED, null);
        var knowledgeFailed = failure("knowledge_failed", RetryMode.NONE);
        assertRegistered(codec.encodeKnowledge(KNOWLEDGE_EXECUTE,
                new KnowledgeOutcome.Failed(knowledgeFailed)),
                KNOWLEDGE_EXECUTE, true, FORBIDDEN, knowledgeFailed);
        var knowledgeUnknown = failure("knowledge_unknown", RetryMode.NONE);
        assertRegistered(codec.encodeKnowledge(KNOWLEDGE_EXECUTE,
                new KnowledgeOutcome.OutcomeUnknown(knowledgeUnknown)),
                KNOWLEDGE_EXECUTE, false, FORBIDDEN, knowledgeUnknown);
    }

    /** Retryable、本地 pre-dispatch、barrier 与无权威 revision 的失败不能抢占 winner。 */
    @Test
    void excludesEveryNonResolvingFailureClass() {
        assertIneligible(codec.encodeModel(MODEL_GENERATE,
                new ModelResult.Failed(failure(
                        "pre_dispatch_contract_rejected", RetryMode.NONE))),
                MODEL_GENERATE);
        assertIneligible(codec.encodeTool(TOOL_INVOKE,
                new ToolOutcome.Failed(failure(
                        "retryable", RetryMode.IDEMPOTENT_BY_STABLE_KEY))),
                TOOL_INVOKE);
        assertIneligible(codec.encodeTool(TOOL_INVOKE,
                new ToolOutcome.Failed(failure(
                        "pre_dispatch_contract_rejected", RetryMode.NONE))),
                TOOL_INVOKE);
        assertIneligible(codec.encodeKnowledge(KNOWLEDGE_EXECUTE,
                new KnowledgeOutcome.Failed(failure("retryable", RetryMode.COORDINATED))),
                KNOWLEDGE_EXECUTE);
        assertIneligible(codec.encodeKnowledge(KNOWLEDGE_EXECUTE,
                new KnowledgeOutcome.Failed(failure(
                        "pre_dispatch_contract_rejected", RetryMode.NONE))),
                KNOWLEDGE_EXECUTE);

        assertIneligible(codec.encodeWorkflow(WORKFLOW_DISPATCH,
                workflowFailed(OptionalLong.empty(), "provider_failed", RetryMode.NONE)),
                WORKFLOW_DISPATCH);
        assertIneligible(codec.encodeWorkflow(WORKFLOW_DISPATCH,
                workflowFailed(OptionalLong.of(3), "provider_failed", RetryMode.RESULT_QUERYABLE)),
                WORKFLOW_DISPATCH);
        assertIneligible(codec.encodeWorkflow(WORKFLOW_DISPATCH,
                workflowFailed(OptionalLong.of(3),
                        "pre_dispatch_contract_rejected", RetryMode.NONE)),
                WORKFLOW_DISPATCH);
        assertIneligible(codec.encodeWorkflow(WORKFLOW_DISPATCH,
                workflowFailed(OptionalLong.of(3),
                        "invocation_barrier_rejected", RetryMode.NONE)),
                WORKFLOW_DISPATCH);
    }

    /** 比较只使用 registry key 和三类已登记 Workflow 生命周期细化。 */
    @Test
    void comparesEquivalentCompatibleAndConflictingResolutions() {
        var firstTool = codec.encodeTool(TOOL_INVOKE,
                new ToolOutcome.Succeeded(TOOL, new ResultRef("first"), List.of()));
        var sameTool = codec.encodeTool(TOOL_INVOKE,
                new ToolOutcome.Succeeded(TOOL, new ResultRef("later"), List.of()));
        assertEquals(EQUIVALENT, codec.compareResolutions(
                firstTool, TOOL_INVOKE, sameTool, TOOL_INVOKE));

        var accepted = codec.encodeWorkflow(WORKFLOW_DISPATCH,
                new WorkflowResult.Accepted(EXECUTION, 1));
        var suspended = codec.encodeWorkflow(WORKFLOW_DISPATCH,
                new WorkflowResult.Suspended(
                        EXECUTION, "wait", 2, new ResultRef("suspended"),
                        com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL));
        assertEquals(COMPATIBLE, codec.compareResolutions(
                accepted, WORKFLOW_DISPATCH, suspended, WORKFLOW_DISPATCH));

        var recovered = codec.encodeWorkflowRecovery(WORKFLOW_RECOVER_RESULT,
                new WorkflowRecoveredResult.Completed(workflowCompleted(3, "completed")));
        assertEquals(COMPATIBLE, codec.compareResolutions(
                accepted, WORKFLOW_DISPATCH, recovered, WORKFLOW_RECOVER_RESULT));
        assertEquals(COMPATIBLE, codec.compareResolutions(
                recovered, WORKFLOW_RECOVER_RESULT, accepted, WORKFLOW_DISPATCH));

        var backwards = codec.encodeWorkflow(WORKFLOW_DISPATCH,
                new WorkflowResult.Accepted(EXECUTION, 1));
        assertEquals(CONFLICT, codec.compareResolutions(
                suspended, WORKFLOW_DISPATCH, backwards, WORKFLOW_DISPATCH));

        var recoveredFailure = codec.encodeWorkflowRecovery(WORKFLOW_RECOVER_RESULT,
                new WorkflowRecoveredResult.Failed(workflowFailed(
                        OptionalLong.of(3), "terminal_failed", RetryMode.NONE)));
        assertEquals(CONFLICT, codec.compareResolutions(
                recovered, WORKFLOW_RECOVER_RESULT,
                recoveredFailure, WORKFLOW_RECOVER_RESULT));

        var unknown = codec.encodeTool(TOOL_INVOKE,
                new ToolOutcome.OutcomeUnknown(failure("unknown", RetryMode.NONE)));
        assertThrows(IllegalArgumentException.class, () -> codec.compareResolutions(
                firstTool, TOOL_INVOKE, unknown, TOOL_INVOKE));
    }

    /** 原生完成键覆盖完整有序回合；审计结果引用不改变同一模型事实的等价性。 */
    @Test
    void nativeModelResolutionDistinguishesRawToolFactsAndContentOrder() {
        var firstCall = new AssistantContent.ToolCall("call-1", "read_file", "{\"path\":\"a.txt\"}");
        var secondCall = new AssistantContent.ToolCall("call-2", "read_file", "{\"path\":\"b.txt\"}");
        var text = new AssistantContent.Text("准备读取");
        var turn = new AssistantTurn(List.of(text, firstCall, secondCall));
        var output = new TypedPayload(ModelPort.TEXT_OUTPUT_SCHEMA,
                CanonicalJson.encode(CanonicalJson.string(turn.content())));
        var runtime = new CoreRuntime(LIMITS, (payload, limits) -> assertEquals(output, payload));
        var nativeCodec = runtime.portalResults((payload, limits) -> {}, (payload, limits) -> {}, (payload, limits) -> {});
        for (var kind : List.of(MODEL_GENERATE, MODEL_STREAM)) {
            var first = nativeCodec.encodeModel(kind, nativeCompleted(turn, "first"));
            var identical = nativeCodec.encodeModel(kind, nativeCompleted(turn, "later-result-ref"));
            assertTrue(nativeCodec.semantics(first, kind).resolutionKey().orElseThrow()
                    .startsWith("skillforge.runtime.action-resolution.model-completed.v2:"));
            assertEquals(EQUIVALENT, nativeCodec.compareResolutions(first, kind, identical, kind));
            for (var changed : List.of(
                    new AssistantTurn(List.of(text, new AssistantContent.ToolCall("different-id", "read_file", firstCall.argumentsJson()), secondCall)),
                    new AssistantTurn(List.of(text, new AssistantContent.ToolCall("call-1", "write_file", firstCall.argumentsJson()), secondCall)),
                    new AssistantTurn(List.of(text, new AssistantContent.ToolCall("call-1", "read_file", "{\"path\":\"changed.txt\"}"), secondCall)),
                    new AssistantTurn(List.of(text, new AssistantContent.ToolCall("call-1", "read_file", "{ \"path\":\"a.txt\" }"), secondCall)),
                    new AssistantTurn(List.of(text, secondCall, firstCall)),
                    new AssistantTurn(List.of(firstCall, text, secondCall)),
                    new AssistantTurn(List.of(text)))) {
                var candidate = nativeCodec.encodeModel(kind, nativeCompleted(changed, "candidate"));
                assertEquals(CONFLICT, nativeCodec.compareResolutions(first, kind, candidate, kind));
            }
        }
    }

    private static ModelResult.Completed nativeCompleted(AssistantTurn turn, String resultRef) {
        return new ModelResult.Completed(new TypedPayload(ModelPort.TEXT_OUTPUT_SCHEMA,
                CanonicalJson.encode(CanonicalJson.string(turn.content()))), "model-1",
                new ModelUsage(OptionalLong.of(3), OptionalLong.of(5)), "tool_calls",
                new ResultRef(resultRef), Optional.empty(), Optional.of(turn));
    }

    private void assertWorkflowInvokeFamily(PortalCallKind kind) {
        assertRegistered(codec.encodeWorkflow(kind,
                new WorkflowResult.Accepted(EXECUTION, 1)),
                kind, true, FORBIDDEN, null);
        assertRegistered(codec.encodeWorkflow(kind,
                new WorkflowResult.Suspended(
                        EXECUTION, "wait", 2, new ResultRef("suspended"),
                        com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL)),
                kind, true, FORBIDDEN, null);
        assertRegistered(codec.encodeWorkflow(kind, workflowCompleted(3, "completed")),
                kind, true, REQUIRED, null);
        var failed = workflowFailed(OptionalLong.of(3), "workflow_failed", RetryMode.NONE);
        assertRegistered(codec.encodeWorkflow(kind, failed),
                kind, true, FORBIDDEN, failed.failure());
        assertRegistered(codec.encodeWorkflow(kind, workflowTerminated(4, "terminated")),
                kind, true, FORBIDDEN, null);
        assertRegistered(codec.encodeWorkflow(kind, workflowCancelled(4, "cancelled")),
                kind, true, FORBIDDEN, null);
        var unknown = failure("workflow_unknown", RetryMode.RESULT_QUERYABLE);
        assertRegistered(codec.encodeWorkflow(kind,
                new WorkflowResult.OutcomeUnknown(EXECUTION, OptionalLong.empty(), unknown)),
                kind, false, FORBIDDEN, unknown);
    }

    private void assertIneligible(PortalOutcomeSnapshot snapshot, PortalCallKind kind) {
        assertFalse(codec.semantics(snapshot, kind).actionResolvingEligible());
    }

    private void assertRegistered(
            PortalOutcomeSnapshot snapshot,
            PortalCallKind kind,
            boolean eligible,
            CanonicalObservationRule observationRule,
            PortFailure failure) {
        var semantics = codec.semantics(snapshot, kind);
        assertEquals(eligible, semantics.actionResolvingEligible());
        assertEquals(observationRule, semantics.canonicalObservationRule());
        assertEquals(failure == null
                        ? Optional.empty()
                        : Optional.of(new FailureSemantics(
                                failure.reasonCode(), failure.retryMode())),
                semantics.failure());
        if (eligible) {
            String version = (kind == MODEL_GENERATE || kind == MODEL_STREAM)
                    && snapshot.outcomeType().equals("Completed") ? "2" : "1";
            assertTrue(semantics.resolutionKey().orElseThrow().matches(
                    "skillforge\\.runtime\\.action-resolution\\.[a-z-]+\\.v" + version + ":[0-9a-f]{64}"));
        } else {
            assertTrue(semantics.resolutionKey().isEmpty());
        }
    }

    private static ModelResult.Completed modelCompleted(String resultRef) {
        return new ModelResult.Completed(
                MODEL,
                "model-1",
                new ModelUsage(OptionalLong.of(3), OptionalLong.of(5)),
                "stop",
                new ResultRef(resultRef));
    }

    private static WorkflowResult.Completed workflowCompleted(long revision, String resultRef) {
        return new WorkflowResult.Completed(
                EXECUTION, WORKFLOW, revision, new ResultRef(resultRef));
    }

    private static WorkflowResult.Failed workflowFailed(
            OptionalLong revision, String reason, RetryMode mode) {
        return new WorkflowResult.Failed(EXECUTION, revision, failure(reason, mode));
    }

    private static WorkflowResult.Terminated workflowTerminated(
            long revision, String resultRef) {
        return new WorkflowResult.Terminated(
                EXECUTION,
                revision,
                LoopTerminationReason.WORKFLOW_MAX_STEPS_REACHED,
                new ResultRef(resultRef));
    }

    private static WorkflowResult.Cancelled workflowCancelled(
            long revision, String resultRef) {
        return new WorkflowResult.Cancelled(
                EXECUTION, revision, new ResultRef(resultRef));
    }

    private static WorkflowRecoveredResult.Absent absent() {
        return new WorkflowRecoveredResult.Absent(
                EXECUTION,
                new InvocationBarrierProof(
                        new ActionRef(TENANT, "action"),
                        "invocation",
                        "execute-attempt",
                        "blocked-fingerprint",
                        new ResultRef("reconciliation"),
                        new ResultRef("barrier")));
    }

    private static WorkflowSnapshot workflowSnapshot(
            WorkflowExecutionState state, long revision) {
        return new WorkflowSnapshot(
                EXECUTION,
                new FrozenWorkflowTarget(
                        new WorkflowHandle("workflow:mounted"),
                        "instance",
                        1,
                        new CapabilityRef(
                                CapabilityType.WORKFLOW, "workflow-capability", "v1"),
                        Duration.ofSeconds(5), 1L),
                state,
                revision,
                Optional.empty(),
                Instant.EPOCH);
    }

    private static KnowledgeOutcome.Retrieved knowledge(String resultRef) {
        var plan = new KnowledgePlan("plan", "request", List.of(), "enough", "v1");
        var item = new EvidenceItem(
                "item",
                "source",
                "v1",
                KnowledgeOperatorType.KEYWORD,
                EVIDENCE,
                List.of("provenance"),
                List.of(),
                0.5,
                "trusted");
        var bundle = new EvidenceBundle(
                "bundle",
                "request",
                plan,
                List.of(item),
                List.of(),
                EvidenceSufficiency.SUFFICIENT,
                List.of(),
                1,
                Instant.EPOCH, java.util.Optional.empty());
        return new KnowledgeOutcome.Retrieved(bundle, new ResultRef(resultRef));
    }

    private static PortFailure failure(String reason, RetryMode mode) {
        return new PortFailure(reason, mode, new ResultRef(reason + "-result"));
    }

    private static PortalOutcomeCodec codec() {
        var runtime = new CoreRuntime(
                LIMITS,
                (payload, limits) -> assertEquals(MODEL, payload));
        return runtime.portalResults(
                (payload, limits) -> assertEquals(TOOL, payload),
                (payload, limits) -> assertEquals(WORKFLOW, payload),
                (payload, limits) -> assertEquals(EVIDENCE, payload));
    }
}

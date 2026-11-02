package com.skillforge.runtime.core;

import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowControlResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowRecoveredResult;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec.CanonicalObservationRule;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec.FailureSemantics;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec.OutcomeSemantics;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec.ResolutionRelation;
import com.skillforge.runtime.core.CanonicalJson.Field;
import com.skillforge.runtime.core.CanonicalJson.Value;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 将严格解码后的 Portal 结果投影为 Action resolved-index 语义。
 *
 * <p>本 registry 不判断 attempt、permit、租约或事实 currentness；这些仍由 Ledger 状态机闭合。
 */
final class PortalOutcomeResolutionRegistry {
    private static final String PRE_DISPATCH_REJECTED =
            "pre_dispatch_contract_rejected";
    private static final String INVOCATION_BARRIER_REJECTED =
            "invocation_barrier_rejected";

    private static final String MODEL_COMPLETED =
            "skillforge.runtime.action-resolution.model-completed.v2";
    private static final String MODEL_FAILED =
            "skillforge.runtime.action-resolution.model-failed.v1";
    private static final String TOOL_SUCCEEDED =
            "skillforge.runtime.action-resolution.tool-succeeded.v1";
    private static final String TOOL_FAILED =
            "skillforge.runtime.action-resolution.tool-failed.v1";
    private static final String WORKFLOW_ACCEPTED =
            "skillforge.runtime.action-resolution.workflow-accepted.v1";
    private static final String WORKFLOW_SUSPENDED =
            "skillforge.runtime.action-resolution.workflow-suspended.v1";
    private static final String WORKFLOW_COMPLETED =
            "skillforge.runtime.action-resolution.workflow-completed.v1";
    private static final String WORKFLOW_FAILED =
            "skillforge.runtime.action-resolution.workflow-failed.v1";
    private static final String WORKFLOW_TERMINATED =
            "skillforge.runtime.action-resolution.workflow-terminated.v1";
    private static final String WORKFLOW_CANCELLED =
            "skillforge.runtime.action-resolution.workflow-cancelled.v1";
    private static final String WORKFLOW_CONTROL_CONFLICT =
            "skillforge.runtime.action-resolution.workflow-control-conflict.v1";
    private static final String KNOWLEDGE_RETRIEVED =
            "skillforge.runtime.action-resolution.knowledge-retrieved.v1";
    private static final String KNOWLEDGE_FAILED =
            "skillforge.runtime.action-resolution.knowledge-failed.v1";

    private final ModelOutcomeCodec models;
    private final ToolOutcomeCodec tools;
    private final WorkflowOutcomeCodec workflows;
    private final KnowledgeOutcomeCodec knowledge;

    PortalOutcomeResolutionRegistry(
            ModelOutcomeCodec models,
            ToolOutcomeCodec tools,
            WorkflowOutcomeCodec workflows,
            KnowledgeOutcomeCodec knowledge) {
        this.models = Objects.requireNonNull(models, "models");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    OutcomeSemantics semantics(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedCallKind) {
        return describe(snapshot, expectedCallKind).semantics();
    }

    ResolutionRelation compare(
            PortalOutcomeSnapshot firstCommitted,
            PortalCallKind expectedFirstCallKind,
            PortalOutcomeSnapshot laterCandidate,
            PortalCallKind expectedLaterCallKind) {
        Described first = describe(firstCommitted, expectedFirstCallKind);
        Described later = describe(laterCandidate, expectedLaterCallKind);
        String firstKey = first.semantics().resolutionKey().orElseThrow(
                PortalOutcomeResolutionRegistry::ineligibleComparison);
        String laterKey = later.semantics().resolutionKey().orElseThrow(
                PortalOutcomeResolutionRegistry::ineligibleComparison);
        if (firstKey.equals(laterKey)) {
            return ResolutionRelation.EQUIVALENT;
        }
        if (compatible(first.workflowPoint(), later.workflowPoint())) {
            return ResolutionRelation.COMPATIBLE;
        }
        return ResolutionRelation.CONFLICT;
    }

    private Described describe(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedCallKind) {
        Objects.requireNonNull(expectedCallKind, "expectedCallKind");
        return switch (expectedCallKind) {
            case MODEL_GENERATE, MODEL_STREAM ->
                    describeModel(models.decode(snapshot, expectedCallKind));
            case TOOL_INVOKE -> describeTool(tools.decode(snapshot, expectedCallKind));
            case WORKFLOW_DISPATCH, WORKFLOW_RESUME -> describeWorkflow(
                    workflows.decodeInvoke(snapshot, expectedCallKind), WorkflowSource.INVOKE);
            case WORKFLOW_RECOVER_RESULT -> describeWorkflowRecovery(
                    workflows.decodeRecover(snapshot, expectedCallKind));
            case WORKFLOW_CANCEL -> describeWorkflowControl(
                    workflows.decodeControl(snapshot, expectedCallKind));
            case KNOWLEDGE_EXECUTE -> describeKnowledge(
                    knowledge.decode(snapshot, expectedCallKind));
        };
    }

    private static Described describeModel(ModelResult result) {
        return switch (result) {
            case ModelResult.Completed value -> known(
                    MODEL_COMPLETED,
                    object(
                            field("output", payload(value.output())),
                            field("assistantTurn", AssistantTurnCodec.encode(value.assistantTurn())),
                            text("effectiveModelId", value.effectiveModelId()),
                            field("usage", usage(value.usage())),
                            text("finishReasonCode", value.finishReasonCode())),
                    Optional.empty(), CanonicalObservationRule.REQUIRED,
                    Optional.empty());
            case ModelResult.Failed value -> stableFailure(
                    MODEL_FAILED, value.failure(), false);
            case ModelResult.OutcomeUnknown value -> ineligible(value.failure());
        };
    }

    private static Described describeTool(ToolOutcome result) {
        return switch (result) {
            case ToolOutcome.Succeeded value -> known(
                    TOOL_SUCCEEDED,
                    object(
                            field("observation", payload(value.observation())),
                            field("usageRefs", refs(value.usageRefs()))),
                    Optional.empty(), CanonicalObservationRule.REQUIRED,
                    Optional.empty());
            case ToolOutcome.InputRequired ignored -> ineligible();
            case ToolOutcome.Failed value -> stableFailure(
                    TOOL_FAILED, value.failure(), false);
            case ToolOutcome.OutcomeUnknown value -> ineligible(value.failure());
        };
    }

    private static Described describeWorkflow(
            WorkflowResult result, WorkflowSource source) {
        return switch (result) {
            case WorkflowResult.Accepted value -> workflowKnown(
                    WORKFLOW_ACCEPTED,
                    object(
                            field("executionRef", execution(value.executionRef())),
                            integer("revision", value.revision())),
                    value.executionRef(), value.revision(), WorkflowStage.ACCEPTED,
                    source, CanonicalObservationRule.FORBIDDEN, Optional.empty());
            case WorkflowResult.Suspended value -> workflowKnown(
                    WORKFLOW_SUSPENDED,
                    object(
                            field("executionRef", execution(value.executionRef())),
                            text("suspendRef", value.suspendRef()),
                            text("reason", value.reason().name()),
                            integer("revision", value.revision())),
                    value.executionRef(), value.revision(), WorkflowStage.SUSPENDED,
                    source, CanonicalObservationRule.FORBIDDEN, Optional.empty());
            case WorkflowResult.Completed value -> workflowKnown(
                    WORKFLOW_COMPLETED,
                    object(
                            field("executionRef", execution(value.executionRef())),
                            field("output", payload(value.output())),
                            integer("revision", value.revision())),
                    value.executionRef(), value.revision(), WorkflowStage.COMPLETED,
                    source, CanonicalObservationRule.REQUIRED, Optional.empty());
            case WorkflowResult.Failed value -> describeWorkflowFailure(value, source);
            case WorkflowResult.Terminated value -> workflowKnown(
                    WORKFLOW_TERMINATED,
                    object(
                            field("executionRef", execution(value.executionRef())),
                            integer("revision", value.revision()),
                            text("reason", value.reason().name())),
                    value.executionRef(), value.revision(), WorkflowStage.TERMINATED,
                    source, CanonicalObservationRule.FORBIDDEN, Optional.empty());
            case WorkflowResult.Cancelled value -> workflowKnown(
                    WORKFLOW_CANCELLED,
                    object(
                            field("executionRef", execution(value.executionRef())),
                            integer("revision", value.revision())),
                    value.executionRef(), value.revision(), WorkflowStage.CANCELLED,
                    source, CanonicalObservationRule.FORBIDDEN, Optional.empty());
            case WorkflowResult.OutcomeUnknown value -> ineligible(value.failure());
        };
    }

    private static Described describeWorkflowFailure(
            WorkflowResult.Failed value, WorkflowSource source) {
        PortFailure failure = value.failure();
        boolean eligible = value.revision().isPresent()
                && failure.retryMode() == RetryMode.NONE
                && !PRE_DISPATCH_REJECTED.equals(failure.reasonCode())
                && !INVOCATION_BARRIER_REJECTED.equals(failure.reasonCode());
        if (!eligible) {
            return ineligible(failure);
        }
        long revision = value.revision().orElseThrow();
        return workflowKnown(
                WORKFLOW_FAILED,
                object(
                        field("executionRef", execution(value.executionRef())),
                        integer("revision", revision),
                        field("failure", failureReason(failure))),
                value.executionRef(), revision, WorkflowStage.FAILED, source,
                CanonicalObservationRule.FORBIDDEN,
                Optional.of(failure(failure)));
    }

    private static Described describeWorkflowRecovery(
            WorkflowRecoveredResult result) {
        return switch (result) {
            case WorkflowRecoveredResult.Suspended value -> describeWorkflow(value.result(), WorkflowSource.RECOVER);
            case WorkflowRecoveredResult.Completed value ->
                    describeWorkflow(value.result(), WorkflowSource.RECOVER);
            case WorkflowRecoveredResult.Failed value ->
                    describeWorkflow(value.result(), WorkflowSource.RECOVER);
            case WorkflowRecoveredResult.Terminated value ->
                    describeWorkflow(value.result(), WorkflowSource.RECOVER);
            case WorkflowRecoveredResult.Cancelled value ->
                    describeWorkflow(value.result(), WorkflowSource.RECOVER);
            case WorkflowRecoveredResult.Absent ignored -> ineligible();
            case WorkflowRecoveredResult.Rejected value -> ineligible(value.failure());
        };
    }

    private static Described describeWorkflowControl(
            WorkflowControlResult result) {
        return switch (result) {
            case WorkflowControlResult.Applied value ->
                    controlCancelled(value.execution());
            case WorkflowControlResult.Replayed value ->
                    controlCancelled(value.execution());
            case WorkflowControlResult.Conflict value -> known(
                    WORKFLOW_CONTROL_CONFLICT,
                    object(
                            field("current", object(
                                    field("executionRef", execution(
                                            value.current().executionRef())),
                                    text("state", value.current().state().name()),
                                    integer("revision", value.current().revision()))),
                            field("failure", failureReason(value.failure()))),
                    Optional.of(failure(value.failure())),
                    CanonicalObservationRule.FORBIDDEN, Optional.empty());
            case WorkflowControlResult.Rejected value -> ineligible(value.failure());
            case WorkflowControlResult.OutcomeUnknown value -> ineligible(value.failure());
        };
    }

    private static Described controlCancelled(
            com.skillforge.runtime.api.port.WorkflowPortal.WorkflowSnapshot value) {
        return workflowKnown(
                WORKFLOW_CANCELLED,
                object(
                        field("executionRef", execution(value.executionRef())),
                        integer("revision", value.revision())),
                value.executionRef(), value.revision(), WorkflowStage.CANCELLED,
                WorkflowSource.CONTROL, CanonicalObservationRule.FORBIDDEN,
                Optional.empty());
    }

    private static Described describeKnowledge(KnowledgeOutcome result) {
        return switch (result) {
            case KnowledgeOutcome.Retrieved value -> known(
                    KNOWLEDGE_RETRIEVED,
                    object(field("bundle",
                            KnowledgeOutcomeCodec.bundleValue(value.bundle()))),
                    Optional.empty(), CanonicalObservationRule.REQUIRED,
                    Optional.empty());
            case KnowledgeOutcome.Failed value -> stableFailure(
                    KNOWLEDGE_FAILED, value.failure(), false);
            case KnowledgeOutcome.OutcomeUnknown value -> ineligible(value.failure());
        };
    }

    private static Described stableFailure(
            String schema, PortFailure failure, boolean rejectBarrier) {
        boolean eligible = failure.retryMode() == RetryMode.NONE
                && !PRE_DISPATCH_REJECTED.equals(failure.reasonCode())
                && (!rejectBarrier
                || !INVOCATION_BARRIER_REJECTED.equals(failure.reasonCode()));
        if (!eligible) {
            return ineligible(failure);
        }
        return known(
                schema, object(field("failure", failureReason(failure))),
                Optional.of(failure(failure)), CanonicalObservationRule.FORBIDDEN,
                Optional.empty());
    }

    private static Described workflowKnown(
            String schema,
            Value value,
            ExecutionRef executionRef,
            long revision,
            WorkflowStage stage,
            WorkflowSource source,
            CanonicalObservationRule observationRule,
            Optional<FailureSemantics> failure) {
        return known(schema, value, failure, observationRule,
                Optional.of(new WorkflowPoint(executionRef, revision, stage, source)));
    }

    private static Described known(
            String schema,
            Value value,
            Optional<FailureSemantics> failure,
            CanonicalObservationRule observationRule,
            Optional<WorkflowPoint> point) {
        String key = schema + ":" + CanonicalJson.contentHash(value);
        return new Described(
                new OutcomeSemantics(Optional.of(key), failure, observationRule),
                point);
    }

    private static Described ineligible(PortFailure value) {
        return new Described(
                new OutcomeSemantics(Optional.empty(), Optional.of(failure(value)),
                        CanonicalObservationRule.FORBIDDEN),
                Optional.empty());
    }

    private static Described ineligible() {
        return new Described(
                new OutcomeSemantics(Optional.empty(), Optional.empty(),
                        CanonicalObservationRule.FORBIDDEN),
                Optional.empty());
    }

    private static boolean compatible(
            Optional<WorkflowPoint> firstValue,
            Optional<WorkflowPoint> laterValue) {
        if (firstValue.isEmpty() || laterValue.isEmpty()) {
            return false;
        }
        WorkflowPoint first = firstValue.orElseThrow();
        WorkflowPoint later = laterValue.orElseThrow();
        if (!first.executionRef().equals(later.executionRef())) {
            return false;
        }
        if (acceptedSuspended(first, later)) {
            return later.revision() >= first.revision();
        }
        if (nonterminalTerminal(first, later)) {
            WorkflowPoint terminal = terminal(first) ? first : later;
            WorkflowPoint nonterminal = terminal(first) ? later : first;
            return terminal.revision() >= nonterminal.revision();
        }
        return first.stage() == WorkflowStage.CANCELLED
                && later.stage() == WorkflowStage.CANCELLED
                && first.revision() == later.revision()
                && ((first.source() == WorkflowSource.CONTROL
                        && later.source() == WorkflowSource.RECOVER)
                || (first.source() == WorkflowSource.RECOVER
                        && later.source() == WorkflowSource.CONTROL));
    }

    private static boolean acceptedSuspended(
            WorkflowPoint first, WorkflowPoint later) {
        return first.source() == WorkflowSource.INVOKE
                && later.source() == WorkflowSource.INVOKE
                && ((first.stage() == WorkflowStage.ACCEPTED
                        && later.stage() == WorkflowStage.SUSPENDED)
                || (first.stage() == WorkflowStage.SUSPENDED
                        && later.stage() == WorkflowStage.ACCEPTED));
    }

    private static boolean nonterminalTerminal(
            WorkflowPoint first, WorkflowPoint later) {
        return (nonterminal(first) && terminal(later)
                && later.source() != WorkflowSource.CONTROL)
                || (terminal(first) && first.source() != WorkflowSource.CONTROL
                && nonterminal(later));
    }

    private static boolean nonterminal(WorkflowPoint value) {
        return value.stage() == WorkflowStage.ACCEPTED
                || value.stage() == WorkflowStage.SUSPENDED;
    }

    private static boolean terminal(WorkflowPoint value) {
        return value.stage() == WorkflowStage.COMPLETED
                || value.stage() == WorkflowStage.FAILED
                || value.stage() == WorkflowStage.TERMINATED
                || value.stage() == WorkflowStage.CANCELLED;
    }

    private static FailureSemantics failure(PortFailure value) {
        return new FailureSemantics(value.reasonCode(), value.retryMode());
    }

    private static Value failureReason(PortFailure value) {
        return object(text("reasonCode", value.reasonCode()));
    }

    private static Value payload(TypedPayload value) {
        return object(
                text("schemaId", value.schemaId()),
                text("canonicalJson", value.canonicalJson()));
    }

    private static Value execution(ExecutionRef value) {
        return object(
                text("tenantId", value.tenantId().value()),
                text("executionId", value.executionId()));
    }

    private static Value usage(ModelUsage value) {
        return ModelUsageCodec.encode(value);
    }

    private static Value optionalLong(OptionalLong value) {
        return value.isPresent()
                ? CanonicalJson.integer(value.getAsLong()) : CanonicalJson.nil();
    }

    private static Value refs(List<ResultRef> values) {
        return CanonicalJson.array(values.stream()
                .map(value -> CanonicalJson.string(value.value())).toList());
    }

    private static Value object(Field... fields) {
        return CanonicalJson.object(List.of(fields));
    }

    private static Field field(String name, Value value) {
        return new Field(name, value);
    }

    private static Field text(String name, String value) {
        return field(name, CanonicalJson.string(value));
    }

    private static Field integer(String name, long value) {
        return field(name, CanonicalJson.integer(value));
    }

    private static IllegalArgumentException ineligibleComparison() {
        return new IllegalArgumentException(
                "resolution comparison requires eligible outcomes");
    }

    private record Described(
            OutcomeSemantics semantics,
            Optional<WorkflowPoint> workflowPoint) {
    }

    private record WorkflowPoint(
            ExecutionRef executionRef,
            long revision,
            WorkflowStage stage,
            WorkflowSource source) {
    }

    private enum WorkflowStage {
        ACCEPTED,
        SUSPENDED,
        COMPLETED,
        FAILED,
        TERMINATED,
        CANCELLED
    }

    private enum WorkflowSource {
        INVOKE,
        RECOVER,
        CONTROL
    }
}

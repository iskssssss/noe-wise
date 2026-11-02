package com.skillforge.runtime.api.port;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提供 §8.2 全部 Provider outcome tuple 的 API 封套契约 fixture。
 *
 * <p>本类只登记 call kind、resolution、outcome type、schema identity 与公开 result class；
 * outcome body 的编码、解码和 canonical 结构由 Core registry 独占验证。
 */
final class ProviderOutcomeCodecFixtures {

    private static final List<Fixture> FIXTURES = List.of(
            fixture(PortalCallKind.MODEL_GENERATE, PortalResolution.KNOWN_RESULT,
                    "Completed", ModelPort.ModelResult.Completed.class),
            fixture(PortalCallKind.MODEL_GENERATE, PortalResolution.KNOWN_FAILURE,
                    "Failed", ModelPort.ModelResult.Failed.class),
            fixture(PortalCallKind.MODEL_GENERATE, PortalResolution.OUTCOME_UNKNOWN,
                    "OutcomeUnknown", ModelPort.ModelResult.OutcomeUnknown.class),
            fixture(PortalCallKind.MODEL_STREAM, PortalResolution.KNOWN_RESULT,
                    "Completed", ModelPort.ModelResult.Completed.class),
            fixture(PortalCallKind.MODEL_STREAM, PortalResolution.KNOWN_FAILURE,
                    "Failed", ModelPort.ModelResult.Failed.class),
            fixture(PortalCallKind.MODEL_STREAM, PortalResolution.OUTCOME_UNKNOWN,
                    "OutcomeUnknown", ModelPort.ModelResult.OutcomeUnknown.class),
            fixture(PortalCallKind.TOOL_INVOKE, PortalResolution.KNOWN_RESULT,
                    "Succeeded", ToolPortal.ToolOutcome.Succeeded.class),
            fixture(PortalCallKind.TOOL_INVOKE, PortalResolution.INPUT_REQUIRED,
                    "InputRequired", ToolPortal.ToolOutcome.InputRequired.class),
            fixture(PortalCallKind.TOOL_INVOKE, PortalResolution.KNOWN_FAILURE,
                    "Failed", ToolPortal.ToolOutcome.Failed.class),
            fixture(PortalCallKind.TOOL_INVOKE, PortalResolution.OUTCOME_UNKNOWN,
                    "OutcomeUnknown", ToolPortal.ToolOutcome.OutcomeUnknown.class),
            fixture(PortalCallKind.WORKFLOW_DISPATCH, PortalResolution.KNOWN_RESULT,
                    "Accepted", WorkflowPortal.WorkflowResult.Accepted.class),
            fixture(PortalCallKind.WORKFLOW_DISPATCH, PortalResolution.KNOWN_RESULT,
                    "Suspended", WorkflowPortal.WorkflowResult.Suspended.class),
            fixture(PortalCallKind.WORKFLOW_DISPATCH, PortalResolution.KNOWN_RESULT,
                    "Completed", WorkflowPortal.WorkflowResult.Completed.class),
            fixture(PortalCallKind.WORKFLOW_DISPATCH, PortalResolution.KNOWN_RESULT,
                    "Terminated", WorkflowPortal.WorkflowResult.Terminated.class),
            fixture(PortalCallKind.WORKFLOW_DISPATCH, PortalResolution.KNOWN_RESULT,
                    "Cancelled", WorkflowPortal.WorkflowResult.Cancelled.class),
            fixture(PortalCallKind.WORKFLOW_DISPATCH, PortalResolution.KNOWN_FAILURE,
                    "Failed", WorkflowPortal.WorkflowResult.Failed.class),
            fixture(PortalCallKind.WORKFLOW_DISPATCH, PortalResolution.OUTCOME_UNKNOWN,
                    "OutcomeUnknown", WorkflowPortal.WorkflowResult.OutcomeUnknown.class),
            fixture(PortalCallKind.WORKFLOW_RESUME, PortalResolution.KNOWN_RESULT,
                    "Accepted", WorkflowPortal.WorkflowResult.Accepted.class),
            fixture(PortalCallKind.WORKFLOW_RESUME, PortalResolution.KNOWN_RESULT,
                    "Suspended", WorkflowPortal.WorkflowResult.Suspended.class),
            fixture(PortalCallKind.WORKFLOW_RESUME, PortalResolution.KNOWN_RESULT,
                    "Completed", WorkflowPortal.WorkflowResult.Completed.class),
            fixture(PortalCallKind.WORKFLOW_RESUME, PortalResolution.KNOWN_RESULT,
                    "Terminated", WorkflowPortal.WorkflowResult.Terminated.class),
            fixture(PortalCallKind.WORKFLOW_RESUME, PortalResolution.KNOWN_RESULT,
                    "Cancelled", WorkflowPortal.WorkflowResult.Cancelled.class),
            fixture(PortalCallKind.WORKFLOW_RESUME, PortalResolution.KNOWN_FAILURE,
                    "Failed", WorkflowPortal.WorkflowResult.Failed.class),
            fixture(PortalCallKind.WORKFLOW_RESUME, PortalResolution.OUTCOME_UNKNOWN,
                    "OutcomeUnknown", WorkflowPortal.WorkflowResult.OutcomeUnknown.class),
            fixture(PortalCallKind.WORKFLOW_RECOVER_RESULT, PortalResolution.KNOWN_RESULT,
                    "Suspended", WorkflowPortal.WorkflowRecoveredResult.Suspended.class),
            fixture(PortalCallKind.WORKFLOW_RECOVER_RESULT, PortalResolution.KNOWN_RESULT,
                    "Completed", WorkflowPortal.WorkflowRecoveredResult.Completed.class),
            fixture(PortalCallKind.WORKFLOW_RECOVER_RESULT, PortalResolution.KNOWN_RESULT,
                    "Terminated", WorkflowPortal.WorkflowRecoveredResult.Terminated.class),
            fixture(PortalCallKind.WORKFLOW_RECOVER_RESULT, PortalResolution.KNOWN_RESULT,
                    "Cancelled", WorkflowPortal.WorkflowRecoveredResult.Cancelled.class),
            fixture(PortalCallKind.WORKFLOW_RECOVER_RESULT, PortalResolution.KNOWN_RESULT,
                    "Absent", WorkflowPortal.WorkflowRecoveredResult.Absent.class),
            fixture(PortalCallKind.WORKFLOW_RECOVER_RESULT, PortalResolution.KNOWN_FAILURE,
                    "Failed", WorkflowPortal.WorkflowRecoveredResult.Failed.class),
            fixture(PortalCallKind.WORKFLOW_RECOVER_RESULT, PortalResolution.KNOWN_FAILURE,
                    "Rejected", WorkflowPortal.WorkflowRecoveredResult.Rejected.class),
            fixture(PortalCallKind.WORKFLOW_CANCEL, PortalResolution.KNOWN_RESULT,
                    "Applied", WorkflowPortal.WorkflowControlResult.Applied.class),
            fixture(PortalCallKind.WORKFLOW_CANCEL, PortalResolution.KNOWN_RESULT,
                    "Replayed", WorkflowPortal.WorkflowControlResult.Replayed.class),
            fixture(PortalCallKind.WORKFLOW_CANCEL, PortalResolution.KNOWN_FAILURE,
                    "Conflict", WorkflowPortal.WorkflowControlResult.Conflict.class),
            fixture(PortalCallKind.WORKFLOW_CANCEL, PortalResolution.KNOWN_FAILURE,
                    "Rejected", WorkflowPortal.WorkflowControlResult.Rejected.class),
            fixture(PortalCallKind.WORKFLOW_CANCEL, PortalResolution.OUTCOME_UNKNOWN,
                    "OutcomeUnknown", WorkflowPortal.WorkflowControlResult.OutcomeUnknown.class),
            fixture(PortalCallKind.KNOWLEDGE_EXECUTE, PortalResolution.KNOWN_RESULT,
                    "Retrieved", KnowledgePortal.KnowledgeOutcome.Retrieved.class),
            fixture(PortalCallKind.KNOWLEDGE_EXECUTE, PortalResolution.KNOWN_FAILURE,
                    "Failed", KnowledgePortal.KnowledgeOutcome.Failed.class),
            fixture(PortalCallKind.KNOWLEDGE_EXECUTE, PortalResolution.OUTCOME_UNKNOWN,
                    "OutcomeUnknown", KnowledgePortal.KnowledgeOutcome.OutcomeUnknown.class));

    private static final Map<TupleKey, Fixture> FIXTURES_BY_KEY = FIXTURES.stream()
            .collect(Collectors.toUnmodifiableMap(Fixture::tupleKey, fixture -> fixture));

    /** 测试 fixture 容器不允许实例化。 */
    private ProviderOutcomeCodecFixtures() {
    }

    /** 返回按事实源表顺序冻结的全部 39 个封套 fixture。 */
    static List<Fixture> fixtures() {
        return FIXTURES;
    }

    /** 按完整 tuple key 读取唯一 fixture；缺失 tuple 立即失败，避免测试静默跳过。 */
    static Fixture require(
            PortalCallKind callKind,
            PortalResolution resolution,
            String outcomeType) {
        TupleKey key = new TupleKey(callKind, resolution, outcomeType);
        Fixture fixture = FIXTURES_BY_KEY.get(key);
        if (fixture == null) {
            throw new IllegalArgumentException("provider outcome tuple is not registered: " + key);
        }
        return fixture;
    }

    /** 返回全部 tuple key，供 registry 完整性测试做精确集合比较。 */
    static Set<TupleKey> tupleKeys() {
        return FIXTURES_BY_KEY.keySet();
    }

    /** 构造单个封套 fixture，并通过 record 构造器执行最小完整性检查。 */
    private static Fixture fixture(
            PortalCallKind callKind,
            PortalResolution resolution,
            String outcomeType,
            Class<? extends Record> resultClass) {
        return new Fixture(
                new TupleKey(callKind, resolution, outcomeType),
                schemaId(callKind),
                resultClass);
    }

    /** 返回 §8.2 为 call kind 登记的精确私有 outcome schema identity。 */
    private static String schemaId(PortalCallKind callKind) {
        return switch (callKind) {
            case MODEL_GENERATE, MODEL_STREAM -> "skillforge.runtime.portal.model-outcome.v5";
            case TOOL_INVOKE -> "skillforge.runtime.portal.tool-outcome.v2";
            case WORKFLOW_DISPATCH, WORKFLOW_RESUME ->
                    "skillforge.runtime.portal.workflow-invoke-outcome.v2";
            case WORKFLOW_RECOVER_RESULT ->
                    "skillforge.runtime.portal.workflow-recover-outcome.v2";
            case WORKFLOW_CANCEL -> "skillforge.runtime.portal.workflow-control-outcome.v1";
            case KNOWLEDGE_EXECUTE -> "skillforge.runtime.portal.knowledge-outcome.v2";
        };
    }

    /** 唯一标识一个已登记的 call kind、确定性与公开 result subtype。 */
    record TupleKey(
            PortalCallKind callKind,
            PortalResolution resolution,
            String outcomeType) {

        /** 拒绝缺失 discriminator 或空白 subtype。 */
        TupleKey {
            callKind = Objects.requireNonNull(callKind, "callKind");
            resolution = Objects.requireNonNull(resolution, "resolution");
            if (outcomeType == null || outcomeType.isBlank()) {
                throw new IllegalArgumentException("outcomeType must not be blank");
            }
        }
    }

    /** 保存一个 tuple 的 schema identity 与对应公开 result class，不保存 outcome body。 */
    record Fixture(
            TupleKey tupleKey,
            String schemaId,
            Class<? extends Record> resultClass) {

        /** 拒绝缺失 tuple、schema 或 result class，保证封套登记项可完整验证。 */
        Fixture {
            tupleKey = Objects.requireNonNull(tupleKey, "tupleKey");
            if (schemaId == null || schemaId.isBlank()) {
                throw new IllegalArgumentException("schemaId must not be blank");
            }
            resultClass = Objects.requireNonNull(resultClass, "resultClass");
        }
    }
}

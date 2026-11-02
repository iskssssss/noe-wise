package com.skillforge.runtime.codec.spi;

import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowControlResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowRecoveredResult;
import java.util.Objects;
import java.util.Optional;

/**
 * 供生产适配器复用 Core 私有 Portal outcome 编码的强类型边界。
 *
 * <p>实现必须同时验证调用种类、schema、结果分支与规范载荷，并保证解码后重新编码得到
 * 完全相同的 {@link PortalOutcomeSnapshot}。该接口不证明 Provider 来源、调用许可或账本提交。
 */
public interface PortalOutcomeCodec {

    /** 严格解码后返回 resolved-index、失败与 canonicalObservation 的注册语义。 */
    OutcomeSemantics semantics(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedCallKind);

    /** 按 first-winner 与 later-candidate 的固定顺序判断等价、登记兼容或冲突。 */
    ResolutionRelation compareResolutions(
            PortalOutcomeSnapshot firstCommitted,
            PortalCallKind expectedFirstCallKind,
            PortalOutcomeSnapshot laterCandidate,
            PortalCallKind expectedLaterCallKind);

    /** 编码模型生成或流式调用的聚合终态。 */
    PortalOutcomeSnapshot encodeModel(PortalCallKind callKind, ModelResult result);

    /** 按调用方预期的模型调用种类解码聚合终态。 */
    ModelResult decodeModel(PortalOutcomeSnapshot snapshot, PortalCallKind expectedCallKind);

    /** 编码 Tool 调用结果。 */
    PortalOutcomeSnapshot encodeTool(PortalCallKind callKind, ToolOutcome result);

    /** 按 TOOL_INVOKE 解码 Tool 调用结果。 */
    ToolOutcome decodeTool(PortalOutcomeSnapshot snapshot, PortalCallKind expectedCallKind);

    /** 编码 Workflow dispatch 或 resume 结果。 */
    PortalOutcomeSnapshot encodeWorkflow(
            PortalCallKind callKind, WorkflowResult result);

    /** 按 dispatch 或 resume 的原调用种类解码 Workflow 结果。 */
    WorkflowResult decodeWorkflow(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedCallKind);

    /** 编码 Workflow 权威恢复查询结果。 */
    PortalOutcomeSnapshot encodeWorkflowRecovery(
            PortalCallKind callKind, WorkflowRecoveredResult result);

    /** 按 WORKFLOW_RECOVER_RESULT 解码权威恢复查询结果。 */
    WorkflowRecoveredResult decodeWorkflowRecovery(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedCallKind);

    /** 编码 Workflow 取消控制结果。 */
    PortalOutcomeSnapshot encodeWorkflowControl(
            PortalCallKind callKind, WorkflowControlResult result);

    /** 按 WORKFLOW_CANCEL 解码取消控制结果。 */
    WorkflowControlResult decodeWorkflowControl(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedCallKind);

    /** 编码 Knowledge 检索结果。 */
    PortalOutcomeSnapshot encodeKnowledge(
            PortalCallKind callKind, KnowledgeOutcome result);

    /** 按 KNOWLEDGE_EXECUTE 解码 Knowledge 检索结果。 */
    KnowledgeOutcome decodeKnowledge(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedCallKind);

    /** complete 命令是否必须携带已经由 Core/Session 路径形成的安全投影。 */
    enum CanonicalObservationRule {
        REQUIRED,
        FORBIDDEN
    }

    /** 两个 eligible outcome 相对 first-winner 的唯一登记关系。 */
    enum ResolutionRelation {
        EQUIVALENT,
        COMPATIBLE,
        CONFLICT
    }

    /** 供 retry/terminal 判定使用的最窄失败字段，不暴露业务 ResultRef。 */
    record FailureSemantics(String reasonCode, RetryMode retryMode) {
        public FailureSemantics {
            Objects.requireNonNull(reasonCode, "reasonCode");
            if (reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode must be nonblank");
            }
            retryMode = Objects.requireNonNull(retryMode, "retryMode");
        }
    }

    /** key 存在性同时就是 registry eligibility，避免独立 boolean 漂移。 */
    record OutcomeSemantics(
            Optional<String> resolutionKey,
            Optional<FailureSemantics> failure,
            CanonicalObservationRule canonicalObservationRule) {

        public OutcomeSemantics {
            resolutionKey = Objects.requireNonNull(resolutionKey, "resolutionKey");
            failure = Objects.requireNonNull(failure, "failure");
            canonicalObservationRule = Objects.requireNonNull(
                    canonicalObservationRule, "canonicalObservationRule");
            resolutionKey.ifPresent(value -> {
                if (value.isBlank()) {
                    throw new IllegalArgumentException("resolutionKey must be nonblank");
                }
            });
        }

        /** 只有 registry 返回 key 的 subtype 才能竞争 Action first-winner。 */
        public boolean actionResolvingEligible() {
            return resolutionKey.isPresent();
        }
    }
}

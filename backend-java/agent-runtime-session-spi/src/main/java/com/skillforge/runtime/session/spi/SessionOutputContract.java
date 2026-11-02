package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.port.RunIdentity;
import java.util.Objects;
import java.util.Optional;

/**
 * 宿主输出完成协议。Runtime 只消费动作准入、模型提示和可信结果引用，不拥有宿主成果领域。
 */
public interface SessionOutputContract {
    String TASK_RESULT_PRESENTATION_KIND = "task_result";
    String TASK_RESULT_PAYLOAD_SCHEMA = "skillforge.task-result.ref.v1";
    String TASK_RESULT_REFERENCE_FIELD = "result_ref";

    /** 返回当前动作的稳定拒绝及模型可见反馈；空值表示允许继续。 */
    Optional<ActionRejection> actionRejection(RunIdentity run, String action);

    /** 公开文字采用前的宿主校验；拒绝码使 Run 终结，不进入模型修正循环。 */
    Optional<String> publicContentRejection(RunIdentity run, java.util.List<String> texts);

    /** 累计原文的公开安全前缀；未显式提供流式检查的宿主默认保留全文到完成校验。 */
    default StreamingContent publicStreamingContent(RunIdentity run, String accumulated) {
        Objects.requireNonNull(accumulated, "accumulated");
        return new StreamingContent(0, publicContentRejection(run, java.util.List.of(accumulated)));
    }

    /** 前缀长度采用原文 UTF-16 下标，拒绝时不得追加公开正文。 */
    record StreamingContent(int safePrefixCharacters, Optional<String> rejection) {
        public StreamingContent {
            if (safePrefixCharacters < 0) throw new IllegalArgumentException("streaming_content_prefix_invalid");
            Objects.requireNonNull(rejection, "rejection");
        }
    }

    /** 能力待确认卡或显式公开结果采用前检查，宿主解释其所属协议。 */
    Optional<String> capabilityContentRejection(RunIdentity run, java.util.List<com.skillforge.runtime.api.TypedPayload> payloads);

    /** Workflow 挂起采用前检查由所属 Provider 提供的公开确认卡。 */
    Optional<String> workflowContentRejection(RunIdentity run, com.skillforge.runtime.api.port.VerifiedIdentity actor, com.skillforge.runtime.api.ExecutionRef execution);

    /** 已提交能力结果中的终结型输出拒绝；必须在下一次模型或动作之前收敛。 */
    Optional<String> terminalRejection(RunIdentity run);

    /** 返回本轮准备需要冻结的输出规则与当前 Run 事实；两者均来自可信宿主。 */
    Optional<PreparationHint> preparationHint(RunIdentity run);

    /**
     * 执行与最终答复规则由宿主分别提供；Runtime 依据冻结的最终答复准入选择其中一份。
     * 当前 Run 事实单独后置，不降低可信角色；两份规则均不得混入当前输入、时间或 Run 标识。
     * @param stableInstructions 执行阶段的成果操作协议
     * @param finalAnswerInstructions 最终答复阶段的正文与宿主展示分工，不包含已关闭的操作指导
     * @param runContext 当前 Run 的真实状态与引用
     */
    record PreparationHint(String stableInstructions, String finalAnswerInstructions, String runContext) {
        public PreparationHint {
            stableInstructions = text(stableInstructions);
            finalAnswerInstructions = text(finalAnswerInstructions);
            runContext = text(runContext);
        }
        @Override public String toString() { return "OutputPreparationHint[REDACTED]"; }
    }

    /** 返回已冻结、可随最终响应公开的可信结果引用。 */
    Optional<String> preparedResult(RunIdentity run);

    /** 由宿主真实成果与已挂载资源的收尾义务决定规模，Runtime 不解释宿主业务字段。 */
    default ClosingPlan closingPlan(RunIdentity run) { return ClosingPlan.TEXT; }

    /** 依据已冻结定义批准收尾工具，不能依据模型文字或调用参数。 */
    default boolean closingToolAllowed(RunIdentity run, com.skillforge.runtime.api.port.ToolPortal.ToolBinding binding) { return false; }

    /**
     * 宿主声明的有限收尾资源，包含停止已有资源和成果冻结，不授权新的业务操作。
     * @param modelCalls 收尾模型轮次，范围 1..3
     * @param finalizationCalls 收尾工具调用次数，范围 0..2
     * @param localProcessing 全部非模型收尾处理时间，包含工具冻结超时
     * @param receiptInputUpperBound 后续各模型轮次累计读取新回执的输入 Token 上界
     * @param facts 宿主核证的收尾事实
     */
    record ClosingPlan(int modelCalls, int finalizationCalls, java.time.Duration localProcessing, long receiptInputUpperBound,
                       java.util.List<String> facts) {
        public static final ClosingPlan TEXT = new ClosingPlan(1, 0, java.time.Duration.ofSeconds(1), 0, java.util.List.of());
        public ClosingPlan {
            Objects.requireNonNull(localProcessing); facts = java.util.List.copyOf(facts);
            if (modelCalls < 1 || modelCalls > 3 || finalizationCalls < 0 || finalizationCalls > 2
                    || localProcessing.isNegative() || receiptInputUpperBound < 0) throw new IllegalArgumentException("closing_plan_invalid");
        }
    }

    /** Run 真实终态落库后的幂等宿主发布回调。 */
    void reconcile(RunIdentity run);

    record ActionRejection(String code, String feedback) {
        public ActionRejection {
            code = text(code);
            feedback = text(feedback);
        }
    }

    static SessionOutputContract disabled() {
        return Disabled.INSTANCE;
    }

    final class Disabled implements SessionOutputContract {
        private static final Disabled INSTANCE = new Disabled();

        private Disabled() { }

        @Override public Optional<ActionRejection> actionRejection(RunIdentity run, String action) {
            Objects.requireNonNull(run); text(action); return Optional.empty();
        }

        @Override public Optional<String> publicContentRejection(RunIdentity run, java.util.List<String> texts) {
            Objects.requireNonNull(run); Objects.requireNonNull(texts); return Optional.empty();
        }

        @Override public Optional<String> capabilityContentRejection(RunIdentity run, java.util.List<com.skillforge.runtime.api.TypedPayload> payloads) {
            Objects.requireNonNull(run); Objects.requireNonNull(payloads); return Optional.empty();
        }

        @Override public Optional<String> workflowContentRejection(RunIdentity run, com.skillforge.runtime.api.port.VerifiedIdentity actor, com.skillforge.runtime.api.ExecutionRef execution) {
            Objects.requireNonNull(run); Objects.requireNonNull(execution); return Optional.empty();
        }

        @Override public Optional<String> terminalRejection(RunIdentity run) {
            Objects.requireNonNull(run); return Optional.empty();
        }

        @Override public Optional<PreparationHint> preparationHint(RunIdentity run) {
            Objects.requireNonNull(run); return Optional.empty();
        }

        @Override public Optional<String> preparedResult(RunIdentity run) {
            Objects.requireNonNull(run); return Optional.empty();
        }

        @Override public void reconcile(RunIdentity run) {
            Objects.requireNonNull(run);
        }
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("session_output_contract_text_invalid");
        return value;
    }
}

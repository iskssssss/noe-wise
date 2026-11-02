package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.ToolCallLimits;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ActionRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 当前执行链的纯决策入口，只解析模型决策并计算下一动作。
 * Session 持有运行状态和已加载材料，负责登记 Action、派发 Attempt、提交结果与推进版本；
 * 本类不调用能力端口，不生成持久身份，也不要求内部阶段 journal。
 */
public final class AgentExecutionLoop {
    private final AgentDecisionDecoder decoder;
    private final AgentDecisionRouter router;

    /** 创建有明确解析配额的无状态计算器；同一实例可以服务不同 Run。 */
    public AgentExecutionLoop(CodecLimits limits) {
        Objects.requireNonNull(limits, "limits");
        decoder = new AgentDecisionDecoder(limits);
        router = new AgentDecisionRouter(limits);
    }

    /**
     * 解析已经注册保存的精确决策快照；不用于接收生产模型适配器的模型JSON输出文档。
     * 本方法不累计用量，真实结果的去重、保存和用量采纳属于结果提交事务。
     */
    public AgentDecisionDecoder.Result decode(TypedPayload modelDecision) {
        return decoder.decodeSnapshot(modelDecision);
    }

    /**
     * 解析生产适配器归一化后的原生模型决策文档，schema必须为ModelPort的JSON输出契约。
     * 文档按标准JSON规则有界解析；其他schema明确拒绝，包括只适用于工具参数的application/json。
     * 不在快照解析失败后回退到文档解析。
     */
    public AgentDecisionDecoder.Result decodeModel(TypedPayload modelDocument) {
        if (modelDocument == null || !ModelPort.JSON_OUTPUT_SCHEMA.equals(modelDocument.schemaId()))
            return new AgentDecisionDecoder.Failure(AgentDecisionDecoder.Reason.UNSUPPORTED_SCHEMA);
        return decoder.decodeWire(modelDocument.canonicalJson());
    }

    /** 新执行链只消费已加载事实，不为纯路由构造旧 FrozenContextSnapshot 或来源事件。 */
    public LoadedTransition next(LoadedInput input) {
        Objects.requireNonNull(input);
        if (input.usage().businessModelCalls() <= 0) return new LoadedRejected(LoadedRejection.MODEL_RESULT_REQUIRED);
        if (input.decision() instanceof AgentDecisionDecoder.Failure failure)
            return new LoadedDecodeFeedback(failure.reason());
        if (input.decision() instanceof AgentDecisionDecoder.DecodedBatch) return new LoadedBatchDeferred();
        var action = ((AgentDecisionDecoder.Decoded) input.decision()).action();
        // 已付费产生的本地收口值保留，其他动作先遵守共同资源上限。
        if (!(action instanceof AgentAction.Respond || action instanceof AgentAction.AskUser
                || action instanceof AgentAction.ActivateSkill || action instanceof AgentAction.ReadSkillResource)) {
            var common = BudgetGuard.evaluate(input.budget(), input.usage(), BudgetGuard.Operation.CONTINUATION, input.elapsed());
            if (common.isPresent()) return new LoadedBudgetDenied(common.orElseThrow());
        }
        if (!(action instanceof AgentAction.ToolCall call)) return new LoadedSelected(action, Optional.empty());
        var selected = resolveTool(call, input.tools(), input.history(), input.currentRead(), input.modelOrdinal(), input.modelSlot());
        // 原安全反馈的复用没有新外调，不再次占用工具次数；未决事实始终先于新调用预算判定。
        if (!(selected instanceof LoadedSelected)) return selected;
        var limited = BudgetGuard.evaluate(input.budget(), input.usage(), BudgetGuard.Operation.TOOL, input.elapsed());
        return limited.<LoadedTransition>map(LoadedBudgetDenied::new).orElse(selected);
    }

    /**
     * 只计算完整目标、参数及原观察的关系，不授予调用权。
     * 结果提交可以用它核对原已派发模型请求中的复用配对，不能在结果后重复检查新派发预算。
     */
    public LoadedTransition resolveTool(AgentAction.ToolCall call, List<LoadedTool> tools, List<ToolObservation> history) {
        return resolveTool(call, tools, history, false, 0, 0);
    }

    /** 可信的当前观察按原模型操作复用；不同操作重新读取，未决目标始终优先。 */
    public LoadedTransition resolveTool(AgentAction.ToolCall call, List<LoadedTool> tools, List<ToolObservation> history,
            boolean currentRead, long modelOrdinal, int modelSlot) {
        Objects.requireNonNull(call); tools = List.copyOf(tools); history = List.copyOf(history);
        if (currentRead && (modelOrdinal <= 0 || modelSlot <= 0)) throw new IllegalArgumentException("model_operation_required");
        if (tools.stream().map(tool -> tool.binding().target().handle()).distinct().count() != tools.size())
            return new LoadedRejected(LoadedRejection.INVALID_HISTORY);
        var matches = tools.stream().filter(tool -> tool.binding().target().handle().value().equals(call.toolHandle())).toList();
        if (matches.size() != 1) return new LoadedRejected(LoadedRejection.TOOL_NOT_VISIBLE);
        var selected = matches.getFirst();
        for (var observation : history) {
            if (!observation.tool().binding().sameTarget(selected.binding())) {
                if (observation.call().toolHandle().equals(call.toolHandle()))
                    return new LoadedRejected(LoadedRejection.INVALID_HISTORY);
                continue;
            }
            if (!observation.call().toolHandle().equals(call.toolHandle()))
                return new LoadedRejected(LoadedRejection.INVALID_HISTORY);
            if (observation.resolution() == ToolResolution.UNKNOWN || observation.resolution() == ToolResolution.INPUT_REQUIRED)
                return new LoadedRejected(LoadedRejection.TOOL_RESULT_UNRESOLVED);
        }
        if (currentRead) for (var observation : history) {
            if (observation.modelOrdinal() != modelOrdinal || observation.modelSlot() != modelSlot) continue;
            if (!observation.tool().binding().sameTarget(selected.binding()) || !observation.call().equals(call))
                return new LoadedRejected(LoadedRejection.INVALID_HISTORY);
            if (observation.feedback().isEmpty()) return new LoadedRejected(LoadedRejection.SAFE_FEEDBACK_REQUIRED);
            return new LoadedToolReuse(call, selected, observation);
        }
        for (var observation : history) {
            if (!observation.tool().binding().sameTarget(selected.binding())) continue;
            boolean qualifiedCurrentRead = currentRead && observation.currentRead();
            boolean sameArguments = observation.call().arguments().equals(call.arguments());
            if (!selected.binding().parameterized() && !sameArguments)
                return new LoadedRejected(LoadedRejection.REPEATED_WRITE_ARGUMENTS);
            if (sameArguments) {
                if (qualifiedCurrentRead && observation.modelOrdinal() != modelOrdinal) continue;
                // 已知失败的获准参数化读取可由模型新决定再次申请；不重放旧Attempt，也不跳过预算或未决检查。
                if (!qualifiedCurrentRead && observation.resolution() == ToolResolution.FAILED && selected.binding().parameterized()
                        && selected.binding().target().effect() == ToolEffect.READ_ONLY) continue;
                if (observation.feedback().isEmpty()) return new LoadedRejected(LoadedRejection.SAFE_FEEDBACK_REQUIRED);
                return new LoadedToolReuse(call, selected, observation);
            }
        }
        return new LoadedSelected(call, Optional.of(selected));
    }

    /**
     * 新链纯路由材料，不依赖旧checkpoint、journal或模型来源块。
     * @param decision 实际模型结果的已解码决策
     * @param budget 冻结限制，余额仍归账本
     * @param usage 已提交实际计数和用量
     * @param elapsed 已确认活动时长
     * @param tools 已加载完整工具绑定及稳定协议名
     * @param history 原已保存工具结果的安全观察，不以模型声明补齐
     * @param currentRead 当前调用由宿主按冻结目标和参数核证为当前观察
     * @param modelOrdinal 本 Run 内原模型操作序号；未核证时可为零
     * @param modelSlot 原模型操作中的工具槽位；未核证时可为零
     */
    public record LoadedInput(AgentDecisionDecoder.Result decision, LoopBudget budget, LoopUsage usage,
            Duration elapsed, List<LoadedTool> tools, List<ToolObservation> history, boolean currentRead, long modelOrdinal, int modelSlot) {
        public LoadedInput(AgentDecisionDecoder.Result decision, LoopBudget budget, LoopUsage usage,
                Duration elapsed, List<LoadedTool> tools, List<ToolObservation> history) {
            this(decision, budget, usage, elapsed, tools, history, false, 0, 0);
        }
        /** 冻结当前材料，不查询配置、身份或数据库。 */
        public LoadedInput {
            Objects.requireNonNull(decision); Objects.requireNonNull(budget); Objects.requireNonNull(usage); Objects.requireNonNull(elapsed);
            tools = List.copyOf(tools); history = List.copyOf(history);
            if (elapsed.isNegative()) throw new IllegalArgumentException("elapsed must not be negative");
            if (currentRead && (modelOrdinal <= 0 || modelSlot <= 0)) throw new IllegalArgumentException("model_operation_required");
        }
        /** 模型业务正文不进入普通日志。 */
        @Override public String toString() { return "LoadedExecutionInput[REDACTED]"; }
    }

    /** @param binding 实际冻结目标及副作用资格 @param wireName 本轮模型原稳定协议名 */
    public record LoadedTool(ToolCallRouting.Binding binding, String wireName) {
        /** 协议名只用于原生配对，不替代真实执行目标。 */
        public LoadedTool {
            Objects.requireNonNull(binding);
            if (wireName == null || wireName.isBlank()) throw new IllegalArgumentException("tool_wire_name_required");
        }
        /** 能力目标不进入普通日志。 */
        @Override public String toString() { return "LoadedExecutionTool[REDACTED]"; }
    }

    /** 原观察明确区分已确定成功/失败、UNKNOWN与输入等待；后两者不得重新派发。 */
    public enum ToolResolution { SUCCEEDED, FAILED, UNKNOWN, INPUT_REQUIRED }

    /**
     * 原结果提交时已取得的工具观察；引用存在性由Session真实材料来源负责。
     * @param call 原真实参数
     * @param tool 原冻结目标及协议名
     * @param resolution 原结果确定性
     * @param feedback 已保存安全反馈引用；缺失不能复用
     * @param contents 原安全模型内容，图片仍为持久引用
     * @param source 原Action、Attempt及请求/实际结果引用，复用保持原归属
     * @param currentRead 原调用由宿主核证为当前观察
     * @param modelOrdinal 原模型操作序号；未核证时可为零
     * @param modelSlot 原模型操作中的工具槽位；未核证时可为零
     */
    public record ToolObservation(AgentAction.ToolCall call, LoadedTool tool, ToolResolution resolution,
            Optional<ResultRef> feedback, List<ModelContent> contents, ToolSource source, boolean currentRead, long modelOrdinal, int modelSlot) {
        public ToolObservation(AgentAction.ToolCall call, LoadedTool tool, ToolResolution resolution,
                Optional<ResultRef> feedback, List<ModelContent> contents, ToolSource source) {
            this(call, tool, resolution, feedback, contents, source, false, 0, 0);
        }
        /** 原目标、句柄与结果确定性必须一致，未知结果不接受安全成功正文。 */
        public ToolObservation {
            Objects.requireNonNull(call); Objects.requireNonNull(tool); Objects.requireNonNull(resolution); Objects.requireNonNull(feedback); Objects.requireNonNull(source);
            contents = List.copyOf(contents);
            if (!call.toolHandle().equals(tool.binding().target().handle().value())
                    || (resolution == ToolResolution.UNKNOWN || resolution == ToolResolution.INPUT_REQUIRED)
                        && (feedback.isPresent() || !contents.isEmpty())) throw new IllegalArgumentException("tool_observation_invalid");
            if (currentRead && (modelOrdinal <= 0 || modelSlot <= 0)) throw new IllegalArgumentException("model_operation_required");
        }
        /** 原参数和安全结果仍含私有业务信息。 */
        @Override public String toString() { return "ExecutionToolObservation[resolution=" + resolution + ",REDACTED]"; }
    }

    /** 已保存观察的最小实际来源；复用保留原Action，不另建来源图或重写原批次归属。 */
    public record ToolSource(ActionRef action, String attemptId, ResultRef request, ResultRef result) {
        public ToolSource {
            Objects.requireNonNull(action); Objects.requireNonNull(request); Objects.requireNonNull(result);
            if (attemptId == null || attemptId.isBlank()) throw new IllegalArgumentException("tool_source_invalid");
        }
        @Override public String toString() { return "ExecutionToolSource[REDACTED]"; }
    }

    /** 新链选择只描述实际动作、已保存安全观察复用或明确拒绝，不生成操作票据。 */
    public sealed interface LoadedTransition permits LoadedSelected, LoadedToolReuse, LoadedRejected,
            LoadedBudgetDenied, LoadedDecodeFeedback, LoadedBatchDeferred { }
    /** @param action 原模型动作 @param tool 工具动作的精确已加载绑定，其他能力由Session所属入口处理 */
    public record LoadedSelected(AgentAction action, Optional<LoadedTool> tool) implements LoadedTransition {
        /** 工具动作不能缺失精确目标，其他动作不能夹带工具目标。 */
        public LoadedSelected {
            Objects.requireNonNull(action); Objects.requireNonNull(tool);
            if ((action instanceof AgentAction.ToolCall) != tool.isPresent()) throw new IllegalArgumentException("loaded_tool_selection_mismatch");
        }
    }
    /** @param call 本轮实际调用 @param tool 本轮稳定映射 @param original 原已保存安全观察 */
    public record LoadedToolReuse(AgentAction.ToolCall call, LoadedTool tool, ToolObservation original) implements LoadedTransition { }
    /** @param reason 明确拒绝分类，不把未决事实变为新调用 */
    public record LoadedRejected(LoadedRejection reason) implements LoadedTransition { }
    /** @param limit 原预算拒绝，不伪造调用许可 */
    public record LoadedBudgetDenied(BudgetGuard.Limit limit) implements LoadedTransition { }
    /** @param reason 原模型解码失败分类 */
    public record LoadedDecodeFeedback(AgentDecisionDecoder.Reason reason) implements LoadedTransition { }
    /** 并行工具在所属后续批次接入，不返回可执行的部分动作。 */
    public record LoadedBatchDeferred() implements LoadedTransition { }
    /** 当前事实不足或冲突时的封闭拒绝原因。 */
    public enum LoadedRejection { MODEL_RESULT_REQUIRED, INVALID_HISTORY, TOOL_NOT_VISIBLE,
        TOOL_RESULT_UNRESOLVED, REPEATED_WRITE_ARGUMENTS, SAFE_FEEDBACK_REQUIRED }

    /**
     * 根据已加载的类型化材料计算单动作或完整并行批次。
     * 选中仅表示计算通过；当前权限、取消、租约、预算余额和版本仍在派发事务核验。
     */
    public Transition next(DecisionInput input) {
        Objects.requireNonNull(input, "input");
        if (!(input.decision() instanceof AgentDecisionDecoder.DecodedBatch batch)) {
            return new Single(route(input.decision(), input, input.usage()));
        }
        var routes = new ArrayList<AgentDecisionRouter.Result>();
        var identities = new HashSet<Object>();
        var usage = input.usage();
        for (int index = 0; index < batch.calls().size(); index++) {
            var call = batch.calls().get(index);
            var routed = router.routeBatchSlot(batch, index + 1, input.context(), input.budget(), usage,
                    input.elapsed(), input.history(), input.delegation(), input.tools());
            if (!(routed instanceof AgentDecisionRouter.Selected
                    || routed instanceof AgentDecisionRouter.Reuse
                    || routed instanceof AgentDecisionRouter.Converge)) {
                return new Single(routed);
            }
            // 全批完成前不暴露任何已选动作；重复完整请求不能产生两份新派发。
            if (!identities.add(input.tools().identity(call))) {
                return new Single(new AgentDecisionRouter.Rejected(
                        AgentDecisionRouter.Rejection.INVALID_HISTORY));
            }
            routes.add(routed);
            // 只有新动作占用后续槽位的纯预算预检额度；原结果或未决动作沿原身份处理。
            if (routed instanceof AgentDecisionRouter.Selected) usage = usage.nextToolCall();
        }
        return new Batch(routes);
    }

    /** 逐槽复用同一纯路由算法，临时计数不写回调用方状态。 */
    private AgentDecisionRouter.Result route(AgentDecisionDecoder.Result decision, DecisionInput input,
            LoopUsage usage) {
        return router.route(decision, input.context(), input.budget(), usage, input.elapsed(),
                input.history(), input.delegation(), input.tools());
    }

    /**
     * 一次决策所需的不可变计算材料，不持有状态写权或外部调用许可。
     *
     * @param decision 已保存模型结果的类型化决策
     * @param context 本轮已加载的模型上下文和冻结能力材料
     * @param budget 本轮使用的执行上限；余额权威仍归预算账本
     * @param usage 结果提交后已采纳的调用次数与实际用量
     * @param elapsed 当前已确认的活动时长，不由 Core 读取时钟
     * @param history 当前 Run 的动作视图，保留未决和 UNKNOWN 身份
     * @param delegation 已装配委派能力的可见目标
     * @param tools 已加载的精确工具绑定与历史路由材料
     */
    public record DecisionInput(AgentDecisionDecoder.Result decision, FrozenContextSnapshot context,
            LoopBudget budget, LoopUsage usage, Duration elapsed, List<AgentActionSnapshot> history,
            AgentDecisionRouter.DelegationSelection delegation, ToolCallRouting tools) {
        /** 冻结集合并拒绝缺失值；不在构造时查询身份、存储或当前配置。 */
        public DecisionInput {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(elapsed, "elapsed");
            history = List.copyOf(history);
            Objects.requireNonNull(delegation, "delegation");
            Objects.requireNonNull(tools, "tools");
            if (elapsed.isNegative()) throw new IllegalArgumentException("elapsed must not be negative");
        }

        /** 决策、上下文和历史正文不进入普通对象日志。 */
        @Override public String toString() { return "AgentExecutionDecisionInput[REDACTED]"; }
    }

    /** 计算结果复用现有动作与路由值，不创建第二套动作协议或持久化回执。 */
    public sealed interface Transition permits Single, Batch { }

    /**
     * 单动作路由或整批拒绝结果；批次失败时不携带任何部分已选动作。
     * @param route 保留原 Selected、Reuse、Converge、预算或确定拒绝语义
     */
    public record Single(AgentDecisionRouter.Result route) implements Transition {
        /** 路由必须显式提供，不以 null 表示等待或结束。 */
        public Single { Objects.requireNonNull(route, "route"); }
    }

    /**
     * 全部槽位已通过纯预检的有序工具路由；Session 按原顺序登记新动作并采用原结果。
     * @param routes 列表下标加一是原模型调用槽位；只有 Selected 需要新的 Action/Attempt
     */
    public record Batch(List<AgentDecisionRouter.Result> routes) implements Transition {
        /** 只接受完整可处理批次，禁止拒绝值或部分动作混入批次。 */
        public Batch {
            routes = List.copyOf(routes);
            if (routes.size() < ToolCallLimits.MIN_READ_ONLY_BATCH
                    || routes.size() > ToolCallLimits.READ_ONLY_BATCH
                    || routes.stream().anyMatch(route -> !(route instanceof AgentDecisionRouter.Selected
                        || route instanceof AgentDecisionRouter.Reuse
                        || route instanceof AgentDecisionRouter.Converge))) {
                throw new IllegalArgumentException("invalid execution batch");
            }
        }

        /** 批次中的模型参数和历史结果身份不进入普通对象日志。 */
        @Override public String toString() { return "AgentExecutionBatch[REDACTED]"; }
    }
}

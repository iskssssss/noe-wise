package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** reactive/planned 共用的纯七动作路由；不持有 Port、不读取时钟或存储、不修改预算和 Session 状态。 */
public final class AgentDecisionRouter {
    private final CodecLimits limits;
    private final AgentDecisionDecoder decoder;

    /** 委派完整正文参与有界重复摘要，不能以任意长度或自由对象绕过 decoder 配额。 */
    public AgentDecisionRouter(CodecLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.decoder = new AgentDecisionDecoder(limits);
    }

    /** 输入是已由 Session 验证来源的当前消费视图；构造成功与路由 Selected 均不构成持久提交或执行授权。 */
    public Result route(AgentDecisionDecoder.Result decision, FrozenContextSnapshot context, LoopBudget budget,
                 LoopUsage usage, Duration elapsed, List<AgentActionSnapshot> history, DelegationSelection delegation) {
        return route(decision, context, budget, usage, elapsed, history, delegation, ToolCallRouting.none(context));
    }

    /** 可信工具输入由 Session 从原准备及 journal 重建，Core 只消费纯值并保留原写工具行为。 */
    public Result route(AgentDecisionDecoder.Result decision, FrozenContextSnapshot context, LoopBudget budget,
                 LoopUsage usage, Duration elapsed, List<AgentActionSnapshot> history, DelegationSelection delegation,
                 ToolCallRouting tools) {
        return route(decision, context, budget, usage, elapsed, history, delegation, tools, List.of(), 0);
    }

    /** 整批历史先按原槽位验证，当前槽只复用自己的原动作，不把相邻子项当成新调用。 */
    Result routeBatchSlot(AgentDecisionDecoder.DecodedBatch batch, int ordinal, FrozenContextSnapshot context,
                 LoopBudget budget, LoopUsage usage, Duration elapsed, List<AgentActionSnapshot> history,
                 DelegationSelection delegation, ToolCallRouting tools) {
        if (ordinal <= 0 || ordinal > batch.calls().size()) throw new IllegalArgumentException("invalid_batch_slot");
        return route(new AgentDecisionDecoder.Decoded(batch.calls().get(ordinal - 1)), context, budget,
                usage, elapsed, history, delegation, tools, batch.calls(), ordinal);
    }

    /** 批次形状仅影响同轮子项的身份校验，权限、预算及历史完整性继续共用同一算法。 */
    private Result route(AgentDecisionDecoder.Result decision, FrozenContextSnapshot context, LoopBudget budget,
                 LoopUsage usage, Duration elapsed, List<AgentActionSnapshot> history, DelegationSelection delegation,
                 ToolCallRouting tools, List<AgentAction.ToolCall> batch, int ordinal) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(delegation, "delegation");
        if (usage.businessModelCalls() != context.operation().iteration()) {
            return new Rejected(Rejection.INVALID_USAGE);
        }
        // 当前结果不重复计一次业务模型调用。解码失败仍受公共 token/wall-time 限制；
        // 已得到 respond/ask_user 等本地收口动作时不能因为产生该结果的用量越线而丢弃答案。
        var common = BudgetGuard.evaluate(budget, usage, BudgetGuard.Operation.CONTINUATION, elapsed);
        if (decision instanceof AgentDecisionDecoder.Failure failure) {
            if (common.isPresent()) { return new BudgetDenied(common.orElseThrow()); }
            return new DecodeFeedback(failure.reason());
        }
        if (decision instanceof AgentDecisionDecoder.DecodedBatch) return new Rejected(Rejection.INVALID_HISTORY);
        var action = ((AgentDecisionDecoder.Decoded) decision).action();
        if (common.isPresent() && !localCompletion(action)) {
            return new BudgetDenied(common.orElseThrow());
        }
        boolean restricted = context.contextScope() == FrozenContextSnapshot.ContextScope.RESTRICTED_WORKFLOW;
        if (restricted && !(action instanceof AgentAction.Respond) && !(action instanceof AgentAction.AskUser)) {
            return new Rejected(Rejection.RESTRICTED_SCOPE);
        }
        var visible = visibility(action, context, delegation);
        if (visible instanceof Rejected) { return visible; }
        // Workflow 的两类局部动作不需要 Agent capability snapshot，也不触碰 Agent 的历史/目标输入。
        if (restricted) { return visible; }
        if (!Objects.requireNonNull(tools).matches(context, history)) return new Rejected(Rejection.INVALID_HISTORY);
        var snapshot = context.capabilitySnapshot().orElseThrow();
        if (history.size() > (long) context.operation().iteration() * com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH) { return new Rejected(Rejection.INVALID_HISTORY); }
        List<AgentActionSnapshot> frozenHistory = List.copyOf(history);
        if (!validHistory(frozenHistory, context.operation(), snapshot, action, tools, batch)) {
            return new Rejected(Rejection.INVALID_HISTORY);
        }
        // 同轮其余槽已由整批校验绑定，不能被当作跨轮 UNKNOWN 改参冲突；旧轮未决仍保留。
        var slotTools = batch.isEmpty() ? tools : new ToolCallRouting(context, tools.current(),
                tools.history().stream().filter(value -> value.action().operation().iteration()
                        != context.operation().iteration() || value.action().childOrdinal() == ordinal).toList(),
                tools.currentReads());
        // 未决精确目标先于新参数选择检查，不能通过改参绕过原 UNKNOWN。
        if (action instanceof AgentAction.ToolCall tool && slotTools.unresolvedConflict(tool))
            return new Rejected(Rejection.INVALID_HISTORY);
        // 单调用仍只有一个本轮动作；batch 的逐槽来源由 Session 校验后恢复，不走此单步交接。
        var currentEntry = frozenHistory.stream()
                .filter(entry -> entry.operation().iteration() == context.operation().iteration())
                .filter(entry -> batch.isEmpty() || entry.childOrdinal() == ordinal)
                .findFirst();
        if (currentEntry.isPresent()) {
            var original = currentEntry.orElseThrow();
            return original.outcome().resolved() ? new Reuse(original) : new Converge(original);
        }
        // 新鲜读取只区分已经结束的旧观察；原未决请求仍沿同一 operation 与 journal 收敛。
        if (action instanceof AgentAction.ToolCall tool) {
            var unresolved = tools.unresolvedRead(tool);
            if (unresolved.isPresent()) return new Converge(unresolved.orElseThrow());
        }
        var key = repeatKey(action, tools, Optional.empty());
        if (key.isPresent()) {
            for (var entry : frozenHistory) {
                if (repeatKey(entry.action(), tools, Optional.of(entry)).equals(key)) {
                    // 同 scope/handle 只允许原规范执行参数；commentary 不属于 ToolCall 执行值。
                    // 完整 target/binding 的来源仍由 Session 从原 journal 核对，不能在此重选目标。
                    if (action instanceof AgentAction.ToolCall tool
                            && entry.action() instanceof AgentAction.ToolCall originalTool
                            && !originalTool.arguments().equals(tool.arguments())) {
                        return new Rejected(Rejection.INVALID_HISTORY);
                    }
                    // 返回整个原条目，保留 journal/operation；不能只返回裸 eventId 后用当前身份重建请求。
                    return entry.outcome().resolved() ? new Reuse(entry) : new Converge(entry);
                }
            }
        }
        if (visible instanceof MissingDelegation) { return visible; }
        var count = switch (action) {
            case AgentAction.ToolCall ignored -> BudgetGuard.Operation.TOOL;
            case AgentAction.Dispatch ignored -> BudgetGuard.Operation.WORKFLOW;
            case AgentAction.KnowledgeAccess ignored -> BudgetGuard.Operation.KNOWLEDGE;
            case AgentAction.Delegate ignored -> BudgetGuard.Operation.DELEGATE;
            case AgentAction.Plan ignored -> BudgetGuard.Operation.CONTINUATION;
            case AgentAction.Respond ignored -> BudgetGuard.Operation.CONTINUATION;
            case AgentAction.ActivateSkill ignored -> BudgetGuard.Operation.CONTINUATION;
            case AgentAction.ReadSkillResource ignored -> BudgetGuard.Operation.CONTINUATION;
            case AgentAction.AskUser ignored -> BudgetGuard.Operation.CONTINUATION;
        };
        // 只有 fresh 外部调用才检查次数与 token；本地收口动作沿用已产生的当前结果。
        if (localCompletion(action)) {
            return visible;
        }
        var limited = BudgetGuard.evaluate(budget, usage, count, elapsed);
        return limited.<Result>map(BudgetDenied::new).orElse(visible);
    }

    private static boolean localCompletion(AgentAction action) {
        return action instanceof AgentAction.Respond
                || action instanceof AgentAction.AskUser
                || action instanceof AgentAction.ActivateSkill
                || action instanceof AgentAction.ReadSkillResource;
    }

    /** 可见性只消费冻结值；不查当前 registry，也不把结构命中提升为 Policy 或 Provider 权限。 */
    private Result visibility(AgentAction action, FrozenContextSnapshot context, DelegationSelection delegation) {
        if (action instanceof AgentAction.Respond response) {
            var ids = response.evidenceItemIds();
            var bindings = new ArrayList<EvidenceBinding>();
            for (String itemId : ids) {
                var matches = context.evidenceRefs().stream().filter(ref -> ref.itemIds().contains(itemId)).toList();
                if (matches.size() != 1) { return new Rejected(Rejection.EVIDENCE_NOT_UNIQUE); }
                bindings.add(new EvidenceBinding(itemId, matches.getFirst()));
            }
            return new Selected(action, bindings);
        }
        if (action instanceof AgentAction.Delegate request) {
            if (delegation instanceof DelegationSelection.Absent) { return new MissingDelegation(request); }
            var configured = (DelegationSelection.Configured) delegation;
            if (!configured.operation().equals(context.operation())) { return new Rejected(Rejection.DELEGATION_SCOPE_MISMATCH); }
            var requestedTargets = switch (request.command().operation()) {
                case START -> List.of(request.command().targetHandle());
                default -> List.<String>of();
            };
            if (!configured.targetHandles().containsAll(requestedTargets)) {
                return new Rejected(Rejection.TARGET_NOT_VISIBLE);
            }
            return new Selected(action, List.of());
        }
        String handle = switch (action) {
            case AgentAction.ToolCall call -> call.toolHandle();
            case AgentAction.Dispatch dispatch -> dispatch.handle();
            case AgentAction.KnowledgeAccess query -> query.knowledgeHandle();
            case AgentAction.ActivateSkill skill -> skill.skillHandle();
            case AgentAction.ReadSkillResource resource -> resource.skillHandle();
            case AgentAction.AskUser ignored -> null;
            case AgentAction.Plan ignored -> null;
            // Respond/Delegate 已显式处理，这里不提供未知动作 fallback。
            case AgentAction.Respond ignored -> throw new IllegalStateException("response already routed");
            case AgentAction.Delegate ignored -> throw new IllegalStateException("delegation already routed");
        };
        return handle != null && !context.capabilityHandles().contains(handle)
                ? new Rejected(Rejection.CAPABILITY_NOT_VISIBLE) : new Selected(action, List.of());
    }

    /** 验证全部历史而非只找匹配项；一个损坏的无关条目也不能被过滤为“没执行过”。 */
    private boolean validHistory(List<AgentActionSnapshot> history, ModelPreparationIdentity current,
                                 CapabilitySnapshotRef snapshot, AgentAction currentAction, ToolCallRouting tools,
                                 List<AgentAction.ToolCall> batch) {
        if (history.size() > (long) current.iteration() * com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH) { return false; }
        // 历史 batch 可因复用槽位而留空洞；真实子号仍须有界、唯一且递增，不能混入单调用零号。
        var keys = new HashSet<RepeatKey>();
        int previousIteration = -1;
        int previousOrdinal = 0;
        for (var entry : history) {
            var original = entry.operation();
            if (!original.runIdentity().equals(current.runIdentity()) || !original.loopId().equals(current.loopId())
                    || entry.contextScope() != FrozenContextSnapshot.ContextScope.AGENT_INPUT
                    || !entry.capabilitySnapshot().equals(Optional.of(snapshot)) || original.iteration() > current.iteration()
                    || original.iteration() < previousIteration
                    || entry.childOrdinal() > com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH
                    || original.iteration() == previousIteration && (previousOrdinal == 0 || entry.childOrdinal() <= previousOrdinal)) {
                return false;
            }
            previousIteration = original.iteration(); previousOrdinal = entry.childOrdinal();
            var key = repeatKey(entry.action(), tools, Optional.of(entry));
            if (key.isPresent() && !keys.add(key.orElseThrow())) { return false; }
            if (original.iteration() == current.iteration()) {
                if (batch.isEmpty()) {
                    if (!entry.action().equals(currentAction)) return false;
                } else if (entry.childOrdinal() <= 0 || entry.childOrdinal() > batch.size()
                        || !entry.action().equals(batch.get(entry.childOrdinal() - 1))) return false;
            }
        }
        return true;
    }

    /** 普通非幂等写工具保持句柄级身份；已核只读、幂等写与平台代码例外比较完整原目标、绑定及规范参数。 */
    private Optional<RepeatKey> repeatKey(AgentAction action, ToolCallRouting tools, Optional<AgentActionSnapshot> original) {
        return switch (action) {
            case AgentAction.ToolCall tool -> tools.currentRead(tool)
                    ? original.map(entry -> new RepeatKey(Kind.TOOL,
                            new CurrentRead(tools.identity(entry), entry.operation())))
                    : Optional.of(new RepeatKey(Kind.TOOL, original.<Object>map(tools::identity)
                            .orElseGet(() -> tools.identity(tool))));
            case AgentAction.Dispatch dispatch -> Optional.of(new RepeatKey(Kind.WORKFLOW, dispatch.handle()));
            case AgentAction.KnowledgeQuery query -> Optional.of(new RepeatKey(Kind.KNOWLEDGE,
                    CanonicalJson.encode(CanonicalJson.array(List.of(CanonicalJson.string(query.knowledgeHandle()),
                            CanonicalJson.string(query.query()))))));
            // 目录与原文每次重新经过当前撤权门禁；旧读取不扩大当前授权。
            case AgentAction.KnowledgeListDocuments ignored -> Optional.empty();
            case AgentAction.KnowledgeReadDocument ignored -> Optional.empty();
            // 状态查询、补充与 follow-up 可在后续轮次再次发生，幂等身份绑定本次 operation。
            case AgentAction.Delegate ignored -> Optional.empty();
            case AgentAction.Plan ignored -> Optional.empty();
            case AgentAction.Respond ignored -> Optional.empty();
            case AgentAction.ActivateSkill ignored -> Optional.empty();
            // 每次模型资源调用必须登记本轮结果（包括 NOT_FOUND）；旧轮结果不能替代新 call。
            // 同 operation 的重放已由上方 currentEntry 分支返回原条目。
            case AgentAction.ReadSkillResource ignored -> Optional.empty();
            case AgentAction.AskUser ignored -> Optional.empty();
        };
    }

    /** 重复键仅在当前已验证 scope 内使用，不能作为跨 Run 的全局账本 key。 */
    private record RepeatKey(Kind kind, Object identity) { }
    /** 已核证观察的跨轮身份包括原 operation；同轮恢复仍采用 currentEntry 的原动作。 */
    private record CurrentRead(Object identity, ModelPreparationIdentity operation) { }
    /** 同名句柄的不同能力种类不会错误复用彼此结果。 */
    private enum Kind { TOOL, WORKFLOW, KNOWLEDGE, DELEGATE }

    /** 缺省与已装配投影明确区分；没有从 capabilityHandles 猜测目标或缺省允许的分支。 */
    public sealed interface DelegationSelection {
        /** 无实现不需要目标解析，实际拒绝审计由 NoDelegationSubmission 完成。 */
        record Absent() implements DelegationSelection { }
        /** 只读目标目录视图；具体任务、身份与授权由 Session 委派协调器处理。 */
        record Configured(ModelPreparationIdentity operation,
                          List<String> targetHandles) implements DelegationSelection {
            public Configured {
                Objects.requireNonNull(operation, "operation");
                targetHandles = List.copyOf(targetHandles);
                if (new HashSet<>(targetHandles).size() != targetHandles.size()) {
                    throw new IllegalArgumentException(
                            "duplicate delegation visibility");
                }
                for (String handle : targetHandles) {
                    if (handle.isBlank()) { throw new IllegalArgumentException("blank target handle"); }
                    CanonicalJson.string(handle);
                }
            }
            /** 目标目录与引用不进入普通日志。 */
            @Override public String toString() { return "DelegationSelection[REDACTED]"; }
        }
    }

    /** 精确引用绑定按模型请求顺序保留，不能用 Set 去重或 findFirst 掩盖同 itemId 歧义。 */
    public record EvidenceBinding(String itemId, EvidenceRef reference) {
        /** 引用只是已选择来源，真正输出前仍须验证持久来源与 Policy。 */
        public EvidenceBinding { Objects.requireNonNull(itemId, "itemId"); Objects.requireNonNull(reference, "reference"); }
        /** 证据身份不输出到普通日志。 */
        @Override public String toString() { return "EvidenceBinding[REDACTED]"; }
    }

    /** 所有结果均是待提交/待读取候选，不向调用者提供 Provider permit 或持久消费凭证。 */
    public sealed interface Result permits Selected, MissingDelegation, Reuse, Converge, Rejected, BudgetDenied, DecodeFeedback { }
    /** 新动作的纯选择；计数实际递增仍须由后续 Session 消费提交保证一次性。 */
    public record Selected(AgentAction action, List<EvidenceBinding> evidence) implements Result {
        /** 引用映射防御性复制，不能在准入后被其他线程替换。 */
        public Selected { Objects.requireNonNull(action, "action"); evidence = List.copyOf(evidence); }
        /** 即使只选句柄，也不暴露用户动作正文。 */
        @Override public String toString() { return "SelectedAgentAction[REDACTED]"; }
    }
    /** 缺省拒绝还没有写入 SessionLog；绝不能当作已提交 AgentObservation 反馈模型。 */
    public record MissingDelegation(AgentAction.Delegate action) implements Result {
        /** 只接受结构有效的 Delegate，不接纳解码失败。 */
        public MissingDelegation { Objects.requireNonNull(action, "action"); }
        /** 不打印委派任务。 */
        @Override public String toString() { return "MissingDelegation[REDACTED]"; }
    }
    /** 必须读取并核对原已提交结果；读取失败不能回退 fresh 执行。 */
    public record Reuse(AgentActionSnapshot original) implements Result {
        /** 原条目不得被裸 eventId 替代。 */
        public Reuse { Objects.requireNonNull(original, "original"); }
    }
    /** 未确定或已受理的原请求只能从原身份继续，不新增 Action/child 或调用计数。 */
    public record Converge(AgentActionSnapshot original) implements Result {
        /** 留存完整原操作和 journal 引用供后续收敛校验。 */
        public Converge { Objects.requireNonNull(original, "original"); }
    }
    /** 协议/可见性拒绝的反馈仍待审计，不携带用户正文。 */
    public record Rejected(Rejection reason) implements Result {
        /** 原因必须是稳定枚举。 */
        public Rejected { Objects.requireNonNull(reason, "reason"); }
    }
    /** 复用原 BudgetGuard 的拒绝原因，不维护第二套预算终止规则。 */
    public record BudgetDenied(BudgetGuard.Limit limit) implements Result {
        /** 拒绝不能用空限制表示。 */
        public BudgetDenied { Objects.requireNonNull(limit, "limit"); }
    }
    /** 外层后续审计并在新的业务模型调用预算内继续，router 不运行模型重试。 */
    public record DecodeFeedback(AgentDecisionDecoder.Reason reason) implements Result {
        /** 保留 decoder 的稳定分类，不拼接解析异常。 */
        public DecodeFeedback { Objects.requireNonNull(reason, "reason"); }
    }
    /** 当前路由不把协议错误伪装成业务已提交结果。 */
    public enum Rejection { INVALID_USAGE, RESTRICTED_SCOPE, CAPABILITY_NOT_VISIBLE, EVIDENCE_NOT_UNIQUE,
        DELEGATION_SCOPE_MISMATCH, TARGET_NOT_VISIBLE, INVALID_HISTORY }
}

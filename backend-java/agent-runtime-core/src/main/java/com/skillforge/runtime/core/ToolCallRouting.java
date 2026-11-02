package com.skillforge.runtime.core;

import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Session 从原准备及调用 journal 重建的当轮工具路由值；结构成立不授予执行或重试权。 */
public record ToolCallRouting(FrozenContextSnapshot context, List<Binding> current, List<Historical> history,
                              List<AgentAction.ToolCall> currentReads) {
    /** 保序冻结完整目标及原历史，不接受同句柄多目标或重复历史来源。 */
    public ToolCallRouting {
        Objects.requireNonNull(context);
        current = List.copyOf(current);
        history = List.copyOf(history);
        currentReads = List.copyOf(currentReads);
        var frozenCurrent = current;
        if (current.stream().map(value -> value.target().handle()).distinct().count() != current.size()
                || history.stream().map(Historical::action).distinct().count() != history.size()
                || currentReads.stream().distinct().count() != currentReads.size()
                || currentReads.stream().anyMatch(call -> frozenCurrent.stream().noneMatch(binding ->
                        binding.target().handle().value().equals(call.toolHandle()) && binding.parameterized()))) throw invalid();
    }

    /** 未核证当前读取资格的调用保持精确请求跨轮复用，不从参数或名称自行推断资格。 */
    public ToolCallRouting(FrozenContextSnapshot context, List<Binding> current, List<Historical> history) {
        this(context, current, history, List.of());
    }

    /** 未装配可信工具来源时保持原句柄级行为，不默认启用参数化例外。 */
    public static ToolCallRouting none(FrozenContextSnapshot context) {
        return new ToolCallRouting(context, List.of(), List.of());
    }

    /** 当前完整快照及全部工具历史必须与 Session 重建的来源一致。 */
    public boolean matches(FrozenContextSnapshot expected, List<AgentActionSnapshot> actions) {
        return context.equals(expected) && (current.isEmpty() && history.isEmpty()
                || history.stream().map(Historical::action).toList().equals(actions.stream()
                    .filter(value -> value.action() instanceof AgentAction.ToolCall).toList()));
    }

    /**
     * 获准参数化调用和仅有已证明 schema 未派发历史的目标按完整参数比较。
     * 一旦存在真正执行/未决历史，普通写工具恢复句柄约束；纠正不成为通用写重试权限。
     */
    public Object identity(AgentAction.ToolCall call) {
        return current(call.toolHandle()).filter(value -> value.parameterized() || history.stream().anyMatch(old ->
                        old.schemaRejectedBeforeDispatch() && value.sameTarget(old.binding()))
                    && history.stream().noneMatch(old -> !old.schemaRejectedBeforeDispatch() && value.sameTarget(old.binding())))
                .<Object>map(value -> new ParameterizedCall(value.target(), value.binding(), call))
                .orElse(call.toolHandle());
    }

    /** 历史身份仅取原 journal 对应的冻结值，不能用当前目录补造旧调用。 */
    public Object identity(AgentActionSnapshot action) {
        var call = (AgentAction.ToolCall) action.action();
        return history.stream().filter(value -> value.action().equals(action)).map(Historical::binding)
                .filter(value -> value.parameterized() || history.stream().anyMatch(old -> old.action().equals(action)
                        && old.schemaRejectedBeforeDispatch())).<Object>map(value -> new ParameterizedCall(value.target(), value.binding(), call))
                .findFirst().orElse(call.toolHandle());
    }

    /** 同精确目标仍未决时，先拒绝不同参数的新请求；原完整调用继续沿原账本收敛。 */
    public boolean unresolvedConflict(AgentAction.ToolCall call) {
        var selected = current(call.toolHandle()).filter(Binding::parameterized);
        return selected.isPresent() && history.stream().anyMatch(value -> !value.action().outcome().resolved()
                && selected.orElseThrow().sameTarget(value.binding()) && !value.action().action().equals(call));
    }

    /**
     * 当前读取资格只接受 Session 从精确冻结绑定与原命令核证的完整调用。
     * 集合同时覆盖历史核证调用，供历史重复身份检查；不改变效果、并行性或执行授权。
     */
    public boolean currentRead(AgentAction.ToolCall call) {
        return currentReads.contains(call);
    }

    /** 新模型轮次的读取不能绕过同一精确目标上相同原请求的未决或未知结果。 */
    public Optional<AgentActionSnapshot> unresolvedRead(AgentAction.ToolCall call) {
        if (!currentRead(call)) return Optional.empty();
        var selected = current(call.toolHandle()).orElseThrow();
        return history.stream().filter(value -> !value.action().outcome().resolved()
                && selected.sameTarget(value.binding()) && value.action().action().equals(call))
                .map(Historical::action).findFirst();
    }

    /** 参数化资格必须由相同 target/binding 的原冻结目录项提供。 */
    private Optional<Binding> current(String handle) {
        return current.stream().filter(value -> value.target().handle().value().equals(handle)).findFirst();
    }

    /** 完整目录事实；parallelSafe 不由模型参数或说明文字推断。 */
    public record Binding(FrozenToolTarget target, ToolBinding binding, boolean parallelSafe) {
        /** 构造不替代原 Prepared 与实时授权核验。 */
        public Binding { Objects.requireNonNull(target); Objects.requireNonNull(binding); }

        /**
         * 可信注册/发布的ARGUMENTS策略按完整参数区分新动作；不从名称、描述或效果推断身份资格。
         * parallelSafe 只控制调度并行性，串行目标仍可在原权限和预算内提交不同参数。
         * 相同调用默认复用原结果；另行核证的当前读取可跨轮更新观察，未决目标仍禁止改参。
         */
        public boolean parameterized() {
            return target.confirmation() == ToolConfirmationPolicy.NEVER && target.modelInvocable()
                    && target.callIdentityPolicy() == ToolCallIdentityPolicy.ARGUMENTS;
        }

        /** 未决判断及复用采用完整冻结目标与绑定，不仅比较展示句柄。 */
        public boolean sameTarget(Binding other) {
            return target.equals(other.target) && binding.equals(other.binding);
        }
        @Override public String toString() { return "ToolRoutingBinding[REDACTED]"; }
    }

    /**
     * 原快照包含 operation、childOrdinal 及 journal；Session 独立核验完整来源。
     * schemaRejectedBeforeDispatch 还须由原终态、反馈和原 schema 实际违反共同证明，不能来自模型声明。
     */
    public record Historical(AgentActionSnapshot action, Binding binding, boolean schemaRejectedBeforeDispatch) {
        /** 禁止非工具条目或目标句柄不一致的来源进入路由。 */
        public Historical {
            Objects.requireNonNull(action); Objects.requireNonNull(binding);
            if (!(action.action() instanceof AgentAction.ToolCall call)
                    || !call.toolHandle().equals(binding.target().handle().value())
                    || action.requestJournalRef().isEmpty()
                    || schemaRejectedBeforeDispatch && !(action.outcome() instanceof AgentActionSnapshot.Failed)) throw invalid();
        }
        @Override public String toString() { return "ToolRoutingHistory[REDACTED]"; }
    }

    /** 只作当轮比较值，包含规范参数与原目标绑定；不作为 Provider 幂等键。 */
    private record ParameterizedCall(FrozenToolTarget target, ToolBinding binding, AgentAction.ToolCall call) { }

    /** 私有参数、目录与模型上下文不得进入普通日志。 */
    @Override public String toString() { return "ToolCallRouting[REDACTED]"; }

    /** 结构来源矛盾时失败关闭。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("tool_routing_source_mismatch"); }
}

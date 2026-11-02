package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.RunRef;
import java.util.Objects;
import java.util.Optional;

/** 可信执行入口的资源作用域；线程迁移必须显式捕获，不从请求字段或 Trace 推断。 */
public final class ExecutionBudgetScope implements AutoCloseable {
    public static final String CLOSING_MODEL_INTERVAL_PREFIX = "output-closing-model:";
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private final Binding previous;
    private final Thread owner;
    private ExecutionBudgetScope(Binding binding) {
        previous = CURRENT.get(); owner = Thread.currentThread(); CURRENT.set(binding);
    }
    /** 当前冻结请求提供的收尾计量输入；准备阶段与候选准入共用同一公式。 */
    public record ClosingSizing(ExecutionBudgetPort.Category category, long inputUpperBound,
                                long outputUpperBound, java.time.Duration timeout) {
        public ClosingSizing {
            Objects.requireNonNull(category); Objects.requireNonNull(timeout);
            if (category != ExecutionBudgetPort.Category.BUSINESS_MODEL && category != ExecutionBudgetPort.Category.AUXILIARY_MODEL)
                throw new IllegalArgumentException("model_budget_category_invalid");
            if (inputUpperBound < 0 || outputUpperBound < 0) throw new IllegalArgumentException("budget_resources_negative");
        }
        public ExecutionBudgetPort.Closing size(ExecutionBudgetPort.ClosingSpec spec) {
            return size(spec, 0);
        }
        /** 已用模型窗口从同一Run的持久活动事实取得，不能在下一轮模型或恢复时重置。 */
        public ExecutionBudgetPort.Closing size(ExecutionBudgetPort.ClosingSpec spec, long modelActiveMillis) {
            var resources = ExecutionBudgetPort.Resources.ZERO;
            for (int call = 0; call < spec.modelCalls(); call++) resources = resources.plus(
                    ExecutionBudgetPort.Resources.call(category, inputUpperBound, outputUpperBound));
            if (spec.receiptInputUpperBound() > 0) resources = resources.plus(new ExecutionBudgetPort.Resources(
                    0, 0, 0, 0, spec.receiptInputUpperBound(), 0, spec.receiptInputUpperBound()));
            if (spec.finalizationCalls() > 0) resources = resources.plus(new ExecutionBudgetPort.Resources(
                    0, 0, spec.finalizationCalls(), 0, 0, 0, 0));
            long millis = calculation(spec, modelActiveMillis).requiredMillis();
            return new ExecutionBudgetPort.Closing(resources, Math.addExact(millis / 1000, millis % 1000 == 0 ? 0 : 1));
        }
        public ExecutionBudgetPort.ClosingTimeCalculation calculation(ExecutionBudgetPort.ClosingSpec spec, long modelActiveMillis) {
            if (modelActiveMillis < 0 || timeout.isNegative() || timeout.isZero())
                throw new IllegalArgumentException("budget_closing_model_timeout_invalid");
            long window = Math.max(0, timeout.toMillis() - modelActiveMillis);
            long local = Math.multiplyExact(spec.localSeconds(), 1000);
            return new ExecutionBudgetPort.ClosingTimeCalculation(spec.modelCalls(), timeout.toMillis(), window, local, Math.addExact(window, local));
        }
    }

    /**
     * 模型信息来自当前冻结准备；跨线程捕获后只能读取原值。
     * activeElapsed 仅观察当前 drive 已确认活动及单调增量，必须线程安全且不执行 I/O；不能授予预留或发送许可。
     */
    public record Binding(ExecutionBudgetPort port, RunRef run, java.util.function.Supplier<ExecutionBudgetPort.ClosingSpec> closing,
                          java.util.function.Supplier<Optional<java.time.Duration>> modelTimeout,
                          java.util.function.Supplier<Optional<ClosingSizing>> closingSizing,
                          java.util.function.Supplier<java.time.Duration> activeElapsed) {
        public Binding { Objects.requireNonNull(port); Objects.requireNonNull(run); Objects.requireNonNull(closing); Objects.requireNonNull(modelTimeout); Objects.requireNonNull(closingSizing); Objects.requireNonNull(activeElapsed); }
        public Binding(ExecutionBudgetPort port, RunRef run, java.util.function.Supplier<ExecutionBudgetPort.ClosingSpec> closing,
                       java.util.function.Supplier<Optional<java.time.Duration>> modelTimeout) {
            this(port, run, closing, modelTimeout, Optional::empty, ExecutionBudgetScope::activityUnavailable);
        }
        public Binding(ExecutionBudgetPort port, RunRef run, java.util.function.Supplier<ExecutionBudgetPort.ClosingSpec> closing) {
            this(port, run, closing, Optional::empty);
        }
        public Binding(ExecutionBudgetPort port, RunRef run) { this(port,run,() -> ExecutionBudgetPort.ClosingSpec.TEXT); }

        /** 在原执行线程冻结模型计量，避免虚拟线程读取未绑定的 checkpoint。 */
        public Binding capture() {
            var frozen = Objects.requireNonNull(modelTimeout.get());
            var sizing = Objects.requireNonNull(closingSizing.get());
            return new Binding(port, run, closing, () -> frozen, () -> sizing, activeElapsed);
        }

        /** 候选执行前完整保护收尾资源和时间；尚无阶段差异的入口采用相同未来需求。 */
        public void admitClosing(ExecutionBudgetPort.ClosingSpec prospective) {
            admitClosing(prospective, prospective);
        }

        /**
         * 宿主明确当前受理点的完整与剩余需求；成员不取得主 Run 的根收尾所有权。
         * 快照仅验证可信作用域，阶段选择和保护合并由账本原子完成，不以旧完整保护重复计算已派发调用。
         */
        public void admitClosing(ExecutionBudgetPort.ClosingSpec prospective, ExecutionBudgetPort.ClosingSpec remaining) {
            var snapshot = port.snapshot(run);
            if (!snapshot.registration().run().equals(run)) throw new IllegalStateException("budget_scope_run_mismatch");
            if (snapshot.registration().role() != ExecutionBudgetPort.Role.PRIMARY) return;
            var sizing = closingSizing.get().orElseThrow(() -> new IllegalStateException("budget_closing_sizing_required"));
            long modelActive = snapshot.closingModelActiveMillis();
            port.admitClosing(run, new ExecutionBudgetPort.ClosingAdmission(sizing.size(prospective, modelActive), sizing.size(remaining, modelActive)));
        }
    }
    /** 只传递预算身份或声明收尾的作用域不提供活动观察，缺失时拒绝，不能暗中重读完整账本。 */
    private static java.time.Duration activityUnavailable() { throw new IllegalStateException("budget_activity_observation_required"); }
    public static ExecutionBudgetScope open(Binding binding) { return new ExecutionBudgetScope(Objects.requireNonNull(binding)); }
    public static Optional<Binding> current() { return Optional.ofNullable(CURRENT.get()); }

    /** 单次timeout是上限；实际deadline不能消费后续保护或在每次收尾模型时重新取得共享窗口。 */
    public static java.time.Duration effectiveTimeout(ExecutionBudgetPort.Snapshot snapshot, ClosingSizing sizing,
            ExecutionBudgetPort.ClosingSpec spec, ExecutionBudgetPort.Category category,
            java.time.Duration configured, long unconfirmedActiveMillis) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(sizing); Objects.requireNonNull(spec); Objects.requireNonNull(category);
        if (configured.isNegative() || configured.isZero() || unconfirmedActiveMillis < 0)
            throw new IllegalArgumentException("budget_effective_timeout_invalid");
        if (!(snapshot.registration().policy() instanceof ExecutionBudgetPort.Policy.Enforced enforced)) return configured;
        var limits=enforced.limits();
        long root=Math.max(0,Math.multiplyExact(limits.rootActiveSeconds(),1000)-snapshot.activeMillis()-unconfirmedActiveMillis);
        long own=Math.max(0,Math.multiplyExact(limits.runActiveSeconds(),1000)-snapshot.runActiveMillis()-unconfirmedActiveMillis);
        long available=Math.min(root,own);
        if(snapshot.phase()==ExecutionBudgetPort.Phase.BUSINESS) available-=Math.multiplyExact(snapshot.closing().activeSeconds(),1000);
        else {
            long modelWindow=Math.max(0,sizing.timeout().toMillis()-snapshot.closingModelActiveMillis());
            if(category==ExecutionBudgetPort.Category.BUSINESS_MODEL || category==ExecutionBudgetPort.Category.AUXILIARY_MODEL)
                available=Math.min(available-Math.multiplyExact(spec.localSeconds(),1000),modelWindow/spec.modelCalls());
            else available-=modelWindow;
        }
        return java.time.Duration.ofMillis(Math.max(0,Math.min(configured.toMillis(),available)));
    }
    @Override public void close() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("budget_scope_thread_mismatch");
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}

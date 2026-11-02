package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.RunRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** 执行资源账本；不拥有 Run、Goal、权限、计费或供应商重试状态。 */
public interface ExecutionBudgetPort {
    enum Scenario { STANDARD, DELEGATION, TEAM, MAINTENANCE }
    enum Role { PRIMARY, MEMBER }
    enum Category { BUSINESS_MODEL, AUXILIARY_MODEL, TOOL, KNOWLEDGE }
    enum Dimension { BUSINESS_MODELS, AUXILIARY_MODELS, TOOLS, KNOWLEDGE, INPUT_TOKENS, OUTPUT_TOKENS, TOTAL_TOKENS }
    enum AttemptState { RESERVED, SENT, RESULT_SAVED, SETTLED }
    enum Phase { BUSINESS, CLOSING }
    enum BalanceScope { ROOT, RUN, STAGE_BASE, STAGE, CLOSING, CLOSING_RUN }
    enum Refusal { ROOT_LIMIT, RUN_LIMIT, CLOSING_REQUIRED, ACTIVE_TIME_LIMIT, REQUEST_CONFLICT }

    /** 调用次数与 Tokens 是独立维度，缓存和 reasoning 不能重复加入消费。 */
    record Resources(long businessModels, long auxiliaryModels, long tools, long knowledge,
                     long inputTokens, long outputTokens, long totalTokens) {
        public static final Resources ZERO = new Resources(0, 0, 0, 0, 0, 0, 0);
        public Resources {
            if (businessModels < 0 || auxiliaryModels < 0 || tools < 0 || knowledge < 0
                    || inputTokens < 0 || outputTokens < 0 || totalTokens < 0)
                throw new IllegalArgumentException("budget_resources_negative");
        }
        public long value(Dimension dimension) {
            return switch (dimension) {
                case BUSINESS_MODELS -> businessModels;
                case AUXILIARY_MODELS -> auxiliaryModels;
                case TOOLS -> tools;
                case KNOWLEDGE -> knowledge;
                case INPUT_TOKENS -> inputTokens;
                case OUTPUT_TOKENS -> outputTokens;
                case TOTAL_TOKENS -> totalTokens;
            };
        }
        public static Resources from(long[] values) {
            if (values.length != Dimension.values().length) throw new IllegalArgumentException("budget_dimensions_invalid");
            return new Resources(values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
        }
        public Resources plus(Resources other) {
            long[] values = new long[Dimension.values().length];
            for (var dimension : Dimension.values()) values[dimension.ordinal()] = Math.addExact(value(dimension), other.value(dimension));
            return from(values);
        }
        public boolean fits(Resources limit) {
            return firstExceededDimension(limit).isEmpty();
        }
        /** 固定按维度声明顺序报告首个超限；调用方须在同一余额锁内提供实际需求与上限。 */
        public Optional<Dimension> firstExceededDimension(Resources limit) {
            Objects.requireNonNull(limit, "limit");
            for (var dimension : Dimension.values())
                if (value(dimension) > limit.value(dimension)) return Optional.of(dimension);
            return Optional.empty();
        }
        public Resources remainingAfter(Resources used) {
            long[] values = new long[Dimension.values().length];
            for (var dimension : Dimension.values()) values[dimension.ordinal()] = Math.max(0, value(dimension) - used.value(dimension));
            return from(values);
        }
        public static Resources call(Category category, long input, long output) {
            return new Resources(category == Category.BUSINESS_MODEL ? 1 : 0,
                    category == Category.AUXILIARY_MODEL ? 1 : 0, category == Category.TOOL ? 1 : 0,
                    category == Category.KNOWLEDGE ? 1 : 0, input, output, Math.addExact(input, output));
        }
    }

    record Limits(Scenario scenario, Resources root, Resources run, Resources stage,
                  long rootActiveSeconds, long runActiveSeconds, String sourceVersion) {
        public Limits {
            Objects.requireNonNull(scenario); Objects.requireNonNull(root); Objects.requireNonNull(run);
            Objects.requireNonNull(stage); Objects.requireNonNull(sourceVersion);
            if (sourceVersion.isBlank() || rootActiveSeconds <= 0 || runActiveSeconds <= 0
                    || runActiveSeconds > rootActiveSeconds || !run.fits(root)
                    || root.inputTokens() > root.totalTokens() || root.outputTokens() > root.totalTokens()
                    || run.inputTokens() > run.totalTokens() || run.outputTokens() > run.totalTokens())
                throw new IllegalArgumentException("budget_limits_invalid");
        }
    }
    /** 累计治理与只计量是不同策略；只计量没有数值上限或隐式继承。 */
    sealed interface Policy {
        Scenario scenario();
        String sourceVersion();
        /** 仅支持有限预算的适配器和宿主须明确收窄，不把只计量转换为伪无限额度。 */
        default Limits requireEnforced() {
            if (this instanceof Enforced enforced) return enforced.limits();
            throw new IllegalArgumentException("budget_policy_not_supported");
        }
        record Enforced(Limits limits) implements Policy {
            public Enforced { Objects.requireNonNull(limits, "limits"); }
            @Override public Scenario scenario() { return limits.scenario(); }
            @Override public String sourceVersion() { return limits.sourceVersion(); }
        }
        record ObserveOnly(Scenario scenario, String sourceVersion) implements Policy {
            public ObserveOnly {
                Objects.requireNonNull(scenario, "scenario");
                if (sourceVersion == null || sourceVersion.isBlank())
                    throw new IllegalArgumentException("budget_policy_source_invalid");
            }
        }
    }
    record Registration(RunRef run, RunRef root, Role role, Policy policy) {
        public Registration {
            Objects.requireNonNull(run); Objects.requireNonNull(root); Objects.requireNonNull(role); Objects.requireNonNull(policy);
            if (!run.tenantId().equals(root.tenantId())) throw new IllegalArgumentException("budget_scope_tenant_mismatch");
        }
    }
    record Demand(RunRef run, String attemptId, String fingerprint, Category category,
                  Resources resources, boolean closing) {
        public Demand {
            Objects.requireNonNull(run); Objects.requireNonNull(category); Objects.requireNonNull(resources);
            if (attemptId == null || attemptId.isBlank() || fingerprint == null || fingerprint.isBlank())
                throw new IllegalArgumentException("budget_attempt_invalid");
        }
    }
    record Permit(Demand demand, AttemptState state) { }
    sealed interface Reservation {
        record Granted(Permit permit) implements Reservation { }
        /** ROOT/RUN 资源拒绝保留原锁内的首个超限维度，其它拒绝不猜测资源原因。 */
        record Denied(Refusal reason, Optional<Dimension> exhaustedDimension) implements Reservation {
            public Denied {
                Objects.requireNonNull(reason, "reason");
                Objects.requireNonNull(exhaustedDimension, "exhaustedDimension");
                if ((reason == Refusal.ROOT_LIMIT || reason == Refusal.RUN_LIMIT) != exhaustedDimension.isPresent())
                    throw new IllegalArgumentException("budget_refusal_dimension_invalid");
            }

            /** 执行器、辅助调用和展示共享稳定原因，不通过异常文本重建资源分类。 */
            public String reasonCode() {
                if (exhaustedDimension.isEmpty())
                    return "execution_budget_" + reason.name().toLowerCase(java.util.Locale.ROOT);
                String resource = switch (exhaustedDimension.orElseThrow()) {
                    case BUSINESS_MODELS -> "business_model_calls";
                    case AUXILIARY_MODELS -> "auxiliary_model_calls";
                    case TOOLS -> "tool_calls";
                    case KNOWLEDGE -> "knowledge_calls";
                    case INPUT_TOKENS -> "input_tokens";
                    case OUTPUT_TOKENS -> "output_tokens";
                    case TOTAL_TOKENS -> "total_tokens";
                };
                return "execution_budget_" + (reason == Refusal.ROOT_LIMIT ? "root_" : "run_") + resource + "_limit";
            }
        }
    }
    /** 每个可证明维度独立结算；缺报和结果未知不能释放对应预留。 */
    record Usage(OptionalLong inputTokens, OptionalLong outputTokens, OptionalLong totalTokens) {
        public Usage { Objects.requireNonNull(inputTokens); Objects.requireNonNull(outputTokens); Objects.requireNonNull(totalTokens); }
        public static Usage unreported() { return new Usage(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty()); }
        public static Usage reported(OptionalLong input, OptionalLong output) {
            return new Usage(input, output, input.isPresent() && output.isPresent()
                    ? OptionalLong.of(Math.addExact(input.getAsLong(), output.getAsLong())) : OptionalLong.empty());
        }
        public OptionalLong value(Dimension dimension) {
            return switch (dimension) {
                case INPUT_TOKENS -> inputTokens;
                case OUTPUT_TOKENS -> outputTokens;
                case TOTAL_TOKENS -> totalTokens;
                default -> OptionalLong.empty();
            };
        }
    }
    record Closing(Resources resources, long activeSeconds) {
        public Closing { Objects.requireNonNull(resources); if (activeSeconds < 0) throw new IllegalArgumentException("budget_closing_time_invalid"); }
        public static final Closing ZERO = new Closing(Resources.ZERO, 0);
    }

    /** 同一账本根锁内的不足项；required=已消耗+未知预留+未来需求，available与limit均为原总额度。 */
    record ClosingShortfall(BalanceScope scope, String dimension, long required, long available,
                            long deficit, long consumed, long reserved, long limit) {
        public ClosingShortfall {
            Objects.requireNonNull(scope); Objects.requireNonNull(dimension);
            if ((scope != BalanceScope.ROOT && scope != BalanceScope.RUN)
                    || !dimension.matches("[A-Z][A-Z0-9_]{0,63}") || required < 0 || available < 0
                    || deficit <= 0 || deficit != required - available || consumed < 0 || reserved < 0 || limit < 0)
                throw new IllegalArgumentException("budget_closing_shortfall_invalid");
        }
        public String unit() {
            return dimension.endsWith("_MILLIS") ? "ms" : dimension.endsWith("TOKENS") ? "tokens" : "count";
        }
    }

    /** 冻结模型超时是共享窗口上限，模型调用次数与 Token 仍分别预留。 */
    record ClosingTimeCalculation(int modelCalls, long modelTimeoutMillis, long modelWindowMillis,
                                  long localProcessingMillis, long requiredMillis) {
        public ClosingTimeCalculation {
            if (modelCalls < 1 || modelCalls > 3 || modelTimeoutMillis <= 0 || modelWindowMillis < 0
                    || modelWindowMillis > modelTimeoutMillis || localProcessingMillis < 0
                    || requiredMillis != Math.addExact(modelWindowMillis, localProcessingMillis))
                throw new IllegalArgumentException("budget_closing_time_calculation_invalid");
        }
    }

    /** 输出失败的公开安全事实；由原锁内不足项与原冻结时间公式组成，不读取当前配置补历史。 */
    record ClosingFailure(Phase phase, java.util.List<ClosingShortfall> shortfalls, ClosingTimeCalculation timeCalculation) {
        public ClosingFailure {
            Objects.requireNonNull(phase); shortfalls = java.util.List.copyOf(shortfalls); Objects.requireNonNull(timeCalculation);
            if (shortfalls.isEmpty()) throw new IllegalArgumentException("budget_closing_shortfalls_required");
        }
    }

    /** 确定的收尾准入拒绝；技术故障不能转换成本类型。 */
    final class ClosingInsufficient extends IllegalStateException {
        private final Phase phase;
        private final java.util.List<ClosingShortfall> shortfalls;
        public ClosingInsufficient(Phase phase, java.util.List<ClosingShortfall> shortfalls) {
            super("budget_closing_cannot_fit");
            this.phase = Objects.requireNonNull(phase); this.shortfalls = java.util.List.copyOf(shortfalls);
            if (shortfalls.isEmpty()) throw new IllegalArgumentException("budget_closing_shortfalls_required");
        }
        public Phase phase() { return phase; }
        public java.util.List<ClosingShortfall> shortfalls() { return shortfalls; }
        public ClosingFailure failure(ClosingTimeCalculation calculation) { return new ClosingFailure(phase, shortfalls, calculation); }
    }
    /**
     * 同一受理点在业务阶段与收尾阶段的未来最低需求；阶段由账本在根锁内选择。
     * @param prospective 尚未进入收尾时的完整保护，不得缩减既有保护
     * @param remaining 已进入收尾时从当前受理点起尚需支付的资源与活动时间；不得包含已派发调用
     */
    record ClosingAdmission(Closing prospective, Closing remaining) {
        public ClosingAdmission { Objects.requireNonNull(prospective); Objects.requireNonNull(remaining); }
    }
    /**
     * 宿主提供真实收尾事实，Session负责组装和测量；不是模型预计费用。
     * @param modelCalls 收尾模型轮次，范围 1..3
     * @param finalizationCalls 收尾工具调用次数，范围 0..2，包含必要资源停止与成果冻结
     * @param localSeconds 全部非模型收尾处理时间，单位秒，包含冻结工具超时
     * @param receiptInputUpperBound 未来各模型轮次累计读取新增回执的输入 Token 上界；仅剩一次最终模型时仍须计入
     * @param facts 宿主核证的收尾事实
     * @param toolHandles 获准收尾的精确冻结目标句柄
     */
    record ClosingSpec(int modelCalls, int finalizationCalls, long localSeconds, long receiptInputUpperBound,
                       java.util.List<String> facts, java.util.Set<String> toolHandles) {
        public ClosingSpec {
            facts = java.util.List.copyOf(facts); toolHandles = java.util.Set.copyOf(toolHandles);
            if (modelCalls < 1 || modelCalls > 3 || finalizationCalls < 0 || finalizationCalls > 2
                    || localSeconds < 0 || receiptInputUpperBound < 0) throw new IllegalArgumentException("budget_closing_spec_invalid");
        }
        public static final ClosingSpec TEXT = new ClosingSpec(1,0,1,0,java.util.List.of(),java.util.Set.of());

        /** 收尾各模型共享一个冻结超时窗口，本地处理另行保护；不足一秒向上取整。 */
        public long activeSeconds(java.time.Duration modelTimeout) {
            Objects.requireNonNull(modelTimeout);
            if (modelTimeout.isNegative() || modelTimeout.isZero())
                throw new IllegalArgumentException("budget_closing_model_timeout_invalid");
            long seconds = Math.addExact(modelTimeout.getSeconds(), modelTimeout.getNano() == 0 ? 0 : 1);
            return Math.addExact(seconds, localSeconds);
        }
    }
    record Snapshot(Registration registration, Resources consumed, Resources reserved,
                    Resources runConsumed, Resources runReserved, Closing closing,
                    Phase phase, long activeMillis, long runActiveMillis, long closingModelActiveMillis) { }
    /**
     * 本次活动确认事务内的时间观察，不携带资源余额、发送许可或其它成员的新授权。
     * @param run 已确认活动所属的完整 Run
     * @param intervalId 原驱动活动区间标识，不能跨 flight 复用
     * @param rootActiveMillis 本事务确认后的根活动区间并集，单位毫秒
     * @param runActiveMillis 本事务确认后的当前 Run 活动区间并集，单位毫秒
     * @param confirmedThrough 该区间实际持久终点，幂等较早确认仍返回原较大终点
     */
    record ActivityObservation(RunRef run, String intervalId, long rootActiveMillis, long runActiveMillis,
                               Instant confirmedThrough) {
        public ActivityObservation {
            Objects.requireNonNull(run);Objects.requireNonNull(confirmedThrough);
            if(intervalId==null||intervalId.isBlank()||rootActiveMillis<0||runActiveMillis<0||runActiveMillis>rootActiveMillis)
                throw new IllegalArgumentException("budget_activity_observation_invalid");
        }
    }

    /** 受理时冻结；重复登记内容必须相同，成员及同 Goal 续轮继承真实根。 */
    Registration register(Registration registration);
    Optional<Registration> find(RunRef run);
    Reservation reserve(Demand demand);
    /** 外部发送前在根锁内核对当前根/Run 活动时间并持久确认计次；时间耗尽、阶段拒绝或重复发送返回 false。 */
    boolean markSent(RunRef run, String attemptId);
    void saveResult(RunRef run, String attemptId, Usage usage, String resultReference);
    void settle(RunRef run, String attemptId);
    void recover(RunRef run);
    Snapshot snapshot(RunRef run);
    /** 仅主助手更新宿主测量的最低收尾保护，缩减时可归还多余保护。 */
    void updateClosing(RunRef run, Closing closing);
    /**
     * 仅主助手准入宿主测量的未来收尾需求；根锁内按当前阶段核验根及 Run 资源和时间。
     * BUSINESS 逐维保留既有保护与 prospective 的较大值；CLOSING 使用 remaining 替换先前保护。
     * 已消费与未知预留始终计入 used，不能从历史调用推算或退款；拒绝时账本保持不变。
     */
    void admitClosing(RunRef run, ClosingAdmission admission);
    /** 宿主根据冻结工具定义批准精确目标；模型及客户端不能提供此授权。 */
    void allowClosingTarget(RunRef run, String targetHandle);
    void enterClosing(RunRef run);
    /** 可信执行区间采用并集计时；同一根锁事务确认区间并返回时间观察，不重置重复、重叠或恢复前的活动。 */
    ActivityObservation recordActivity(RunRef run, String intervalId, Instant startedAt, Instant confirmedThrough);
}

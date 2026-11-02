package com.skillforge.core.foundation.runtimeconfig;

import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.Resources;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.Scenario;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.Role;
import java.math.BigInteger;
import java.util.List;

/** 宿主预算装配的纯计算策略；运营只拥有三个场景的根额度。 */
public final class ChatBudgetSettings {
    public static final String GROUP = "task_budget";
    public static final long MAX_ACTIVE_SECONDS = java.time.Duration.ofDays(7).toSeconds();
    public static final int CLOSING_OUTPUT_TOKENS = 32_768;
    public static final int SOFT_REMINDER_PERCENT = 75;
    public static final Resources STAGE = resources(256, 128, 1_024, 256, 5_000_000);
    private static final Resources MAINTENANCE = resources(0, 1_024, 0, 0, 20_000_000);
    /** 唯一可编辑档案；输入和输出计量上限均为总额度。 */
    public enum Profile {
        STANDARD_ROOT("standard_root", "普通", resources(256, 128, 1_024, 256, 8_000_000), 7_200),
        DELEGATION_ROOT("delegation_root", "协作", resources(1_024, 512, 8_192, 1_024, 32_000_000), 14_400),
        TEAM_ROOT("team_root", "团队", resources(2_048, 1_024, 16_384, 2_048, 64_000_000), 28_800);
        private final String key;
        private final String label;
        private final Resources defaults;
        private final long activeSeconds;
        Profile(String key, String label, Resources defaults, long seconds) {
            this.key = key; this.label = label; this.defaults = defaults; this.activeSeconds = seconds;
        }
        public String key() { return key; }
        public String label() { return label; }
        public Resources defaults() { return defaults; }
        public long activeSeconds() { return activeSeconds; }
        public String field(ExecutionBudgetPort.Dimension dimension) {
            return key + "_" + dimension.name().toLowerCase(java.util.Locale.ROOT);
        }
        public String activeKey() { return key + "_active_seconds"; }
        public Resources read(Snapshot snapshot) {
            long business = snapshot.longValue(field(ExecutionBudgetPort.Dimension.BUSINESS_MODELS));
            return resources(business, business / 2 + business % 2,
                    snapshot.longValue(field(ExecutionBudgetPort.Dimension.TOOLS)),
                    snapshot.longValue(field(ExecutionBudgetPort.Dimension.KNOWLEDGE)),
                    snapshot.longValue(field(ExecutionBudgetPort.Dimension.TOTAL_TOKENS)));
        }
    }
    private ChatBudgetSettings() { }
    public static List<ExecutionBudgetPort.Dimension> editableDimensions() {
        return List.of(ExecutionBudgetPort.Dimension.BUSINESS_MODELS, ExecutionBudgetPort.Dimension.TOOLS,
                ExecutionBudgetPort.Dimension.KNOWLEDGE, ExecutionBudgetPort.Dimension.TOTAL_TOKENS);
    }
    /** 首次受理从单个预算修订冻结；维护档属于代码策略。 */
    public static ExecutionBudgetPort.Limits freeze(Snapshot snapshot, Scenario scenario, Role role, AgentExecutionConfig override) {
        if (scenario == Scenario.MAINTENANCE)
            return derive(scenario, role, MAINTENANCE, 43_200, override, "budget-policy.v4:maintenance");
        Profile profile = profile(scenario);
        return derive(scenario, role, profile.read(snapshot), snapshot.longValue(profile.activeKey()),
                override, "budget-policy.v4:" + GROUP + ":" + snapshot.revision());
    }
    /** 成员只按已登记的父根派生，平台当前修订不能改变共享根。 */
    public static ExecutionBudgetPort.Limits freezeMember(ExecutionBudgetPort.Limits parent, AgentExecutionConfig override) {
        return derive(parent.scenario(), Role.MEMBER, parent.root(), parent.rootActiveSeconds(), override, parent.sourceVersion());
    }
    private static Profile profile(Scenario scenario) {
        return switch (scenario) {
            case STANDARD -> Profile.STANDARD_ROOT;
            case DELEGATION -> Profile.DELEGATION_ROOT;
            case TEAM -> Profile.TEAM_ROOT;
            case MAINTENANCE -> throw new IllegalArgumentException("maintenance_has_no_operational_profile");
        };
    }
    /** 角色比例独立于代码根默认值；覆盖不得突破真实根，阶段受最终 Run 约束。 */
    private static ExecutionBudgetPort.Limits derive(Scenario scenario, Role role, Resources root,
            long rootSeconds, AgentExecutionConfig override, String version) {
        override.validate();
        Allocation allocation = allocation(scenario, role);
        Resources inherited = allocation.apply(root);
        long total = value(override.maxTotalTokens(), inherited.totalTokens());
        Resources run = resources(value(override.maxBusinessModelCalls(), inherited.businessModels()), inherited.auxiliaryModels(),
                value(override.maxToolCalls(), inherited.tools()), value(override.maxKnowledgeCalls(), inherited.knowledge()), total);
        long inheritedSeconds = scaled(rootSeconds, 1, allocation.activeDivisor);
        long seconds = value(override.maxWallTimeSeconds(), inheritedSeconds);
        if (!run.fits(root) || seconds > rootSeconds)
            throw new IllegalArgumentException("agent_budget_exceeds_task_root");
        // 本地Agent循环只计业务模型调用；辅助调用由同一根账本独立治理。
        Math.toIntExact(run.businessModels());
        Math.toIntExact(run.tools()); Math.toIntExact(run.knowledge());
        return new ExecutionBudgetPort.Limits(scenario, root, run, cap(STAGE, run), rootSeconds, seconds, version);
    }
    private static Allocation allocation(Scenario scenario, Role role) {
        return switch (scenario) {
            case STANDARD -> role == Role.PRIMARY ? Allocation.STANDARD_PRIMARY : Allocation.STANDARD_MEMBER;
            case DELEGATION -> role == Role.PRIMARY ? Allocation.DELEGATION_PRIMARY : Allocation.DELEGATION_MEMBER;
            case TEAM -> role == Role.PRIMARY ? Allocation.TEAM_PRIMARY : Allocation.TEAM_MEMBER;
            case MAINTENANCE -> Allocation.MAINTENANCE;
        };
    }
    /** 次数和活跃时间取根的 1/divisor；token 单独使用明确分数。 */
    private enum Allocation {
        STANDARD_PRIMARY(2, 1, 2, 2, 4, 5, 1),
        STANDARD_MEMBER(2, 1, 1, 1, 2, 5, 1),
        DELEGATION_PRIMARY(2, 1, 1, 1, 4, 5, 1),
        DELEGATION_MEMBER(4, 2, 4, 4, 1, 5, 2),
        TEAM_PRIMARY(4, 2, 2, 2, 2, 5, 1),
        TEAM_MEMBER(8, 4, 8, 8, 1, 10, 3),
        MAINTENANCE(1, 1, 1, 1, 1, 1, 1);

        private final int businessDivisor;
        private final int auxiliaryDivisor;
        private final int toolDivisor;
        private final int knowledgeDivisor;
        private final int tokenNumerator;
        private final int tokenDenominator;
        private final int activeDivisor;

        Allocation(int businessDivisor, int auxiliaryDivisor, int toolDivisor, int knowledgeDivisor,
                int tokenNumerator, int tokenDenominator, int activeDivisor) {
            this.businessDivisor = businessDivisor;
            this.auxiliaryDivisor = auxiliaryDivisor;
            this.toolDivisor = toolDivisor;
            this.knowledgeDivisor = knowledgeDivisor;
            this.tokenNumerator = tokenNumerator;
            this.tokenDenominator = tokenDenominator;
            this.activeDivisor = activeDivisor;
        }

        Resources apply(Resources root) {
            return resources(scaled(root.businessModels(), 1, businessDivisor),
                    scaled(root.auxiliaryModels(), 1, auxiliaryDivisor),
                    scaled(root.tools(), 1, toolDivisor), scaled(root.knowledge(), 1, knowledgeDivisor),
                    scaled(root.totalTokens(), tokenNumerator, tokenDenominator));
        }
    }
    /** BigInteger 避免乘法溢出；正额度最低 1，始终不超根。 */
    private static long scaled(long root, long numerator, long denominator) {
        if (root == 0 || numerator == 0) return 0;
        long result = BigInteger.valueOf(root).multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator)).min(BigInteger.valueOf(root)).longValueExact();
        return Math.max(1, result);
    }
    private static Resources cap(Resources requested, Resources run) {
        long[] values = new long[ExecutionBudgetPort.Dimension.values().length];
        for (var d : ExecutionBudgetPort.Dimension.values()) values[d.ordinal()] = Math.min(requested.value(d), run.value(d));
        return Resources.from(values);
    }
    private static Resources resources(long business, long auxiliary, long tools, long knowledge, long total) {
        return new Resources(business, auxiliary, tools, knowledge, total, total, total);
    }
    private static long value(Number explicit, long inherited) { return explicit == null ? inherited : explicit.longValue(); }
}

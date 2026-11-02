package com.skillforge.runtime.session.spi;

/**
 * 冻结到模型准备配置的上下文缩减策略；版本与全部阈值共同参与准备身份。
 */
public record SessionContextPolicy(
        String version,
        long immediateToolTokens,
        int pressureToolCharacters,
        int previewHeadCharacters,
        int previewTailCharacters,
        int pressurePercent,
        int summaryTargetPercent,
        int recentPercent) {

    public static final int HISTORY_PAGE_SIZE = 100;
    /** 为 JSON 包装、引用和裁剪标记预留技术空间；实际渲染仍检查最终长度。 */
    private static final int PREVIEW_MARKER_RESERVE = 128;

    public SessionContextPolicy {
        if (version == null || version.isBlank()
                || immediateToolTokens <= 0
                || pressureToolCharacters <= 0
                || previewHeadCharacters <= 0
                || previewTailCharacters <= 0
                || (long) previewHeadCharacters + previewTailCharacters + PREVIEW_MARKER_RESERVE > pressureToolCharacters
                || recentPercent <= 0
                || recentPercent >= summaryTargetPercent
                || summaryTargetPercent >= pressurePercent
                || pressurePercent >= 100) {
            throw new IllegalArgumentException("session_context_policy_invalid");
        }
    }

    /** 小窗口按有效输入预算降低立即外置阈值；输出与安全余量已由宿主扣除。 */
    public static SessionContextPolicy forInputBudget(long budget) {
        if (budget <= 1) throw new IllegalArgumentException("model_context_capacity_insufficient");
        var policy = defaults();
        return new SessionContextPolicy(policy.version(), Math.min(policy.immediateToolTokens(), budget - 1),
                policy.pressureToolCharacters(), policy.previewHeadCharacters(), policy.previewTailCharacters(),
                policy.pressurePercent(), policy.summaryTargetPercent(), policy.recentPercent());
    }

    /** 首期已批准的生产默认策略。 */
    public static SessionContextPolicy defaults() {
        return new SessionContextPolicy(
                "context-policy.v2", 12_500, 8_192, 4_096, 1_024, 75, 50, 20);
    }
}

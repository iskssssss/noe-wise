package com.skillforge.core.foundation.settings;

/**
 * 运营配置的代码默认值。
 *
 * <p>这里只维护非提示词默认值；提示词正文与组合入口归 skillforge-prompts。</p>
 */
public final class OperationalSettingsDefaults {
    public static final int CHAT_TIMEOUT_SECONDS = 600;
    public static final int AUXILIARY_TIMEOUT_SECONDS = 300;
    public static final int HTTP_TIMEOUT_SECONDS = 60;
    /** HTTP 工具允许配置的请求时限；目录治理不得截断有效配置。 */
    public static final int HTTP_MAX_TIMEOUT_SECONDS = 3_600;
    public static final long HTTP_RESPONSE_BYTES = 16L * 1024 * 1024;


    /** 外部幂等调用最大尝试次数（含首次）。 */
    public static final int EXTERNAL_RETRY_ATTEMPTS = 3;

    /** 外部幂等调用首次重试退避。 */
    public static final long EXTERNAL_RETRY_BACKOFF_MILLISECONDS = 500;

    private OperationalSettingsDefaults() {
    }
}

package com.skillforge.core.billing.quota;

import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;

/** 配额拒绝不创建执行；渠道边界可投影友好卡片，API 保留稳定 429 和重试时间。 */
public final class QuotaExceededException extends ApiException {
    public static final String CODE = "MONTHLY_QUOTA_EXHAUSTED";
    private final Instant resetsAt;
    private final long retryAfterSeconds;

    public QuotaExceededException(Instant now, Instant resetsAt) {
        super(HttpStatus.TOO_MANY_REQUESTS, CODE, "您的月度使用配额已用尽，请等待下月恢复或联系机构管理员", true);
        this.resetsAt = resetsAt;
        this.retryAfterSeconds = Math.max(1, resetsAt.getEpochSecond() - now.getEpochSecond());
    }

    @Override
    public java.util.OptionalLong getRetryAfterSeconds() {
        return java.util.OptionalLong.of(retryAfterSeconds);
    }

    public Instant resetsAt() { return resetsAt; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}

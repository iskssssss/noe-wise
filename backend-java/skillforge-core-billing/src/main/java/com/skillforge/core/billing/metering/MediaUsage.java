package com.skillforge.core.billing.metering;

import java.math.BigDecimal;

/** 供应商实际报告的媒体计量；各字段独立可空，文件时长和请求参数不能补造报告。 */
public record MediaUsage(Long inputTokens, Long outputTokens, Long imageCount, Long videoCount,
        Long characters, BigDecimal durationSeconds) {
    public MediaUsage {
        for (Long count : new Long[]{inputTokens, outputTokens, imageCount, videoCount, characters}) {
            if (count != null && count < 0) throw new IllegalArgumentException("media_usage_negative");
        }
        if (durationSeconds != null && durationSeconds.signum() < 0)
            throw new IllegalArgumentException("media_usage_negative");
    }

    public static MediaUsage empty() { return new MediaUsage(null, null, null, null, null, null); }

    public boolean reported() {
        return inputTokens != null || outputTokens != null || imageCount != null || videoCount != null
                || characters != null || durationSeconds != null;
    }

    /** 缺失不是清零；新观察仅覆盖其实际提供的字段。 */
    public MediaUsage merge(MediaUsage later) {
        return new MediaUsage(later.inputTokens != null ? later.inputTokens : inputTokens,
                later.outputTokens != null ? later.outputTokens : outputTokens,
                later.imageCount != null ? later.imageCount : imageCount,
                later.videoCount != null ? later.videoCount : videoCount,
                later.characters != null ? later.characters : characters,
                later.durationSeconds != null ? later.durationSeconds : durationSeconds);
    }
}

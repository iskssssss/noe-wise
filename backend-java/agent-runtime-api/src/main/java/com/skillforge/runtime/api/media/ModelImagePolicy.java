package com.skillforge.runtime.api.media;

/** 冻结具体模型与协议的图片能力及实际变体计量口径。 */
public record ModelImagePolicy(boolean enabled, boolean toolImages, int maxImages,
        long maxImageBytes, long maxRequestBytes, int maxDimension, int tileSize,
        int baseTokens, int tileTokens, String version) {
    public ModelImagePolicy {
        if (version == null || version.isBlank() || maxImages < 0 || maxImageBytes < 0
                || maxRequestBytes < 0 || maxDimension < 0 || tileSize < 0
                || baseTokens < 0 || tileTokens < 0
                || enabled && (maxImages == 0 || maxImageBytes == 0 || maxRequestBytes == 0
                    || maxDimension == 0 || tileSize == 0)
                || !enabled && toolImages)
            throw new IllegalArgumentException("invalid model image policy");
    }
    /** 明确禁用视觉输入，不推测模型能力。 */
    public static ModelImagePolicy textOnly() {
        return new ModelImagePolicy(false, false, 0, 0, 0, 0, 0, 0, 0, "text-only.v1");
    }
}

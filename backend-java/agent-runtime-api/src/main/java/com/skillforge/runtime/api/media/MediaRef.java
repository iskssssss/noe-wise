package com.skillforge.runtime.api.media;

import com.skillforge.runtime.api.SessionRef;
import java.util.Objects;

/** 已持久化图片的不可变身份；引用本身不授予读取权。 */
public record MediaRef(SessionRef owner, String id, String sha256, String mimeType,
        long byteLength, int width, int height) {
    public MediaRef {
        Objects.requireNonNull(owner, "owner");
        if (id == null || id.isBlank() || sha256 == null || !sha256.matches("[a-f0-9]{64}")
                || mimeType == null || !mimeType.startsWith("image/")
                || byteLength <= 0 || width <= 0 || height <= 0)
            throw new IllegalArgumentException("invalid media reference");
    }
}

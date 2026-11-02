package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import java.util.Objects;

/** 内部安全原文的不可变身份；引用存在不代表当前读取已获授权。 */
public record ContextReference(String referenceId, SessionOwner owner, EventId sourceEventId,
                               ResultRef contentDigest, long characters, boolean sourceComplete) {
    public ContextReference {
        Objects.requireNonNull(referenceId);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(sourceEventId);
        Objects.requireNonNull(contentDigest);
        // 引用只能是服务端产生的内部键，不能携带任意 URL、浏览器票据或宿主路径。
        if (!referenceId.matches("context:[a-f0-9]{64}")
                || !contentDigest.value().matches("sha256:[a-f0-9]{64}") || characters < 0) {
            throw new IllegalArgumentException("invalid context reference");
        }
    }

    @Override public String toString() { return "ContextReference[REDACTED]"; }
}

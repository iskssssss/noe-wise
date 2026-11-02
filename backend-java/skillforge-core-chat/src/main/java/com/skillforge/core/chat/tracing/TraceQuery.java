package com.skillforge.core.chat.tracing;

import java.time.Instant;
import java.util.Set;

/** TraceView 查询契约；默认只返回脱敏摘要和来源引用。 */
public record TraceQuery(
        String rootInputId,
        Audience audience,
        Set<String> include,
        Instant from,
        Instant to) {

    public TraceQuery {
        if (rootInputId == null || rootInputId.isBlank()) {
            throw new IllegalArgumentException("rootInputId must not be blank");
        }
        audience = audience == null ? Audience.USER : audience;
        include = include == null ? Set.of() : Set.copyOf(include);
    }

    public enum Audience {
        USER,
        DEVELOPER,
        TENANT_ADMIN
    }
}

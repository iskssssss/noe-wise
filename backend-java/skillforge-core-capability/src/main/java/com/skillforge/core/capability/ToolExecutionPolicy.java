package com.skillforge.core.capability;

import java.util.Set;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;

/** 灵知 对一个 ToolVersion 施加的执行策略；与远端定义和 annotations 分离。 */
public record ToolExecutionPolicy(
        Effect effect,
        boolean idempotent,
        ConfirmationPolicy confirmation,
        long timeoutMs,
        Set<String> sensitiveFields,
        Set<String> providerRequirements, boolean parallelSafe, ToolCallIdentityPolicy callIdentityPolicy) {

    /** 普通发布的默认政策来自可信副作用声明；特殊能力在注册时显式指定。 */
    public ToolExecutionPolicy(Effect effect, boolean idempotent, ConfirmationPolicy confirmation, long timeoutMs,
            Set<String> sensitiveFields, Set<String> providerRequirements, boolean parallelSafe) {
        this(effect, idempotent, confirmation, timeoutMs, sensitiveFields, providerRequirements, parallelSafe,
                ToolCallIdentityPolicy.forEffect(ToolEffect.valueOf(effect.name())));
    }

    public ToolExecutionPolicy(Effect effect, boolean idempotent, ConfirmationPolicy confirmation, long timeoutMs,
            Set<String> sensitiveFields, Set<String> providerRequirements) {
        this(effect, idempotent, confirmation, timeoutMs, sensitiveFields, providerRequirements, false);
    }

    public enum Effect { READ_ONLY, IDEMPOTENT_WRITE, NON_IDEMPOTENT_WRITE, DESTRUCTIVE }

    public enum ConfirmationPolicy { NEVER, POLICY, ALWAYS }

    public ToolExecutionPolicy {
        java.util.Objects.requireNonNull(callIdentityPolicy, "callIdentityPolicy");
        if (parallelSafe && (effect != Effect.READ_ONLY || confirmation != ConfirmationPolicy.NEVER))
            throw new IllegalArgumentException("parallelSafe requires ordinary read-only tool without confirmation");
        if (effect == null) {
            throw new IllegalArgumentException("effect must not be null");
        }
        if (confirmation == null) {
            throw new IllegalArgumentException("confirmation must not be null");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        if (effect == Effect.READ_ONLY && confirmation == ConfirmationPolicy.ALWAYS) {
            throw new IllegalArgumentException(
                    "read-only tool must not require unconditional confirmation");
        }
        if (effect == Effect.IDEMPOTENT_WRITE && !idempotent) {
            throw new IllegalArgumentException("IDEMPOTENT_WRITE requires idempotent=true");
        }
        sensitiveFields = immutableTextSet(sensitiveFields, "sensitiveFields");
        providerRequirements = immutableTextSet(
                providerRequirements, "providerRequirements");
    }

    private static Set<String> immutableTextSet(Set<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        for (String value : values) {
            if (value == null || value.isBlank() || !value.equals(value.trim())) {
                throw new IllegalArgumentException(
                        field + " must contain normalized non-blank text");
            }
        }
        return Set.copyOf(values);
    }
}

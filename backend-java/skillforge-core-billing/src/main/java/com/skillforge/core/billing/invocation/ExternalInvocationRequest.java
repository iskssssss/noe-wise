package com.skillforge.core.billing.invocation;

import com.skillforge.core.shared.tracing.InvocationContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 进入外部调用守卫前冻结的尝试身份与因果上下文。
 *
 * <p>{@code invocationId} 标识一个逻辑动作，{@code attemptId} 标识一次实际网络尝试；
 * 两者不能相同替代。metadata 只允许脱敏、非载荷的诊断键值。</p>
 */
public record ExternalInvocationRequest(
        String invocationId,
        String attemptId,
        ExternalOperationType operationType,
        String provider,
        String service,
        String idempotencyKey,
        InvocationContext context,
        Map<String, Object> metadata) {

    public ExternalInvocationRequest {
        invocationId = requireText(invocationId, "invocationId");
        attemptId = requireText(attemptId, "attemptId");
        if (attemptId.equals(invocationId)) {
            throw new IllegalArgumentException("attemptId must identify a concrete attempt");
        }
        operationType = Objects.requireNonNull(operationType, "operationType must not be null");
        provider = requireText(provider, "provider");
        service = requireText(service, "service");
        context = Objects.requireNonNull(context, "context must not be null");
        // 允许调用边界接收任意诊断 Map；真正的持久化白名单由 Ledger 统一清洗。
        // Map.copyOf 会在此处因空键/值提前失败，从而绕过清洗语义。
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

package com.skillforge.core.model.invocation;

/** LLM 调用策略链消费的最小、脱敏输入；不携带 API key 或完整历史载荷。 */
public record ModelInvocationPolicyInput(
        String invocationId,
        String tenantId,
        String provider,
        String model,
        String callPoint,
        int historySize,
        int systemPromptChars,
        int userInputChars) {

    public ModelInvocationPolicyInput {
        invocationId = required(invocationId, "invocationId");
        tenantId = required(tenantId, "tenantId");
        provider = required(provider, "provider");
        model = required(model, "model");
        callPoint = required(callPoint, "callPoint");
        if (historySize < 0 || systemPromptChars < 0 || userInputChars < 0) {
            throw new IllegalArgumentException("model policy dimensions must be nonnegative");
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}

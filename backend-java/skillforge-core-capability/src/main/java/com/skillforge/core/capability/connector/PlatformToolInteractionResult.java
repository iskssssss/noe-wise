package com.skillforge.core.capability.connector;

import com.skillforge.runtime.api.port.ToolPortal.ToolInputRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 交互式平台工具在一次 Provider attempt 上的封闭结果。 */
public sealed interface PlatformToolInteractionResult
        permits PlatformToolInteractionResult.Completed,
                PlatformToolInteractionResult.Rejected,
                PlatformToolInteractionResult.InputRequired {

    /** 工具已完成，output 将按冻结 output schema 校验后进入模型观察。 */
    record Completed(Map<String, Object> output) implements PlatformToolInteractionResult {
        public Completed {
            output = immutable(output, "output");
        }
    }

    /** 可确定的业务拒绝；Provider 以 isError=true 返回 output，不降级成 unknown。 */
    record Rejected(Map<String, Object> output) implements PlatformToolInteractionResult {
        public Rejected {
            output = immutable(output, "output");
        }
    }

    /** 需要 Runtime 冻结请求并挂起原 Action，后续只能经 resume 恢复。 */
    record InputRequired(
            Map<String, ToolInputRequest> inputRequests,
            Optional<String> requestState) implements PlatformToolInteractionResult {
        public InputRequired {
            inputRequests = immutable(inputRequests, "inputRequests");
            requestState = Objects.requireNonNull(requestState, "requestState");
            requestState = requestState.map(value -> text(value, "requestState"));
            if (inputRequests.isEmpty() && requestState.isEmpty()) {
                throw new IllegalArgumentException("input required result must contain requests or state");
            }
        }
    }

    private static <T> Map<String, T> immutable(Map<String, T> values, String field) {
        Objects.requireNonNull(values, field);
        var copy = new LinkedHashMap<String, T>();
        values.forEach((key, value) -> {
            String normalized = text(key, field + " key");
            T prior = copy.put(normalized, Objects.requireNonNull(value, field + " value"));
            if (prior != null) {
                throw new IllegalArgumentException(field + " contains duplicate keys");
            }
        });
        return Map.copyOf(copy);
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " must be normalized text");
        }
        return value;
    }
}

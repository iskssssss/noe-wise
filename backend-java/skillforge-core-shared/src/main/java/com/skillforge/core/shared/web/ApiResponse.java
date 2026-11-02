package com.skillforge.core.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.common.context.RequestContext;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

/** 宿主 HTTP JSON 契约；不进入 Runtime API/Core，也不包装 SSE 或文件正文。 */
public record ApiResponse<T>(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "OK") String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "成功") String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonInclude(JsonInclude.Include.ALWAYS) T data,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, description = "当前请求追踪上下文标识")
        @JsonProperty("trace_id") @JsonInclude(JsonInclude.Include.ALWAYS) String traceId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean retryable,
        @JsonProperty("field_errors") @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> fieldErrors) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "成功", data, RequestContext.getTraceId(), null, Map.of());
    }

    public static ApiResponse<Void> failure(String code, String message, boolean retryable,
            Map<String, String> fields) {
        return new ApiResponse<>(code, message, null, RequestContext.getTraceId(), retryable,
                fields == null ? Map.of() : Map.copyOf(fields));
    }
}

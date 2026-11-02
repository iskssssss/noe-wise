package com.skillforge.core.shared.logging;

import org.springframework.web.client.RestClientResponseException;

/** 外部失败只保留状态和有界诊断位置，不读取消息、载荷或原始异常链。 */
public record SafeFailureDiagnostics(String exceptionType, Integer httpStatus, String stackTop) {
    private static final java.util.regex.Pattern UNSAFE_TOKEN = java.util.regex.Pattern.compile("[^a-zA-Z0-9_.$<>]");
    public static SafeFailureDiagnostics of(Throwable failure) {
        Integer status = null;
        var visited = new java.util.IdentityHashMap<Throwable, Boolean>();
        Throwable candidate = failure;
        // 弹性边界可能包装 HTTP 异常；只读取类型与状态，最多八层并阻断循环。
        for (int depth = 0; candidate != null && depth < 8 && visited.put(candidate, Boolean.TRUE) == null; depth++) {
            if (candidate instanceof RestClientResponseException http) {
                status = http.getStatusCode().value();
                break;
            }
            candidate = candidate.getCause();
        }
        String top = "";
        if (status == null && failure.getStackTrace().length > 0) {
            StackTraceElement frame = failure.getStackTrace()[0];
            top = token(frame.getClassName()) + "." + token(frame.getMethodName()) + ":" + frame.getLineNumber();
        }
        return new SafeFailureDiagnostics(token(failure.getClass().getName()), status, top);
    }
    private static String token(String value) {
        String normalized = UNSAFE_TOKEN.matcher(value).replaceAll("_");
        return normalized.substring(0, Math.min(normalized.length(), 160));
    }
}

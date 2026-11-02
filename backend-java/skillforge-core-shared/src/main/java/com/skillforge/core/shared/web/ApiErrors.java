package com.skillforge.core.shared.web;

/** 宿主 HTTP 状态对应的稳定公共错误码。 */
public final class ApiErrors {
    private ApiErrors() { }

    public static String code(int status) {
        return switch (status) {
            case 400, 422 -> "VALIDATION_ERROR";
            case 401 -> "UNAUTHORIZED";
            case 402 -> "PAYMENT_REQUIRED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 409 -> "CONFLICT";
            case 410 -> "GONE";
            case 412 -> "PRECONDITION_FAILED";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 429 -> "RATE_LIMITED";
            case 502 -> "BAD_GATEWAY";
            case 504 -> "GATEWAY_TIMEOUT";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "INTERNAL_ERROR";
        };
    }

}

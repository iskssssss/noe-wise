package com.skillforge.core.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * API 业务异常。
 *
 * <p>携带 HTTP 状态码与面向用户的提示信息，由全局异常处理器转换为统一的
 * {@code ApiResponse} 错误响应。
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    /** HTTP 状态码。 */
    private final HttpStatus status;

    /** 面向用户的错误提示。 */
    private final Object detail;

    /**
     * 构造业务异常。
     *
     * @param status HTTP 状态码
     * @param detail 面向用户的错误提示
     */
    public ApiException(HttpStatus status, String detail) {
        this(status, (Object) detail);
    }

    public ApiException(HttpStatus status, Object detail) {
        super(String.valueOf(detail));
        this.status = status;
        this.detail = detail;
        this.code = com.skillforge.core.shared.web.ApiErrors.code(status.value());
        this.retryable = status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.SERVICE_UNAVAILABLE;
    }

    /**
     * 构造业务异常（携带原始异常，保留调用链堆栈）。
     *
     * @param status HTTP 状态码
     * @param detail 面向用户的错误提示
     * @param cause  原始异常
     */
    public ApiException(HttpStatus status, String detail, Throwable cause) {
        super(String.valueOf(detail), cause);
        this.status = status;
        this.detail = detail;
        this.code = com.skillforge.core.shared.web.ApiErrors.code(status.value());
        this.retryable = status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.SERVICE_UNAVAILABLE;
    }

    /** 稳定领域原因由拥有该规则的业务边界显式提供，不能从异常文案解析。 */
    public ApiException(HttpStatus status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.detail = message;
        this.code = code;
        this.retryable = retryable;
    }

    /** 可选的 HTTP Retry-After 秒数；具体拒绝规则由异常所属领域计算。 */
    public java.util.OptionalLong getRetryAfterSeconds() { return java.util.OptionalLong.empty(); }

    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }

    /**
     * 获取 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * 获取错误提示。
     *
     * @return 错误提示文本
     */
    public Object getDetail() {
        return detail;
    }
}

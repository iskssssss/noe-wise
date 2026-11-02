package com.skillforge.core.chat.session;

/** 出站 Webhook 的稳定失败分类；不将第三方响应正文或密钥写入账本。 */
public class SessionWebhookTransportException extends Exception {

    private final String errorCode;
    private final boolean retryable;

    public SessionWebhookTransportException(String errorCode, boolean retryable) {
        super(errorCode);
        this.errorCode = errorCode == null || errorCode.isBlank() ? "WEBHOOK_DELIVERY_FAILED" : errorCode;
        this.retryable = retryable;
    }

    public SessionWebhookTransportException(String errorCode, boolean retryable, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode == null || errorCode.isBlank() ? "WEBHOOK_DELIVERY_FAILED" : errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}

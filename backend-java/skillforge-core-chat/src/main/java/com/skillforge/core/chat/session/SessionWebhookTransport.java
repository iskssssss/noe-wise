package com.skillforge.core.chat.session;


/** 将一个冻结 Session Outbox 事件发送给第三方 Webhook 的受控边界。 */
@FunctionalInterface
public interface SessionWebhookTransport {

    void deliver(SessionWebhookTarget subscription, SessionOutboxEntry output)
            throws SessionWebhookTransportException;
}

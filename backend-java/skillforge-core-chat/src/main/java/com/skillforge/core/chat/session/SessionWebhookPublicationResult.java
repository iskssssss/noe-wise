package com.skillforge.core.chat.session;

/** 一次 Webhook 投递账本尝试的受控结果。 */
public record SessionWebhookPublicationResult(Long deliveryId, Status status) {

    public enum Status {
        DELIVERED,
        RETRY_SCHEDULED,
        UNRESOLVED,
        NO_READY_DELIVERY,
        CLAIM_LOST
    }

    public static SessionWebhookPublicationResult noReadyDelivery() {
        return new SessionWebhookPublicationResult(null, Status.NO_READY_DELIVERY);
    }
}

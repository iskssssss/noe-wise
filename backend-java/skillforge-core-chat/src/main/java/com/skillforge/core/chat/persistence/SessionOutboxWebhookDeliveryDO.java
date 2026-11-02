package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** 单一 Webhook 订阅对单一 Session Outbox 事件的独立至少一次投递状态。 */
@Data
@TableName("nw_chat_session_outbox_webhook_deliveries")
public class SessionOutboxWebhookDeliveryDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long subscriptionId;
    private Long outboxEventId;
    private String eventId;
    private String sessionId;
    private Long sessionSequence;
    private String status;
    private Integer attempts;
    private Instant availableAt;
    private Instant claimedAt;
    private Instant deliveredAt;
    private String errorCode;
    private Instant createdAt;
    private Instant updatedAt;
}

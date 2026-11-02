package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** Session Outbox 的出站 Webhook 订阅；秘密字段仅保存密文。 */
@Data
@TableName("nw_chat_session_event_subscriptions")
public class SessionEventSubscriptionDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    /** null 表示当前租户的所有 Session。 */
    private String sessionId;
    private String endpointUrl;
    private String signingSecretEncrypted;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}

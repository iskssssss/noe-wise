package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.chat.session.SessionWebhookDeliveryLedger.Claim;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Webhook 投递账本 Mapper。 */
public interface SessionOutboxWebhookDeliveryMapper extends BaseMapper<SessionOutboxWebhookDeliveryDO> {
    /** 仅为本批新增 Outbox 建立独立投递责任；原唯一键重放不另建传输任务。 */
    int insertDeliveries(@Param("rows") List<SessionOutboxWebhookDeliveryDO> rows);

    /** 最早未终结行不可跳过；领取与 attempts 版本递增在同一 SQL 内完成。 */
    Claim claimNext(@Param("tenantId") long tenantId, @Param("subscriptionId") long subscriptionId,
            @Param("sessionId") String sessionId, @Param("now") Instant now,
            @Param("expiredBefore") Instant expiredBefore);

    int markDelivered(@Param("tenantId") long tenantId, @Param("claim") Claim claim,
            @Param("now") Instant now);

    int retry(@Param("tenantId") long tenantId, @Param("claim") Claim claim,
            @Param("availableAt") Instant availableAt, @Param("errorCode") String errorCode,
            @Param("now") Instant now);

    int markUnresolved(@Param("tenantId") long tenantId, @Param("claim") Claim claim,
            @Param("errorCode") String errorCode, @Param("now") Instant now);
}

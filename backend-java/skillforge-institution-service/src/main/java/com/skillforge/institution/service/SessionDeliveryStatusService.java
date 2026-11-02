package com.skillforge.institution.service;

import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryDO;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 读取已持久的渠道投递事实；不重放 Outbox，也不读取 Runtime 轨迹或事件 payload。 */
@Service
public class SessionDeliveryStatusService {

    private static final int FAILURE_TAIL_LIMIT = 10;

    private final SessionOutboxMapper outbox;

    public SessionDeliveryStatusService(SessionOutboxMapper outbox) {
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public DeliveryStatus status(String tenantId, String sessionId, String runtimeRunId, String runtimeStatus) {
        long tenant = Long.parseLong(tenantId);
        var counts = outbox.deliveryCounts(tenant, sessionId, runtimeRunId);
        List<DeliveryFailure> failures = outbox.latestDeliveryFailures(
                        tenant, sessionId, runtimeRunId, FAILURE_TAIL_LIMIT)
                .stream().map(DeliveryFailure::from).toList();
        return new DeliveryStatus(runtimeRunId, runtimeStatus,
                new SseStatus(counts.outboxTotal()>0?"replay_available":"unknown",counts.outboxTotal()),
                new WebhookStatus(webhookStatus(counts.webhookPending(), counts.webhookDelivered(), counts.webhookFailed(), counts.webhookUnknown()),
                        counts.webhookPending(), counts.webhookDelivered(), counts.webhookFailed(), counts.webhookUnknown()),
                failures);
    }

    private static String webhookStatus(long pending, long delivered, long failed, long unknown) {
        if (failed > 0) return "failed";
        if (pending > 0) return "pending";
        if (unknown > 0) return "unknown";
        if (delivered > 0) return "delivered";
        return "unknown";
    }

    /** 已提交 Outbox 即可重放；数量不证明终端已接收或已读。 */
    public record SseStatus(String status, long replayAvailableCount) { }

    /** Webhook delivered 表示订阅端确认传输，不等同于终端用户已查看内容。 */
    public record WebhookStatus(
            String status, long pendingCount, long deliveredCount, long failedCount, long unknownCount) { }

    /** 有界失败尾部只保留稳定错误码和尝试事实，不返回订阅地址、正文或凭据。 */
    public record DeliveryFailure(String eventId, Integer attempts, String errorCode, Instant updatedAt) {
        private static DeliveryFailure from(SessionOutboxWebhookDeliveryDO value) {
            return new DeliveryFailure(value.getEventId(), value.getAttempts(),
                    value.getErrorCode(), value.getUpdatedAt());
        }
    }

    public record DeliveryStatus(
            String runId, String runtimeStatus, SseStatus sse, WebhookStatus webhook,
            List<DeliveryFailure> failures) { }
}

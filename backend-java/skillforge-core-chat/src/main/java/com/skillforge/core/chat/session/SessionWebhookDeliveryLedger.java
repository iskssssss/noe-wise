package com.skillforge.core.chat.session;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryMapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import com.skillforge.core.chat.persistence.SessionOutboxEntryDO;
import com.skillforge.core.chat.persistence.SessionOutboxWebhookDeliveryDO;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import org.apache.ibatis.session.SqlSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session Outbox Webhook 的独立投递账本。
 *
 * <p>本服务不发送网络请求。它为每个活跃订阅创建幂等 delivery，并按订阅内的 session
 * sequence CAS 领取；因此 SSE 的发布状态不会吞掉 Webhook 的重试责任。</p>
 */
@Service
public class SessionWebhookDeliveryLedger {

    public static final String ACTIVE = "active";
    public static final String QUEUED = "queued";
    public static final String PUBLISHING = "publishing";
    public static final String DELIVERED = "delivered";
    public static final String UNRESOLVED = "unresolved";

    /** 单次领取的不可变身份；attempts 同时作为租约重领后的写回 fencing 版本。 */
    public record Claim(long deliveryId, long outboxEventId, int attempts) { }

    private final SessionEventSubscriptionMapper subscriptions;
    private final SessionOutboxWebhookDeliveryMapper deliveries;

    public SessionWebhookDeliveryLedger(
            SessionEventSubscriptionMapper subscriptions,
            SessionOutboxWebhookDeliveryMapper deliveries) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
    }

    /** 本批新增输出共享一次活跃订阅查询；与 Outbox 原事务一起写入独立投递责任。 */
    @Transactional(rollbackFor = Exception.class)
    void enqueueBatch(String tenantId, List<SessionOutboxEntryDO> outputs) {
        enqueue(tenantId, outputs, subscriptions, deliveries);
    }

    /**
     * 在调用方已绑定租户的原生MyBatis会话内建立投递责任；不另开事务、提交或关闭连接。
     * 与Outbox写入共享回滚边界，提交与实时通知仍由业务事务所有者负责。
     */
    void enqueueBorrowed(SqlSession session, String tenantId, List<SessionOutboxEntryDO> outputs) {
        Objects.requireNonNull(session, "session");
        if (outputs.isEmpty()) return;
        enqueue(tenantId, outputs, session.getMapper(SessionEventSubscriptionMapper.class),
                session.getMapper(SessionOutboxWebhookDeliveryMapper.class));
    }

    /** 两种事务入口共享同一范围校验、活跃订阅查询和幂等投递批量写入算法。 */
    private void enqueue(String tenantId, List<SessionOutboxEntryDO> outputs,
            SessionEventSubscriptionMapper targetsMapper, SessionOutboxWebhookDeliveryMapper deliveryMapper) {
        if (outputs.isEmpty()) return;
        long tenant = tenant(tenantId);
        String session = outputs.getFirst().getSessionId();
        for (var output : outputs) {
            if (output.getId() == null || output.getEventId() == null || output.getEventId().isBlank()
                    || output.getSessionSequence() == null || session == null || session.isBlank()
                    || !session.equals(output.getSessionId()) || !Long.valueOf(tenant).equals(output.getTenantId())) {
                throw new IllegalArgumentException("webhook_output_batch_scope_invalid");
            }
        }
        var targets = targetsMapper.activeForSession(tenant, session);
        var batch = new ArrayList<SessionOutboxWebhookDeliveryDO>();
        Instant now = Instant.now();
        for (var output : outputs) {
            for (var subscription : targets) {
                if (subscription == null || subscription.getId() == null) continue;
                var delivery = new SessionOutboxWebhookDeliveryDO();
                delivery.setId(SnowflakeIds.nextId());
                delivery.setTenantId(tenant);
                delivery.setSubscriptionId(subscription.getId());
                delivery.setOutboxEventId(output.getId());
                delivery.setEventId(output.getEventId());
                delivery.setSessionId(session);
                delivery.setSessionSequence(output.getSessionSequence());
                delivery.setStatus(QUEUED);
                delivery.setAttempts(0);
                delivery.setAvailableAt(now);
                delivery.setCreatedAt(now);
                delivery.setUpdatedAt(now);
                batch.add(delivery);
                // 单条多值语句保持在 PostgreSQL 参数上限内；所有分段仍属于原事务，失败整批回滚。
                if (batch.size() == 1000) {
                    deliveryMapper.insertDeliveries(List.copyOf(batch));
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) deliveryMapper.insertDeliveries(List.copyOf(batch));
    }

    /** 返回可被调度器安全发现的活跃订阅 ID；实际领取仍由 {@link #claimNext} CAS 完成。 */
    @Transactional(readOnly = true)
    public List<Long> listActiveSubscriptionIds(String tenantId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        long tenant = tenant(tenantId);
        return subscriptions.selectList(new QueryWrapper<SessionEventSubscriptionDO>()
                        .select("id")
                        .eq("tenant_id", tenant).eq("status", ACTIVE)
                        .orderByAsc("id").last("LIMIT " + Math.min(limit, 500)))
                .stream()
                .map(SessionEventSubscriptionDO::getId)
                .filter(id -> id != null)
                .toList();
    }

    /** 返回订阅下存在待投递事件的 Session；领取仍须使用 {@link #claimNext}。 */
    @Transactional(readOnly = true)
    public List<String> listSessionsWithPendingDelivery(String tenantId, long subscriptionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        long tenant = tenant(tenantId);
        return deliveries.selectList(new QueryWrapper<SessionOutboxWebhookDeliveryDO>()
                        .select("session_id")
                        .eq("tenant_id", tenant).eq("subscription_id", subscriptionId)
                        .in("status", QUEUED, PUBLISHING)
                        .groupBy("session_id").orderByAsc("min(session_sequence)")
                        .last("LIMIT " + Math.min(limit, 500)))
                .stream()
                .map(SessionOutboxWebhookDeliveryDO::getSessionId)
                .filter(sessionId -> sessionId != null && !sessionId.isBlank())
                .toList();
    }

    /** 领取同一订阅和会话内最早事件；后续写回必须携带原 Claim，重领后旧 Worker 失去写权。 */
    @Transactional(rollbackFor = Exception.class)
    Claim claimNext(
            String tenantId, long subscriptionId, String sessionId, Instant now, Duration lease) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        long tenant = tenant(tenantId);
        Instant effectiveNow = now == null ? Instant.now() : now;
        Duration effectiveLease = lease == null || lease.isNegative() || lease.isZero()
                ? Duration.ofMinutes(1) : lease;
        return deliveries.claimNext(tenant, subscriptionId, sessionId,
                effectiveNow, effectiveNow.minus(effectiveLease));
    }

    /** 仅原领取版本可以确认投递；返回 false 表示 claim 已失效，不得重新读取后覆盖。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean markDelivered(String tenantId, Claim claim, Instant now) {
        Instant deliveredAt = now == null ? Instant.now() : now;
        return deliveries.markDelivered(tenant(tenantId), claim, deliveredAt) > 0;
    }

    /** 仅原领取版本可以归还队列；失去 claim 的旧 Worker 不能重排新 Worker 的责任。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean retry(String tenantId, Claim claim, Instant availableAt, String errorCode) {
        Instant now = Instant.now();
        return deliveries.retry(tenant(tenantId), claim, availableAt == null ? now : availableAt, errorCode, now) > 0;
    }

    /** 仅原领取版本可将不可恢复失败终结为 unresolved，不阻塞其他订阅。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean markUnresolved(String tenantId, Claim claim, String errorCode) {
        return deliveries.markUnresolved(tenant(tenantId), claim, errorCode, Instant.now()) > 0;
    }

    private long tenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return Long.parseLong(tenantId);
    }
}

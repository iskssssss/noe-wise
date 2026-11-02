package com.skillforge.institution.scheduler;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.session.SessionOutboxRecoveryScan;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.skillforge.institution.group.GroupHistorySourcePublisher;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionLogPort;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 唯一Worker恢复钩子的公开投递叶；仅补齐持久产品Outbox，不领取Run或创建调度线程。 */
@Component
public final class SessionOutboxDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(SessionOutboxDispatcher.class);
    private static final int MAX_SESSIONS = 10;
    private final SessionOutboxRecoveryScan sessions;
    private final AgentRuntimeExecutionProjection projection;
    private final SessionLogPort humanLog;
    private final GroupHistorySourcePublisher groupSources;
    private final Map<String, SessionOutboxRecoveryScan.Cursor> cursors = new ConcurrentHashMap<>();

    public SessionOutboxDispatcher(SessionOutboxRecoveryScan sessions, AgentRuntimeExecutionProjection projection, SessionLogPort humanLog,GroupHistorySourcePublisher groupSources) {
        this.sessions = Objects.requireNonNull(sessions); this.projection = Objects.requireNonNull(projection);
        this.humanLog = Objects.requireNonNull(humanLog);
        this.groupSources=Objects.requireNonNull(groupSources);
    }

    /** Session候选轮转有界；重启重扫由Mailbox持久来源游标和稳定事件键收敛。 */
    public void recover(TenantId tenant, int limit) {
        Objects.requireNonNull(tenant);
        if (limit <= 0) throw new IllegalArgumentException("projection_recovery_limit_invalid");
        TenantContext.runWithTenant(tenant.value(), () -> {
            var page = sessions.scan(tenant.value(), cursors.get(tenant.value()), Math.min(limit, MAX_SESSIONS));
            for (var session : page.sessions()) {
                try {
                    if(session.getGroupBindingId()!=null)groupSources.reconcile(session);
                    projection.reconcile(session);
                    if(session.getGroupBindingId()==null)projection.reconcileHuman(session, humanLog);
                } catch (RuntimeException failure) {
                    // 技术失败不推进Mailbox来源游标；下轮Session轮转重试，不改执行终态。
                    LOG.warn("公开投递恢复失败 tenant={} sessionTag={} type={}", tenant.value(),
                            Integer.toHexString(session.getSessionId().hashCode()), failure.getClass().getSimpleName());
                }
            }
            cursors.put(tenant.value(), page.nextCursor());
        });
    }
}

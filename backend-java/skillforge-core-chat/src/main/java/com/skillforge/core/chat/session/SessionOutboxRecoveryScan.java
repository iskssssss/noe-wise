package com.skillforge.core.chat.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.persistence.SessionMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产品 Outbox 恢复候选扫描；数据库顺序与有效 Session 所有权均由 chat 决定。
 * 调度器只保存本进程的租户游标；重启从头扫描仍由持久投影回执和稳定事件身份收敛。
 */
@Service
public class SessionOutboxRecoveryScan {
    private final SessionMapper sessions;

    public SessionOutboxRecoveryScan(SessionMapper sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    /**
     * 在可信调度调用方已绑定的租户上下文内扫描；null 游标表示从头开始。
     * 到达空尾页时同次调用回到起点；无效所有权行仍推进内部游标，避免饿死后续候选。
     */
    @Transactional(readOnly = true)
    public Batch scan(String tenantId, Cursor cursor, int limit) {
        long tenant = Long.parseLong(tenantId);
        if (!tenantId.equals(TenantContext.getTenantId())) {
            throw new SecurityException("outbox_recovery_tenant_context_mismatch");
        }
        if (limit <= 0) throw new IllegalArgumentException("projection_recovery_limit_invalid");
        if (cursor != null && cursor.tenantId != tenant) {
            throw new SecurityException("outbox_recovery_cursor_tenant_mismatch");
        }
        long afterId = cursor == null ? 0 : cursor.afterId;
        var page = after(tenant, afterId, limit);
        if (page.isEmpty() && afterId > 0) {
            afterId = 0;
            page = after(tenant, 0, limit);
        }
        var candidates = new ArrayList<SessionView>();
        for (var session : page) {
            if (session == null || session.getId() == null) continue;
            afterId = session.getId();
            if (validOwner(tenant, session)) candidates.add(SessionService.view(session));
        }
        return new Batch(candidates, new Cursor(tenant, afterId));
    }

    private List<SessionDO> after(long tenant, long afterId, int limit) {
        var page = sessions.selectList(new LambdaQueryWrapper<SessionDO>()
                .eq(SessionDO::getTenantId, tenant).gt(SessionDO::getId, afterId)
                .orderByAsc(SessionDO::getId).last("LIMIT " + limit));
        return page == null ? List.of() : page;
    }

    private static boolean validOwner(long tenant, SessionDO session) {
        if (!Long.valueOf(tenant).equals(session.getTenantId())
                || session.getSessionId() == null || session.getSessionId().isBlank()) return false;
        // 双向组合 FK 固定真实群/Session/scope，不为群会话伪造个人 owner。
        if (session.getGroupBindingId() != null) return session.getGroupBindingId() > 0
                && session.getIntegrationId() != null && session.getBusinessScopeId() != null
                && ("group:" + session.getIntegrationId()).equals(session.getScopeType())
                && session.getGroupBindingId().toString().equals(session.getScopeId())
                && session.getEndUserId() == null && session.getAgentId() == null
                && session.getTeamId() == null && session.getChannelId() == null;
        if (session.getEndUserId() == null || session.getEndUserId() <= 0) return false;
        boolean agent = session.getAgentId() != null && session.getAgentId() > 0;
        boolean team = session.getTeamId() != null && !session.getTeamId().isBlank();
        return agent != team;
    }

    /** chat 签发的租户专属进程内游标；调用方不能构造或解释存储位置。 */
    public static final class Cursor {
        private final long tenantId;
        private final long afterId;

        private Cursor(long tenantId, long afterId) {
            this.tenantId = tenantId;
            this.afterId = afterId;
        }
    }

    /** 不可变候选快照；nextCursor 在本批候选尝试结束后用于继续轮转。 */
    public record Batch(List<SessionView> sessions, Cursor nextCursor) {
        public Batch {
            sessions = List.copyOf(sessions);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }
}

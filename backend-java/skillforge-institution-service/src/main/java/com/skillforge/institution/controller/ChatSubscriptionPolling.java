package com.skillforge.institution.controller;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionService;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 单个 SSE 订阅的轮询控制；仅记本地检查时间，不缓存权限或业务页面。 */
final class ChatSubscriptionPolling {
    /** 空闲订阅最迟每五秒重新验证当前访问权，非空页面始终在发送前验证。 */
    private static final long IDLE_ACCESS_CHECK_NANOS = 5_000_000_000L;
    private final String tenantId;
    private final String sessionId;
    private final FoundationAccess.Subject subject;
    private final SessionService sessions;
    private final SessionMailboxService mailbox;
    private final LongSupplier clock;
    /** 仅由订阅线程写入；记录最近一次成功访问检查的单调时间。 */
    private long checkedAt;
    private boolean checked;

    /** tenant/session/subject 必须来自订阅受理时已经校验的可信上下文。 */
    ChatSubscriptionPolling(String tenantId, String sessionId, FoundationAccess.Subject subject,
            SessionService sessions, SessionMailboxService mailbox, LongSupplier clock) {
        this.tenantId = Objects.requireNonNull(tenantId); this.sessionId = Objects.requireNonNull(sessionId);
        this.subject = subject;
        this.sessions = Objects.requireNonNull(sessions); this.mailbox = Objects.requireNonNull(mailbox);
        this.clock = Objects.requireNonNull(clock);
        if (subject != null && (subject.platform() || !tenantId.equals(subject.tenantId()))) {
            throw new IllegalArgumentException("chat_subscription_subject_scope_mismatch");
        }
    }

    /** 每页直接校验固定可信主体的当前会话权限；短授权返回后才发送，失败不能继续轮询。 */
    void authorized(Runnable send) {
        if (subject == null) sessions.require(tenantId, sessionId);
        else sessions.requireCurrentRead(tenantId, sessionId, subject);
        checkedAt = clock.getAsLong(); checked = true;
        send.run();
    }

    /** 内部按固定租户与会话读取候选页；内容仅在本次授权通过后交给发送端。 */
    long poll(long afterSequence, Consumer<SessionOutboxEntry> send) {
        var page = mailbox.listOutputsAfterSequence(tenantId, sessionId, afterSequence, 100);
        if (!page.isEmpty()) {
            authorized(() -> page.forEach(send));
            return page.getLast().getSessionSequence();
        }
        if (!checked || clock.getAsLong() - checkedAt >= IDLE_ACCESS_CHECK_NANOS) authorized(() -> { });
        return afterSequence;
    }
}

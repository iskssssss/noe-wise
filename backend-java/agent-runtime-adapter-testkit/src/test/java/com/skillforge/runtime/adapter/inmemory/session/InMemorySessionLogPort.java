package com.skillforge.runtime.adapter.inmemory.session;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.PortConflict;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import com.skillforge.runtime.adapter.inmemory.session.InMemorySessionOwnerState.SessionKey;
import com.skillforge.runtime.adapter.inmemory.session.InMemorySessionOwnerState.SessionStream;
import com.skillforge.runtime.adapter.inmemory.session.InMemorySessionOwnerState.StoredEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 仅供契约测试使用的进程内 SessionLog 适配器。 */
public final class InMemorySessionLogPort implements SessionLogPort {


    private static final String AUDIT_SCHEMA =
            "skillforge.runtime.inmemory-session-log-conflict.v1";

    private final InMemorySessionOwnerState state;

    /** 创建拥有独立状态的 development-only 日志。 */
    public InMemorySessionLogPort() {
        this(new InMemorySessionOwnerState());
    }

    /** 使用显式共享状态与 SessionStore 组合。 */
    public InMemorySessionLogPort(InMemorySessionOwnerState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public AppendResult append(AppendSessionEvent command) {
        Objects.requireNonNull(command, "command");
        SessionProjectionTarget target = command.target();
        SessionEventDraft draft = command.event();
        SessionOwner requestedOwner = target.owner();
        SessionKey key = SessionKey.from(requestedOwner);
        // 日志与生命周期 adapter 共用此临界区，避免不同域并发首写建立漂移 owner。
        synchronized (state.monitor) {
            SessionOwner established = state.establishedOwner(key);
            if (established != null && !established.equals(requestedOwner)) {
                return new AppendResult.Conflict(ownerConflict(key, established, requestedOwner));
            }
            SessionStream stream = state.logStreams.get(key);

            StoredEvent existing = stream == null ? null : stream.eventsById.get(draft.eventId());
            if (existing != null) {
                if (existing.target().equals(target) && existing.draft().equals(draft)) {
                    return new AppendResult.Replayed(existing.committed());
                }
                return new AppendResult.Conflict(eventConflict(key, existing, target, draft));
            }

            long sequence = stream == null ? 1 : stream.events.size() + 1L;
            CommittedSessionEvent committed = commit(target, draft, sequence);
            StoredEvent stored = new StoredEvent(target, draft, committed);
            if (stream == null) {
                state.establishOwner(requestedOwner);
                stream = new SessionStream(requestedOwner);
                state.logStreams.put(key, stream);
            }
            stream.events.add(stored);
            stream.eventsById.put(draft.eventId(), stored);
            return new AppendResult.Appended(committed);
        }
    }

    @Override
    public Optional<CommittedSessionEvent> findByEvent(SessionOwner owner, EventId eventId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(eventId, "eventId");
        synchronized (state.monitor) {
            SessionStream stream = state.logStreams.get(SessionKey.from(owner));
            if (stream == null) {
                return Optional.empty();
            }
            if (!stream.owner.equals(owner)) {
                return Optional.empty();
            }
            StoredEvent stored = stream.eventsById.get(eventId);
            return stored == null ? Optional.empty() : Optional.of(stored.committed());
        }
    }

    @Override
    public Optional<CommittedSessionEvent> findUserSource(SessionRef sourceSession, EndUserId sourceUser, EventId eventId) {
        Objects.requireNonNull(sourceSession, "sourceSession");
        Objects.requireNonNull(sourceUser, "sourceUser");
        Objects.requireNonNull(eventId, "eventId");
        synchronized (state.monitor) {
            CommittedSessionEvent found = null;
            for (SessionStream stream : state.logStreams.values()) {
                if (!stream.owner.sessionRef().equals(sourceSession)
                        || !(stream.owner.subject() instanceof SessionSubject.User user)
                        || !user.endUserId().equals(sourceUser)) continue;
                StoredEvent stored = stream.eventsById.get(eventId);
                if (stored == null) continue;
                // 同Session/user/event跨target不唯一时不选择任一来源。
                if (found != null) return Optional.empty();
                found = stored.committed();
            }
            return Optional.ofNullable(found);
        }
    }

    @Override
    public List<CommittedSessionEvent> readAfter(ReadSessionLog query) {
        Objects.requireNonNull(query, "query");
        SessionOwner owner = query.owner();
        synchronized (state.monitor) {
            SessionStream stream = state.logStreams.get(SessionKey.from(owner));
            if (stream == null) {
                return List.of();
            }
            if (!stream.owner.equals(owner)) {
                return List.of();
            }
            List<CommittedSessionEvent> page = new ArrayList<>(
                    Math.min(query.limit(), stream.events.size()));
            for (StoredEvent stored : stream.events) {
                if (stored.committed().sessionSequence() > query.afterSequence()) {
                    page.add(stored.committed());
                    if (page.size() == query.limit()) {
                        break;
                    }
                }
            }
            return List.copyOf(page);
        }
    }

    private static CommittedSessionEvent commit(
            SessionProjectionTarget target, SessionEventDraft draft, long sequence) {
        return new CommittedSessionEvent(
                target.owner(),
                target.inputId(),
                target.runRef(),
                target.executionRef(),
                target.actionRef(),
                target.delegationRef(),
                draft.eventId(),
                sequence,
                draft.eventType(),
                draft.eventVersion(),
                draft.trace(),
                draft.payload(),
                draft.occurredAt());
    }

    private static PortConflict ownerConflict(
            SessionKey key, SessionOwner storedOwner, SessionOwner requestedOwner) {
        String resourceId = key.resourceId();
        return new PortConflict(
                "session_owner",
                resourceId,
                auditRef("session_owner", resourceId, storedOwner, requestedOwner));
    }

    private static PortConflict eventConflict(
            SessionKey key,
            StoredEvent stored,
            SessionProjectionTarget requestedTarget,
            SessionEventDraft requestedDraft) {
        String resourceId = key.resourceId() + "/" + requestedDraft.eventId().value();
        return new PortConflict(
                "session_event",
                resourceId,
                auditRef(
                        "session_event",
                        resourceId,
                        stored.target(),
                        stored.draft(),
                        requestedTarget,
                        requestedDraft));
    }

    /** 只暴露固定长度摘要；冲突输入（包括 payload）不进入可见审计引用。 */
    private static ResultRef auditRef(String kind, String resourceId, Object... facts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, AUDIT_SCHEMA);
            updateDigest(digest, kind);
            updateDigest(digest, resourceId);
            for (Object fact : facts) {
                updateDigest(digest, Objects.toString(fact));
            }
            return new ResultRef(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    /** 长度前缀消除字段拼接歧义，使相同冲突事实始终得到同一摘要。 */
    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

}

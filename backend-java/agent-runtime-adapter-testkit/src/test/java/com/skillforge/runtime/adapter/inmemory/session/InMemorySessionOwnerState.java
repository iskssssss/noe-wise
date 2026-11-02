package com.skillforge.runtime.adapter.inmemory.session;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * development 适配器显式共享的 Session owner 与日志 sequence 状态。
 *
 * <p>实例作用域代替静态全局状态，使 SessionLog 与 SessionStore
 * 在同一临界区内冻结完整 owner；该对象不提供跨进程耐久性。
 */
public final class InMemorySessionOwnerState {

    final Object monitor = new Object();
    final Map<SessionKey, SessionOwner> owners = new LinkedHashMap<>();
    final Map<SessionKey, SessionStream> logStreams = new LinkedHashMap<>();
    private final Map<Class<?>, Object> adapterStates = new LinkedHashMap<>();

    <T> T adapterState(Class<T> type, Supplier<T> factory) {
        return type.cast(adapterStates.computeIfAbsent(type, ignored -> factory.get()));
    }

    SessionOwner establishedOwner(SessionKey key) {
        return owners.get(key);
    }

    void establishOwner(SessionOwner owner) {
        SessionKey key = SessionKey.from(owner);
        SessionOwner previous = owners.putIfAbsent(key, owner);
        if (previous != null && !previous.equals(owner)) {
            throw new IllegalStateException("inmemory_session_owner_mismatch");
        }
    }

    long lastSequence(SessionOwner owner) {
        SessionKey key = SessionKey.from(owner);
        SessionOwner established = owners.get(key);
        if (established == null || !established.equals(owner)) {
            throw new IllegalStateException("inmemory_session_owner_mismatch");
        }
        SessionStream stream = logStreams.get(key);
        if (stream == null) {
            return 0;
        }
        if (!stream.owner.equals(owner)) {
            throw new IllegalStateException("inmemory_session_log_owner_mismatch");
        }
        return stream.events.size();
    }

    record SessionKey(TenantId tenantId, AgentId agentId, String sessionId) {

        static SessionKey from(SessionOwner owner) {
            return new SessionKey(
                    owner.sessionRef().tenantId(), owner.target().requireAgent(),
                    owner.sessionRef().sessionId());
        }

        String resourceId() {
            return tenantId.value() + "/" + agentId.value() + "/" + sessionId;
        }
    }

    static final class SessionStream {

        final SessionOwner owner;
        final List<StoredEvent> events = new ArrayList<>();
        final Map<EventId, StoredEvent> eventsById = new LinkedHashMap<>();

        SessionStream(SessionOwner owner) {
            this.owner = owner;
        }
    }

    record StoredEvent(
            SessionProjectionTarget target,
            SessionEventDraft draft,
            CommittedSessionEvent committed) {
    }
}

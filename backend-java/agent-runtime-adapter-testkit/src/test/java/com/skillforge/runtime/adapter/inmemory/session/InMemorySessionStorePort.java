package com.skillforge.runtime.adapter.inmemory.session;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionStorePort;
import com.skillforge.runtime.adapter.inmemory.session.InMemorySessionOwnerState.SessionKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 仅供契约测试使用的进程内 {@link SessionStorePort}。 */
public final class InMemorySessionStorePort implements SessionStorePort {


    private static final String HASH_SCHEMA =
            "skillforge.runtime.inmemory-session-store.v1";
    private static final long OWNER_ROOT_CONFLICT_REVISION = 1L;

    private final Clock clock;
    private final InMemorySessionOwnerState ownerState;
    private final StoreState storeState;

    /** 使用 UTC 系统时钟与独立 development 状态。 */
    public InMemorySessionStorePort() {
        this(Clock.systemUTC(), new InMemorySessionOwnerState());
    }

    /** 使用显式共享 owner/log 状态与 UTC 系统时钟。 */
    public InMemorySessionStorePort(InMemorySessionOwnerState ownerState) {
        this(Clock.systemUTC(), ownerState);
    }

    /** 使用可控存储时钟和显式共享状态。 */
    public InMemorySessionStorePort(
            Clock clock, InMemorySessionOwnerState ownerState) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownerState = Objects.requireNonNull(ownerState, "ownerState");
        synchronized (ownerState.monitor) {
            storeState = ownerState.adapterState(StoreState.class, StoreState::new);
        }
    }

    @Override
    public SessionCreateResult create(CreateSession command) {
        Objects.requireNonNull(command, "command");
        synchronized (ownerState.monitor) {
            SessionKey key = SessionKey.from(command.owner());
            StoredSession existing = storeState.sessions.get(key);
            if (existing != null) {
                if (existing.createCommand.equals(command)) {
                    return new SessionCreateResult.Replayed(current(existing));
                }
                return createConflict(existing.revision, key, existing.createCommand, command);
            }

            SessionOwner established = ownerState.establishedOwner(key);
            if (established != null && !established.equals(command.owner())) {
                return createConflict(
                        OWNER_ROOT_CONFLICT_REVISION, key, established, command);
            }

            Instant now = storageNow();
            StoredSession created = new StoredSession(
                    command, SessionStatus.CREATED, 1, now, now);
            ownerState.establishOwner(command.owner());
            storeState.sessions.put(key, created);
            return new SessionCreateResult.Created(current(created));
        }
    }

    @Override
    public Optional<SessionRecord> find(SessionLookup query) {
        Objects.requireNonNull(query, "query");
        synchronized (ownerState.monitor) {
            StoredSession stored = storeState.sessions.get(SessionKey.from(query.owner()));
            if (stored == null || !stored.owner().equals(query.owner())) {
                return Optional.empty();
            }
            return Optional.of(current(stored));
        }
    }

    @Override
    public SessionCasResult compareAndSet(ChangeSessionStatus command) {
        Objects.requireNonNull(command, "command");
        synchronized (ownerState.monitor) {
            SessionKey key = SessionKey.from(command.owner());
            StoredSession stored = storeState.sessions.get(key);
            if (stored == null || !stored.owner().equals(command.owner())) {
                return new SessionCasResult.NotFound(notFound(command.owner()));
            }

            OperationKey operationKey = OperationKey.from(command);
            String fingerprint = fingerprint(command);
            StoredMutation mutation = storeState.mutations.get(operationKey);
            if (mutation != null) {
                if (mutation.command.equals(command)
                        && mutation.commandFingerprint.equals(fingerprint)
                        && mutation.matches(stored)) {
                    return new SessionCasResult.Replayed(current(stored));
                }
                return casConflict(
                        stored, key, mutationConflictFingerprint(mutation, stored), fingerprint);
            }

            if (stored.status != command.expectedStatus()
                    || stored.revision != command.expectedRevision()) {
                return casConflict(stored, key, lifecycleFingerprint(stored), fingerprint);
            }

            Instant now = storageNow();
            if (now.isBefore(stored.updatedAt)) {
                throw new SessionStoreStorageException(
                        new IllegalStateException("inmemory_session_store_clock_regressed"));
            }
            StoredSession updated = stored.advance(command.targetStatus(), now);
            SessionRecord result = current(updated);
            StoredMutation applied = new StoredMutation(
                    command, fingerprint, updated.status, updated.revision, updated.updatedAt);
            storeState.sessions.put(key, updated);
            storeState.mutations.put(operationKey, applied);
            return new SessionCasResult.Applied(result);
        }
    }

    private SessionRecord current(StoredSession stored) {
        try {
            return new SessionRecord(
                    stored.owner(), stored.createCommand.channel(),
                    stored.createCommand.scopeKey(), stored.createCommand.personaVersion(),
                    stored.status, stored.revision,
                    ownerState.lastSequence(stored.owner()),
                    stored.createdAt, stored.updatedAt);
        } catch (RuntimeException cause) {
            if (cause instanceof SessionStoreStorageException) {
                throw cause;
            }
            throw new SessionStoreStorageException(cause);
        }
    }

    private SessionCreateResult.Conflict createConflict(
            long currentRevision, SessionKey key, Object stored, CreateSession requested) {
        String storedFingerprint = stored instanceof CreateSession create
                ? fingerprint(create)
                : fingerprint((SessionOwner) stored);
        return new SessionCreateResult.Conflict(
                currentRevision,
                audit("create", key.resourceId(), storedFingerprint, fingerprint(requested)));
    }

    private SessionCasResult.Conflict casConflict(
            StoredSession stored,
            SessionKey key,
            String storedFingerprint,
            String requestedFingerprint) {
        return new SessionCasResult.Conflict(
                current(stored),
                audit("cas", key.resourceId(), storedFingerprint, requestedFingerprint));
    }

    private ResultRef audit(
            String kind,
            String resourceId,
            String storedFingerprint,
            String requestedFingerprint) {
        String digest = hash(
                "conflict", kind, resourceId, storedFingerprint, requestedFingerprint);
        return storeState.conflictAudits.computeIfAbsent(
                digest,
                ignored -> new ResultRef("session-store-conflict:sha256:" + digest));
    }

    private static PortFailure notFound(SessionOwner owner) {
        return new PortFailure(
                "session_not_found", RetryMode.NONE,
                new ResultRef("session-store-not-found:sha256:"
                        + hash("not-found", SessionKey.from(owner).resourceId())));
    }

    private Instant storageNow() {
        try {
            return Objects.requireNonNull(clock.instant(), "clock.instant()");
        } catch (RuntimeException cause) {
            if (cause instanceof SessionStoreStorageException) {
                throw cause;
            }
            throw new SessionStoreStorageException(cause);
        }
    }

    private static String fingerprint(CreateSession command) {
        SessionOwner owner = command.owner();
        return hash(
                "create-command",
                owner.sessionRef().tenantId().value(), owner.target().requireAgent().value(),
                owner.sessionRef().sessionId(), Long.toString(owner.requireUser().value()),
                command.channel(), command.scopeKey(), command.personaVersion(),
                command.causationInputId().value(),
                command.trace().traceId(), command.trace().spanId(),
                command.trace().rootInputId().value(),
                command.trace().causedByEventId().isPresent() ? "present" : "absent",
                command.trace().causedByEventId().map(event -> event.value()).orElse(""),
                Long.toString(command.requestedAt().getEpochSecond()),
                Integer.toString(command.requestedAt().getNano()));
    }

    private static String fingerprint(ChangeSessionStatus command) {
        SessionOwner owner = command.owner();
        return hash(
                "cas-command",
                owner.sessionRef().tenantId().value(), owner.target().requireAgent().value(),
                owner.sessionRef().sessionId(), Long.toString(owner.requireUser().value()),
                command.expectedStatus().name(), command.targetStatus().name(),
                Long.toString(command.expectedRevision()), command.causationInputId().value(),
                command.trace().traceId(), command.trace().spanId(),
                command.trace().rootInputId().value(),
                command.trace().causedByEventId().isPresent() ? "present" : "absent",
                command.trace().causedByEventId().map(event -> event.value()).orElse(""),
                Long.toString(command.requestedAt().getEpochSecond()),
                Integer.toString(command.requestedAt().getNano()));
    }

    private static String fingerprint(SessionOwner owner) {
        return hash(
                "owner-root",
                owner.sessionRef().tenantId().value(), owner.target().requireAgent().value(),
                owner.sessionRef().sessionId(), Long.toString(owner.requireUser().value()));
    }

    private static String lifecycleFingerprint(StoredSession session) {
        return hash(
                "lifecycle", fingerprint(session.createCommand), session.status.name(),
                Long.toString(session.revision),
                Long.toString(session.createdAt.getEpochSecond()),
                Integer.toString(session.createdAt.getNano()),
                Long.toString(session.updatedAt.getEpochSecond()),
                Integer.toString(session.updatedAt.getNano()));
    }

    private static String mutationConflictFingerprint(
            StoredMutation mutation, StoredSession current) {
        return hash(
                "journal-current", mutation.commandFingerprint,
                mutation.outputStatus.name(), Long.toString(mutation.outputRevision),
                Long.toString(mutation.outputUpdatedAt.getEpochSecond()),
                Integer.toString(mutation.outputUpdatedAt.getNano()),
                lifecycleFingerprint(current));
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, HASH_SCHEMA);
            for (String value : values) {
                update(digest, value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static final class StoredSession {

        private final CreateSession createCommand;
        private final SessionStatus status;
        private final long revision;
        private final Instant createdAt;
        private final Instant updatedAt;

        private StoredSession(
                CreateSession createCommand,
                SessionStatus status,
                long revision,
                Instant createdAt,
                Instant updatedAt) {
            this.createCommand = createCommand;
            this.status = status;
            this.revision = revision;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        private SessionOwner owner() {
            return createCommand.owner();
        }

        private StoredSession advance(SessionStatus targetStatus, Instant updatedAt) {
            return new StoredSession(
                    createCommand, targetStatus, Math.incrementExact(revision),
                    createdAt, updatedAt);
        }
    }

    private static final class StoreState {

        private final Map<SessionKey, StoredSession> sessions = new LinkedHashMap<>();
        private final Map<OperationKey, StoredMutation> mutations = new LinkedHashMap<>();
        private final Map<String, ResultRef> conflictAudits = new LinkedHashMap<>();
    }

    /** 只向同 package 的准入事务暴露不可变状态/revision，不暴露 Session 配置。 */
    private record OperationKey(
            SessionOwner owner,
            SessionStatus expectedStatus,
            SessionStatus targetStatus,
            long expectedRevision,
            com.skillforge.runtime.api.InputId causationInputId) {

        private static OperationKey from(ChangeSessionStatus command) {
            return new OperationKey(
                    command.owner(), command.expectedStatus(), command.targetStatus(),
                    command.expectedRevision(), command.causationInputId());
        }
    }

    private record StoredMutation(
            ChangeSessionStatus command,
            String commandFingerprint,
            SessionStatus outputStatus,
            long outputRevision,
            Instant outputUpdatedAt) {

        private boolean matches(StoredSession session) {
            return session.status == outputStatus
                    && session.revision == outputRevision
                    && session.updatedAt.equals(outputUpdatedAt);
        }
    }

    /** 保留内部原因链，并对 development 宿主暴露与 JDBC 一致的稳定错误码。 */
    private static final class SessionStoreStorageException extends IllegalStateException {

        private SessionStoreStorageException(Throwable cause) {
            super("session_store_storage_failure", cause);
        }
    }
}

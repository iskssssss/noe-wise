package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionStorePort;
import com.skillforge.runtime.api.port.SessionStorePort.ChangeSessionStatus;
import com.skillforge.runtime.api.port.SessionStorePort.CreateSession;
import com.skillforge.runtime.api.port.SessionStorePort.SessionCasResult;
import com.skillforge.runtime.api.port.SessionStorePort.SessionCreateResult;
import com.skillforge.runtime.api.port.SessionStorePort.SessionLookup;
import com.skillforge.runtime.api.port.SessionStorePort.SessionRecord;
import com.skillforge.runtime.api.port.SessionStorePort.SessionStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 基于 PostgreSQL owner root、CAS journal 与 RLS 的 {@link SessionStorePort}。 */
public final class JdbcSessionStorePort implements SessionStorePort {

    private static final String MAPPER_RESOURCE =
            "mapper/AgentRuntimeSessionStoreMapper.xml";
    private static final long OWNER_ROOT_CONFLICT_REVISION = 1L;

    private final SqlSessionFactory sessionFactory;

    /**
     * 从宿主连接池创建 JDBC 适配器。
     *
     * <p>DataSource 必须使用受 RLS 约束的非 superuser、NOBYPASSRLS 角色。
     */
    public JdbcSessionStorePort(DataSource dataSource) {
        sessionFactory = buildSessionFactory(
                Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public SessionCreateResult create(CreateSession command) {
        Objects.requireNonNull(command, "command");
        String commandData = SessionPersistenceCodec.encode(command);
        String fingerprint = SessionStoreHashes.fingerprint("create", commandData);
        return inTransaction(command.owner().sessionRef().tenantId(),
                mapper -> create(mapper, command, commandData, fingerprint));
    }

    @Override
    public Optional<SessionRecord> find(SessionLookup query) {
        Objects.requireNonNull(query, "query");
        SessionOwner owner = query.owner();
        return inTransaction(owner.sessionRef().tenantId(), mapper -> {
            SessionStoreRow stored = mapper.findSession(
                    tenant(owner), targetKind(owner), targetId(owner), session(owner));
            if (stored == null) {
                return Optional.empty();
            }
            SessionRecord current = stored.toRecord();
            return current.owner().equals(owner) ? Optional.of(current) : Optional.empty();
        });
    }

    @Override
    public SessionCasResult compareAndSet(ChangeSessionStatus command) {
        Objects.requireNonNull(command, "command");
        String commandData = SessionPersistenceCodec.encode(command);
        String fingerprint = SessionStoreHashes.fingerprint("cas", commandData);
        return inTransaction(command.owner().sessionRef().tenantId(),
                mapper -> compareAndSet(mapper, command, commandData, fingerprint));
    }

    private static SessionCreateResult create(
            SessionStoreMapper mapper,
            CreateSession command,
            String commandData,
            String fingerprint) {
        SessionOwner owner = command.owner();
        mapper.ensureOwner(SessionOwnerRow.initial(owner));
        SessionOwnerRow root = mapper.lockOwner(
                tenant(owner), targetKind(owner), targetId(owner), session(owner));
        if (root == null) {
            throw new IllegalStateException("session_owner_root_not_visible");
        }
        SessionOwner establishedOwner = root.validatedStoreOwner();

        SessionStoreRow existing = mapper.lockSession(
                tenant(owner), targetKind(owner), targetId(owner), session(owner));
        if (!establishedOwner.equals(owner)) {
            long revision = existing == null
                    ? OWNER_ROOT_CONFLICT_REVISION
                    : existing.getSessionRevision();
            String storedFingerprint = existing == null
                    ? SessionStoreHashes.fingerprint(
                            "owner-root", SessionPersistenceCodec.encode(establishedOwner))
                    : existing.validatedCreateFingerprint();
            return createConflict(
                    mapper, owner, revision, storedFingerprint, fingerprint);
        }

        if (existing != null) {
            existing.toRecord();
            if (existing.matchesCreate(command, commandData, fingerprint)) {
                return new SessionCreateResult.Replayed(existing.toRecord());
            }
            return createConflict(
                    mapper, owner, existing.getSessionRevision(),
                    existing.validatedCreateFingerprint(), fingerprint);
        }

        SessionStorageTimeRow now = mapper.storageNow();
        SessionStoreRow inserted = SessionStoreRow.initial(
                command, commandData, fingerprint, now.toInstant());
        if (mapper.insertSession(inserted) != 1) {
            throw new IllegalStateException("session_create_not_applied");
        }
        SessionStoreRow current = mapper.findSession(
                tenant(owner), targetKind(owner), targetId(owner), session(owner));
        if (current == null) {
            throw new IllegalStateException("created_session_not_visible");
        }
        return new SessionCreateResult.Created(current.toRecord());
    }

    private static SessionCasResult compareAndSet(
            SessionStoreMapper mapper,
            ChangeSessionStatus command,
            String commandData,
            String fingerprint) {
        SessionOwner owner = command.owner();
        // 与 create 一致先锁 owner root，避免 journal 外键检查与 Session 行锁形成环路。
        SessionOwnerRow root = mapper.lockOwner(
                tenant(owner), targetKind(owner), targetId(owner), session(owner));
        if (root == null || !root.validatedStoreOwner().equals(owner)) {
            return new SessionCasResult.NotFound(notFound(owner));
        }
        SessionStoreRow current = mapper.lockSession(
                tenant(owner), targetKind(owner), targetId(owner), session(owner));
        if (current == null) {
            return new SessionCasResult.NotFound(notFound(owner));
        }
        SessionRecord currentRecord = current.toRecord();
        if (!currentRecord.owner().equals(owner)) {
            return new SessionCasResult.NotFound(notFound(owner));
        }

        SessionStoreMutationRow journal = mapper.findMutation(
                tenant(owner), targetKind(owner), targetId(owner), session(owner), endUser(owner),
                command.expectedStatus().name(), command.targetStatus().name(),
                command.expectedRevision(), command.causationInputId().value());
        if (journal != null) {
            journal.validate(command);
            if (journal.getCommandFingerprint().equals(fingerprint)
                    && journal.getCommandData().equals(commandData)
                    && journal.matches(current)) {
                return new SessionCasResult.Replayed(current.toRecord());
            }
            String stored = SessionStoreHashes.fingerprint(
                    "journal-current", journal.getCommandFingerprint(),
                    current.lifecycleFingerprint());
            return casConflict(mapper, current, fingerprint, stored);
        }

        if (!current.getStatus().equals(command.expectedStatus().name())
                || current.getSessionRevision() != command.expectedRevision()) {
            return casConflict(
                    mapper, current, fingerprint, current.lifecycleFingerprint());
        }

        SessionStorageTimeRow now = mapper.storageNow();
        long outputRevision = Math.incrementExact(command.expectedRevision());
        SessionStoreMutationRow mutation = SessionStoreMutationRow.from(
                command, commandData, fingerprint, outputRevision, now.toInstant());
        if (mapper.insertMutation(mutation) != 1) {
            throw new IllegalStateException("session_mutation_journal_not_inserted");
        }
        if (mapper.updateSession(
                tenant(owner), targetKind(owner), targetId(owner), session(owner), endUser(owner),
                command.expectedStatus().name(), command.targetStatus().name(),
                command.expectedRevision(), outputRevision,
                now.getEpochSecond(), now.getNano()) != 1) {
            throw new IllegalStateException("session_cas_not_applied");
        }
        SessionStoreRow updated = mapper.findSession(
                tenant(owner), targetKind(owner), targetId(owner), session(owner));
        if (updated == null) {
            throw new IllegalStateException("updated_session_not_visible");
        }
        return new SessionCasResult.Applied(updated.toRecord());
    }

    private static SessionCreateResult.Conflict createConflict(
            SessionStoreMapper mapper,
            SessionOwner owner,
            long currentRevision,
            String storedFingerprint,
            String requestedFingerprint) {
        ResultRef auditRef = persistConflict(
                mapper, owner, "CREATE", currentRevision,
                storedFingerprint, requestedFingerprint);
        return new SessionCreateResult.Conflict(currentRevision, auditRef);
    }

    private static SessionCasResult.Conflict casConflict(
            SessionStoreMapper mapper,
            SessionStoreRow current,
            String requestedFingerprint,
            String storedFingerprint) {
        SessionOwner owner = current.toOwner();
        ResultRef auditRef = persistConflict(
                mapper, owner, "CAS", current.getSessionRevision(),
                storedFingerprint, requestedFingerprint);
        return new SessionCasResult.Conflict(current.toRecord(), auditRef);
    }

    private static ResultRef persistConflict(
            SessionStoreMapper mapper,
            SessionOwner owner,
            String kind,
            long currentRevision,
            String storedFingerprint,
            String requestedFingerprint) {
        String resourceHash = SessionStoreHashes.fingerprint(
                "resource", tenant(owner), targetKind(owner), targetId(owner), session(owner));
        String digest = SessionStoreHashes.fingerprint(
                "conflict", kind, resourceHash, storedFingerprint, requestedFingerprint);
        ResultRef auditRef = new ResultRef(
                "session-store-conflict:sha256:" + digest);
        SessionConflictAuditRow audit = new SessionConflictAuditRow(
                tenant(owner), auditRef.value(), resourceHash, kind,
                currentRevision, storedFingerprint, requestedFingerprint);
        mapper.insertConflictAudit(audit);
        return auditRef;
    }

    private static PortFailure notFound(SessionOwner owner) {
        String digest = SessionStoreHashes.fingerprint(
                "not-found", tenant(owner), targetKind(owner), targetId(owner), session(owner));
        return new PortFailure(
                "session_not_found", RetryMode.NONE,
                new ResultRef("session-store-not-found:sha256:" + digest));
    }

    private <T> T inTransaction(TenantId tenantId, MapperWork<T> work) {
        try (SqlSession sqlSession = sessionFactory.openSession(false)) {
            try {
                SessionStoreMapper mapper = sqlSession.getMapper(SessionStoreMapper.class);
                mapper.bindTenant(tenantId.value());
                T result = work.execute(mapper);
                sqlSession.commit();
                return result;
            } catch (RuntimeException failure) {
                try {
                    sqlSession.rollback();
                } catch (RuntimeException rollbackFailure) {
                    if (rollbackFailure != failure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw storageFailure(failure);
            }
        } catch (RuntimeException failure) {
            if (failure instanceof SessionStoreStorageException) {
                throw failure;
            }
            throw storageFailure(failure);
        }
    }

    private static SqlSessionFactory buildSessionFactory(DataSource dataSource) {
        Environment environment = new Environment(
                "agent-runtime-session-store", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream mapperXml = JdbcSessionStorePort.class.getClassLoader()
                .getResourceAsStream(MAPPER_RESOURCE)) {
            if (mapperXml == null) {
                throw new IllegalStateException("session_store_mapper_missing");
            }
            new XMLMapperBuilder(
                    mapperXml, configuration, MAPPER_RESOURCE,
                    configuration.getSqlFragments()).parse();
            return new SqlSessionFactoryBuilder().build(configuration);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(
                    "session_store_adapter_initialization_failure", failure);
        }
    }

    private static SessionStoreStorageException storageFailure(Throwable cause) {
        return new SessionStoreStorageException(cause);
    }

    private static String tenant(SessionOwner owner) {
        return owner.sessionRef().tenantId().value();
    }

    private static String session(SessionOwner owner) {
        return owner.sessionRef().sessionId();
    }

    private static String targetKind(SessionOwner owner) {
        return owner.target().kind().name();
    }

    private static String targetId(SessionOwner owner) {
        return owner.target().id();
    }

    private static long endUser(SessionOwner owner) {
        return owner.requireUser().value();
    }

    @FunctionalInterface
    private interface MapperWork<T> {
        T execute(SessionStoreMapper mapper);
    }

    /** 保留技术原因链，但只跨 Port 暴露稳定存储失败码。 */
    private static final class SessionStoreStorageException extends IllegalStateException {

        private SessionStoreStorageException(Throwable cause) {
            super("session_store_storage_failure", cause);
        }
    }
}

/** SQL 唯一维护在 AgentRuntimeSessionStoreMapper.xml。 */
interface SessionStoreMapper {

    String bindTenant(@Param("tenantId") String tenantId);
    SessionStorageTimeRow storageNow();
    int ensureOwner(SessionOwnerRow owner);
    SessionOwnerRow lockOwner(
            @Param("tenantId") String tenantId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("sessionId") String sessionId);
    SessionStoreRow lockSession(
            @Param("tenantId") String tenantId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("sessionId") String sessionId);
    SessionStoreRow findSession(
            @Param("tenantId") String tenantId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("sessionId") String sessionId);
    java.util.List<String> findActiveRunIds(
            @Param("tenantId") String tenantId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("sessionId") String sessionId,
            @Param("endUserId") long endUserId);
    int insertSession(SessionStoreRow row);
    int updateSession(
            @Param("tenantId") String tenantId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("sessionId") String sessionId,
            @Param("endUserId") long endUserId,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus,
            @Param("expectedRevision") long expectedRevision,
            @Param("outputRevision") long outputRevision,
            @Param("updatedEpochSecond") long updatedEpochSecond,
            @Param("updatedNano") int updatedNano);
    SessionStoreMutationRow findMutation(
            @Param("tenantId") String tenantId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("sessionId") String sessionId,
            @Param("endUserId") long endUserId,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus,
            @Param("expectedRevision") long expectedRevision,
            @Param("causationInputId") String causationInputId);
    int insertMutation(SessionStoreMutationRow row);
    int insertConflictAudit(SessionConflictAuditRow row);
}

/** Session 行与 V93 owner/stream 的同一查询快照。 */
final class SessionStoreRow {

    private String tenantId;
    private String sessionId;
    private String targetKind;
    private String targetId;
    private long endUserId;
    private String channel;
    private String scopeKey;
    private String personaVersion;
    private String createFingerprint;
    private String createCommandData;
    private String status;
    private long sessionRevision;
    private long createdEpochSecond;
    private int createdNano;
    private long updatedEpochSecond;
    private int updatedNano;
    private Long rootEndUserId;
    private Long streamEndUserId;
    private Long streamNextSequence;
    private Boolean executionOwnerConsistent;

    SessionStoreRow() {}

    static SessionStoreRow initial(
            SessionStorePort.CreateSession command,
            String commandData,
            String fingerprint,
            Instant now) {
        SessionOwner owner = command.owner();
        SessionStoreRow row = new SessionStoreRow();
        row.tenantId = owner.sessionRef().tenantId().value();
        row.sessionId = owner.sessionRef().sessionId();
        row.targetKind = owner.target().kind().name();
        row.targetId = owner.target().id();
        row.endUserId = owner.requireUser().value();
        row.channel = command.channel();
        row.scopeKey = command.scopeKey();
        row.personaVersion = command.personaVersion();
        row.createFingerprint = fingerprint;
        row.createCommandData = commandData;
        row.status = SessionStatus.CREATED.name();
        row.sessionRevision = 1;
        row.createdEpochSecond = now.getEpochSecond();
        row.createdNano = now.getNano();
        row.updatedEpochSecond = now.getEpochSecond();
        row.updatedNano = now.getNano();
        return row;
    }

    SessionOwner toOwner() {
        return SessionOwner.user(
                new SessionRef(new TenantId(tenantId), sessionId),
                new SessionTarget(SessionTarget.Kind.valueOf(targetKind), targetId),
                new EndUserId(endUserId));
    }

    SessionRecord toRecord() {
        CreateSession command = SessionPersistenceCodec.decode(
                createCommandData, CreateSession.class);
        String expectedFingerprint = SessionStoreHashes.fingerprint(
                "create", createCommandData);
        if (!command.owner().equals(toOwner())
                || !command.channel().equals(channel)
                || !command.scopeKey().equals(scopeKey)
                || !command.personaVersion().equals(personaVersion)
                || !expectedFingerprint.equals(createFingerprint)
                || rootEndUserId == null || rootEndUserId.longValue() != endUserId
                || streamNextSequence == null && streamEndUserId != null
                || streamNextSequence != null
                    && (streamEndUserId == null
                        || streamEndUserId.longValue() != endUserId
                        || streamNextSequence < 1)
                || !Boolean.TRUE.equals(executionOwnerConsistent)) {
            throw new IllegalStateException("session_store_row_corrupt");
        }
        long lastSequence = streamNextSequence == null ? 0 : streamNextSequence - 1;
        return new SessionRecord(
                toOwner(), channel, scopeKey, personaVersion,
                SessionStatus.valueOf(status), sessionRevision, lastSequence,
                Instant.ofEpochSecond(createdEpochSecond, createdNano),
                Instant.ofEpochSecond(updatedEpochSecond, updatedNano));
    }

    boolean matchesCreate(CreateSession command, String commandData, String fingerprint) {
        return createFingerprint.equals(fingerprint)
                && createCommandData.equals(commandData)
                && SessionPersistenceCodec.decode(
                        createCommandData, CreateSession.class).equals(command);
    }

    String validatedCreateFingerprint() {
        toRecord();
        return createFingerprint;
    }

    String lifecycleFingerprint() {
        return SessionStoreHashes.fingerprint(
                "lifecycle", createFingerprint, status,
                Long.toString(sessionRevision),
                Long.toString(createdEpochSecond), Integer.toString(createdNano),
                Long.toString(updatedEpochSecond), Integer.toString(updatedNano));
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String value) { targetKind = value; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String value) { targetId = value; }
    public long getEndUserId() { return endUserId; }
    public void setEndUserId(long value) { endUserId = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String value) { scopeKey = value; }
    public String getPersonaVersion() { return personaVersion; }
    public void setPersonaVersion(String value) { personaVersion = value; }
    public String getCreateFingerprint() { return createFingerprint; }
    public void setCreateFingerprint(String value) { createFingerprint = value; }
    public String getCreateCommandData() { return createCommandData; }
    public void setCreateCommandData(String value) { createCommandData = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public long getSessionRevision() { return sessionRevision; }
    public void setSessionRevision(long value) { sessionRevision = value; }
    public long getCreatedEpochSecond() { return createdEpochSecond; }
    public void setCreatedEpochSecond(long value) { createdEpochSecond = value; }
    public int getCreatedNano() { return createdNano; }
    public void setCreatedNano(int value) { createdNano = value; }
    public long getUpdatedEpochSecond() { return updatedEpochSecond; }
    public void setUpdatedEpochSecond(long value) { updatedEpochSecond = value; }
    public int getUpdatedNano() { return updatedNano; }
    public void setUpdatedNano(int value) { updatedNano = value; }
    public Long getRootEndUserId() { return rootEndUserId; }
    public void setRootEndUserId(Long value) { rootEndUserId = value; }
    public Long getStreamEndUserId() { return streamEndUserId; }
    public void setStreamEndUserId(Long value) { streamEndUserId = value; }
    public Long getStreamNextSequence() { return streamNextSequence; }
    public void setStreamNextSequence(Long value) { streamNextSequence = value; }
    public Boolean getExecutionOwnerConsistent() { return executionOwnerConsistent; }
    public void setExecutionOwnerConsistent(Boolean value) {
        executionOwnerConsistent = value;
    }
}

/** CAS operation journal 行。 */
final class SessionStoreMutationRow {

    private String tenantId;
    private String sessionId;
    private String targetKind;
    private String targetId;
    private long endUserId;
    private String expectedStatus;
    private String targetStatus;
    private long expectedRevision;
    private String causationInputId;
    private String commandFingerprint;
    private String commandData;
    private String outputStatus;
    private long outputRevision;
    private long outputUpdatedEpochSecond;
    private int outputUpdatedNano;

    SessionStoreMutationRow() {}

    static SessionStoreMutationRow from(
            ChangeSessionStatus command,
            String commandData,
            String fingerprint,
            long outputRevision,
            Instant updatedAt) {
        SessionOwner owner = command.owner();
        SessionStoreMutationRow row = new SessionStoreMutationRow();
        row.tenantId = owner.sessionRef().tenantId().value();
        row.sessionId = owner.sessionRef().sessionId();
        row.targetKind = owner.target().kind().name();
        row.targetId = owner.target().id();
        row.endUserId = owner.requireUser().value();
        row.expectedStatus = command.expectedStatus().name();
        row.targetStatus = command.targetStatus().name();
        row.expectedRevision = command.expectedRevision();
        row.causationInputId = command.causationInputId().value();
        row.commandFingerprint = fingerprint;
        row.commandData = commandData;
        row.outputStatus = command.targetStatus().name();
        row.outputRevision = outputRevision;
        row.outputUpdatedEpochSecond = updatedAt.getEpochSecond();
        row.outputUpdatedNano = updatedAt.getNano();
        return row;
    }

    void validate(ChangeSessionStatus requested) {
        ChangeSessionStatus stored = SessionPersistenceCodec.decode(
                commandData, ChangeSessionStatus.class);
        String expectedFingerprint = SessionStoreHashes.fingerprint("cas", commandData);
        if (!stored.owner().equals(requested.owner())
                || stored.expectedStatus() != requested.expectedStatus()
                || stored.targetStatus() != requested.targetStatus()
                || stored.expectedRevision() != requested.expectedRevision()
                || !stored.causationInputId().equals(requested.causationInputId())
                || !commandFingerprint.equals(expectedFingerprint)
                || !outputStatus.equals(targetStatus)
                || outputRevision != expectedRevision + 1) {
            throw new IllegalStateException("session_mutation_row_corrupt");
        }
    }

    boolean matches(SessionStoreRow current) {
        return outputStatus.equals(current.getStatus())
                && outputRevision == current.getSessionRevision()
                && outputUpdatedEpochSecond == current.getUpdatedEpochSecond()
                && outputUpdatedNano == current.getUpdatedNano();
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String value) { targetKind = value; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String value) { targetId = value; }
    public long getEndUserId() { return endUserId; }
    public void setEndUserId(long value) { endUserId = value; }
    public String getExpectedStatus() { return expectedStatus; }
    public void setExpectedStatus(String value) { expectedStatus = value; }
    public String getTargetStatus() { return targetStatus; }
    public void setTargetStatus(String value) { targetStatus = value; }
    public long getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(long value) { expectedRevision = value; }
    public String getCausationInputId() { return causationInputId; }
    public void setCausationInputId(String value) { causationInputId = value; }
    public String getCommandFingerprint() { return commandFingerprint; }
    public void setCommandFingerprint(String value) { commandFingerprint = value; }
    public String getCommandData() { return commandData; }
    public void setCommandData(String value) { commandData = value; }
    public String getOutputStatus() { return outputStatus; }
    public void setOutputStatus(String value) { outputStatus = value; }
    public long getOutputRevision() { return outputRevision; }
    public void setOutputRevision(long value) { outputRevision = value; }
    public long getOutputUpdatedEpochSecond() { return outputUpdatedEpochSecond; }
    public void setOutputUpdatedEpochSecond(long value) { outputUpdatedEpochSecond = value; }
    public int getOutputUpdatedNano() { return outputUpdatedNano; }
    public void setOutputUpdatedNano(int value) { outputUpdatedNano = value; }
}

/** 不包含冲突原文的持久摘要行。 */
record SessionConflictAuditRow(
        String tenantId,
        String auditRef,
        String resourceIdHash,
        String conflictKind,
        long currentRevision,
        String storedFingerprint,
        String requestedFingerprint) {
}

/** SessionStore canonical codec 的 domain-separated SHA-256。 */
final class SessionStoreHashes {

    private static final String SCHEMA = "skillforge.runtime.jdbc-session-store.v1";

    private SessionStoreHashes() {}

    static String fingerprint(String domain, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, SCHEMA);
            update(digest, domain);
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
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}

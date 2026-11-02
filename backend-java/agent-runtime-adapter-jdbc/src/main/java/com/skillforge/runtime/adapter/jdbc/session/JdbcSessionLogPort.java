package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.DelegationRef;
import com.skillforge.runtime.api.port.PortConflict;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

/** 基于 PostgreSQL 事务与 RLS 实现唯一 Session sequence 的追加日志。 */
public final class JdbcSessionLogPort implements SessionLogPort {

    private static final String MAPPER_RESOURCE =
            "mapper/AgentRuntimeSessionLogMapper.xml";

    private final SqlSessionFactory sessionFactory;
    /** 仅调用方同步业务边界可借用的事务；不跨线程、不拥有提交权。 */
    private final ThreadLocal<Participation> participation = new ThreadLocal<>();

    /** 保存显式租户，拒绝嵌套调用更换 SqlSession 或租户。 */
    private record Participation(SqlSession session, TenantId tenant) {}

    /** 将日志映射加入调用方事务配置，不创建连接。 */
    public static void configure(Configuration configuration) { register(configuration); }

    /**
     * 本实例在调用方已绑定租户的 SqlSession 内执行日志读取和追加。
     * 作用域结束清理线程引用；提交、回滚和租户绑定均归调用方，领域异常不得吞掉。
     */
    public <T> T participate(SqlSession session, TenantId tenant, java.util.function.Supplier<T> operation) {
        Objects.requireNonNull(session); Objects.requireNonNull(tenant); Objects.requireNonNull(operation);
        var prior = participation.get();
        if (prior != null && (prior.session() != session || !prior.tenant().equals(tenant)))
            throw new IllegalStateException("session_log_transaction_scope_conflict");
        participation.set(new Participation(session, tenant));
        try { return operation.get(); }
        finally { if (prior == null) participation.remove(); else participation.set(prior); }
    }

    /**
     * 从宿主提供的连接池创建无 Spring 的 JDBC 适配器。
     *
     * <p>DataSource 必须使用受 RLS 约束的非 superuser、NOBYPASSRLS 角色；租户身份仍由
     * 已认证宿主传入的 {@link SessionOwner} 决定，数据库 GUC 不替代 IAM 判定。
     */
    public JdbcSessionLogPort(DataSource dataSource) {
        this.sessionFactory = buildSessionFactory(
                Objects.requireNonNull(dataSource, "dataSource"));
    }

    /** 仅个人分支具有EndUser所有者；GROUP绝不使用零值占位。 */
    static Long userId(SessionOwner owner) {
        return owner.subject() instanceof com.skillforge.runtime.api.SessionSubject.User user?user.endUserId().value():null;
    }

    /** 群共享目录仅接受已交付领域事件及其精确 schema，不向普通个人日志开放。 */
    private static boolean isGroupDomainEvent(SessionEventDraft event) {
        String schema = event.payload().schemaId();
        if ("dag.work.v1".equals(schema)
                || event.eventType().startsWith("plan.")
                && schema.equals("skillforge." + event.eventType() + ".v1")) return true;
        return switch (event.eventType()) {
            case "group.memory.source" -> "v1".equals(event.eventVersion())
                    && "skillforge.group.memory-source.v1".equals(schema);
            case "memory.written" -> "2".equals(event.eventVersion())
                    && SessionMemoryEventStorage.SCHEMA.equals(schema);
            case "retrieval.performed" -> "2".equals(event.eventVersion())
                    && "skillforge.runtime.memory.retrieval-performed.v3".equals(schema);
            default -> false;
        };
    }

    @Override
    public AppendResult append(AppendSessionEvent command) {
        Objects.requireNonNull(command, "command");
        SessionOwner owner = command.target().owner();
        return inTransaction(owner.sessionRef().tenantId(), mapper -> append(mapper, command));
    }

    @Override public Optional<HistoryRetention> historyRetention(SessionOwner owner) {
        return inTransaction(owner.sessionRef().tenantId(), mapper -> Optional.ofNullable(mapper.findHistoryRetention(owner))
                .map(SessionHistoryRetentionRow::toRetention));
    }

    @Override
    public List<AppendResult> appendBatch(List<AppendSessionEvent> commands) {
        var batch = SessionLogPort.checkedAppendBatch(commands);
        return inTransaction(batch.getFirst().target().owner().sessionRef().tenantId(),
                mapper -> appendBatch(mapper, batch));
    }

    /** 同一owner的有界诊断批次共用事务；任何冲突回滚整批，不伪造单条提交回执。 */
    public List<CommittedSessionEvent> appendDiagnosticBatch(List<AppendSessionEvent> commands) {
        var batch = List.copyOf(commands);
        if (batch.isEmpty() || batch.size() > 32) throw new IllegalArgumentException("diagnostic_batch_size_invalid");
        var owner = batch.getFirst().target().owner();
        if (owner.target().kind() == SessionTarget.Kind.GROUP
                && batch.stream().anyMatch(command -> !isGroupDomainEvent(command.event())))
            throw new IllegalArgumentException("group_domain_log_event_required");
        for (var command : batch) {
            if (!owner.equals(command.target().owner()) || !"agent.runtime-diagnostic".equals(command.event().eventType()))
                throw new IllegalArgumentException("diagnostic_batch_scope_invalid");
        }
        return inTransaction(owner.sessionRef().tenantId(), mapper -> appendBatch(mapper, batch).stream().map(result -> {
            return switch (result) {
                case AppendResult.Appended value -> value.event();
                case AppendResult.Replayed value -> value.event();
                case AppendResult.Conflict ignored -> throw new IllegalStateException("diagnostic_batch_conflict");
            };
        }).toList());
    }

    @Override
    public Optional<CommittedSessionEvent> findByEvent(SessionOwner owner, EventId eventId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(eventId, "eventId");
        return inTransaction(owner.sessionRef().tenantId(), mapper -> Optional.ofNullable(
                mapper.findEvent(owner.sessionRef().tenantId().value(),
                        owner.sessionRef().sessionId(), owner.target().kind().name(), owner.target().id(),
                        JdbcSessionLogPort.userId(owner), eventId.value()))
                .map(SessionEventRow::toCommittedEvent));
    }

    /** 已授权USER持久事实按原用户会话读取来源，不以当前审核Agent重建原target。 */
    @Override
    public Optional<CommittedSessionEvent> findUserSource(SessionRef sourceSession, EndUserId sourceUser, EventId eventId) {
        Objects.requireNonNull(sourceSession, "sourceSession");
        Objects.requireNonNull(sourceUser, "sourceUser");
        Objects.requireNonNull(eventId, "eventId");
        return inTransaction(sourceSession.tenantId(), mapper -> {
            var matches = mapper.findUserSource(sourceSession.tenantId().value(), sourceSession.sessionId(),
                    sourceUser.value(), eventId.value());
            // 原schema允许不同target复用session/event；多匹配时不能猜测事实所指的来源。
            if (matches.size() != 1) return Optional.empty();
            return Optional.of(matches.getFirst().toCommittedEvent());
        });
    }

    @Override
    public List<CommittedSessionEvent> readAfter(ReadSessionLog query) {
        Objects.requireNonNull(query, "query");
        SessionOwner owner = query.owner();
        return inTransaction(owner.sessionRef().tenantId(), mapper -> mapper.readAfter(
                        owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                        owner.target().kind().name(), owner.target().id(), JdbcSessionLogPort.userId(owner),
                        query.afterSequence(), query.limit()).stream()
                .map(SessionEventRow::toCommittedEvent)
                .toList());
    }

    @Override
    public java.util.Map<EventId, CommittedSessionEvent> findByEvents(SessionOwner owner, java.util.Set<EventId> eventIds) {
        var ids = java.util.Set.copyOf(eventIds);
        if (ids.isEmpty()) return java.util.Map.of();
        return inTransaction(owner.sessionRef().tenantId(), mapper -> mapper.findEvents(
                owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                owner.target().kind().name(), owner.target().id(),
                JdbcSessionLogPort.userId(owner), ids.stream().map(EventId::value).toList()).stream()
                .map(SessionEventRow::toCommittedEvent).collect(java.util.stream.Collectors.toUnmodifiableMap(
                        CommittedSessionEvent::eventId, java.util.function.Function.identity())));
    }

    @Override
    public List<CommittedSessionEvent> readAfter(ReadSessionLog query, java.util.Set<String> eventTypes) {
        var types = java.util.Set.copyOf(eventTypes);
        if (types.isEmpty()) throw new IllegalArgumentException("eventTypes must not be empty");
        var owner = query.owner();
        return inTransaction(owner.sessionRef().tenantId(), mapper -> mapper.readFilteredAfter(
                owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                owner.target().kind().name(), owner.target().id(), JdbcSessionLogPort.userId(owner),
                query.afterSequence(), query.limit(), types)
                .stream().map(SessionEventRow::toCommittedEvent).toList());
    }

    @Override
    public Optional<CommittedSessionEvent> latest(SessionOwner owner) {
        Objects.requireNonNull(owner, "owner");
        return inTransaction(owner.sessionRef().tenantId(), mapper -> Optional.ofNullable(mapper.latest(
                owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                owner.target().kind().name(), owner.target().id(), JdbcSessionLogPort.userId(owner)))
                .map(SessionEventRow::toCommittedEvent));
    }

    @Override
    public List<CommittedSessionEvent> readAfter(ReadSessionLog query, RunRef run) {
        var owner = query.owner();
        if (!owner.sessionRef().tenantId().equals(run.tenantId()))
            throw new IllegalArgumentException("session_log_run_scope_invalid");
        return inTransaction(owner.sessionRef().tenantId(), mapper -> mapper.readRunAfter(
                owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                owner.target().kind().name(), owner.target().id(), JdbcSessionLogPort.userId(owner),
                query.afterSequence(), query.limit(), run.runId(), java.util.Set.of())
                .stream().map(SessionEventRow::toCommittedEvent).toList());
    }

    @Override
    public Optional<CommittedSessionEvent> findFirst(SessionOwner owner, InputId inputId, RunRef run) {
        Objects.requireNonNull(inputId, "inputId");
        if (!owner.sessionRef().tenantId().equals(run.tenantId()))
            throw new IllegalArgumentException("session_log_run_scope_invalid");
        return inTransaction(owner.sessionRef().tenantId(), mapper -> Optional.ofNullable(mapper.findFirst(
                owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                owner.target().kind().name(), owner.target().id(), JdbcSessionLogPort.userId(owner),
                inputId.value(), run.runId())).map(SessionEventRow::toCommittedEvent));
    }

    @Override
    public List<CommittedSessionEvent> readAfter(ReadSessionLog query, RunRef run, java.util.Set<String> eventTypes) {
        var types = java.util.Set.copyOf(eventTypes);
        var owner = query.owner();
        if (types.isEmpty() || !owner.sessionRef().tenantId().equals(run.tenantId()))
            throw new IllegalArgumentException("session_log_run_scope_invalid");
        return inTransaction(owner.sessionRef().tenantId(), mapper -> mapper.readRunAfter(
                owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                owner.target().kind().name(), owner.target().id(), JdbcSessionLogPort.userId(owner),
                query.afterSequence(), query.limit(), run.runId(), types)
                .stream().map(SessionEventRow::toCommittedEvent).toList());
    }

    /** 同包组合适配器必须在已绑定租户的同一事务内调用，不自行提交。 */
    static AppendResult append(
            SessionLogMapper mapper, AppendSessionEvent command) {
        return appendBatch(mapper, List.of(command)).getFirst();
    }

    /** 租户已绑定；一次 owner/stream 锁下分配全部新序号，冲突只提交之前接受的前缀。 */
    private static List<AppendResult> appendBatch(SessionLogMapper mapper, List<AppendSessionEvent> batch) {
        SessionOwner owner = batch.getFirst().target().owner();
        mapper.ensureOwner(SessionOwnerRow.initial(owner));
        SessionOwnerRow ownerRoot = mapper.lockOwner(
                owner.sessionRef().tenantId().value(), owner.target().kind().name(), owner.target().id(),
                owner.sessionRef().sessionId());
        if (ownerRoot == null) {
            throw new IllegalStateException("session_owner_root_not_visible");
        }
        if (!ownerRoot.toOwner().equals(owner)) {
            return List.of(new AppendResult.Conflict(ownerConflict(owner)));
        }
        mapper.ensureStream(SessionStreamRow.initial(owner));
        SessionStreamRow stream = mapper.lockStream(
                owner.sessionRef().tenantId().value(), owner.target().kind().name(), owner.target().id(),
                owner.sessionRef().sessionId());
        if (stream == null) {
            throw new IllegalStateException("session_stream_not_visible");
        }
        if (!stream.toOwner().equals(owner)) {
            return List.of(new AppendResult.Conflict(ownerConflict(owner)));
        }
        return appendBatchLocked(mapper, batch, ownerRoot, stream);
    }

    /** 原子 Session 提交已按 owner→stream→Run 取得锁；此内核不得升级 owner 锁或自行提交。 */
    static List<AppendResult> appendBatchLocked(SessionLogMapper mapper, List<AppendSessionEvent> batch,
            SessionOwnerRow ownerRoot, SessionStreamRow stream) {
        var owner = batch.getFirst().target().owner();
        if (owner.target().kind() == SessionTarget.Kind.GROUP
                && batch.stream().anyMatch(command -> !isGroupDomainEvent(command.event())))
            throw new IllegalArgumentException("group_domain_log_event_required");
        if (!owner.equals(ownerRoot.toOwner()) || !owner.equals(stream.toOwner()))
            return List.of(new AppendResult.Conflict(ownerConflict(owner)));
        var existing = new HashMap<String, SessionEventRow>();
        boolean historyRetired = mapper.findHistoryRetention(owner) != null;
        var retired = new HashMap<String, SessionEventTombstoneRow>();
        for (var tombstone : mapper.findEventTombstones(owner,
                batch.stream().map(command -> command.event().eventId().value()).distinct().toList()))
            retired.put(tombstone.eventId, tombstone);
        for (var row : mapper.findEvents(
                owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                owner.target().kind().name(), owner.target().id(), JdbcSessionLogPort.userId(owner),
                batch.stream().map(command -> command.event().eventId().value()).distinct().toList())) {
            existing.put(row.getEventId(), row);
        }
        var results = new ArrayList<AppendResult>(batch.size());
        var inserts = new ArrayList<SessionEventRow>(batch.size());
        long sequence = stream.getNextSequence();
        for (var command : batch) {
            var old = existing.get(command.event().eventId().value());
            if (old != null) {
                if (old.matches(command)) results.add(new AppendResult.Replayed(old.toCommittedEvent()));
                else {
                    results.add(new AppendResult.Conflict(eventConflict(owner, command.event().eventId())));
                    break;
                }
            } else if (retired.containsKey(command.event().eventId().value())) {
                var tombstone = retired.get(command.event().eventId().value());
                if (!tombstone.matches(command)) {
                    results.add(new AppendResult.Conflict(eventConflict(owner, command.event().eventId()))); break;
                }
                results.add(new AppendResult.Replayed(SessionEventRow.from(command, tombstone.sessionSequence).toCommittedEvent()));
            } else if (historyRetired) {
                results.add(new AppendResult.Conflict(conflict("session_history_retired",
                        owner.sessionRef().tenantId().value() + ":" + owner.target().kind() + ":"
                                + owner.target().id() + ":" + owner.sessionRef().sessionId())));
                break;
            } else {
                var row = SessionEventRow.from(command, sequence);
                sequence = Math.incrementExact(sequence);
                inserts.add(row);
                existing.put(row.getEventId(), row);
                results.add(new AppendResult.Appended(row.toCommittedEvent()));
            }
        }
        // 数据库逐行触发器要求 INSERT 恰好使用当前 next_sequence，随后 advance 也验证该事件已存在。
        // 共用事务、owner/stream锁和批量查重即可减少往返；不能先插入多个未来序号绕过这两个约束。
        for (var row : inserts) {
            var memoryBodies = freezeMemoryBodies(mapper, row);
            if (mapper.insertEvent(row) != 1 || mapper.advanceStream(
                        owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                        owner.target().kind().name(), owner.target().id(), JdbcSessionLogPort.userId(owner),
                        row.getSessionSequence()) != 1) throw new IllegalStateException("session_append_not_applied");
            for (var digest : memoryBodies.bodies().keySet())
                if (mapper.insertEventBodyReference(row, digest, memoryBodies.originalDigest()) != 1)
                    throw new IllegalStateException("session_event_body_reference_not_applied");
        }
        return List.copyOf(results);
    }

    /** Memory可能采用合成RunIdentity；正文所有权限定完整SessionOwner/event，不假设Run表外键存在。 */
    private static SessionMemoryEventStorage.Frozen freezeMemoryBodies(SessionLogMapper mapper, SessionEventRow row) {
        var frozen = SessionMemoryEventStorage.freeze(row.getEventType(),
                new TypedPayload(row.getPayloadSchemaId(),row.getPayloadCanonicalJson()));
        if (frozen.bodies().isEmpty()) return frozen;
        var existing = new java.util.HashMap<String,String>();
        for (var stored : mapper.findEventBodies(row, frozen.bodies().keySet().stream().toList()))
            existing.put(stored.contentHash,stored.canonicalContent);
        for (var entry : frozen.bodies().entrySet()) {
            if (existing.containsKey(entry.getKey())) {
                if (!entry.getValue().equals(existing.get(entry.getKey()))) throw new IllegalStateException("session_event_body_reference_rejected");
            } else if (mapper.insertEventBody(row,entry.getKey(),entry.getValue()) != 1)
                throw new IllegalStateException("session_event_body_freeze_conflict");
        }
        row.setPayloadCanonicalJson(frozen.compact().canonicalJson());
        return frozen;
    }

    private <T> T inTransaction(TenantId tenantId, MapperWork<T> work) {
        var borrowed = participation.get();
        if (borrowed != null) {
            if (!borrowed.tenant().equals(tenantId)) throw new IllegalStateException("session_log_transaction_tenant_conflict");
            return work.execute(borrowed.session().getMapper(SessionLogMapper.class));
        }
        try (SqlSession session = sessionFactory.openSession(false)) {
            try {
                SessionLogMapper mapper = session.getMapper(SessionLogMapper.class);
                mapper.bindTenant(tenantId.value());
                T result = work.execute(mapper);
                session.commit();
                return result;
            } catch (RuntimeException failure) {
                try {
                    session.rollback();
                } catch (RuntimeException rollbackFailure) {
                    if (rollbackFailure != failure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw storageFailure(failure);
            }
        } catch (RuntimeException failure) {
            if (isStorageFailure(failure)) {
                throw failure;
            }
            throw storageFailure(failure);
        }
    }

    private static SqlSessionFactory buildSessionFactory(DataSource dataSource) {
        Environment environment = new Environment(
                "agent-runtime-jdbc", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        register(configuration);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 组合持久化在同一 SqlSessionFactory 注册日志 mapper，不创建第二条连接或事务。 */
    static void register(Configuration configuration) {
        if (configuration.hasMapper(SessionLogMapper.class)) return;
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream mapperXml = JdbcSessionLogPort.class.getClassLoader()
                .getResourceAsStream(MAPPER_RESOURCE)) {
            if (mapperXml == null) throw new IllegalStateException("session_log_mapper_missing");
            new XMLMapperBuilder(mapperXml, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("session_log_adapter_initialization_failure", failure);
        }
    }

    private static boolean isStorageFailure(RuntimeException failure) {
        return failure instanceof SessionLogStorageException;
    }

    private static IllegalStateException storageFailure(Throwable cause) {
        // 数据库异常不跨 Port 泄露；稳定原因码供宿主统一技术故障处理。
        return new SessionLogStorageException(cause);
    }

    private static PortConflict ownerConflict(SessionOwner owner) {
        String resourceId = owner.sessionRef().tenantId().value() + "/"
                + owner.target().kind() + "/" + owner.target().id() + "/" + owner.sessionRef().sessionId();
        return conflict("session_owner", resourceId);
    }

    private static PortConflict eventConflict(SessionOwner owner, EventId eventId) {
        String resourceId = owner.sessionRef().tenantId().value() + "/"
                + owner.target().kind() + "/" + owner.target().id() + "/" + owner.sessionRef().sessionId()
                + "/" + eventId.value();
        return conflict("session_event", resourceId);
    }

    private static PortConflict conflict(String resourceKind, String resourceId) {
        return new PortConflict(resourceKind, resourceId,
                new ResultRef("session-log-conflict:sha256:" + sha256(
                        resourceKind + "\u0000" + resourceId)));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable");
        }
    }

    @FunctionalInterface
    private interface MapperWork<T> {
        T execute(SessionLogMapper mapper);
    }

    /** 保留技术原因链，但顶层只暴露稳定 adapter 原因码。 */
    private static final class SessionLogStorageException extends IllegalStateException {

        private SessionLogStorageException(Throwable cause) {
            super("session_log_storage_failure", cause);
        }
    }
}

/** MyBatis Mapper 只声明端口需要的事务操作；SQL 唯一维护在配套 XML。 */
interface SessionLogMapper {
    List<SessionEventRow> findUserSource(@Param("tenantId") String tenantId, @Param("sessionId") String sessionId,
            @Param("endUserId") long endUserId, @Param("eventId") String eventId);
    List<SessionEventBodyRow> findEventBodies(@Param("row") SessionEventRow row, @Param("digests") List<String> digests);
    int insertEventBody(@Param("row") SessionEventRow row, @Param("digest") String digest, @Param("canonical") String canonical);
    int insertEventBodyReference(@Param("row") SessionEventRow row, @Param("digest") String digest, @Param("originalDigest") String originalDigest);
    SessionHistoryRetentionRow findHistoryRetention(@Param("owner") SessionOwner owner);
    List<SessionEventTombstoneRow> findEventTombstones(@Param("owner") SessionOwner owner, @Param("eventIds") List<String> eventIds);

    SessionEventRow findFirst(@Param("tenantId") String tenantId, @Param("sessionId") String sessionId,
            @Param("targetKind") String targetKind, @Param("targetId") String targetId,
            @Param("endUserId") Long endUserId, @Param("inputId") String inputId, @Param("runId") String runId);

    SessionEventRow latest(@Param("tenantId") String tenantId, @Param("sessionId") String sessionId,
            @Param("targetKind") String targetKind, @Param("targetId") String targetId,
            @Param("endUserId") Long endUserId);

    List<SessionEventRow> readRunAfter(@Param("tenantId") String tenantId, @Param("sessionId") String sessionId,
            @Param("targetKind") String targetKind, @Param("targetId") String targetId,
            @Param("endUserId") Long endUserId, @Param("afterSequence") long afterSequence,
            @Param("limit") int limit, @Param("runId") String runId,
            @Param("eventTypes") java.util.Set<String> eventTypes);

    String bindTenant(@Param("tenantId") String tenantId);

    int ensureOwner(SessionOwnerRow owner);

    SessionOwnerRow lockOwner(
            @Param("tenantId") String tenantId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("sessionId") String sessionId);

    int ensureStream(SessionStreamRow stream);

    SessionStreamRow lockStream(
            @Param("tenantId") String tenantId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("sessionId") String sessionId);

    SessionEventRow findEvent(
            @Param("tenantId") String tenantId,
            @Param("sessionId") String sessionId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("endUserId") Long endUserId,
            @Param("eventId") String eventId);

    int insertEvent(SessionEventRow event);


    List<SessionEventRow> findEvents(@Param("tenantId") String tenantId, @Param("sessionId") String sessionId,
            @Param("targetKind") String targetKind, @Param("targetId") String targetId,
            @Param("endUserId") Long endUserId,
            @Param("eventIds") List<String> eventIds);

    int advanceStream(
            @Param("tenantId") String tenantId,
            @Param("sessionId") String sessionId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("endUserId") Long endUserId,
            @Param("expectedNextSequence") long expectedNextSequence);

    List<SessionEventRow> readAfter(
            @Param("tenantId") String tenantId,
            @Param("sessionId") String sessionId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("endUserId") Long endUserId,
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit);

    List<SessionEventRow> readFilteredAfter(
            @Param("tenantId") String tenantId,
            @Param("sessionId") String sessionId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("endUserId") Long endUserId,
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit,
            @Param("eventTypes") java.util.Set<String> eventTypes);
}

/** 三个 Session 持久域共用的不可变 owner root 行。 */
final class SessionOwnerRow {

    private String tenantId;
    private String sessionId;
    private String targetKind;
    private String targetId;
    private Long endUserId;
    private Boolean logStreamOwnerConsistent;
    private Boolean executionOwnerConsistent;

    SessionOwnerRow() {}

    static SessionOwnerRow initial(SessionOwner owner) {
        SessionOwnerRow row = new SessionOwnerRow();
        row.tenantId = owner.sessionRef().tenantId().value();
        row.sessionId = owner.sessionRef().sessionId();
        row.targetKind = owner.target().kind().name();
        row.targetId = owner.target().id();
        row.endUserId = JdbcSessionLogPort.userId(owner);
        return row;
    }

    SessionOwner toOwner() {
        return com.skillforge.runtime.adapter.jdbc.identity.OwnerPolicyFields.owner(new TenantId(tenantId),sessionId,targetKind,targetId,
                "GROUP".equals(targetKind)?"group:"+targetId:Long.toString(Objects.requireNonNull(endUserId)));
    }

    SessionOwner validatedStoreOwner() {
        if (!Boolean.TRUE.equals(logStreamOwnerConsistent)
                || !Boolean.TRUE.equals(executionOwnerConsistent)) {
            throw new IllegalStateException("session_owner_graph_corrupt");
        }
        return toOwner();
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String targetKind) { this.targetKind = targetKind; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public Long getEndUserId() { return endUserId; }
    public void setEndUserId(Long endUserId) { this.endUserId = endUserId; }
    public Boolean getLogStreamOwnerConsistent() { return logStreamOwnerConsistent; }
    public void setLogStreamOwnerConsistent(Boolean value) {
        logStreamOwnerConsistent = value;
    }
    public Boolean getExecutionOwnerConsistent() { return executionOwnerConsistent; }
    public void setExecutionOwnerConsistent(Boolean value) {
        executionOwnerConsistent = value;
    }
}

/** Session stream 行只保存固定归属和下一 sequence。 */
final class SessionStreamRow {

    private String tenantId;
    private String sessionId;
    private String targetKind;
    private String targetId;
    private Long endUserId;
    private long nextSequence;

    SessionStreamRow() {}

    static SessionStreamRow initial(SessionOwner owner) {
        SessionStreamRow row = new SessionStreamRow();
        row.tenantId = owner.sessionRef().tenantId().value();
        row.sessionId = owner.sessionRef().sessionId();
        row.targetKind = owner.target().kind().name();
        row.targetId = owner.target().id();
        row.endUserId = JdbcSessionLogPort.userId(owner);
        row.nextSequence = 1;
        return row;
    }

    SessionOwner toOwner() {
        return com.skillforge.runtime.adapter.jdbc.identity.OwnerPolicyFields.owner(new TenantId(tenantId),sessionId,targetKind,targetId,
                "GROUP".equals(targetKind)?"group:"+targetId:Long.toString(Objects.requireNonNull(endUserId)));
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String targetKind) { this.targetKind = targetKind; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public Long getEndUserId() { return endUserId; }
    public void setEndUserId(Long endUserId) { this.endUserId = endUserId; }
    public long getNextSequence() { return nextSequence; }
    public void setNextSequence(long nextSequence) { this.nextSequence = nextSequence; }
}

/** Session event 行负责完整无损地映射冻结 API envelope。 */
/** 固定输出块的批量查重结果；读取范围已由完整来源限定。 */
final class SessionEventBodyRow {
    public String contentHash;
    public String canonicalContent;
}

/** 退休事实只声明普通展示历史，完整恢复/重放引用仍由其所属账本持有。 */
final class SessionHistoryRetentionRow {
    public long scanAfterSequence;
    public long sourceSessionRevision;
    public long closedEpochSecond;
    public int closedNano;
    public long retiredThroughSequence;
    public long retiredEventCount;
    public long retiredReasoningCount;
    public String status;
    SessionLogPort.HistoryRetention toRetention() {
        var types = new java.util.HashSet<String>();
        if (retiredReasoningCount > 0) types.add("agent.model-reasoning");
        return new SessionLogPort.HistoryRetention(sourceSessionRevision, Instant.ofEpochSecond(closedEpochSecond, closedNano),
                retiredThroughSequence, retiredEventCount, types, "RETIRED".equals(status));
    }
}

/** 原eventId/sequence的全字段摘要墓碑；重放请求本身必须带原正文，不能从当前模型结果补造。 */
final class SessionEventTombstoneRow {
    public String eventId;
    public long sessionSequence;
    public String eventDigest;
    boolean matches(SessionLogPort.AppendSessionEvent command) {
        return eventId.equals(command.event().eventId().value()) && eventDigest.equals(digest(command, sessionSequence));
    }
    static String digest(SessionLogPort.AppendSessionEvent command, long sequence) {
        return SessionHistoryDigest.digest(command, sequence);
    }
}

final class SessionEventRow {

    private String tenantId;
    private String sessionId;
    private String targetKind;
    private String targetId;
    private String agentId;
    private Long endUserId;
    private long sessionSequence;
    private String inputId;
    private String runId;
    private String executionId;
    private String actionId;
    private String delegationId;
    private String eventId;
    private String eventFingerprint;
    private String eventType;
    private String eventVersion;
    private String traceId;
    private String spanId;
    private String rootInputId;
    private String causedByEventId;
    private String payloadSchemaId;
    private String payloadCanonicalJson;
    private long occurredAtEpochSecond;
    private int occurredAtNano;

    SessionEventRow() {}

    static SessionEventRow from(SessionLogPort.AppendSessionEvent command, long sequence) {
        SessionProjectionTarget target = command.target();
        SessionEventDraft event = command.event();
        SessionEventRow row = new SessionEventRow();
        row.tenantId = target.owner().sessionRef().tenantId().value();
        row.sessionId = target.owner().sessionRef().sessionId();
        row.targetKind = target.owner().target().kind().name();
        row.targetId = target.owner().target().id();
        row.agentId = target.agentId().value();
        row.endUserId = JdbcSessionLogPort.userId(target.owner());
        row.sessionSequence = sequence;
        row.inputId = target.inputId().value();
        row.runId = target.runRef().map(RunRef::runId).orElse(null);
        row.executionId = target.executionRef().map(ExecutionRef::executionId).orElse(null);
        row.actionId = target.actionRef().map(ActionRef::actionId).orElse(null);
        row.delegationId = target.delegationRef().map(DelegationRef::delegationId).orElse(null);
        row.eventId = event.eventId().value();
        row.eventFingerprint = event.eventFingerprint();
        row.eventType = event.eventType();
        row.eventVersion = event.eventVersion();
        row.traceId = event.trace().traceId();
        row.spanId = event.trace().spanId();
        row.rootInputId = event.trace().rootInputId().value();
        row.causedByEventId = event.trace().causedByEventId().map(EventId::value).orElse(null);
        row.payloadSchemaId = event.payload().schemaId();
        row.payloadCanonicalJson = event.payload().canonicalJson();
        row.occurredAtEpochSecond = event.occurredAt().getEpochSecond();
        row.occurredAtNano = event.occurredAt().getNano();
        return row;
    }

    CommittedSessionEvent toCommittedEvent() {
        TenantId tenant = new TenantId(tenantId);
        return new CommittedSessionEvent(
                com.skillforge.runtime.adapter.jdbc.identity.OwnerPolicyFields.owner(tenant,sessionId,targetKind,targetId,
                        "GROUP".equals(targetKind)?"group:"+targetId:Long.toString(Objects.requireNonNull(endUserId))),
                new AgentId(agentId), new InputId(inputId),
                optional(runId).map(value -> new RunRef(tenant, value)),
                optional(executionId).map(value -> new ExecutionRef(tenant, value)),
                optional(actionId).map(value -> new ActionRef(tenant, value)),
                optional(delegationId).map(value -> new DelegationRef(tenant, value)),
                new EventId(eventId), sessionSequence, eventType, eventVersion,
                new TraceEnvelope(traceId, spanId, new InputId(rootInputId),
                        optional(causedByEventId).map(EventId::new)),
                new TypedPayload(payloadSchemaId, payloadCanonicalJson),
                Instant.ofEpochSecond(occurredAtEpochSecond, occurredAtNano));
    }

    boolean matches(SessionLogPort.AppendSessionEvent command) {
        return eventFingerprint.equals(command.event().eventFingerprint())
                && toCommittedEvent().equals(from(command, sessionSequence).toCommittedEvent());
    }

    private static Optional<String> optional(String value) {
        return Optional.ofNullable(value);
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String targetKind) { this.targetKind = targetKind; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public Long getEndUserId() { return endUserId; }
    public void setEndUserId(Long endUserId) { this.endUserId = endUserId; }
    public long getSessionSequence() { return sessionSequence; }
    public void setSessionSequence(long sessionSequence) { this.sessionSequence = sessionSequence; }
    public String getInputId() { return inputId; }
    public void setInputId(String inputId) { this.inputId = inputId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }
    public String getDelegationId() { return delegationId; }
    public void setDelegationId(String delegationId) { this.delegationId = delegationId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getEventFingerprint() { return eventFingerprint; }
    public void setEventFingerprint(String eventFingerprint) { this.eventFingerprint = eventFingerprint; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getEventVersion() { return eventVersion; }
    public void setEventVersion(String eventVersion) { this.eventVersion = eventVersion; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }
    public String getRootInputId() { return rootInputId; }
    public void setRootInputId(String rootInputId) { this.rootInputId = rootInputId; }
    public String getCausedByEventId() { return causedByEventId; }
    public void setCausedByEventId(String causedByEventId) { this.causedByEventId = causedByEventId; }
    public String getPayloadSchemaId() { return payloadSchemaId; }
    public void setPayloadSchemaId(String payloadSchemaId) { this.payloadSchemaId = payloadSchemaId; }
    public String getPayloadCanonicalJson() { return payloadCanonicalJson; }
    public void setPayloadCanonicalJson(String payloadCanonicalJson) { this.payloadCanonicalJson = payloadCanonicalJson; }
    public long getOccurredAtEpochSecond() { return occurredAtEpochSecond; }
    public void setOccurredAtEpochSecond(long occurredAtEpochSecond) {
        this.occurredAtEpochSecond = occurredAtEpochSecond;
    }
    public int getOccurredAtNano() { return occurredAtNano; }
    public void setOccurredAtNano(int occurredAtNano) { this.occurredAtNano = occurredAtNano; }
}

package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.ConversationTask;
import com.skillforge.runtime.api.port.ConversationTaskPort;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 在同一 PostgreSQL 事务中维护任务事件、投影、会话绑定和推进租约。 */
public final class JdbcConversationTaskPort implements ConversationTaskPort {
    /** 宿主提供完整任务快照编码；schemaId 固定为当前 conversation-task.snapshot.v2，适配器不依赖应用 JSON 框架。 */
    public interface Codec {
        TypedPayload encode(ConversationTask task);
        ConversationTask decode(TypedPayload payload);
    }

    private final SqlSessionFactory factory;
    private final Codec codec;

    public JdbcConversationTaskPort(DataSource dataSource, Codec codec) {
        this.codec = Objects.requireNonNull(codec);
        Configuration configuration = new Configuration(new Environment("conversation-task",
                new JdbcTransactionFactory(), Objects.requireNonNull(dataSource)));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of("mapper/AgentRuntimeSessionLogMapper.xml",
                "mapper/AgentRuntimeConversationTaskMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("task_mapper_missing");
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            } catch (Exception failure) {
                throw new IllegalStateException("task_adapter_initialization_failure", failure);
            }
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Override public Optional<ConversationTask> find(SessionOwner owner, AgentId agentId, String taskId) {
        owner.requireUser();
        return transaction(owner, session -> Optional.ofNullable(mapper(session).find(owner, agentId, taskId))
                .map(row -> decode(owner, agentId, row)));
    }

    @Override public List<ConversationTask> search(SessionOwner owner, AgentId agentId, String query,
            boolean includeClosed, int offset, int limit) {
        owner.requireUser();
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("task_page_invalid");
        return transaction(owner, session -> mapper(session).search(owner, agentId,
                Objects.requireNonNull(query), includeClosed, offset, limit).stream()
                .map(row -> decode(owner, agentId, row)).toList());
    }

    @Override public Optional<String> current(SessionOwner owner, AgentId agentId) {
        owner.requireUser();
        return transaction(owner, session -> Optional.ofNullable(mapper(session).current(owner, agentId)));
    }

    @Override public Result commit(Change change) {
        Objects.requireNonNull(change);
        TypedPayload encoded = codec.encode(change.task());
        if (!encoded.equals(change.event().event().payload()))
            throw new IllegalArgumentException("task_event_snapshot_mismatch");
        if (!change.task().equals(codec.decode(encoded)))
            throw new IllegalArgumentException("task_codec_not_lossless");
        return transaction(change.owner(), session -> commit(session, change, encoded));
    }

    private Result commit(SqlSession session, Change change, TypedPayload encoded) {
        var mapper = mapper(session);
        var log = session.getMapper(SessionLogMapper.class);
        SessionOwner owner = change.owner();
        AgentId agentId = change.event().target().agentId();
        String taskId = change.task().taskId();
        mapper.ensureRoot(owner, agentId, taskId);
        var root = mapper.lockRoot(owner, agentId, taskId);
        if (root == null) return new Result.Conflict("task_owner_mismatch");

        // 重放先于 revision 和租约判定；旧请求不会覆盖新的投影或当前任务绑定。
        var operation = mapper.operation(owner, agentId, change.event().event().eventId().value());
        if (operation != null) {
            var event = log.findEvent(owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                    owner.target().kind().name(), owner.target().id(), owner.requireUser().value(),
                    change.event().event().eventId().value());
            if (!taskId.equals(operation.taskId) || operation.selected != change.select()
                    || operation.expectedRevision != change.expectedRevision() || event == null
                    || !event.matches(change.event())) return new Result.Conflict("task_event_conflict");
            return new Result.Committed(codec.decode(event.toCommittedEvent().payload()),
                    event.toCommittedEvent(), true);
        }
        var previousRow = mapper.find(owner, agentId, taskId);
        if ((previousRow == null ? 0 : previousRow.revision) != change.expectedRevision())
            return new Result.Conflict("task_revision_conflict");
        ConversationTask previous = previousRow == null ? null : decode(owner, agentId, previousRow);
        Instant now = mapper.databaseNow();
        if (change.lease().isPresent()) {
            var lease = change.lease().orElseThrow();
            if (!taskId.equals(lease.taskId()) || !lease.runId().equals(root.runId)
                    || change.event().target().runRef().filter(run -> !run.runId().equals(lease.runId())).isPresent()
                    || lease.fence() != root.fence || root.until() == null || !root.until().isAfter(now)
                    || !lease.until().equals(root.until())) return new Result.Conflict("task_lease_lost");
        } else if (root.runId != null && root.until().isAfter(now)) {
            return new Result.Conflict("task_lease_required");
        }
        String maintenanceConflict = maintenanceConflict(change, previous, log);
        if (maintenanceConflict != null) return new Result.Conflict(maintenanceConflict);
        for (var source : change.task().sources()) {
            var sourceOwner = source.owner();
            var sourceRow = log.findEvent(sourceOwner.sessionRef().tenantId().value(), sourceOwner.sessionRef().sessionId(),
                    sourceOwner.target().kind().name(), sourceOwner.target().id(),
                    sourceOwner.requireUser().value(), source.eventId().value());
            if (sourceRow == null || !sourceRow.toCommittedEvent().agentId().equals(source.agentId()))
                return new Result.Conflict("task_source_missing");
        }
        var appended = JdbcSessionLogPort.append(log, change.event());
        if (!(appended instanceof SessionLogPort.AppendResult.Appended result))
            return new Result.Conflict("task_event_conflict");
        // 选择拥有独立事实，重建会话绑定不必依赖操作幂等投影。
        if (change.select() && !(JdbcSessionLogPort.append(log, selectionEvent(change))
                instanceof SessionLogPort.AppendResult.Appended))
            return new Result.Conflict("task_selection_event_conflict");
        if (mapper.save(owner, agentId, change.task(), encoded) != 1
                || mapper.record(owner, agentId, taskId, change.event().event().eventId().value(),
                    change.expectedRevision(), change.select()) != 1
                || (change.select() && mapper.select(owner, agentId, taskId) != 1))
            throw new IllegalStateException("task_commit_not_applied");
        return new Result.Committed(change.task(), result.event(), false);
    }

    /**
     * 快照中的维护游标是权威状态；一次提交只能确认当前 Session 的一份新 receipt，
     * 其他 Session 的已确认游标必须逐值保留。
     */
    private static String maintenanceConflict(Change change, ConversationTask previous, SessionLogMapper log) {
        Map<SessionRef, ConversationTask.Maintenance> before = maintenanceBySession(
                previous == null ? List.of() : previous.maintenance());
        Map<SessionRef, ConversationTask.Maintenance> after = maintenanceBySession(
                change.task().maintenance());
        var currentSession = change.owner().sessionRef();
        for (var entry : before.entrySet()) {
            if (!entry.getKey().equals(currentSession) && !entry.getValue().equals(after.get(entry.getKey())))
                return "task_maintenance_foreign_cursor_changed";
        }
        for (var session : after.keySet()) {
            if (!session.equals(currentSession) && !before.containsKey(session))
                return "task_maintenance_foreign_cursor_changed";
        }
        var oldReceipt = before.get(currentSession);
        var receipt = after.get(currentSession);
        if (Objects.equals(oldReceipt, receipt)) return null;
        if (receipt == null) return "task_maintenance_cursor_removed";
        if (!receipt.owner().equals(change.owner()) || !receipt.taskId().equals(change.task().taskId()))
            return "task_maintenance_owner_mismatch";
        if (receipt.expectedRevision() != change.expectedRevision())
            return "task_maintenance_revision_mismatch";
        if (change.event().target().runRef().isEmpty()
                || !receipt.runRef().equals(change.event().target().runRef().orElseThrow()))
            return "task_maintenance_run_mismatch";
        if (!(receipt.batchId() + ":change").equals(change.event().event().eventId().value()))
            return "task_maintenance_batch_mismatch";
        long confirmedThrough = oldReceipt == null ? 0 : oldReceipt.throughSequence();
        if (receipt.fromExclusiveSequence() != confirmedThrough
                || receipt.throughSequence() < confirmedThrough)
            return "task_maintenance_sequence_mismatch";
        if (receipt.throughSequence() > 0) {
            var boundary = log.readAfter(receipt.owner().sessionRef().tenantId().value(),
                    receipt.owner().sessionRef().sessionId(), receipt.owner().target().kind().name(),
                    receipt.owner().target().id(),
                    receipt.owner().requireUser().value(), receipt.throughSequence() - 1, 1);
            if (boundary.isEmpty() || boundary.getFirst().getSessionSequence() != receipt.throughSequence())
                return "task_maintenance_through_missing";
        }
        if (receipt.sourceEventIds().isEmpty()) return null;
        var rows = log.findEvents(receipt.owner().sessionRef().tenantId().value(),
                receipt.owner().sessionRef().sessionId(), receipt.owner().target().kind().name(),
                receipt.owner().target().id(),
                receipt.owner().requireUser().value(), receipt.sourceEventIds().stream().map(EventId::value).toList());
        if (rows.size() != receipt.sourceEventIds().size()) return "task_maintenance_source_missing";
        Map<EventId, CommittedSessionEvent> events = new LinkedHashMap<>();
        for (var row : rows) {
            var event = row.toCommittedEvent();
            events.put(event.eventId(), event);
        }
        for (var eventId : receipt.sourceEventIds()) {
            var event = events.get(eventId);
            if (event == null || !event.owner().equals(receipt.owner())
                    || !event.agentId().equals(receipt.agentId()))
                return "task_maintenance_source_owner_mismatch";
            if (event.sessionSequence() <= receipt.fromExclusiveSequence()
                    || event.sessionSequence() > receipt.throughSequence())
                return "task_maintenance_source_out_of_range";
        }
        return null;
    }

    private static Map<SessionRef, ConversationTask.Maintenance> maintenanceBySession(
            List<ConversationTask.Maintenance> maintenance) {
        Map<SessionRef, ConversationTask.Maintenance> result = new LinkedHashMap<>();
        for (var receipt : maintenance) result.put(receipt.owner().sessionRef(), receipt);
        return result;
    }

    private static SessionLogPort.AppendSessionEvent selectionEvent(Change change) {
        var original = change.event().event();
        String payload = "{\"taskId\":" + quote(change.task().taskId())
                + ",\"revision\":" + change.task().revision() + ",\"select\":true,\"operationEventId\":"
                + quote(original.eventId().value()) + "}";
        var trace = original.trace();
        var event = new SessionEventDraft(new EventId("task-selection:" + digest(original.eventId().value())),
                "conversation-task.selected", "1", digest(original.eventFingerprint() + "\n" + payload),
                new TraceEnvelope(trace.traceId(), trace.spanId(), trace.rootInputId(), Optional.of(original.eventId())),
                new TypedPayload("conversation-task.selection.v1", payload), original.occurredAt());
        return new SessionLogPort.AppendSessionEvent(change.event().target(), event);
    }

    /** 选择事件只有固定标量，手工转义避免给适配器引入应用 JSON 依赖。 */
    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') result.append('\\').append(character);
            else if (character < 0x20) result.append(String.format("\\u%04x", (int) character));
            else result.append(character);
        }
        return result.append('"').toString();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    @Override public Lease acquire(SessionOwner owner, AgentId agentId, String taskId,
                                   String runId, Instant now, Instant until) {
        owner.requireUser();
        if (runId == null || runId.isBlank() || !until.isAfter(now))
            throw new IllegalArgumentException("task_lease_invalid");
        return transaction(owner, session -> {
            var mapper = mapper(session);
            var root = mapper.lockRoot(owner, agentId, taskId);
            if (root == null || mapper.find(owner, agentId, taskId) == null)
                throw new IllegalStateException("task_not_found");
            Instant databaseNow = mapper.databaseNow();
            if (!until.isAfter(databaseNow)) throw new IllegalArgumentException("task_lease_expired");
            boolean live = root.runId != null && root.until().isAfter(databaseNow);
            if (live && !root.runId.equals(runId)) throw new IllegalStateException("task_lease_busy");
            long fence = live ? root.fence : Math.addExact(root.fence, 1);
            // 同 Run 续租不缩短现有租期；过期后即使 Run 相同也获得新 fence。
            Instant nextUntil = live && root.until().isAfter(until) ? root.until() : until;
            if (mapper.lease(owner, agentId, taskId, runId, fence,
                    nextUntil.getEpochSecond(), nextUntil.getNano()) != 1)
                throw new IllegalStateException("task_lease_not_applied");
            return new Lease(taskId, runId, fence, nextUntil);
        });
    }

    @Override public void release(SessionOwner owner, AgentId agentId, String taskId, String runId, long fence) {
        owner.requireUser();
        transaction(owner, session -> { mapper(session).release(owner, agentId, taskId, runId, fence); return null; });
    }

    private ConversationTask decode(SessionOwner owner, AgentId agentId, ConversationTaskRow row) {
        ConversationTask task = codec.decode(new TypedPayload(row.schemaId, row.snapshot));
        if (!task.belongsTo(owner, agentId) || !task.taskId().equals(row.taskId) || task.revision() != row.revision)
            throw new IllegalStateException("task_projection_corrupt");
        return task;
    }

    private static ConversationTaskMapper mapper(SqlSession session) {
        return session.getMapper(ConversationTaskMapper.class);
    }

    private <T> T transaction(SessionOwner owner, Function<SqlSession, T> work) {
        Objects.requireNonNull(owner);
        try (SqlSession session = factory.openSession(false)) {
            try {
                session.getMapper(SessionLogMapper.class).bindTenant(owner.sessionRef().tenantId().value());
                T result = work.apply(session);
                if (result instanceof Result.Conflict) session.rollback(); else session.commit();
                return result;
            } catch (RuntimeException failure) {
                try { session.rollback(); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (PersistenceException failure) {
            throw new IllegalStateException("task_storage_failure", failure);
        }
    }
}

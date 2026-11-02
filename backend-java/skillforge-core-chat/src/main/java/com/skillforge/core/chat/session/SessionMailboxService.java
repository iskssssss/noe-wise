package com.skillforge.core.chat.session;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.chat.persistence.SessionOutboxSequenceMapper;
import com.skillforge.core.chat.persistence.SessionOutboxEntryDO;
import com.skillforge.core.shared.tracing.TraceContext;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 产品 Session Outbox 的唯一业务入口。
 *
 * <p>该 Outbox 只负责 UI、SSE 与 Webhook 输出投影；输入受理、执行和恢复全部由
 * Agent Runtime 权威账本负责，不再存在产品 Session Inbox。</p>
 */
@Service
public class SessionMailboxService {

    private final SessionOutboxMapper outboxMapper;
    private final SessionOutboxSequenceMapper sequenceMapper;
    private final SessionWebhookDeliveryLedger webhookDeliveryLedger;
    /** 仅持有活跃连接的通知，不拥有持久事实或待投递责任。 */
    private final SessionOutputNotifications notifications = new SessionOutputNotifications();

    public SessionMailboxService(SessionOutboxMapper outboxMapper,
            SessionOutboxSequenceMapper sequenceMapper,
            SessionWebhookDeliveryLedger webhookDeliveryLedger) {
        this.outboxMapper = outboxMapper;
        this.sequenceMapper = sequenceMapper;
        this.webhookDeliveryLedger = Objects.requireNonNull(
                webhookDeliveryLedger, "webhookDeliveryLedger");
    }

    /**
     * 为外层事务拥有的原生 MyBatis 会话注册产品输出所需 XML；重复配置不重复注册。
     * 不安装租户拦截器或改动事务配置，租户绑定和连接生命周期仍由外层所有者负责。
     */
    public static void configureBorrowed(Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        for (String resource : List.of("mapper/SessionOutboxMapper.xml",
                "mapper/SessionOutboxSequenceMapper.xml", "mapper/SessionEventSubscriptionMapper.xml",
                "mapper/SessionOutboxWebhookDeliveryMapper.xml")) {
            if (configuration.isResourceLoaded(resource)) continue;
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            } catch (IOException failure) {
                throw new IllegalStateException("borrowed_session_output_mapper_unavailable:" + resource, failure);
            }
        }
    }

    /**
     * 参与调用方已绑定租户的业务事务；序号锁、Outbox、水位与 Webhook 责任沿同一会话写入。
     * 不绑定租户、不提交或关闭会话、不注册或发送通知；调用方仅在真实提交后通知输出已提交。
     */
    public List<SessionOutboxEntry> projectOutputsBorrowed(SqlSession session, String tenantId,
            String sessionId, SessionOutboxProjectionSource source,
            Function<SessionOutboxProjectionCheckpoint, SessionOutboxProjectionBatch> projection) {
        Objects.requireNonNull(session, "session");
        return projectOutputsLocked(session.getMapper(SessionOutboxMapper.class),
                session.getMapper(SessionOutboxSequenceMapper.class), tenantId, sessionId, source, projection,
                inserted -> webhookDeliveryLedger.enqueueBorrowed(session, tenantId, inserted)).stream()
                .map(SessionMailboxService::view).toList();
    }

    /** 仅由事务所有者在真实提交后唤醒本会话；不读取或重写 Outbox 和 Webhook 责任。 */
    public void outputsCommitted(String tenantId, String sessionId) {
        parseTenant(tenantId);
        notifications.committed(tenantId, sessionId);
    }

    /** 已授权会话连接先注册通知再读取 Outbox；断开时必须关闭回执。 */
    public SessionOutputNotifications.Subscription observeOutputs(String tenantId, String sessionId) {
        parseTenant(tenantId);
        return notifications.observe(tenantId, sessionId);
    }

    /**
     * 锁前只读持久回执，已完成的同政策来源无需重新进入投影写事务。
     * 新来源仍走锁内复验；永久拒绝按完整来源身份重放，不能当作成功水位。
     */
    public boolean projectionAlreadyHandled(String tenantId, String sessionId,
            SessionOutboxProjectionSource source) {
        Objects.requireNonNull(source, "source");
        long tenant = parseTenant(tenantId);
        if (sessionId == null || sessionId.isBlank())
            throw new IllegalArgumentException("sessionId must not be blank");
        var recorded = sequenceMapper.projectionRecord(tenant, sessionId);
        if (recorded == null) return false;
        if (recorded.rejects(source))
            throw new SessionOutboxProjectionRejectedException(recorded.errorCode());
        return recorded.errorCode() == null && recorded.sourceSequence() != null
                && source.policyVersion().equals(recorded.policyVersion())
                && (recorded.sourceSequence() > source.sequence()
                    || recorded.sourceSequence() == source.sequence()
                        && source.eventId().equals(recorded.sourceEventId()));
    }

    /** 公开事件消费者从已提交水位继续读取，不恢复或查询 Runtime Run。 */
    @Transactional(readOnly = true)
    public SessionOutboxProjectionCheckpoint projectionCheckpoint(String tenantId, String sessionId, String policy) {
        var checkpoint = sequenceMapper.projectionCheckpoint(parseTenant(tenantId), sessionId);
        return checkpoint == null || !checkpoint.policyVersion().equals(policy)
                ? SessionOutboxProjectionCheckpoint.empty(policy) : checkpoint;
    }

    /**
     * 按 Session 串行执行一批 Outbox 投影。
     *
     * <p>同一 Runtime 日志的恢复扫描与实时跟随可能并发到达。复用序号行锁覆盖整批
     * source 扫描后的追加，确保较早的 source 事件不会在终态之后才取得 Outbox 序号。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public List<SessionOutboxEntry> projectOutputsIncrementally(String tenantId, String sessionId,
            SessionOutboxProjectionSource source,
            Function<SessionOutboxProjectionCheckpoint, SessionOutboxProjectionBatch> projection) {
        return projectOutputsLocked(outboxMapper, sequenceMapper, tenantId, sessionId, source, projection,
                inserted -> outputsInserted(tenantId, sessionId, inserted)).stream()
                .map(SessionMailboxService::view).toList();
    }

    /** 两种事务入口共享同一持锁算法；新增输出的责任写入方式由该事务入口提供。 */
    private List<SessionOutboxEntryDO> projectOutputsLocked(SessionOutboxMapper outbox,
            SessionOutboxSequenceMapper sequences, String tenantId, String sessionId,
            SessionOutboxProjectionSource source,
            Function<SessionOutboxProjectionCheckpoint, SessionOutboxProjectionBatch> projection,
            Consumer<List<SessionOutboxEntryDO>> inserted) {
        if (projection == null) throw new IllegalArgumentException("projection must not be null");
        Objects.requireNonNull(source, "source");
        long tenant = parseTenant(tenantId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        sequences.ensure(tenant, sessionId);
        var locked = sequences.lockProjection(tenant, sessionId);
        if (locked == null) throw new IllegalStateException("projection_sequence_missing");
        var recorded = locked.projectionRecord();
        if (recorded != null && recorded.rejects(source))
            throw new SessionOutboxProjectionRejectedException(recorded.errorCode());
        var checkpoint = locked.projectionCheckpoint();
        if (checkpoint != null && checkpoint.covers(source)) return List.of();
        if (checkpoint == null || !checkpoint.policyVersion().equals(source.policyVersion()))
            checkpoint = SessionOutboxProjectionCheckpoint.empty(source.policyVersion());
        var batch = Objects.requireNonNull(projection.apply(checkpoint), "projection batch");
        var outputs = appendLocked(outbox, sequences, tenant, sessionId, batch.outputs(), inserted);
        if (sequences.recordProjectionCompletion(tenant, sessionId, source, batch.stateJson()) != 1)
            throw new IllegalStateException("projection_checkpoint_not_recorded");
        return outputs;
    }

    /** 永久格式错误在投影事务回滚后独立收敛；不追加 Outbox 输出、不修改来源与 Run。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean recordProjectionRejection(String tenantId, String sessionId,
            SessionOutboxProjectionSource source, String errorCode) {
        Objects.requireNonNull(source, "source");
        if (sessionId == null || sessionId.isBlank() || errorCode == null || errorCode.isBlank())
            throw new IllegalArgumentException("projection rejection must include session and reason");
        long tenant = parseTenant(tenantId);
        sequenceMapper.ensure(tenant, sessionId);
        sequenceMapper.lock(tenant, sessionId);
        return sequenceMapper.recordProjectionRejection(tenant, sessionId, source, errorCode) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public SessionOutboxEntry appendOutput(String tenantId, String sessionId, String eventId,
            String eventType, Map<String, Object> payload) {
        return appendOutput(tenantId, sessionId, eventId, eventType, payload, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SessionOutboxEntry appendOutput(String tenantId, String sessionId, String eventId,
            String eventType, Map<String, Object> payload, TraceContext trace) {
        return appendOutput(tenantId, sessionId, eventId, eventType, payload, trace, null);
    }

    /** 已验证 Runtime Run 的可见投影显式写入关联；普通输出保持 null。 */
    @Transactional(rollbackFor = Exception.class)
    public SessionOutboxEntry appendOutput(String tenantId, String sessionId, String eventId,
            String eventType, Map<String, Object> payload, TraceContext trace, String runtimeRunId) {
        long tenant = parseTenant(tenantId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        var output = new SessionOutboxOutput(eventId, eventType, payload, trace, runtimeRunId);
        sequenceMapper.ensure(tenant, sessionId);
        sequenceMapper.lock(tenant, sessionId);
        return view(appendLocked(outboxMapper, sequenceMapper, tenant, sessionId, List.of(output),
                inserted -> outputsInserted(Long.toString(tenant), sessionId, inserted)).getFirst());
    }

    /** 唯一持锁写入路径；只为新增身份分配序号，原行重放保留首次时间与传输责任。 */
    private List<SessionOutboxEntryDO> appendLocked(SessionOutboxMapper outbox,
            SessionOutboxSequenceMapper sequences, long tenant, String sessionId,
            List<SessionOutboxOutput> outputs, Consumer<List<SessionOutboxEntryDO>> onInserted) {
        if (outputs.isEmpty()) return List.of();
        var unique = new LinkedHashMap<String, SessionOutboxOutput>();
        for (var output : outputs) {
            unique.putIfAbsent(output.eventId(), output);
        }
        var rows = new LinkedHashMap<String, SessionOutboxEntryDO>();
        for (var existing : outbox.findEvents(tenant, unique.keySet().toArray(String[]::new))) {
            if (!Long.valueOf(tenant).equals(existing.getTenantId()) || !sessionId.equals(existing.getSessionId())
                    || !unique.containsKey(existing.getEventId())
                    || rows.putIfAbsent(existing.getEventId(), existing) != null) {
                throw new IllegalStateException("outbox_event_scope_conflict");
            }
        }
        int count = unique.size() - rows.size();
        if (count > 0) {
            Long end = sequences.reserve(tenant, sessionId, count);
            if (end == null || end < count) {
                throw new IllegalStateException("outbox_sequence_range_invalid");
            }
            long sequence = Math.subtractExact(end, count);
            var inserted = new ArrayList<SessionOutboxEntryDO>(count);
            Instant now = Instant.now();
            for (var output : unique.values()) {
                if (rows.containsKey(output.eventId())) continue;
                var row = new SessionOutboxEntryDO();
                row.setId(SnowflakeIds.nextId());
                row.setTenantId(tenant);
                row.setSessionId(sessionId);
                row.setEventId(output.eventId());
                row.setEventType(output.eventType());
                row.setRuntimeRunId(output.runtimeRunId());
                row.setPayload(output.payload());
                var trace = output.trace();
                if (trace != null) {
                    row.setInputId(trace.inputId());
                    row.setRootInputId(trace.rootInputId());
                    row.setTraceId(trace.traceId());
                    row.setSourceEventId(trace.eventId());
                    row.setCausedByEventId(trace.causedByEventId());
                }
                sequence = Math.incrementExact(sequence);
                row.setSessionSequence(sequence);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                inserted.add(row);
                rows.put(output.eventId(), row);
            }
            // 重投影可覆盖长历史；分段只限制 SQL 参数数量，不拆分事务或公开 checkpoint。
            for (int offset = 0; offset < inserted.size(); offset += 1000) {
                var part = inserted.subList(offset, Math.min(inserted.size(), offset + 1000));
                if (outbox.insertOutputs(part) != part.size()) {
                    throw new IllegalStateException("outbox_batch_incomplete");
                }
            }
            onInserted.accept(inserted);
        }
        return outputs.stream().map(output -> rows.get(output.eventId())).toList();
    }

    /** Spring事务入口沿原事务建立投递责任，只注册提交后的唤醒。 */
    private void outputsInserted(String tenantId, String sessionId, List<SessionOutboxEntryDO> inserted) {
        String canonicalTenant = Long.toString(parseTenant(tenantId));
        webhookDeliveryLedger.enqueueBatch(canonicalTenant, inserted);
        notifyAfterCommit(canonicalTenant, sessionId);
    }

    /** 通知必须晚于数据库提交；回滚不发布，新事件仍以持久游标作为重连事实。 */
    private void notifyAfterCommit(String tenantId, String sessionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { outputsCommitted(tenantId, sessionId); }
            });
        } else if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("outbox_transaction_synchronization_missing");
        } else {
            // 无外层事务时 SQL 已由连接自动提交；常规 Spring 入口始终走上面的提交回调。
            outputsCommitted(tenantId, sessionId);
        }
    }

    @Transactional(readOnly = true)
    public List<SessionOutboxEntry> listOutputsAfter(String tenantId, String sessionId,
            String afterEventId, int limit) {
        if (invalidScope(tenantId, sessionId, limit)) return List.of();
        return listOutputsAfterSequence(tenantId, sessionId,
                resolveOutputSequence(tenantId, sessionId, afterEventId), limit);
    }

    /** 建立观察时验证游标归属；连接内仅推进服务端已确认的 Session 序号。 */
    @Transactional(readOnly = true)
    public long resolveOutputSequence(String tenantId, String sessionId, String afterEventId) {
        long tenant = parseTenant(tenantId);
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId must not be blank");
        if (afterEventId != null && !afterEventId.isBlank()) {
            Long sequence = outboxMapper.outputSequence(tenant, sessionId, afterEventId);
            if (sequence == null) {
                throw new com.skillforge.core.shared.exception.ApiException(
                        org.springframework.http.HttpStatus.CONFLICT, "EVENT_CURSOR_EXPIRED",
                        "事件游标不可用，请重新读取会话历史", false);
            }
            return sequence;
        }
        return 0;
    }

    /** 序号只由已验证游标或已读取条目产生；此入口不接收客户端序号。 */
    @Transactional(readOnly = true)
    public List<SessionOutboxEntry> listOutputsAfterSequence(String tenantId, String sessionId,
            long afterSequence, int limit) {
        if (invalidScope(tenantId, sessionId, limit)) return List.of();
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must not be negative");
        long tenant = parseTenant(tenantId);
        return outboxMapper.selectList(new QueryWrapper<SessionOutboxEntryDO>()
                .eq("tenant_id", tenant).eq("session_id", sessionId)
                .gt("session_sequence", afterSequence).orderByAsc("session_sequence")
                .last("LIMIT " + limit)).stream().map(SessionMailboxService::view).toList();
    }

    /** History 读取前捕获已提交输出的保守起点；新输出从此游标继续，观察不代表客户端已读。 */
    @Transactional(readOnly = true)
    public String committedCursor(String tenantId, String sessionId) {
        return outboxMapper.committedCursor(parseTenant(tenantId), sessionId);
    }

    /** 模型首次加载只读取有界独立消息的最新公开快照，不扫描私有Run或缓存历史。 */
    @Transactional(readOnly = true)
    public List<SessionOutboxEntry> recentMessages(String tenantId, String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit < 1 || limit > 100)
            throw new IllegalArgumentException("outbox_recent_messages_scope_invalid");
        return outboxMapper.recentMessages(parseTenant(tenantId), sessionId, limit).stream()
                .map(SessionMailboxService::view).toList();
    }

    /** 在已授权会话中读取指定消息的最新快照，不展开其他消息。 */
    @Transactional(readOnly = true)
    public java.util.Optional<SessionOutboxEntry> message(String tenantId, String sessionId, String messageId) {
        if (sessionId == null || sessionId.isBlank() || messageId == null || messageId.isBlank())
            throw new IllegalArgumentException("outbox_message_scope_invalid");
        return java.util.Optional.ofNullable(outboxMapper.message(parseTenant(tenantId), sessionId, messageId))
                .map(SessionMailboxService::view);
    }

    /** 仅在 chat 内将已读事实转为业务快照；存储身份和可写引用不穿过模块边界。 */
    static SessionOutboxEntry view(SessionOutboxEntryDO row) {
        return new SessionOutboxEntry(row.getTenantId(), row.getSessionId(), row.getRuntimeRunId(),
                row.getEventId(), row.getInputId(), row.getRootInputId(), row.getTraceId(),
                row.getSourceEventId(), row.getCausedByEventId(), row.getSessionSequence(),
                row.getEventType(), row.getPayload(), row.getCreatedAt(),
                row.getFirstMessageSequence(), row.getFirstMessageCreatedAt());
    }

    private boolean invalidScope(String tenantId, String sessionId, int limit) {
        return tenantId == null || tenantId.isBlank() || sessionId == null || sessionId.isBlank() || limit <= 0;
    }

    private long parseTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        return Long.parseLong(tenantId);
    }
}

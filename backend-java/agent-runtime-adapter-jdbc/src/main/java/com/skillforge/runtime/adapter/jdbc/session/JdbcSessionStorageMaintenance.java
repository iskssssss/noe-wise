package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionOwner;
import java.io.InputStream;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * Session 存储的有界维护入口。到期CLOSED Owner先冻结可解析归档，保持恢复/幂等身份，再释放热正文引用。
 * 诊断清理另需宿主传入明确 Session 和保留截止，不默认业务 TTL，不处理模型结果或幂等账本。
 */
public final class JdbcSessionStorageMaintenance {
    private static final String RESOURCE = "mapper/AgentRuntimeSessionStorageMaintenanceMapper.xml";
    private static final int MAXIMUM_BATCH = 128;
    private static final Duration MINIMUM_HISTORY_RETENTION = Duration.ofDays(30);
    private final SqlSessionFactory sessions;
    private final Duration historyRetention;

    /** 独立维护事务仍使用应用受限角色及 FORCE RLS；不会复用业务线程的租户上下文。 */
    public JdbcSessionStorageMaintenance(DataSource source) {
        this(source, Duration.ofDays(30));
    }

    /** 测试使用与生产相同的事务/算法入口；数据库权限、锁与FK仍须真实PostgreSQL验证。 */
    JdbcSessionStorageMaintenance(SqlSessionFactory sessions, Duration historyRetention) {
        if (historyRetention == null || historyRetention.compareTo(MINIMUM_HISTORY_RETENTION) < 0)
            throw new IllegalArgumentException("session_history_retention_invalid");
        this.sessions = Objects.requireNonNull(sessions);
        this.historyRetention = historyRetention;
    }

    /** 装配仅接收JDK类型，避免构造器重载解析把私有MyBatis工厂带入Starter编译边界。 */
    public static JdbcSessionStorageMaintenance withRetention(DataSource source, Duration historyRetention) {
        return new JdbcSessionStorageMaintenance(source, historyRetention);
    }

    /** 保留期限来自明确业务政策，不改变任何Run或输入幂等回执窗口。 */
    private JdbcSessionStorageMaintenance(DataSource source, Duration historyRetention) {
        if (historyRetention == null || historyRetention.compareTo(MINIMUM_HISTORY_RETENTION) < 0)
            throw new IllegalArgumentException("session_history_retention_invalid");
        this.historyRetention = historyRetention;
        var configuration = new Configuration(new Environment("session-storage-maintenance",
                new JdbcTransactionFactory(), Objects.requireNonNull(source)));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream log = getClass().getClassLoader().getResourceAsStream("mapper/AgentRuntimeSessionLogMapper.xml")) {
            if (log == null) throw new IllegalStateException("session_log_mapper_missing");
            new XMLMapperBuilder(log, configuration, "mapper/AgentRuntimeSessionLogMapper.xml", configuration.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("session_storage_maintenance_initialization_failure", failure); }
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (xml == null) throw new IllegalStateException("session_storage_maintenance_mapper_missing");
            new XMLMapperBuilder(xml, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("session_storage_maintenance_initialization_failure", failure); }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 返回数据库 DELETE RETURNING 聚合的真实行数与原正文 bytes，不能替换为调用计数。 */
    public record Reclaimed(long checkpointBodyRows, long checkpointBodyBytes, long eventBodyRows, long eventBodyBytes, Cursor checkpointNext, EventCursor eventNext) { }

    /** PK顺序的有界续接位置；NULL游标开始一轮扫描，返回NULL表示本轮结束，下一轮覆盖新插入和曾被锁定的条目。 */
    public record Cursor(String runId, String contentHash) {
        public Cursor {
            if (runId == null || runId.isBlank() || contentHash == null || !contentHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("session_storage_maintenance_cursor_invalid");
        }
    }

    /** 无真实Run的Memory事件正文按完整Owner主键域分页，不能借用Run游标。 */
    public record EventCursor(String targetKind, String targetId, String sessionId, String contentHash) {
        public EventCursor {
            if (targetKind == null || targetKind.isBlank() || targetId == null || targetId.isBlank()
                    || sessionId == null || sessionId.isBlank() || contentHash == null || !contentHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("session_event_body_maintenance_cursor_invalid");
        }
    }

    /** 纯诊断本批删除结果与Owner整体退役的保护理由；理由不表示授予了删除普通历史的权限。 */
    public record DiagnosticPurge(long rows, long bytes, List<String> ownerProtectedReasons) {
        public DiagnosticPurge { ownerProtectedReasons = List.copyOf(ownerProtectedReasons); }
    }

    /** 已关闭Owner的公平扫描位置；返回NULL表示完成一轮，重启从头仍保留每Owner持久事件水位。 */
    public record OwnerCursor(String targetKind, String targetId, String sessionId) { }

    /** 普通展示历史批次；rows/bytes来自真实DELETE，保护理由不等于给了全部Owner退役权限。 */
    public record HistoryBatch(int scannedOwners, long rows, long bytes, OwnerCursor next, boolean moreWork, List<String> protectedReasons) {
        public HistoryBatch { protectedReasons = List.copyOf(protectedReasons); }
    }

    /** 已完成归档切换的对象量；bytes计热字段净缩小及正文删除，准备性复制与冷留存另计，不代表关系文件缩小。 */
    public record OwnerBatch(int scannedOwners, long archivedRows, long archivedBytes, OwnerCursor next,
            boolean moreWork, List<String> protectedReasons) {
        public OwnerBatch { protectedReasons = List.copyOf(protectedReasons); }
    }

    /**
     * 每Owner至多objectLimit个冻结对象，同事务登记真实关闭事实、归档、切换读路由和释放热引用。
     * 原Owner、Run、输入/操作指纹与sequence保持有效；归档读取仍核原完整事实，不产生新准入或效果。
     * 私有退休函数再次锁Owner/Session/Run并核保护闭包；游标只负责租户内公平分页，持久事实负责续接。
     */
    public OwnerBatch retireOwners(TenantId tenant, int ownerLimit, int objectLimit, OwnerCursor cursor) {
        requireLimit(ownerLimit); requireLimit(objectLimit);
        if (ownerLimit > 4 || objectLimit > 16)
            throw new IllegalArgumentException("session_owner_retirement_batch_too_large");
        return transaction(tenant, mapper -> {
            Instant cutoff = mapper.storageNow().minus(historyRetention);
            var owners = mapper.scanRetirementOwners(tenant.value(), cutoff.getEpochSecond(), cutoff.getNano(), ownerLimit, cursor);
            long rows = 0, bytes = 0;
            boolean pending = false;
            var reasons = new java.util.LinkedHashSet<String>();
            for (var candidate : owners) {
                var result = mapper.retireOwner(candidate.owner(tenant), objectLimit);
                if (result == null) throw new IllegalStateException("session_owner_retirement_result_missing");
                rows += result.rows; bytes += result.bytes; pending |= result.moreWork;
                if (result.protectedReasons != null && !result.protectedReasons.isBlank())
                    reasons.addAll(List.of(result.protectedReasons.split("\\|")));
            }
            OwnerCursor next = owners.size() < ownerLimit ? null : owners.getLast().cursor();
            return new OwnerBatch(owners.size(), rows, bytes, next, pending || next != null, List.copyOf(reasons));
        });
    }

    /**
     * 按30天（或显式配置）筛选域内CLOSED Session，再锁Owner/Session重查真实保护闭包。
     * 每Owner最多eventLimit个展示事件；保留首/末助手快照和adoption，退休推理明确声明历史不完整。
     * Run mutation、commit journal、input receipt及其checkpoint引用不参与此回收。
     */
    public HistoryBatch retireHistory(TenantId tenant, int ownerLimit, int eventLimit, OwnerCursor cursor) {
        requireLimit(ownerLimit); requireLimit(eventLimit);
        if (ownerLimit > 8 || eventLimit > 16) throw new IllegalArgumentException("session_history_retirement_batch_too_large");
        return transaction(tenant, mapper -> {
            Instant cutoff = mapper.storageNow().minus(historyRetention);
            var owners = mapper.scanClosedOwners(tenant.value(), cutoff.getEpochSecond(), cutoff.getNano(), ownerLimit, cursor);
            long rows = 0, bytes = 0;
            boolean unfinished = false;
            var protectedReasons = new java.util.LinkedHashSet<String>();
            for (var candidate : owners) {
                var owner = candidate.owner(tenant);
                if (mapper.lockOwner(owner) == null) { protectedReasons.add("OWNER_NOT_FOUND_OR_CHANGED"); continue; }
                var closed = mapper.lockClosedOwner(owner, cutoff.getEpochSecond(), cutoff.getNano());
                if (closed == null) { protectedReasons.add("OWNER_NOT_CLOSED_OR_NOT_EXPIRED"); continue; }
                var protectedOwner = mapper.ownerProtection(owner);
                if (!protectedOwner.isEmpty()) { protectedReasons.addAll(protectedOwner); continue; }
                mapper.ensureHistoryRetirement(owner, closed.sourceSessionRevision, closed.closedEpochSecond, closed.closedNano);
                var retirement = mapper.lockHistoryRetirement(owner);
                if (retirement == null || retirement.sourceSessionRevision != closed.sourceSessionRevision
                        || retirement.closedEpochSecond != closed.closedEpochSecond || retirement.closedNano != closed.closedNano)
                    throw new IllegalStateException("session_history_retirement_source_changed");
                var keys = mapper.historyEventKeys(owner, retirement.resumeAfterSequence, eventLimit);
                var page = keys.isEmpty() ? List.<SessionEventRow>of() : mapper.lockHistoryEvents(owner,
                        retirement.resumeAfterSequence, keys.getLast().sessionSequence, eventLimit);
                var locked = new java.util.HashMap<Long, SessionEventRow>();
                for (var row : page) locked.put(row.getSessionSequence(), row);
                long through = retirement.resumeAfterSequence;
                Long recheck = retirement.recheckAfterSequence;
                long reasoning = 0;
                long localRows = 0, localBytes = 0;
                for (var key : keys) {
                    through = key.sessionSequence;
                    var source = locked.get(through);
                    if (source == null) {
                        protectedReasons.add("EVENT_LOCKED");
                        long retryAfter = through - 1;
                        recheck = recheck == null ? retryAfter : Math.min(recheck, retryAfter);
                        continue;
                    }
                    if (!key.eventId.equals(source.getEventId())) throw new IllegalStateException("session_history_event_source_changed");
                    var event = source.toCommittedEvent();
                    if (mapper.eventProtected(owner, event.eventId().value())) {
                        protectedReasons.add("EVENT_REFERENCE");
                        long retryAfter = event.sessionSequence() - 1;
                        recheck = recheck == null ? retryAfter : Math.min(recheck, retryAfter);
                        continue;
                    }
                    if (!"agent.model-reasoning".equals(event.eventType())) continue;
                    var target = new com.skillforge.runtime.api.port.SessionProjectionTarget(event.owner(), event.agentId(), event.inputId(),
                            event.runRef(), event.executionRef(), event.actionRef(), event.delegationRef());
                    var draft = new com.skillforge.runtime.api.port.SessionEventDraft(event.eventId(), event.eventType(), event.eventVersion(),
                            source.getEventFingerprint(), event.trace(), event.payload(), event.occurredAt());
                    String digest = SessionEventTombstoneRow.digest(new com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent(target, draft), event.sessionSequence());
                    if (mapper.insertEventTombstone(source, digest) != 1) throw new IllegalStateException("session_history_tombstone_conflict");
                    var deleted = mapper.deleteHistoryEvent(owner, event.eventId().value(), event.sessionSequence());
                    if (deleted.rows != 1) throw new IllegalStateException("session_history_retirement_not_applied");
                    localRows += deleted.rows; localBytes += deleted.bytes;
                    reasoning++;
                }
                boolean roundEnded = keys.size() < eventLimit;
                boolean complete = roundEnded && recheck == null;
                unfinished |= !roundEnded;
                long resume = roundEnded && recheck != null ? recheck : through;
                Long nextRecheck = roundEnded ? null : recheck;
                // 单独回到最早受保护位置；公开处理/退休水位从不回退。保护解除后可继续，空保护轮不反复UPDATE。
                if (through > retirement.scanAfterSequence || resume != retirement.resumeAfterSequence
                        || !Objects.equals(nextRecheck, retirement.recheckAfterSequence) || localRows > 0
                        || complete && !"RETIRED".equals(retirement.status)) {
                    if (mapper.advanceHistoryRetirement(owner, retirement.resumeAfterSequence, through, resume, nextRecheck,
                            localRows, localBytes, reasoning, complete) != 1)
                        throw new IllegalStateException("session_history_retirement_progress_conflict");
                }
                rows += localRows; bytes += localBytes;
            }
            OwnerCursor next = owners.size() < ownerLimit ? null : owners.getLast().cursor();
            return new HistoryBatch(owners.size(), rows, bytes, next, unfinished || next != null, List.copyOf(protectedReasons));
        });
    }

    /** 只读检查Owner闭包；由宿主决定明确退役操作，不以终态/ACK代替退役事实。 */
    public List<String> protectedOwnerReasons(SessionOwner owner) {
        owner.requireUser();
        Objects.requireNonNull(owner);
        return transaction(owner.sessionRef().tenantId(), mapper -> List.copyOf(mapper.ownerProtection(owner)));
    }

    /**
     * 每类至多 limit 行，先 SKIP LOCKED 领取正文，再用新语句快照重新检查引用。
     * 锁顺序与 writer 的 FOR KEY SHARE 互斥，避免领取前的旧 NOT EXISTS 快照漏掉刚提交引用。
     * 无引用正文不承担业务保留期限；执行/等待/UNKNOWN 的全部 checkpoint 引用仍由 FK 保护。
     */
    public Reclaimed collectOrphans(TenantId tenant, int limit, Cursor checkpointCursor, EventCursor eventCursor) {
        requireLimit(limit);
        return transaction(tenant, mapper -> {
            var checkpointKeys = mapper.lockCheckpointBodies(tenant.value(), limit, checkpointCursor);
            var checkpoint = checkpointKeys.isEmpty() ? StorageReclaimedRow.empty()
                    : mapper.deleteCheckpointBodies(tenant.value(), checkpointKeys);
            var eventKeys = mapper.lockEventBodies(tenant.value(), limit, eventCursor);
            var event = eventKeys.isEmpty() ? StorageReclaimedRow.empty()
                    : mapper.deleteEventBodies(tenant.value(), eventKeys);
            return new Reclaimed(checkpoint.rows, checkpoint.bytes,
                    event.rows, event.bytes, next(checkpointKeys, limit), nextEvent(eventKeys, limit));
        });
    }

    /** 每批只扫描已领取的有限主键页；保护条目同样推进水位，避免队头反复阻挡后续孤儿。 */
    private static Cursor next(List<SessionStorageBodyKey> keys, int limit) {
        if (keys.size() < limit) return null;
        var last = keys.getLast();
        return new Cursor(last.runId, last.contentHash);
    }

    private static EventCursor nextEvent(List<SessionEventBodyKey> keys, int limit) {
        if (keys.size() < limit) return null;
        var last = keys.getLast();
        return new EventCursor(last.targetKind, last.targetId, last.sessionId, last.contentHash);
    }

    /**
     * 仅删除真实CLOSED且满批准保留期Session内的纯计量，显式cutoff只能延长保留。
     * 原事件也冻结完整v2墓碑，精确Append幂等和原sequence不因诊断正文回收而改变。
     */
    public DiagnosticPurge purgeDiagnostics(SessionOwner owner, Instant cutoff, int limit) {
        owner.requireUser();
        Objects.requireNonNull(owner); Objects.requireNonNull(cutoff); requireLimit(limit);
        return transaction(owner.sessionRef().tenantId(), mapper -> {
            Instant now = mapper.storageNow();
            if (cutoff.isAfter(now)) throw new IllegalArgumentException("diagnostic_retention_cutoff_in_future");
            if (mapper.lockOwner(owner) == null)
                return new DiagnosticPurge(0, 0, List.of("OWNER_NOT_FOUND_OR_CHANGED"));
            Instant approvedCutoff = now.minus(historyRetention);
            var closed = mapper.lockClosedOwner(owner, approvedCutoff.getEpochSecond(), approvedCutoff.getNano());
            if (closed == null) return new DiagnosticPurge(0, 0, List.of("OWNER_NOT_CLOSED_OR_NOT_EXPIRED"));
            var protection = List.copyOf(mapper.ownerProtection(owner));
            if (!protection.isEmpty()) return new DiagnosticPurge(0, 0, protection);
            mapper.ensureHistoryRetirement(owner, closed.sourceSessionRevision, closed.closedEpochSecond, closed.closedNano);
            var retirement = mapper.lockHistoryRetirement(owner);
            if (retirement == null || retirement.sourceSessionRevision != closed.sourceSessionRevision
                    || retirement.closedEpochSecond != closed.closedEpochSecond || retirement.closedNano != closed.closedNano)
                throw new IllegalStateException("session_history_retirement_source_changed");
            Instant effectiveCutoff = cutoff.isBefore(approvedCutoff) ? cutoff : approvedCutoff;
            var page = mapper.lockDiagnosticEvents(owner, effectiveCutoff.getEpochSecond(), effectiveCutoff.getNano(), limit);
            long rows = 0, bytes = 0;
            for (var source : page) {
                var event = source.toCommittedEvent();
                if (mapper.eventProtected(owner, event.eventId().value())) continue;
                var target = new com.skillforge.runtime.api.port.SessionProjectionTarget(event.owner(), event.agentId(), event.inputId(),
                        event.runRef(), event.executionRef(), event.actionRef(), event.delegationRef());
                var draft = new com.skillforge.runtime.api.port.SessionEventDraft(event.eventId(), event.eventType(), event.eventVersion(),
                        source.getEventFingerprint(), event.trace(), event.payload(), event.occurredAt());
                String digest = SessionHistoryDigest.digest(new com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent(target, draft), event.sessionSequence());
                if (mapper.insertEventTombstone(source, digest) != 1) throw new IllegalStateException("session_history_tombstone_conflict");
                var deleted = mapper.deleteHistoryEvent(owner, event.eventId().value(), event.sessionSequence());
                if (deleted.rows != 1) throw new IllegalStateException("session_diagnostic_retirement_not_applied");
                rows += deleted.rows; bytes += deleted.bytes;
            }
            if (rows > 0 && mapper.advanceDiagnosticRetirement(owner, rows, bytes) != 1)
                throw new IllegalStateException("session_diagnostic_retirement_progress_conflict");
            return new DiagnosticPurge(rows, bytes, protection);
        });
    }

    private <T> T transaction(TenantId tenant, MaintenanceWork<T> work) {
        Objects.requireNonNull(tenant);
        try (var session = sessions.openSession(false)) {
            try {
                var mapper = session.getMapper(SessionStorageMaintenanceMapper.class);
                mapper.bindTenant(tenant.value());
                T result = work.apply(mapper);
                session.commit(); return result;
            } catch (RuntimeException failure) { session.rollback(); throw failure; }
        }
    }
    private static void requireLimit(int limit) {
        if (limit <= 0 || limit > MAXIMUM_BATCH) throw new IllegalArgumentException("session_storage_maintenance_limit_invalid");
    }
    /** 维护事务内的操作；调用方不能取得连接或另提交。 */
    @FunctionalInterface private interface MaintenanceWork<T> { T apply(SessionStorageMaintenanceMapper mapper); }
}

/** 维护候选只携带所属复合身份；大型正文不进入扫描结果。 */
final class SessionStorageBodyKey {
    public String runId;
    public String contentHash;
}

final class SessionEventBodyKey {
    public String targetKind;
    public String targetId;
    public String sessionId;
    public String contentHash;
}

/** DELETE RETURNING 的实际删除聚合，零行也有显式 0。 */
final class StorageReclaimedRow {
    public long rows;
    public long bytes;
    static StorageReclaimedRow empty() { return new StorageReclaimedRow(); }
}

/** 从权威Session表读取的精确关闭事实，不由请求体提供状态或修订。 */
final class SessionHistoryOwnerRow {
    public String targetKind;
    public String targetId;
    public String sessionId;
    public long endUserId;
    public long sourceSessionRevision;
    public long closedEpochSecond;
    public int closedNano;
    com.skillforge.runtime.api.port.SessionOwner owner(TenantId tenant) {
        return com.skillforge.runtime.api.port.SessionOwner.user(new com.skillforge.runtime.api.SessionRef(tenant, sessionId),
                new com.skillforge.runtime.api.SessionTarget(com.skillforge.runtime.api.SessionTarget.Kind.valueOf(targetKind), targetId),
                new com.skillforge.runtime.api.EndUserId(endUserId));
    }
    JdbcSessionStorageMaintenance.OwnerCursor cursor() { return new JdbcSessionStorageMaintenance.OwnerCursor(targetKind, targetId, sessionId); }
}

/** 首片保留原排序和创建时刻，末片保留最终公开version与前缀。 */

/** 维护私有扫描位置独立于公开退休水位；保护引用解除后重扫原位置，不重分配任何已确认序号。 */
final class SessionHistoryScanRow {
    public long scanAfterSequence;
    public long resumeAfterSequence;
    public Long recheckAfterSequence;
    public long sourceSessionRevision;
    public long closedEpochSecond;
    public int closedNano;
    public String status;
}

/** 先读有界身份页再SKIP LOCKED领取；未锁住的身份保存重扫位置，不能推进后永久漏掉。 */
final class SessionHistoryEventKey { public String eventId; public long sessionSequence; }

/** 同事务私有归档函数的实际处理量和未解除保护。 */
final class SessionOwnerRetirementRow {
    public long rows;
    public long bytes;
    public boolean moreWork;
    public String protectedReasons;
}

/** 受 RLS 约束的有界维护 SQL，未绑定租户不能领取或删除。 */
interface SessionStorageMaintenanceMapper {
    String bindTenant(@Param("tenantId") String tenantId);
    Instant storageNow();
    Long lockOwner(@Param("owner") SessionOwner owner);
    List<String> ownerProtection(@Param("owner") SessionOwner owner);
    List<SessionHistoryOwnerRow> scanRetirementOwners(@Param("tenantId") String tenantId, @Param("cutoffSecond") long cutoffSecond,
            @Param("cutoffNano") int cutoffNano, @Param("limit") int limit, @Param("cursor") JdbcSessionStorageMaintenance.OwnerCursor cursor);
    SessionOwnerRetirementRow retireOwner(@Param("owner") SessionOwner owner, @Param("limit") int limit);
    List<SessionHistoryOwnerRow> scanClosedOwners(@Param("tenantId") String tenantId, @Param("cutoffSecond") long cutoffSecond,
            @Param("cutoffNano") int cutoffNano, @Param("limit") int limit, @Param("cursor") JdbcSessionStorageMaintenance.OwnerCursor cursor);
    SessionHistoryOwnerRow lockClosedOwner(@Param("owner") SessionOwner owner, @Param("cutoffSecond") long cutoffSecond, @Param("cutoffNano") int cutoffNano);
    int ensureHistoryRetirement(@Param("owner") SessionOwner owner, @Param("revision") long revision, @Param("closedSecond") long closedSecond,
            @Param("closedNano") int closedNano);
    SessionHistoryScanRow lockHistoryRetirement(@Param("owner") SessionOwner owner);
    List<SessionHistoryEventKey> historyEventKeys(@Param("owner") SessionOwner owner, @Param("after") long after, @Param("limit") int limit);
    List<SessionEventRow> lockHistoryEvents(@Param("owner") SessionOwner owner, @Param("after") long after, @Param("through") long through, @Param("limit") int limit);
    boolean eventProtected(@Param("owner") SessionOwner owner, @Param("eventId") String eventId);
    int insertEventTombstone(@Param("row") SessionEventRow row, @Param("digest") String digest);
    StorageReclaimedRow deleteHistoryEvent(@Param("owner") SessionOwner owner, @Param("eventId") String eventId, @Param("sequence") long sequence);
    int advanceHistoryRetirement(@Param("owner") SessionOwner owner, @Param("expectedAfter") long expectedAfter, @Param("after") long after,
            @Param("resume") long resume, @Param("recheck") Long recheck,
            @Param("rows") long rows, @Param("bytes") long bytes, @Param("reasoning") long reasoning, @Param("complete") boolean complete);
    List<SessionStorageBodyKey> lockCheckpointBodies(@Param("tenantId") String tenantId, @Param("limit") int limit,
            @Param("cursor") JdbcSessionStorageMaintenance.Cursor cursor);
    StorageReclaimedRow deleteCheckpointBodies(@Param("tenantId") String tenantId, @Param("keys") List<SessionStorageBodyKey> keys);
    List<SessionEventBodyKey> lockEventBodies(@Param("tenantId") String tenantId, @Param("limit") int limit,
            @Param("cursor") JdbcSessionStorageMaintenance.EventCursor cursor);
    StorageReclaimedRow deleteEventBodies(@Param("tenantId") String tenantId, @Param("keys") List<SessionEventBodyKey> keys);
    List<SessionEventRow> lockDiagnosticEvents(@Param("owner") SessionOwner owner, @Param("cutoffSecond") long cutoffSecond,
            @Param("cutoffNano") int cutoffNano, @Param("limit") int limit);
    int advanceDiagnosticRetirement(@Param("owner") SessionOwner owner, @Param("rows") long rows, @Param("bytes") long bytes);
}

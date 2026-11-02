package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.SessionDelegations;
import com.skillforge.runtime.session.spi.SessionDelegations.Entry;
import com.skillforge.runtime.session.spi.SessionDelegations.State;
import com.skillforge.runtime.session.spi.SessionDelegations.Wait;
import java.io.InputStream;
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

/** 原生委派 Entry/Wait 冻结快照、生命周期 CAS 与租户恢复扫描的 PostgreSQL 适配。 */
public final class JdbcSessionDelegations implements SessionDelegations.Store {

    /** JSON 由宿主按 Runtime 的 Jackson/Jdk8/JavaTime 配置提供，适配器只验证无损往返。 */
    public interface Codec {
        String encodeEntry(Entry entry);
        Entry decodeEntry(String snapshot);
        String encodeWait(Wait wait);
        Wait decodeWait(String snapshot);
    }

    private static final String MAPPER_RESOURCE =
            "mapper/AgentRuntimeSessionDelegationsMapper.xml";
    /** 会话历史包含终态和多个父Run；该读取上限不扩大恢复扫描批次。 */
    private static final int MAX_HISTORY_LIMIT = 1000;
    private static final int MAX_SCAN_LIMIT = 100;

    // 扫描位置不属于业务状态；重启从头开始，后续每批仍跨过未变化的候选。
    private final java.util.concurrent.ConcurrentMap<TenantId, ScanCursor> activeCursors = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<TenantId, ScanCursor> waitCursors = new java.util.concurrent.ConcurrentHashMap<>();
    public record ScanCursor(String parentRun, String id) { }
    private final SqlSessionFactory sessions;
    private final Codec codec;
    public JdbcSessionDelegations(DataSource dataSource, Codec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
        var configuration = new Configuration(new Environment(
                "session-delegations", new JdbcTransactionFactory(),
                Objects.requireNonNull(dataSource, "dataSource")));
        configuration.setMapUnderscoreToCamelCase(true);
        configure(configuration);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 新执行Store工厂预注册领域SQL；提交事务不再创建工厂或开启独立连接。 */
    public static void configure(Configuration configuration) {
        Objects.requireNonNull(configuration);
        if (configuration.hasMapper(SessionDelegationsMapper.class)) return;
        try (InputStream xml = JdbcSessionDelegations.class.getClassLoader()
                .getResourceAsStream(MAPPER_RESOURCE)) {
            if (xml == null) {
                throw new IllegalStateException(
                        "session_delegations_mapper_missing");
            }
            new XMLMapperBuilder(xml, configuration, MAPPER_RESOURCE,
                    configuration.getSqlFragments()).parse();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "session_delegations_initialization_failure", failure);
        }
    }

    /** 调用方已取得budget root和父Run锁；这里仅锁真实Entry并消费同一SqlSession。 */
    public Entry requireEntry(SqlSession session, RunIdentity parent, String id) {
        Objects.requireNonNull(session); requireParentAndText(parent, id);
        var mapper = session.getMapper(SessionDelegationsMapper.class);
        var before = mapper.findEntry(SessionDelegationParentRow.from(parent), id);
        if (before == null) throw invalid();
        mapper.lockEntry(parent.runRef().runId(), id, before.operationKey);
        var row = mapper.findEntry(SessionDelegationParentRow.from(parent), id);
        if (row == null) throw invalid();
        return row.restore(codec);
    }

    /** 精确领域Wait和依赖Entry按稳定顺序锁定，不读取旧Run/checkpoint/recovery。 */
    public List<Entry> requireWait(SqlSession session, Wait expected) {
        Objects.requireNonNull(session); Objects.requireNonNull(expected);
        var mapper = session.getMapper(SessionDelegationsMapper.class);
        mapper.lockWait(expected.parent().runRef().runId(), expected.id());
        var row = mapper.findWait(SessionDelegationParentRow.from(expected.parent()), expected.id());
        if (row == null || !sameFrozenWait(row.restore(codec), expected)) throw invalid();
        var entries = new java.util.LinkedHashMap<String, Entry>();
        expected.delegationIds().stream().sorted().forEach(id -> entries.put(id, requireEntry(session, expected.parent(), id)));
        return expected.delegationIds().stream().map(entries::get).toList();
    }

    /** 只有新Store已验证并接受内部依赖回应的事务可调用；不构建RECOVERY envelope。 */
    public void confirmDelivery(SqlSession session, Wait expected) {
        var mapper = session.getMapper(SessionDelegationsMapper.class);
        var row = mapper.findWait(SessionDelegationParentRow.from(expected.parent()), expected.id());
        if (row == null) throw invalid();
        var current = row.restore(codec);
        if (!sameFrozenWait(current, expected)) throw invalid();
        if (current.delivered()) return;
        var next = new Wait(current.id(), current.parent(), current.delegationIds(), true, current.revision() + 1);
        if (mapper.deliverWait(SessionDelegationWaitRow.from(next, encodeWait(next)), current.revision()) != 1) throw invalid();
    }

    /** 首次只接受 queued/revision=1；重放返回可能已推进的当前记录。 */
    @Override
    public Entry create(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.state() != State.QUEUED || entry.revision() != 1
                || entry.result().isPresent() || entry.cancelKey().isPresent()) {
            throw invalid();
        }
        String snapshot = encodeEntry(entry);
        return transaction(entry.parent().runRef().tenantId(),
                "session_delegation_create_rejected", mapper -> {
                    mapper.lockEntry(entry.parent().runRef().runId(),
                            entry.id(), entry.operationKey());
                    var collisions = mapper.findEntryCollisions(
                            entry.parent().runRef().runId(), entry.id(),
                            entry.operationKey());
                    if (!collisions.isEmpty()) {
                        if (collisions.size() != 1) {
                            throw invalid();
                        }
                        Entry current = collisions.getFirst().restore(codec);
                        if (!sameFrozenEntry(current, entry)) {
                            throw invalid();
                        }
                        return current;
                    }
                    if (mapper.insertEntry(
                            SessionDelegationEntryRow.from(entry, snapshot)) != 1) {
                        throw invalid();
                    }
                    return entry;
                });
    }

    @Override
    public Optional<Entry> read(RunIdentity parent, String id) {
        requireParentAndText(parent, id);
        return transaction(parent.runRef().tenantId(),
                "session_delegation_read_rejected", mapper ->
                        Optional.ofNullable(mapper.findEntry(
                                        SessionDelegationParentRow.from(parent), id))
                                .map(row -> row.restore(codec)));
    }

    @Override
    public List<Entry> list(RunIdentity parent) {
        Objects.requireNonNull(parent, "parent");
        return transaction(parent.runRef().tenantId(),
                "session_delegation_list_rejected", mapper ->
                        mapper.listEntries(SessionDelegationParentRow.from(parent))
                                .stream()
                                .map(row -> row.restore(codec)).toList());
    }

    @Override
    public List<Entry> forSession(SessionOwner owner, int limit) {
        Objects.requireNonNull(owner, "owner");
        requireHistoryLimit(limit);
        return transaction(owner.sessionRef().tenantId(),
                "session_delegation_history_read_rejected", mapper ->
                        mapper.sessionEntries(owner, limit).stream()
                                .map(row -> row.restore(codec)).toList());
    }

    @Override
    public List<Entry> active(TenantId tenant, int limit) {
        Objects.requireNonNull(tenant, "tenant");
        requireLimit(limit);
        var page = transaction(tenant, "session_delegation_scan_rejected",
                mapper -> mapper.activeEntries(limit, activeCursors.get(tenant)).stream()
                        .map(row -> row.restore(codec)).toList());
        if (!page.isEmpty()) {
            var last = page.getLast();
            activeCursors.put(tenant, new ScanCursor(last.parent().runRef().runId(), last.id()));
        } else activeCursors.remove(tenant);
        return page;
    }

    /** CAS 竞争只返回 false；身份、冻结任务或非法状态迁移属于契约错误并整笔回滚。 */
    @Override
    public boolean update(Entry entry, long expectedRevision) {
        Objects.requireNonNull(entry, "entry");
        if (expectedRevision < 1 || entry.revision() != expectedRevision + 1) {
            throw invalid();
        }
        String snapshot = encodeEntry(entry);
        return transaction(entry.parent().runRef().tenantId(),
                "session_delegation_update_rejected", mapper -> {
                    mapper.lockEntry(entry.parent().runRef().runId(),
                            entry.id(), entry.operationKey());
                    var collisions = mapper.findEntryCollisions(
                            entry.parent().runRef().runId(), entry.id(),
                            entry.operationKey());
                    if (collisions.isEmpty()) {
                        return false;
                    }
                    if (collisions.size() != 1) {
                        throw invalid();
                    }
                    Entry current = collisions.getFirst().restore(codec);
                    if (!sameFrozenEntry(current, entry)) {
                        throw invalid();
                    }
                    if (current.revision() != expectedRevision) {
                        return false;
                    }
                    requireTransition(current, entry);
                    return mapper.updateEntry(
                            SessionDelegationEntryRow.from(entry, snapshot),
                            expectedRevision) == 1;
                });
    }

    @Override
    public boolean admit(Entry entry, Runnable admission) {
        Objects.requireNonNull(admission, "admission");
        return transaction(entry.parent().runRef().tenantId(), "session_delegation_admission_rejected", mapper -> {
            // 与取消 CAS 使用同一持久锁，避免“尚无 child 即取消”之后出现迟到的准入。
            mapper.lockEntry(entry.parent().runRef().runId(), entry.id(), entry.operationKey());
            var row = mapper.findEntry(SessionDelegationParentRow.from(entry.parent()), entry.id());
            if (row == null) throw invalid();
            var current = row.restore(codec);
            if (!sameFrozenEntry(current, entry)) throw invalid();
            if (SessionDelegations.terminal(current.state()) || current.cancelKey().isPresent()) return false;
            admission.run();
            return true;
        });
    }

    /** 首次只登记未投递 revision=1；相同等待重放返回可能已确认的当前记录。 */
    @Override
    public Wait register(Wait wait) {
        Objects.requireNonNull(wait, "wait");
        if (wait.delivered() || wait.revision() != 1) {
            throw invalid();
        }
        String snapshot = encodeWait(wait);
        return transaction(wait.parent().runRef().tenantId(),
                "session_delegation_wait_registration_rejected", mapper -> {
                    mapper.lockWait(wait.parent().runRef().runId(), wait.id());
                    var row = mapper.findWaitCollision(
                            wait.parent().runRef().runId(), wait.id());
                    if (row != null) {
                        Wait current = row.restore(codec);
                        if (!sameFrozenWait(current, wait)) {
                            throw invalid();
                        }
                        return current;
                    }
                    var parent = SessionDelegationParentRow.from(wait.parent());
                    boolean work = workHandle(wait);
                    if (planHandle(wait)) {
                        if (!mapper.ownedPlanWait(parent,wait.delegationIds().getFirst().substring(5))) throw invalid();
                    } else if (work ? !mapper.ownedWork(parent, wait.delegationIds().getFirst().substring(5), false)
                            : mapper.countDelegations(parent, wait.delegationIds()) != wait.delegationIds().size()) throw invalid();
                    if (mapper.insertWait(
                            SessionDelegationWaitRow.from(wait, snapshot)) != 1) {
                        throw invalid();
                    }
                    return wait;
                });
    }

    @Override
    public Optional<Wait> wait(RunIdentity parent, String id) {
        requireParentAndText(parent, id);
        return transaction(parent.runRef().tenantId(),
                "session_delegation_wait_read_rejected", mapper ->
                        Optional.ofNullable(mapper.findWait(
                                        SessionDelegationParentRow.from(parent), id))
                                .map(row -> row.restore(codec)));
    }

    @Override
    public List<Wait> pendingWaits(TenantId tenant, int limit) {
        Objects.requireNonNull(tenant, "tenant");
        requireLimit(limit);
        var page = transaction(tenant, "session_delegation_wait_scan_rejected",
                mapper -> mapper.pendingWaits(limit, waitCursors.get(tenant)).stream()
                        .map(row -> row.restore(codec)).toList());
        if (!page.isEmpty()) {
            var last = page.getLast();
            waitCursors.put(tenant, new ScanCursor(last.parent().runRef().runId(), last.id()));
        } else waitCursors.remove(tenant);
        return page;
    }

    /** 完全相同的已投递快照可重复确认，首次确认必须以 revision+1 做 CAS。 */
    @Override public List<Wait> pendingWaits(RunIdentity parent) {
        Objects.requireNonNull(parent);
        return transaction(parent.runRef().tenantId(), "session_delegation_wait_read_rejected", mapper ->
                mapper.parentPendingWaits(SessionDelegationParentRow.from(parent)).stream().map(row -> row.restore(codec)).toList());
    }

    /** 完全相同的已投递快照可重复确认，首次确认必须以 revision+1 做 CAS。 */


    /** 已由同事务依赖采纳关闭的 Wait 仅重放；父 Run 终态允许关闭失去用途的业务等待。 */
    @Override public boolean delivered(Wait wait) {
        Objects.requireNonNull(wait);
        if (!wait.delivered() || wait.revision() < 2) throw invalid();
        return transaction(wait.parent().runRef().tenantId(), "session_delegation_wait_delivery_rejected", mapper -> {
            mapper.lockWait(wait.parent().runRef().runId(), wait.id());
            var row = mapper.findWaitCollision(wait.parent().runRef().runId(), wait.id());
            if (row == null) return false;
            var current = row.restore(codec);
            if (!sameFrozenWait(current, wait)) throw invalid();
            if (current.equals(wait)) return true;
            if (current.delivered() || wait.revision() != current.revision() + 1) return false;
            if (!mapper.parentEnded(SessionDelegationParentRow.from(wait.parent()))) throw invalid();
            return mapper.deliverWait(SessionDelegationWaitRow.from(wait, encodeWait(wait)), current.revision()) == 1;
        });
    }

    private static boolean workHandle(Wait wait) {
        boolean work = wait.delegationIds().stream().anyMatch(value -> value.startsWith("work:"));
        if (work && (wait.delegationIds().size() != 1 || wait.delegationIds().getFirst().length() <= 5)) throw invalid();
        return work;
    }
    private static boolean planHandle(Wait wait) {
        boolean plan=wait.delegationIds().stream().anyMatch(value->value.startsWith("plan:"));
        if(plan && (wait.delegationIds().size()!=1 || wait.delegationIds().getFirst().length()<=5
                || !wait.id().equals("delegation-wait:"+wait.delegationIds().getFirst().substring(5)))) throw invalid();
        return plan;
    }


    private String encodeEntry(Entry entry) {
        String snapshot = Objects.requireNonNull(
                codec.encodeEntry(entry), "entry snapshot");
        if (snapshot.isBlank() || !entry.equals(codec.decodeEntry(snapshot))) {
            throw invalid();
        }
        return snapshot;
    }

    private String encodeWait(Wait wait) {
        String snapshot = Objects.requireNonNull(
                codec.encodeWait(wait), "wait snapshot");
        if (snapshot.isBlank() || !wait.equals(codec.decodeWait(snapshot))) {
            throw invalid();
        }
        return snapshot;
    }





    /** SessionAdmission.fingerprint 的业务字段加独立 causation；认证引用允许刷新。 */




    private static boolean sameFrozenEntry(Entry left, Entry right) {
        return left.id().equals(right.id())
                && left.parent().equals(right.parent())
                && left.operationKey().equals(right.operationKey())
                && left.target().equals(right.target())
                && left.task().equals(right.task())
                && left.childRun().equals(right.childRun())
                && left.childInput().equals(right.childInput())
                && left.previousId().equals(right.previousId())
                && left.createdAt().equals(right.createdAt());
    }

    private static boolean sameFrozenWait(Wait left, Wait right) {
        return left.id().equals(right.id())
                && left.parent().equals(right.parent())
                && left.delegationIds().equals(right.delegationIds());
    }

    private static void requireTransition(Entry current, Entry next) {
        if (SessionDelegations.terminal(current.state())
                || next.updatedAt().isBefore(current.updatedAt())
                || current.cancelKey().isPresent()
                    && !current.cancelKey().equals(next.cancelKey())) {
            throw invalid();
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_SCAN_LIMIT) {
            throw new IllegalArgumentException(
                    "session_delegation_scan_limit_invalid");
        }
    }

    /** 历史读取独立限制为1000项，不能复用恢复调度的百项扫描上限。 */
    private static void requireHistoryLimit(int limit) {
        if (limit < 1 || limit > MAX_HISTORY_LIMIT) {
            throw new IllegalArgumentException("session_delegation_history_limit_invalid");
        }
    }

    private static void requireParentAndText(RunIdentity parent, String value) {
        Objects.requireNonNull(parent, "parent");
        if (value == null || value.isBlank()) {
            throw invalid();
        }
    }

    private <T> T transaction(TenantId tenant, String failureCode,
            MapperWork<T> work) {
        try (var session = sessions.openSession(false)) {
            try {
                var mapper = session.getMapper(SessionDelegationsMapper.class);
                mapper.bindTenant(tenant.value());
                T result = work.apply(mapper);
                session.commit();
                return result;
            } catch (RuntimeException failure) {
                session.rollback();
                throw new IllegalStateException(failureCode, failure);
            }
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("session_delegation_invalid");
    }

    @FunctionalInterface
    private interface MapperWork<T> {
        T apply(SessionDelegationsMapper mapper);
    }
}

/** SQL 唯一维护在 AgentRuntimeSessionDelegationsMapper.xml。 */
interface SessionDelegationsMapper {
    String bindTenant(@Param("tenant") String tenant);
    List<String> lockEntry(@Param("parentRun") String parentRun,
                           @Param("id") String id,
                           @Param("operation") String operation);
    List<SessionDelegationEntryRow> findEntryCollisions(
            @Param("parentRun") String parentRun,
            @Param("id") String id,
            @Param("operation") String operation);
    SessionDelegationEntryRow findEntry(
                                        @Param("parent") SessionDelegationParentRow parent,
                                        @Param("id") String id);
    List<SessionDelegationEntryRow> listEntries(
            @Param("parent") SessionDelegationParentRow parent);
    List<SessionDelegationEntryRow> sessionEntries(
            @Param("owner") SessionOwner owner,
            @Param("limit") int limit);
    List<SessionDelegationEntryRow> activeEntries(@Param("limit") int limit, @Param("cursor") JdbcSessionDelegations.ScanCursor cursor);
    int insertEntry(@Param("row") SessionDelegationEntryRow row);
    int updateEntry(@Param("row") SessionDelegationEntryRow row,
                    @Param("expectedRevision") long expectedRevision);
    String lockWait(@Param("parentRun") String parentRun,
                    @Param("id") String id);
    SessionDelegationWaitRow findWaitCollision(
            @Param("parentRun") String parentRun,
            @Param("id") String id);
    SessionDelegationWaitRow findWait(
                                      @Param("parent") SessionDelegationParentRow parent,
                                      @Param("id") String id);
    List<SessionDelegationWaitRow> pendingWaits(@Param("limit") int limit, @Param("cursor") JdbcSessionDelegations.ScanCursor cursor);
    List<SessionDelegationWaitRow> parentPendingWaits(@Param("parent") SessionDelegationParentRow parent);
    long countDelegations(@Param("parent") SessionDelegationParentRow parent,
                          @Param("ids") List<String> ids);
    long countTerminal(@Param("parent") SessionDelegationParentRow parent, @Param("ids") List<String> ids);
    boolean ownedPlanWait(@Param("parent") SessionDelegationParentRow parent,@Param("operation") String operation);
    boolean ownedWork(@Param("parent") SessionDelegationParentRow parent, @Param("work") String work, @Param("closed") boolean closed);
    boolean parentEnded(@Param("parent") SessionDelegationParentRow parent);
    int insertWait(@Param("row") SessionDelegationWaitRow row);
    int deliverWait(@Param("row") SessionDelegationWaitRow row,
                    @Param("expectedRevision") long expectedRevision);

}

package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.adapter.jdbc.observability.JdbcMapperLogging;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.dag.DagWorkPort;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 分工事件、准入绑定、协调投影与工作占用在同一事务提交；没有内存派发队列事实源。 */
public final class JdbcDagWorkPort implements DagWorkPort {
    private static final System.Logger LOG=System.getLogger(JdbcDagWorkPort.class.getName());
    public interface Codec {
        String encode(DagWork work);
        DagWork decode(String payload);
        String encodeBinding(DagWork.Binding binding);
    }

    private final SqlSessionFactory sessions;
    private final Codec codec;
    /** 仅在调用方同步事务内借用；结束时清理，不能跨线程或自行提交。 */
    private final ThreadLocal<Participation> participation = new ThreadLocal<>();

    /** 调用方已绑定的事务和租户，防止嵌套领域调用切换连接或租户。 */
    private record Participation(SqlSession session, TenantId tenant) {}

    /** 在新执行存储的配置中注册真实 DAG 与日志映射，后续借用同一连接。 */
    public static void configure(Configuration configuration) {
        JdbcSessionLogPort.register(configuration);
        if (configuration.hasMapper(DagWorkMapper.class)) return;
        String resource = "mapper/AgentRuntimeDagWorkMapper.xml";
        try (InputStream stream = JdbcDagWorkPort.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("dag_mapper_missing");
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("dag_store_initialization_failed", failure); }
    }

    /**
     * 在调用方已绑定租户的 SqlSession 中执行本实例的领域操作。
     * 调用方拥有提交与回滚；本作用域既不新开连接也不重新绑定租户，异常原样交给调用方。
     */
    public <T> T participate(SqlSession session, TenantId tenant, java.util.function.Supplier<T> operation) {
        Objects.requireNonNull(session); Objects.requireNonNull(tenant); Objects.requireNonNull(operation);
        var prior = participation.get();
        if (prior != null && (prior.session() != session || !prior.tenant().equals(tenant)))
            throw new IllegalStateException("dag_transaction_scope_conflict");
        participation.set(new Participation(session, tenant));
        try { return operation.get(); }
        finally { if (prior == null) participation.remove(); else participation.set(prior); }
    }

    /** 由宿主数据源及无损编解码器装配 DAG、Session 事件映射；资源缺失时启动失败。 */
    public JdbcDagWorkPort(DataSource dataSource, Codec codec) {
        this.codec = Objects.requireNonNull(codec);
        var configuration = new Configuration(new Environment("dag-work", new JdbcTransactionFactory(), dataSource));
        configuration.addInterceptor(new JdbcMapperLogging());
        String resource = "mapper/AgentRuntimeSessionLogMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("dag_session_log_mapper_missing");
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("dag_store_initialization_failed", failure); }
        for (String mapperResource : List.of("mapper/AgentRuntimeDagWorkMapper.xml")) {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(mapperResource)) {
                if (stream == null) throw new IllegalStateException("dag_mapper_missing");
                new XMLMapperBuilder(stream, configuration, mapperResource, configuration.getSqlFragments()).parse();
            } catch (Exception failure) { throw new IllegalStateException("dag_store_initialization_failed", failure); }
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 按完整工作身份读取快照；读取时核验归属和快照主键，禁止返回串租户事实。 */
    @Override public Optional<DagWork> read(DagWork.Key key) {
        return transaction(key.owner().sessionRef().tenantId(), session -> read(mapper(session), key, false));
    }

    /** 返回所属 Session 当前活动工作，无活动工作时返回空值。 */
    @Override public Optional<DagWork> active(SessionOwner owner) {
        return transaction(owner.sessionRef().tenantId(), session ->
                Optional.ofNullable(mapper(session).active(owner)).map(codec::decode).map(work -> requireOwner(work, owner)));
    }

    /** 按持久受理序号读取有限条可恢复工作，限制批量大小避免无界扫描。 */
    @Override public List<DagWork.Key> recoverable(TenantId tenant, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid DAG recovery limit");
        return transaction(tenant, session -> {
            var mapper = mapper(session);
            return mapper.recoverable(tenant.value(), limit).stream().map(codec::decode)
                    .filter(work -> work.status() != DagWork.Status.CANCELLED || mapper.activeUncancelledControl(work) || mapper.hasUnsettledCancel(work))
                    .map(DagWork::key).toList();
        });
    }

    /** 独立 Run 的角色只能由服务端持久准入绑定确定。 */
    public Optional<DagWork.Key> workForRun(RunRef run) {
        return transaction(run.tenantId(), session -> Optional.ofNullable(mapper(session).workForRun(run))
                .map(codec::decode).map(DagWork::key));
    }

    /**
     * Plan成员准入复用调用方已锁root与父Run的同一SqlSession。
     * 新受理必须证明当前已批准工作、准确RESERVED许可；已受理重读仅核原不可变准入来源。
     */
    public DagWork.Binding requirePlanChild(SqlSession session, RunIdentity parent, DagWork.Key key,
            DagWork.Binding binding, DagWork.Permit permit, boolean existing) {
        Objects.requireNonNull(session); Objects.requireNonNull(parent); Objects.requireNonNull(key);
        Objects.requireNonNull(binding); Objects.requireNonNull(permit);
        if (!key.owner().equals(parent.owner()) || !key.rootInputId().equals(parent.causationInputId())
                || binding.task().executorType() != com.skillforge.runtime.api.dag.DagPlan.ExecutorType.MEMBER
                || binding.task().agentId().equals(parent.agentId()) || binding.runRef().equals(parent.runRef())
                || !binding.runRef().tenantId().equals(parent.runRef().tenantId())) throw planChildRejected();
        var mapper = mapper(session);
        if (!existing) {
            var snapshot = mapper.read(key, true);
            if (snapshot == null) throw planChildRejected();
            var work = codec.decode(snapshot);
            var execution = work.executions().get(binding.task().id());
            if (!work.key().equals(key) || !work.coordinator().equals(parent.agentId())
                    || !work.request().controlRun().equals(parent.runRef()) || work.status() != DagWork.Status.ACTIVE
                    || work.plan() == null || work.plan().revision() < binding.planRevision()
                    || work.plan().approvalRequired() && work.approvedRevision() < binding.planRevision()
                    || execution == null || execution.status() != DagWork.TaskStatus.RESERVED
                    || !execution.binding().equals(binding) || !execution.permit().equals(Optional.of(permit))
                    || !work.plan().effectiveTasks().contains(binding.task()) || binding.task().cancelled()) throw planChildRejected();
        }
        var original = mapper.planChildAdmission(parent,key,binding);
        if (original == null || !original.equals(codec.encodeBinding(binding))) throw planChildRejected();
        if (!existing && !Objects.equals(mapper.planChildPermit(binding,permit),permit.fence())) throw planChildRejected();
        return binding;
    }

    private static com.skillforge.runtime.session.spi.ExecutionStore.Rejection planChildRejected() {
        return new com.skillforge.runtime.session.spi.ExecutionStore.Rejection("execution_plan_child_source_mismatch");
    }

    /** 外部派发前冻结稳定 Action 到当前主步骤；相同绑定幂等，不能挪用到其他步骤。 */
    public boolean bindPrimaryAction(DagWork.Key key, String taskId, String admissionId, RunRef run, String actionId) {
        if (actionId == null || actionId.isBlank()) throw new IllegalArgumentException("action identity required");
        return transaction(key.owner().sessionRef().tenantId(), session -> {
            var mapper = mapper(session);
            lockSession(mapper, key);
            var work = read(mapper, key, true).orElseThrow();
            var execution = work.executions().get(taskId);
            if (execution == null || execution.binding().task().executorType()
                    != com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY
                    || !execution.binding().admissionId().equals(admissionId)
                    || !execution.binding().runRef().equals(run)) return false;
            var prior = mapper.stepAction(run, actionId);
            if (prior != null) return admissionId.equals(prior.admissionId) && taskId.equals(prior.taskId);
            if (work.status() != DagWork.Status.ACTIVE || execution.status() != DagWork.TaskStatus.RUNNING) return false;
            mapper.insertStepAction(run, actionId, admissionId, taskId);
            var bound = mapper.stepAction(run, actionId);
            return bound != null && admissionId.equals(bound.admissionId) && taskId.equals(bound.taskId);
        });
    }

    /** 引用核验按真实 Run 与 Action 身份查步骤，不能从模型声明反推归属。 */
    public Optional<String> taskForAction(RunRef run, String actionId) {
        return transaction(run.tenantId(), session -> Optional.ofNullable(mapper(session).stepAction(run, actionId))
                .map(row -> row.taskId));
    }

    /** 按控制 Run 的持久准入绑定查找工作，不从命名或内存状态推断归属。 */
    public Optional<DagWork.Key> workForControl(RunRef run) {
        return transaction(run.tenantId(), session -> Optional.ofNullable(mapper(session).workForControl(run))
                .map(codec::decode).map(DagWork::key));
    }

    /** 优先返回所属 Session 活动工作，否则返回最近受理工作。 */
    public Optional<DagWork> latest(SessionOwner owner) {
        return transaction(owner.sessionRef().tenantId(), session -> Optional.ofNullable(mapper(session).latest(owner))
                .map(codec::decode).map(work -> requireOwner(work, owner)));
    }

    /** 历史查询沿用完整 Session owner，返回最新有限条记录。 */
    public List<DagWork> history(SessionOwner owner, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid history limit");
        return transaction(owner.sessionRef().tenantId(), session -> {
            List<String> snapshots = mapper(session).history(owner, limit);
            return snapshots.stream().map(codec::decode).map(value -> requireOwner(value, owner)).toList();
        });
    }

    /** 统计完整 Session owner 下的排队工作数量。 */
    public long queuedCount(SessionOwner owner) {
        return transaction(owner.sessionRef().tenantId(), session -> mapper(session).queuedCount(owner));
    }

    /** 在 owner 行锁与工作锁内原子写入初始快照、事件和命令；重复命令幂等，冲突不写入。 */
    @Override public boolean create(DagWork work, String commandId, String fingerprint) {
        if (work.revision() != 1 || (work.status() != DagWork.Status.ACTIVE && work.status() != DagWork.Status.QUEUED) || !work.executions().isEmpty())
            throw new IllegalArgumentException("DAG creation must precede task admission");
        return transaction(work.key().owner().sessionRef().tenantId(), session -> {
            var mapper = mapper(session);
            lockSession(mapper, work.key());
            var prior = command(mapper, work.key(), commandId, fingerprint);
            if (prior.isPresent()) return prior.orElseThrow() == Commit.REPLAYED;
            var existing = read(mapper, work.key(), true);
            if (existing.isPresent()) return false;
            if (mapper.active(work.key().owner()) != null && work.status() == DagWork.Status.ACTIVE) return false;
            String payload = encode(work);
            applied(mapper.insertWork(work, payload));
            append(session, work, commandId, fingerprint, payload,
                    List.of(new Event(work.plan() == null ? "work.accepted" : "work.plan_submitted", "", "工作已持久受理")));
            recordControl(mapper, work);
            recordCommand(mapper, work.key(), commandId, fingerprint);
            return true;
        });
    }

    /** 以期望版本和命令指纹提交事件、准入及占用；竞争失败返回 CONFLICT，异常回滚整个事务。 */
    @Override public Commit commit(DagWork.Key key, long expectedRevision, String commandId,
            String fingerprint, DagWork next, List<Event> events) {
        if (!next.key().equals(key) || next.revision() != expectedRevision + 1 || events.isEmpty())
            throw new IllegalArgumentException("invalid DAG commit");
        return transaction(key.owner().sessionRef().tenantId(), session -> {
            var mapper = mapper(session);
            lockSession(mapper, key);
            var prior = command(mapper, key, commandId, fingerprint);
            if (prior.isPresent()) return prior.orElseThrow();
            var original = read(mapper, key, true);
            if (original.isEmpty() || original.orElseThrow().revision() != expectedRevision) return Commit.CONFLICT;
            DagWork before = original.orElseThrow();
            if (events.stream().anyMatch(event -> !"work.closed".equals(event.type()))) {
                if (mapper.answerExists(key)) return Commit.CONFLICT;
            }
            for (Event event : events) {
                if (!"task.not_dispatched".equals(event.type())) continue;
                var execution = before.executions().get(event.taskId());
                if (execution == null || execution.status() != DagWork.TaskStatus.RESERVED) return Commit.CONFLICT;
                if (mapper.runExists(tenant(key), execution.binding().runRef().runId())) return Commit.CONFLICT;
            }
            if (before.status() == DagWork.Status.QUEUED && next.status() == DagWork.Status.ACTIVE
                    && mapper.activationBlocked(key)) return Commit.CONFLICT;
            // 既有业务准入永远不被分工修订、重启或容量恢复替换。
            for (var entry : before.executions().entrySet()) {
                var after = next.executions().get(entry.getKey());
                if (after == null || !after.binding().equals(entry.getValue().binding()))
                    throw new IllegalArgumentException("DAG admission binding changed");
            }
            String payload = encode(next);
            append(session, next, commandId, fingerprint, payload, events);
            for (var execution : next.executions().values()) {
                if (before.executions().containsKey(execution.binding().task().id())) continue;
                var binding = execution.binding();
                applied(mapper.insertAdmission(key, binding, codec.encodeBinding(binding)));
            }
            for (var execution : next.executions().values()) {
                // 主步骤借用原 Run，只保存步骤许可，不占用或释放子 Run 容量。
                if (execution.binding().task().executorType() == com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY) continue;
                var previous = before.executions().get(execution.binding().task().id());
                if (previous != null && previous.permit().equals(execution.permit())) continue;
                String run = execution.binding().runRef().runId();
                if (execution.permit().isPresent()) {
                    var permit = execution.permit().orElseThrow();
                    applied(mapper.upsertPermit(tenant(key), run, permit.fence(), permit.expiresAt()));
                } else {
                    // Run 挂起事务可能已释放，不能因此拒收该挂起事实。
                    mapper.deletePermit(tenant(key), run);
                }
            }
            applied(mapper.updateWork(next, payload, expectedRevision));
            recordControl(mapper, next);
            recordCommand(mapper, key, commandId, fingerprint);
            return Commit.APPLIED;
        });
    }

    /** 冻结活动工作控制 Run 归属；重复插入后必须核对全部绑定，禁止覆盖既有准入。 */
    private void recordControl(DagWorkMapper mapper, DagWork work) {
        if (work.status() != DagWork.Status.ACTIVE
                || work.request().stage() == DagWork.Request.Stage.ROUTING_FAILED) return;
        mapper.insertControl(work);
        var row = mapper.control(tenant(work.key()), work.request().controlRun().runId());
        if (row == null || !session(work.key()).equals(row.sessionId) || !root(work.key()).equals(row.rootInputId)
                || !work.coordinator().value().equals(row.agentId) || !work.request().stage().name().equals(row.role))
            throw new IllegalStateException("control Run binding conflict");
    }

    /** 按数据库时钟在当前事务追加稳定事件标识；事件冲突使整个 DAG 变更回滚。 */
    private void append(SqlSession session, DagWork work, String commandId, String fingerprint,
            String payload, List<Event> events) {
        Instant now = mapper(session).now();
        var durableEvents = new java.util.ArrayList<>(events);
        durableEvents.add(new Event("work.updated", "", "任务计划状态已持久更新"));
        for (int index = 0; index < durableEvents.size(); index++) {
            var event = durableEvents.get(index);
            var execution = work.executions().get(event.taskId());
            var actor = execution == null ? work.coordinator() : execution.binding().task().agentId();
            var target = new SessionProjectionTarget(work.key().owner(), actor, work.key().rootInputId(),
                    execution == null ? Optional.empty() : Optional.of(execution.binding().runRef()),
                    Optional.empty(), Optional.empty(), Optional.empty());
            String id = "dag:" + hash(work.key().rootInputId().value() + "\n" + commandId + "\n" + index);
            var draft = new SessionEventDraft(new EventId(id), event.type(), "1", hash(fingerprint + "\n" + index),
                    new TraceEnvelope(work.key().rootInputId().value(), id, work.key().rootInputId(),
                            Optional.of(new EventId(commandId))), new TypedPayload("dag.work.v1", payload), now);
            var appended = JdbcSessionLogPort.append(session.getMapper(SessionLogMapper.class),
                    new SessionLogPort.AppendSessionEvent(target, draft));
            if (appended instanceof SessionLogPort.AppendResult.Conflict)
                throw new IllegalStateException("DAG event conflict");
        }
    }

    /** 按完整工作身份读取快照；读取时核验归属和快照主键，禁止返回串租户事实。 */
    private Optional<DagWork> read(DagWorkMapper mapper, DagWork.Key key, boolean lock) {
        String snapshot = mapper.read(key, lock);
        if (snapshot == null) return Optional.empty();
        var work = requireOwner(codec.decode(snapshot), key.owner());
        if (!key.equals(work.key())) throw new IllegalStateException("DAG snapshot key mismatch");
        return Optional.of(work);
    }

    /** 核对 Session 内命令标识的根输入与指纹，分别返回未执行、重放或冲突。 */
    private Optional<Commit> command(DagWorkMapper mapper, DagWork.Key key, String commandId, String fingerprint) {
        var row = mapper.command(key, commandId);
        if (row == null) return Optional.empty();
        return Optional.of(root(key).equals(row.rootInputId) && fingerprint.equals(row.fingerprint)
                ? Commit.REPLAYED : Commit.CONFLICT);
    }

    /** 持久化已应用命令，必须与工作事实和事件同事务提交。 */
    private void recordCommand(DagWorkMapper mapper, DagWork.Key key, String commandId, String fingerprint) {
        applied(mapper.insertCommand(key, commandId, fingerprint));
    }

    /** 按固定顺序持有工作 advisory 锁和 owner 行锁，防止取消与 NewRun 受理交错。 */
    private void lockSession(DagWorkMapper mapper, DagWork.Key key) {
        mapper.lockSession("dag:" + tenant(key) + ":" + session(key));
        // 与 Runtime NewRun 受理使用同一 owner 行锁，先获得锁再判断未派发，避免取消与启动交错。
        if (mapper.lockOwner(key.owner()) == null) throw new IllegalArgumentException("DAG session owner is unavailable");
    }

    /** 核验编解码全值往返一致后返回载荷；有损 codec 不得写入权威快照。 */
    private String encode(DagWork work) {
        String payload = codec.encode(work);
        if (!work.equals(codec.decode(payload))) throw new IllegalArgumentException("DAG codec is not lossless");
        return payload;
    }

    /** 检查解码后的完整 owner，归属不一致时拒绝返回结果。 */
    private static DagWork requireOwner(DagWork work, SessionOwner owner) {
        if (!work.key().owner().equals(owner)) throw new IllegalArgumentException("DAG owner mismatch");
        return work;
    }
    /** 提取工作键内显式租户，供同事务映射使用。 */
    private static String tenant(DagWork.Key key) { return key.owner().sessionRef().tenantId().value(); }
    /** 提取工作键内 Session 标识，不使用线程推断身份。 */
    private static String session(DagWork.Key key) { return key.owner().sessionRef().sessionId(); }
    /** 提取工作根输入标识，用于幂等和事实归属核验。 */
    private static String root(DagWork.Key key) { return key.rootInputId().value(); }
    /** 取得调用方 SqlSession 的 DAG 映射，所有关联写入共用同一事务。 */
    private static DagWorkMapper mapper(SqlSession session) { return session.getMapper(DagWorkMapper.class); }
    /** 要求单行事实变更成功；非单行结果触发事务回滚。 */
    private static void applied(int count) {
        if (count != 1) throw new IllegalStateException("DAG write not applied");
    }
    /** 以稳定 SHA-256 派生事件身份与指纹，不保存额外可变状态。 */
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** 绑定租户后协调 DAG 与 Session 日志的唯一事务；失败回滚全部关联事实。 */
    private <T> T transaction(TenantId tenant, Work<T> work) {
        var borrowed = participation.get();
        if (borrowed != null) {
            if (!borrowed.tenant().equals(tenant)) throw new IllegalStateException("dag_transaction_tenant_conflict");
            return work.run(borrowed.session());
        }
        String transactionTag=java.util.UUID.randomUUID().toString();
        LOG.log(System.Logger.Level.DEBUG,"DAG 事务开始 transactionTag={0}",transactionTag);
        try (var session = sessions.openSession(false)) {
            try {
                session.getMapper(SessionLogMapper.class).bindTenant(tenant.value());
                T result = work.run(session);
                session.commit();
                LOG.log(System.Logger.Level.DEBUG,"DAG 事务提交 transactionTag={0}",transactionTag);
                return result;
            } catch (RuntimeException failure) {
                session.rollback();
                LOG.log(System.Logger.Level.DEBUG,"DAG 事务回滚 transactionTag={0}",transactionTag);
                throw new IllegalStateException("DAG transaction rejected", failure);
            }
        } finally { LOG.log(System.Logger.Level.DEBUG,"DAG 事务结束 transactionTag={0}",transactionTag); }
    }
    @FunctionalInterface private interface Work<T> { T run(SqlSession session); }
}

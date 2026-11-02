package com.skillforge.runtime.adapter.jdbc.budget;

import com.skillforge.runtime.adapter.jdbc.observability.JdbcMapperLogging;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.io.InputStream;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 根行串行化全部预留；事务锁顺序固定为根、Run、attempt，不借用计费或运行状态。 */
public final class JdbcExecutionBudgets implements ExecutionBudgetPort {
    private static final System.Logger LOG=System.getLogger(JdbcExecutionBudgets.class.getName());
    private static final long MILLIS_PER_SECOND = 1_000;
    private final SqlSessionFactory sessions;
    /** 仅所属业务事务存活期间复用已持根锁、冻结登记和已确认活动；不缓存余额、阶段或权限。 */
    private final ThreadLocal<LockedBudget> lockedBudget = new ThreadLocal<>();

    private static final class LockedBudget {
        final SqlSession session;
        final TenantId tenant;
        final java.util.Set<RunRef> roots = new java.util.HashSet<>();
        final Map<RunRef,BudgetRegistrationRow> identities = new java.util.HashMap<>();
        final Map<RunRef,Registration> registrations = new java.util.HashMap<>();
        final Map<RunRef,Long> rootActivity = new java.util.HashMap<>();
        final Map<RunRef,Long> runActivity = new java.util.HashMap<>();
        LockedBudget(SqlSession session,TenantId tenant) { this.session=session;this.tenant=tenant; }
    }

    /**
     * 本次调用方事务拥有的局部预算作用域；租户已由调用方绑定，提交和回滚仍归调用方。
     * 作用域退出立即丢弃所有复用事实；任何新事务、连接或租户均不能继承已持锁标记。
     */
    public <T> T participate(SqlSession session,TenantId tenant,java.util.function.Supplier<T> operation) {
        Objects.requireNonNull(session);Objects.requireNonNull(tenant);Objects.requireNonNull(operation);
        var prior=lockedBudget.get();
        if(prior!=null) {
            if(prior.session!=session || !prior.tenant.equals(tenant))throw new IllegalStateException("budget_transaction_scope_conflict");
            return operation.get();
        }
        lockedBudget.set(new LockedBudget(session,tenant));
        try { return operation.get(); }
        finally { lockedBudget.remove(); }
    }

    private LockedBudget scope(SqlSession session,RunRef run) {
        var scope=lockedBudget.get();
        if(scope!=null && (scope.session!=session || !scope.tenant.equals(run.tenantId())))
            throw new IllegalStateException("budget_transaction_scope_conflict");
        return scope;
    }
    /** 由宿主数据源装配无 Spring 的预算映射与事务工厂；不创建或关闭宿主连接池。 */
    public JdbcExecutionBudgets(DataSource source) {
        this(source, new JdbcTransactionFactory());
    }
    /** 宿主可提供已批准的事务工厂；受理冻结必须加入宿主事务，适配器不引入 Spring。 */
    public JdbcExecutionBudgets(DataSource source, org.apache.ibatis.transaction.TransactionFactory transactions) {
        var configuration = new Configuration(new Environment("execution-budgets", Objects.requireNonNull(transactions), Objects.requireNonNull(source)));
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configure(configuration);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 为新建配置注册预算映射与一次安全访问日志；Action 账本复用此配置和 SqlSession，不创建嵌套事务。 */
    public static void configure(Configuration configuration) {
        configuration.addInterceptor(new JdbcMapperLogging());
        String resource = "mapper/AgentRuntimeExecutionBudgetMapper.xml";
        try (InputStream xml = JdbcExecutionBudgets.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("budget_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (java.io.IOException failure) { throw new IllegalStateException("budget_mapper_unavailable", failure); }
    }

    /** 在根行锁内冻结根与 Run 限额；重复的相同注册返回原事实，冲突拒绝并回滚。 */
    @Override public Registration register(Registration registration) {
        Objects.requireNonNull(registration, "registration");
        registration.policy().requireEnforced();
        return transaction(registration.run(), c -> register(c, registration));
    }

    /**
     * 在调用方 SqlSession 中冻结预算登记，不新建连接、不绑定租户、不提交事务。
     *
     * <p>调用方须在事务开始时绑定本登记的租户，且尚未取得 Run 或 Action/Attempt 锁；
     * 本方法先取得预算根锁，随后预算登记与 Run 受理可在同一事务生效。任何后续失败由
     * 调用方回滚全部业务写入，相同登记重入不改变冻结限额。</p>
     *
     * @param c 已绑定同一租户的业务事务
     * @param registration 本次受理的完整冻结预算
     * @return 已保存且与请求完全相同的登记
     */
    public Registration register(SqlSession c, Registration registration) {
            Objects.requireNonNull(c, "session");
            Objects.requireNonNull(registration, "registration");
            var run = registration.run(); var root = registration.root(); var limits = registration.policy().requireEnforced();
            if (run.equals(root)) {
                mapper(c).insertRoot(tenant(run),root.runId(),limits.scenario().name(),limits.sourceVersion(),limits.rootActiveSeconds());
            }
            lockRoot(c, root);
            // 根锁后再检查 Run，两个并发受理不能各自建立不同的冻结预算。
            var existing = registration(c, run);
            if (existing.isPresent()) {
                if (!existing.get().equals(registration)) throw new IllegalStateException("budget_registration_conflict");
                return existing.get();
            }
            if (run.equals(root)) {
                insertBalances(c,run,"ROOT",root.runId(),limits.root());
                insertBalances(c,run,"CLOSING",root.runId(),Resources.ZERO);
            } else {
                var frozenRoot = registration(c,root).orElseThrow(() -> new IllegalStateException("budget_root_missing"));
                if (frozenRoot.policy().requireEnforced().scenario()!=limits.scenario() || !frozenRoot.policy().requireEnforced().root().equals(limits.root())
                        || frozenRoot.policy().requireEnforced().rootActiveSeconds()!=limits.rootActiveSeconds())
                    throw new IllegalStateException("budget_root_configuration_conflict");
            }
            mapper(c).insertRun(tenant(run),run.runId(),root.runId(),registration.role().name(),limits.sourceVersion(),limits.runActiveSeconds());
            insertBalances(c,run,"RUN",run.runId(),limits.run());
            insertBalances(c,run,"STAGE_BASE",run.runId(),limits.stage());
            insertBalances(c,run,"STAGE",run.runId(),limits.stage());
            insertBalances(c,run,"CLOSING_RUN",run.runId(),Resources.ZERO);
            return registration(c,run).orElseThrow();
    }
    /** 按完整租户与 Run 身份读取冻结注册；未注册返回空值。 */
    @Override public Optional<Registration> find(RunRef run) { return transaction(run,c -> registration(c,run)); }

    /** 按根、Run、阶段及收尾保护核验资源并原子预留；同 attempt 重放只接受完全相同的需求。 */
    @Override public Reservation reserve(Demand demand) {
        return transaction(demand.run(), c -> reserve(c,demand));
    }
    /** 按根、Run、阶段及收尾保护核验资源并原子预留；同 attempt 重放只接受完全相同的需求。 */
    public Reservation reserve(SqlSession c, Demand demand) {
            var reg = registration(c,demand.run()).orElseThrow(() -> new IllegalStateException("budget_scope_missing"));
            lockRoot(c,reg.root());
            var old = mapper(c).attempt(tenant(demand.run()), demand.attemptId());
            if (old != null) {
                if (!old.runId.equals(demand.run().runId()) || !old.fingerprint.equals(demand.fingerprint())
                        || !old.category.equals(demand.category().name()) || old.closing != demand.closing()
                        || !amounts(c,demand.run(),demand.attemptId()).equals(demand.resources()))
                    return new Reservation.Denied(Refusal.REQUEST_CONFLICT, Optional.empty());
                return new Reservation.Granted(new Permit(demand,AttemptState.valueOf(old.state)));
            }
            long rootActive = activeMillis(c,reg,false);
            long runActive = activeMillis(c,reg,true);
            if (rootActive>=reg.policy().requireEnforced().rootActiveSeconds()*MILLIS_PER_SECOND
                    || runActive>=reg.policy().requireEnforced().runActiveSeconds()*MILLIS_PER_SECOND)
                return new Reservation.Denied(Refusal.ACTIVE_TIME_LIMIT, Optional.empty());
            var root = balances(c,demand.run(),"ROOT",reg.root().runId());
            var run = balances(c,demand.run(),"RUN",demand.run().runId());
            var stage = balances(c,demand.run(),"STAGE",demand.run().runId());
            var closing = balances(c,demand.run(),"CLOSING",reg.root().runId()).maximum();
            if (phase(c,reg.root())==Phase.CLOSING && (!demand.closing() || reg.role()!=Role.PRIMARY))
                return new Reservation.Denied(Refusal.CLOSING_REQUIRED, Optional.empty());
            Resources protectedAmount=demand.closing() && reg.role()==Role.PRIMARY ? Resources.ZERO : closing;
            var rootExceeded = root.used().plus(demand.resources()).plus(protectedAmount).firstExceededDimension(root.maximum());
            if (rootExceeded.isPresent())
                return protectedAmount.equals(Resources.ZERO)
                        ? new Reservation.Denied(Refusal.ROOT_LIMIT, rootExceeded)
                        : new Reservation.Denied(Refusal.CLOSING_REQUIRED, Optional.empty());
            var protectedRun = demand.closing() ? Resources.ZERO : balances(c,demand.run(),"CLOSING_RUN",demand.run().runId()).maximum();
            var runExceeded = run.used().plus(demand.resources()).plus(protectedRun).firstExceededDimension(run.maximum());
            if (runExceeded.isPresent())
                return protectedRun.equals(Resources.ZERO)
                        ? new Reservation.Denied(Refusal.RUN_LIMIT, runExceeded)
                        : new Reservation.Denied(Refusal.CLOSING_REQUIRED, Optional.empty());
            long timeProtection = demand.closing() ? 0 : closingSeconds(c,reg.root()) * MILLIS_PER_SECOND;
            if (rootActive+timeProtection >= reg.policy().requireEnforced().rootActiveSeconds()*MILLIS_PER_SECOND
                    || runActive+timeProtection >= reg.policy().requireEnforced().runActiveSeconds()*MILLIS_PER_SECOND)
                return new Reservation.Denied(Refusal.CLOSING_REQUIRED, Optional.empty());
            // 首次调用和后续调用都先测量，再在同一事务扩展当前阶段；没有先拒绝再申请。
            var dimensions = new ArrayList<BudgetAmount>();
            for (var dimension:Dimension.values()) dimensions.add(new BudgetAmount(dimension.name(),
                    demand.resources().value(dimension), Math.max(stage.maximum().value(dimension),
                    Math.addExact(stage.used().value(dimension),demand.resources().value(dimension)))));
            var parameters = Map.<String,Object>of("tenant",tenant(demand.run()),"run",demand.run().runId(),
                    "root",reg.root().runId(),"attempt",demand.attemptId(),"amounts",dimensions);
            // 同一根锁内集合更新，仍核验全部维度存在；不能用跳过零值掩盖损坏的余额行。
            if (mapper(c).reserveBalances(parameters)!=Dimension.values().length*3)
                throw new IllegalStateException("budget_balance_missing");
            mapper(c).insertAttempt(tenant(demand.run()),demand.attemptId(),demand.run().runId(),demand.fingerprint(),demand.category().name(),demand.closing());
            if(mapper(c).insertAmounts(parameters)!=Dimension.values().length)
                throw new IllegalStateException("budget_attempt_missing");
            return new Reservation.Granted(new Permit(demand,AttemptState.RESERVED));
    }
    /** 在根锁内确认真实发送许可并结算次数；已进入收尾或未处于 RESERVED 时返回 false。 */
    @Override public boolean markSent(RunRef run,String attempt) {
        return transaction(run, c -> markSent(c,run,attempt));
    }
    /** 在根锁内确认真实发送许可并结算次数；已进入收尾或未处于 RESERVED 时返回 false。 */
    public boolean markSent(SqlSession c, RunRef run, String attempt) {
            var reg=require(c,run); lockRoot(c,reg.root());
            // 预留到发送之间仍可能耗尽根或当前 Run 时间；在原根锁事务内拒绝，不消费次数或释放预留。
            if(activeMillis(c,reg,false)>=Math.multiplyExact(reg.policy().requireEnforced().rootActiveSeconds(),MILLIS_PER_SECOND)
                    ||activeMillis(c,reg,true)>=Math.multiplyExact(reg.policy().requireEnforced().runActiveSeconds(),MILLIS_PER_SECOND))return false;
            // 预留后可能由主助手进入收尾；尚未发送的普通业务许可不能穿过新的边界。
            if(phase(c,reg.root())==Phase.CLOSING) {
                if (!Boolean.TRUE.equals(mapper(c).attemptClosing(tenant(run),run.runId(),attempt))) return false;
            }
            if (mapper(c).markSent(tenant(run),run.runId(),attempt)!=1) return false;
            // 次数在真实发送许可提交时确认；缺报 Token 的余额仍被保留。
            mapper(c).reportSentCounts(tenant(run),attempt);
            settleAmounts(c,reg,attempt);
            return true;
    }
    /** 在当前事务补齐已报告用量与结果引用；未报告维度保留未知，冲突用量拒绝写入。 */
    @Override public void saveResult(RunRef run,String attempt,Usage usage,String reference) {
        transaction(run, c -> { saveResult(c,run,attempt,usage,reference); return null; });
    }
    /** 在当前事务补齐已报告用量与结果引用；未报告维度保留未知，冲突用量拒绝写入。 */
    public void saveResult(SqlSession c, RunRef run, String attempt, Usage usage, String reference) {
            var reg=require(c,run);lockRoot(c,reg.root());
            saveResult(c,reg,attempt,usage,reference);
    }
    /** 精确原结果、已报告用量和既有账本结算共同属于调用方事务，只读取一次冻结登记。 */
    public void saveAndSettleResult(SqlSession c,RunRef run,String attempt,Usage usage,String reference) {
        var reg=require(c,run);lockRoot(c,reg.root());
        saveResult(c,reg,attempt,usage,reference);
        settleAmounts(c,reg,attempt);
    }
    private void saveResult(SqlSession c,Registration reg,String attempt,Usage usage,String reference) {
            var run=reg.run();
            String storedState = mapper(c).attemptState(tenant(run),run.runId(),attempt);
            if (storedState == null) throw new IllegalStateException("budget_attempt_missing");
            AttemptState state = AttemptState.valueOf(storedState);
            if (state==AttemptState.RESERVED && java.util.stream.Stream.of(usage.inputTokens(),usage.outputTokens(),usage.totalTokens())
                    .anyMatch(value -> value.isPresent() && value.getAsLong()!=0))
                throw new IllegalStateException("budget_unsent_usage_rejected");
            var reportedAmounts=new ArrayList<ReportedAmount>();
            for (var dimension:Dimension.values()) {
                var reported=usage.value(dimension); if (reported.isEmpty()) continue;
                if (reported.getAsLong()<0) throw new IllegalArgumentException("budget_usage_invalid");
                reportedAmounts.add(new ReportedAmount(dimension.name(),reported.getAsLong()));
            }
            if (!reportedAmounts.isEmpty()) {
                var parameters=Map.<String,Object>of("tenant",tenant(run),"attempt",attempt,"amounts",reportedAmounts);
                var existing=new java.util.EnumMap<Dimension,java.util.OptionalLong>(Dimension.class);
                for (var row : mapper(c).readReportedAmounts(parameters)) {
                    existing.put(Dimension.valueOf(row.dimension), row.reported == null
                            ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(row.reported));
                }
                var missingAmounts=new ArrayList<ReportedAmount>();
                // 根锁内逐维核验已确认事实；缺报保持 NULL，明确零仍需落盘。
                for (var amount:reportedAmounts) {
                    var old=existing.get(Dimension.valueOf(amount.dimension()));
                    if (old==null) throw new IllegalStateException("budget_attempt_missing");
                    if (old.isPresent() && old.getAsLong()!=amount.reported())
                        throw new IllegalStateException("budget_usage_conflict");
                    if (old.isEmpty()) missingAmounts.add(amount);
                }
                if (!missingAmounts.isEmpty() && mapper(c).fillReportedAmounts(
                        Map.of("tenant",tenant(run),"attempt",attempt,"amounts",missingAmounts))!=missingAmounts.size())
                    throw new IllegalStateException("budget_attempt_missing");
            }
            // 结果未知到已知可以增加精确证据引用，只补齐先前缺报的用量，不改已确认维度。
            mapper(c).insertReceipt(tenant(run),attempt,reference);
            if (mapper(c).saveResult(reference,tenant(run),run.runId(),attempt)==0) {
                // 已证明未发出的调用也收敛资源，但不算一次实际调用。
                if (mapper(c).saveUnsentResult(reference,tenant(run),run.runId(),attempt)!=1) throw new IllegalStateException("budget_result_conflict");
                mapper(c).reportUnsentAmounts(tenant(run),attempt);
            }

    }
    /** 在独立预算事务中结算已确认维度，未知用量继续保留预留。 */
    @Override public void settle(RunRef run,String attempt) {
        transaction(run,c -> {var reg=require(c,run);lockRoot(c,reg.root());settleAmounts(c,reg,attempt);return null;});
    }
    /** 参与调用方 SqlSession 的原子结算；调用方必须先持有根行锁，本方法不提交事务。 */
    public void settleAmounts(SqlSession c,Registration reg,String attempt) {
        var pending=new ArrayList<Amount>();
        for (var row : mapper(c).pendingAmounts(tenant(reg.run()),attempt))
            pending.add(new Amount(Dimension.valueOf(row.dimension),row.reserved,row.reported));
        if(!pending.isEmpty()) {
            var parameters=Map.<String,Object>of("tenant",tenant(reg.run()),"run",reg.run().runId(),
                    "root",reg.root().runId(),"attempt",attempt,"amounts",pending);
            if(mapper(c).settleBalances(parameters)!=pending.size()*3)
                throw new IllegalStateException("budget_balance_missing");
            if(mapper(c).settleAmounts(parameters)!=pending.size())
                throw new IllegalStateException("budget_attempt_missing");
        }
        mapper(c).markSettled(tenant(reg.run()),attempt,tenant(reg.run()),attempt);
    }
    /** 只重新结算已持久结果，不再次发送外部调用；重复恢复保持幂等。 */
    @Override public void recover(RunRef run) {
        transaction(run,c -> {var reg=require(c,run);lockRoot(c,reg.root());var ids=mapper(c).recoverableAttempts(tenant(run),run.runId());
            for(var id:ids)settleAmounts(c,reg,id); return null;});
    }
    /** 在根锁内读取完整预算快照，合并同根活动区间，避免并发读取部分结算。 */
    @Override public Snapshot snapshot(RunRef run) {
        return transaction(run,c -> {var reg=require(c,run);lockRoot(c,reg.root());return snapshot(c,reg);});
    }
    /** 准备输出仅观察原登记根的当前阶段；不读取余额/活动，也不签发准入或发送许可。 */
    public Phase phase(RunRef run) {
        return transaction(run,c -> phase(c,root(c,run).orElseThrow(() -> new IllegalStateException("budget_scope_missing"))));
    }
    /** 余额与阶段在读取位置重新取真实事实，不由事务作用域缓存己方写入前的状态。 */
    private Snapshot snapshot(SqlSession c,Registration reg) {
            var run=reg.run();var root=balances(c,run,"ROOT",reg.root().runId());
            var own=balances(c,run,"RUN",run.runId());
            return new Snapshot(reg,root.consumed(),root.reserved(),own.consumed(),own.reserved(),
                    new Closing(balances(c,run,"CLOSING",reg.root().runId()).maximum(),closingSeconds(c,reg.root())),
                    phase(c,reg.root()),activeMillis(c,reg,false),activeMillis(c,reg,true),
                    unionMillis(mapper(c).closingModelSpans(tenant(run),run.runId())));
    }
    /** 主 Run 在根锁内调整收尾保护；新保护超出剩余资源或时间时拒绝。 */
    @Override public void updateClosing(RunRef run,Closing closing) {
        transaction(run,c -> {var reg=require(c,run);lockRoot(c,reg.root());
            updateClosing(c,reg,closing);return null;});
    }

    /** 在同一根锁下选择阶段并更新保护，避免快照后进入 CLOSING 仍按完整历史需求重复准入。 */
    @Override public void admitClosing(RunRef run,ClosingAdmission admission) {
        Objects.requireNonNull(admission);
        transaction(run,c -> {
            var reg=require(c,run);lockRoot(c,reg.root());
            admitClosing(c,reg,admission);
            return null;
        });
    }

    /**
     * 一次真实输出预算决策：保护、允许目标及必要的CLOSING切换在同一个根锁事务中提交。
     * 更新保护后重新读取余额与phase；历史UNKNOWN预留继续计入used，不新建账本或跨请求缓存。
     */
    public boolean admitClosingAndChoosePhase(RunRef run,ClosingAdmission admission,java.util.Set<String> handles,Resources demand) {
        Objects.requireNonNull(admission);Objects.requireNonNull(handles);Objects.requireNonNull(demand);
        for(var handle:handles)if(handle==null || handle.isBlank())throw new IllegalArgumentException("budget_closing_target_invalid");
        return transaction(run,c -> {
            var reg=require(c,run);lockRoot(c,reg.root());
            if(reg.role()!=Role.PRIMARY)return false;
            admitClosing(c,reg,admission);
            for(var handle:handles)mapper(c).allowClosingTarget(tenant(run),run.runId(),handle);
            var snapshot=snapshot(c,reg);
            if(snapshot.phase()==Phase.CLOSING)return true;
            boolean fits=snapshot.consumed().plus(snapshot.reserved()).plus(demand).plus(snapshot.closing().resources()).fits(reg.policy().requireEnforced().root())
                    && snapshot.runConsumed().plus(snapshot.runReserved()).plus(demand).plus(snapshot.closing().resources()).fits(reg.policy().requireEnforced().run());
            long protectedMillis=Math.multiplyExact(snapshot.closing().activeSeconds(),MILLIS_PER_SECOND);
            boolean timeFits=Math.addExact(snapshot.activeMillis(),protectedMillis)<Math.multiplyExact(reg.policy().requireEnforced().rootActiveSeconds(),MILLIS_PER_SECOND)
                    && Math.addExact(snapshot.runActiveMillis(),protectedMillis)<Math.multiplyExact(reg.policy().requireEnforced().runActiveSeconds(),MILLIS_PER_SECOND);
            if(!fits || !timeFits) { mapper(c).enterClosing(tenant(run),reg.root().runId());return true; }
            return false;
        });
    }

    private void admitClosing(SqlSession c,Registration reg,ClosingAdmission admission) {
            var run=reg.run();
            var requested=admission.remaining();
            if(phase(c,reg.root())==Phase.BUSINESS) {
                var existing=balances(c,run,"CLOSING",reg.root().runId()).maximum();
                long[] values=new long[Dimension.values().length];
                for(var dimension:Dimension.values())values[dimension.ordinal()]=Math.max(
                        existing.value(dimension),admission.prospective().resources().value(dimension));
                requested=new Closing(Resources.from(values),Math.max(closingSeconds(c,reg.root()),admission.prospective().activeSeconds()));
            }
            updateClosing(c,reg,requested);
    }

    /** 调用方已持有根锁；仅核验未来保护，历史消耗与未知预留均继续完整计入 used。 */
    private void updateClosing(SqlSession c,Registration reg,Closing closing) {
        if(reg.role()!=Role.PRIMARY)throw new IllegalStateException("budget_closing_owner_required");
        var run=reg.run();
        var root=balances(c,run,"ROOT",reg.root().runId());var own=balances(c,run,"RUN",run.runId());var amount=closing.resources();
        long rootActive=activeMillis(c,reg,false);long runActive=activeMillis(c,reg,true);
        long requestedActive=closing.activeSeconds()*MILLIS_PER_SECOND;
        var shortfalls=new java.util.ArrayList<ClosingShortfall>();
        closingShortfalls(shortfalls,BalanceScope.ROOT,root,amount);
        closingShortfalls(shortfalls,BalanceScope.RUN,own,amount);
        closingTimeShortfall(shortfalls,BalanceScope.ROOT,rootActive,requestedActive,Math.multiplyExact(reg.policy().requireEnforced().rootActiveSeconds(),MILLIS_PER_SECOND));
        closingTimeShortfall(shortfalls,BalanceScope.RUN,runActive,requestedActive,Math.multiplyExact(reg.policy().requireEnforced().runActiveSeconds(),MILLIS_PER_SECOND));
        if(!shortfalls.isEmpty()) throw new ClosingInsufficient(phase(c,reg.root()),shortfalls);
        // 一个根只有当前主 Run 的收尾保护；Goal 新轮接替时解除前主 Run 的局部保护。
        mapper(c).clearClosingRun(tenant(run),reg.root().runId());
        if(mapper(c).updateClosingBalances(Map.of("tenant",tenant(run),"root",reg.root().runId(),"run",run.runId(),
                "amounts",maximums(amount)))!=Dimension.values().length*2)
            throw new IllegalStateException("budget_balance_missing");
        mapper(c).configureClosing(closing.activeSeconds(),run.runId(),tenant(run),reg.root().runId());
    }

    private static void closingShortfalls(java.util.List<ClosingShortfall> result,BalanceScope scope,Balance balance,Resources amount) {
        var used=balance.used();
        for(var dimension:Dimension.values()) {
            long required=Math.addExact(used.value(dimension),amount.value(dimension));
            long available=balance.maximum().value(dimension);
            if(required>available)result.add(new ClosingShortfall(scope,dimension.name(),required,available,
                    required-available,balance.consumed().value(dimension),balance.reserved().value(dimension),available));
        }
    }
    private static void closingTimeShortfall(java.util.List<ClosingShortfall> result,BalanceScope scope,long used,long requested,long limit) {
        long required=Math.addExact(used,requested);
        if(required>limit)result.add(new ClosingShortfall(scope,"ACTIVE_MILLIS",required,limit,required-limit,used,0,limit));
    }
    /** 由当前主 Run 将根预算转入收尾阶段，后续普通调用不能越过该边界。 */
    @Override public void enterClosing(RunRef run) {
        transaction(run,c -> {var reg=require(c,run);lockRoot(c,reg.root());if(reg.role()!=Role.PRIMARY)throw new IllegalStateException("budget_closing_owner_required");
            mapper(c).enterClosing(tenant(run),reg.root().runId());return null;});
    }
    /** 冻结主 Run 可在收尾阶段调用的工具目标；空目标或非主 Run 拒绝。 */
    @Override public void allowClosingTarget(RunRef run,String handle) {
        if(handle==null||handle.isBlank())throw new IllegalArgumentException("budget_closing_target_invalid");
        transaction(run,c -> {var reg=require(c,run);lockRoot(c,reg.root());if(reg.role()!=Role.PRIMARY)throw new IllegalStateException("budget_closing_owner_required");
            mapper(c).allowClosingTarget(tenant(run),run.runId(),handle);return null;});
    }
    /** 在调用方事务内核验角色、阶段及工具白名单，返回本次调用是否有收尾资格。 */
    public boolean closingAllowed(SqlSession c,RunRef run,Category category,String handle) {
        var reg=require(c,run);
        if(reg.role()!=Role.PRIMARY || phase(c,reg.root())!=Phase.CLOSING)return false;
        if(category==Category.BUSINESS_MODEL || category==Category.AUXILIARY_MODEL)return true;
        if(category!=Category.TOOL)return false;
        return mapper(c).closingTarget(tenant(run),run.runId(),handle) != null;
    }
    /** 读取根的收尾时间保护；根事实缺失时拒绝继续预算计算。 */
    private long closingSeconds(SqlSession c,RunRef root) {
        Long seconds=mapper(c).closingSeconds(tenant(root),root.runId());
        if(seconds==null)throw new IllegalStateException("budget_root_missing");
        return seconds;
    }
    /** 以区间标识幂等推进确认时间；同一标识更换起点或时间倒序均拒绝。 */
    @Override public ActivityObservation recordActivity(RunRef run,String id,Instant start,Instant through) {
        return transaction(run, c -> recordActivity(c,run,id,start,through));
    }
    /**
     * 将真实活动确认合入调用方派发/结果业务事务，不另开连接、不提交、不切换租户。
     * 若调用方已锁Run，须此前已持有所属预算根锁；本方法对同根重入不会改变锁顺序。
     * 只有实际成功提交的区间进入预算权威；所属事务回滚时活动观察也回滚。
     */
    public ActivityObservation recordActivity(SqlSession c,RunRef run,String id,Instant start,Instant through) {
        Objects.requireNonNull(c,"session"); Objects.requireNonNull(run,"run"); Objects.requireNonNull(start,"start"); Objects.requireNonNull(through,"through");
        if(through.isBefore(start))throw new IllegalArgumentException("budget_activity_invalid");
            var rootRef=root(c,run).orElseThrow(()->new IllegalStateException("budget_scope_missing"));lockRoot(c,rootRef);
            var confirmed=mapper(c).recordActivity(tenant(run),run.runId(),id,start,through);
            if(confirmed==null)throw new IllegalStateException("budget_activity_conflict");
            var scope=scope(c,run);
            if(scope!=null) { scope.rootActivity.remove(rootRef);scope.runActivity.remove(run); }
            return new ActivityObservation(run,id,activeMillis(c,run,rootRef,false),activeMillis(c,run,rootRef,true),confirmed.confirmedThrough);
    }
    /** 合并已排序的重叠活动区间，仅计算已确认活动时间；不计挂起空档。 */
    private long activeMillis(SqlSession c,Registration reg,boolean own) {
        return activeMillis(c,reg.run(),reg.root(),own);
    }
    /** 活动确认只依赖原 Run 与真实根，不为计时重建完整资源限额或余额。 */
    private long activeMillis(SqlSession c,RunRef run,RunRef root,boolean own) {
        var scope=scope(c,run);
        var key=own?run:root;
        var observed=scope==null?null:(own?scope.runActivity:scope.rootActivity);
        // 仅根锁保护下的已确认区间可复用；recordActivity 的己方写入会先使相关值失效。
        boolean reusable=scope!=null && scope.roots.contains(root);
        if(reusable && observed.containsKey(key))return observed.get(key);
        var spans=mapper(c).activitySpans(tenant(run),own?run.runId():root.runId(),own);
        long active=unionMillis(spans);
        if(reusable)observed.put(key,active);
        return active;
    }
    private static long unionMillis(java.util.List<BudgetActivityRow> spans) {
        long total=0; Instant start=null,end=null;
        for(var span:spans){if(start==null){start=span.startedAt;end=span.confirmedThrough;}else if(!span.startedAt.isAfter(end)){if(span.confirmedThrough.isAfter(end))end=span.confirmedThrough;}else{total=Math.addExact(total,Duration.between(start,end).toMillis());start=span.startedAt;end=span.confirmedThrough;}}
        return start==null?0:Math.addExact(total,Duration.between(start,end).toMillis());
    }
    /** 参与调用方事务读取完整冻结注册及全部资源维度；缺失注册返回空值。 */
    public Optional<Registration> registration(SqlSession c,RunRef run) {
        var scope=scope(c,run);
        if(scope!=null && scope.registrations.containsKey(run))return Optional.of(scope.registrations.get(run));
        var row=registrationIdentity(c,run);
        if(row==null)return Optional.empty();
        var root=new RunRef(run.tenantId(),row.rootId);
        var limits=registrationBalances(c,run,root);
        var registration=new Registration(run,root,Role.valueOf(row.role), new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new Limits(Scenario.valueOf(row.scenario),
                limits[0].maximum(),limits[1].maximum(),limits[2].maximum(),row.rootActiveSeconds,row.runActiveSeconds,row.sourceVersion)));
        if(scope!=null)scope.registrations.put(run,registration);
        return Optional.of(registration);
    }

    private BudgetRegistrationRow registrationIdentity(SqlSession c,RunRef run) {
        var scope=scope(c,run);
        if(scope!=null && scope.identities.containsKey(run))return scope.identities.get(run);
        var row=mapper(c).registration(tenant(run),run.runId());
        if(scope!=null && row!=null)scope.identities.put(run,row);
        return row;
    }
    /**
     * 只读取现有登记的预算根身份，不加载资源余额、活动区间或其他 Run 状态。
     *
     * <p>供业务事务在锁 Run 前确定所属根；必须随后调用 {@link #lockRoot}，此读取本身
     * 不签发预留或发送许可。SqlSession 已绑定的租户不可在事务内改变。</p>
     *
     * @param c 已绑定 run 所属租户的业务事务
     * @param run 需要查找预算根的完整 Run 身份
     * @return 原预算根；未登记或被当前租户 RLS 隔离时为空
     */
    public Optional<RunRef> root(SqlSession c, RunRef run) {
        Objects.requireNonNull(c, "session");
        Objects.requireNonNull(run, "run");
        var row = registrationIdentity(c,run);
        return row == null ? Optional.empty() : Optional.of(new RunRef(run.tenantId(), row.rootId));
    }
    /** 冻结限额一次集合读取；所有七维及 consumed/reserved 的原有完整校验保持不变。 */
    private Balance[] registrationBalances(SqlSession c,RunRef run,RunRef root) {
        long[][][] values=new long[3][3][Dimension.values().length];
        int[] counts=new int[3];
        for (var row : mapper(c).registrationBalances(Map.of("tenant",tenant(run),"run",run.runId(),"root",root.runId()))) {
            int scope=switch(row.scopeKind){case "ROOT"->0;case "RUN"->1;case "STAGE_BASE"->2;
                default->throw new IllegalStateException("budget_balance_missing");};
            int dimension=Dimension.valueOf(row.dimension).ordinal();
            values[scope][0][dimension]=row.maximum;
            values[scope][1][dimension]=row.consumed;
            values[scope][2][dimension]=row.reserved;
            counts[scope]++;
        }
        var balances=new Balance[3];
        for(int scope=0;scope<3;scope++) {
            if(counts[scope]!=Dimension.values().length)throw new IllegalStateException("budget_balance_missing");
            balances[scope]=new Balance(Resources.from(values[scope][0]),Resources.from(values[scope][1]),Resources.from(values[scope][2]));
        }
        return balances;
    }
    /** 读取必需注册，缺失时以 budget_scope_missing 拒绝操作。 */
    private Registration require(SqlSession c,RunRef run){return registration(c,run).orElseThrow(()->new IllegalStateException("budget_scope_missing"));}
    /** 读取根的当前阶段；根事实缺失不推测默认阶段。 */
    public Phase phase(SqlSession c,RunRef root) {
        String phase=mapper(c).phase(tenant(root),root.runId());
        if(phase==null)throw new IllegalStateException("budget_root_missing");
        return Phase.valueOf(phase);
    }
    /**
     * 在调用方事务取得预算根锁；不创建连接、不提交事务，也不改变租户绑定。
     *
     * <p>涉及预算和运行状态的事务须先锁预算根，再锁 Run，最后锁 Action/Attempt；
     * 纯取消只锁 Run，不调用本方法。根缺失或被 RLS 隔离时拒绝，不能降级为无预算执行。</p>
     *
     * @param c 已绑定根所属租户的业务事务
     * @param root 原预算登记确定的根身份
     */
    public void lockRoot(SqlSession c,RunRef root) {
        Objects.requireNonNull(c, "session");
        Objects.requireNonNull(root, "root");
        var scope=scope(c,root);
        if(scope!=null && scope.roots.contains(root))return;
        if(mapper(c).lockRoot(tenant(root),root.runId())==null)throw new IllegalStateException("budget_root_missing");
        if(scope!=null)scope.roots.add(root);
    }
    /** 读取指定作用域的全部维度余额；任何维度缺失都拒绝，不以零补齐损坏事实。 */
    private Balance balances(SqlSession c,RunRef run,String kind,String id) {
        long[] maximum=new long[Dimension.values().length],consumed=maximum.clone(),reserved=maximum.clone();
        var rows=mapper(c).balances(tenant(run),kind,id);
        if(rows.size()!=Dimension.values().length)throw new IllegalStateException("budget_balance_missing");
        for(var row:rows){int i=Dimension.valueOf(row.dimension).ordinal();maximum[i]=row.maximum;consumed[i]=row.consumed;reserved[i]=row.reserved;}
        return new Balance(Resources.from(maximum),Resources.from(consumed),Resources.from(reserved));
    }
    /** 还原 attempt 的原始预留金额，用于判定同标识请求是否一致。 */
    private Resources amounts(SqlSession c,RunRef run,String attempt) {
        long[] values=new long[Dimension.values().length];
        for(var row:mapper(c).amounts(tenant(run),attempt))values[Dimension.valueOf(row.dimension).ordinal()]=row.reserved;
        return Resources.from(values);
    }
    /** 为作用域初始化全部维度；仅用于持有根锁的注册事务。 */
    private void insertBalances(SqlSession c,RunRef run,String kind,String id,Resources values) {
        if(mapper(c).insertBalances(Map.of("tenant",tenant(run),"kind",kind,"scope",id,"amounts",maximums(values)))!=Dimension.values().length)
            throw new IllegalStateException("budget_balance_missing");
    }
    /** 全部七维都进入集合SQL，明确零值不跳过，缺行由实际受影响行数拒绝。 */
    private static java.util.List<BalanceMaximum> maximums(Resources resources) {
        var values=new ArrayList<BalanceMaximum>(Dimension.values().length);
        for(var dimension:Dimension.values())values.add(new BalanceMaximum(dimension.name(),resources.value(dimension)));
        return values;
    }
    /** 取得当前 SqlSession 的预算映射，不获取新连接或创建新事务。 */
    private static ExecutionBudgetMapper mapper(SqlSession session) { return session.getMapper(ExecutionBudgetMapper.class); }
    /** 绑定租户后执行单个预算原子操作；成功提交，任何运行时失败回滚并保留原始原因。 */
    private <T>T transaction(RunRef run,Work<T> work) {
        String transactionTag=java.util.UUID.randomUUID().toString();
        LOG.log(System.Logger.Level.DEBUG,"预算事务开始 transactionTag={0}",transactionTag);
        try(var session=sessions.openSession(false)) {
            try {
                mapper(session).bindTenant(tenant(run));
                T result=participate(session,run.tenantId(),()->work.apply(session));
                session.commit();
                LOG.log(System.Logger.Level.DEBUG,"预算事务提交 transactionTag={0}",transactionTag);
                return result;
            } catch(RuntimeException failure) {
                session.rollback();
                LOG.log(System.Logger.Level.DEBUG,"预算事务回滚 transactionTag={0}",transactionTag);
                throw failure;
            }
        } catch(org.apache.ibatis.exceptions.PersistenceException failure) {
            // 适配器异常边界不向 Runtime 契约泄漏框架类型，底层原因仍可供受控诊断。
            throw new IllegalStateException("budget_storage_failed",failure);
        } finally { LOG.log(System.Logger.Level.DEBUG,"预算事务结束 transactionTag={0}",transactionTag); }
    }
    /** 提取调用方明确提供的租户；不从线程或连接残留状态推断。 */
    private static String tenant(RunRef run){return run.tenantId().value();}
    @FunctionalInterface private interface Work<T>{T apply(SqlSession c);}
    private record Balance(Resources maximum,Resources consumed,Resources reserved){Resources used(){return consumed.plus(reserved);}}
    private record Amount(Dimension dimension,long reserved,long reported){}
    private record BudgetAmount(String dimension,long reserved,long maximum){}
    private record BalanceMaximum(String dimension,long maximum){}
    private record ReportedAmount(String dimension,long reported){}
}

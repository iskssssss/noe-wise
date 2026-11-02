package com.skillforge.runtime.adapter.sqlite.budget;

import com.skillforge.runtime.adapter.sqlite.SqliteInstantTypeHandler;
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

/** SQLite IMMEDIATE 写事务串行化全部预留；预算与执行状态共享调用方事务。 */
public final class SqliteExecutionBudgets implements ExecutionBudgetPort {
    private static final System.Logger LOG=System.getLogger(SqliteExecutionBudgets.class.getName());
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
     * 本次调用方 IMMEDIATE 事务拥有的局部预算作用域；租户由显式参数传入，提交和回滚仍归调用方。
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
    /** 由配置为 IMMEDIATE 的宿主数据源装配预算映射；不创建或关闭宿主连接池。 */
    public SqliteExecutionBudgets(DataSource source) {
        this(source, new JdbcTransactionFactory());
    }
    /** 宿主可提供已批准的事务工厂；受理冻结必须加入宿主事务，适配器不引入 Spring。 */
    public SqliteExecutionBudgets(DataSource source, org.apache.ibatis.transaction.TransactionFactory transactions) {
        var configuration = new Configuration(new Environment("execution-budgets", Objects.requireNonNull(transactions), Objects.requireNonNull(source)));
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configure(configuration);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 为新建配置注册预算映射；Action 账本复用此配置和 SqlSession，不创建嵌套事务。 */
    public static void configure(Configuration configuration) {
        configuration.getTypeHandlerRegistry().register(Instant.class, new SqliteInstantTypeHandler());
        String resource = "mapper/SqliteExecutionBudgetMapper.xml";
        try (InputStream xml = SqliteExecutionBudgets.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("budget_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (java.io.IOException failure) { throw new IllegalStateException("budget_mapper_unavailable", failure); }
    }

    /** 在预算根检查内冻结根与 Run 限额；重复的相同注册返回原事实，冲突拒绝并回滚。 */
    @Override public Registration register(Registration registration) {
        Objects.requireNonNull(registration, "registration");
        return transaction(registration.run(), c -> register(c, registration));
    }

    /**
     * 在调用方 SqlSession 中冻结预算登记，不新建连接、不绑定租户、不提交事务。
     *
     * <p>调用方须以 IMMEDIATE 模式开始写事务，且全部读写显式限定本登记的租户；
     * 本方法核验预算根，随后预算登记与 Run 受理可在同一写事务生效。任何后续失败由
     * 调用方回滚全部业务写入，相同登记重入不改变冻结限额。</p>
     *
     * @param c 限定同一租户的业务事务
     * @param registration 本次受理的完整冻结预算
     * @return 已保存且与请求完全相同的登记
     */
    public Registration register(SqlSession c, Registration registration) {
        Objects.requireNonNull(c, "session"); Objects.requireNonNull(registration, "registration");
        var run=registration.run(); var root=registration.root(); var policy=registration.policy();
        var finite=policy instanceof Policy.Enforced enforced ? Optional.of(enforced.limits()) : Optional.<Limits>empty();
        String mode=mode(policy);
        if(run.equals(root)) mapper(c).insertRoot(tenant(run),root.runId(),policy.scenario().name(),
                policy.sourceVersion(),mode,finite.map(Limits::rootActiveSeconds).orElse(null));
        lockRoot(c,root);
        var existing=registration(c,run);
        if(existing.isPresent()) {
            if(!existing.get().equals(registration))throw new IllegalStateException("budget_registration_conflict");
            return existing.get();
        }
        if(run.equals(root)) {
            insertBalances(c,run,"ROOT",root.runId(),policy,finite.map(Limits::root));
            insertBalances(c,run,"CLOSING",root.runId(),policy,Optional.of(Resources.ZERO));
        } else {
            var parent=registration(c,root).orElseThrow(()->new IllegalStateException("budget_root_missing")).policy();
            if(parent.scenario()!=policy.scenario() || !mode(parent).equals(mode))
                throw new IllegalStateException("budget_root_configuration_conflict");
            if(finite.isPresent()) {
                var limits=finite.orElseThrow();var rootLimits=parent.requireEnforced();
                if(!rootLimits.root().equals(limits.root()) || rootLimits.rootActiveSeconds()!=limits.rootActiveSeconds())
                    throw new IllegalStateException("budget_root_configuration_conflict");
            }
        }
        mapper(c).insertRun(tenant(run),run.runId(),root.runId(),registration.role().name(),policy.sourceVersion(),
                mode,finite.map(Limits::runActiveSeconds).orElse(null));
        insertBalances(c,run,"RUN",run.runId(),policy,finite.map(Limits::run));
        insertBalances(c,run,"STAGE_BASE",run.runId(),policy,finite.map(Limits::stage));
        insertBalances(c,run,"STAGE",run.runId(),policy,finite.map(Limits::stage));
        insertBalances(c,run,"CLOSING_RUN",run.runId(),policy,Optional.of(Resources.ZERO));
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
            long rootActive=activeMillis(c,reg,false);long runActive=activeMillis(c,reg,true);
            if(reg.policy() instanceof Policy.Enforced finite
                    && (rootActive>=Math.multiplyExact(finite.limits().rootActiveSeconds(),MILLIS_PER_SECOND)
                    ||runActive>=Math.multiplyExact(finite.limits().runActiveSeconds(),MILLIS_PER_SECOND)))
                return new Reservation.Denied(Refusal.ACTIVE_TIME_LIMIT,Optional.empty());
            var root = balances(c,demand.run(),"ROOT",reg.root().runId());
            var run = balances(c,demand.run(),"RUN",demand.run().runId());
            var stage = balances(c,demand.run(),"STAGE",demand.run().runId());
            if(!stage.mode().equals(mode(reg.policy())))throw new IllegalStateException("budget_policy_corrupt");
            if (phase(c,reg.root())==Phase.CLOSING && (!demand.closing() || reg.role()!=Role.PRIMARY))
                return new Reservation.Denied(Refusal.CLOSING_REQUIRED, Optional.empty());
            if(reg.policy() instanceof Policy.Enforced enforced) {
                var limits=enforced.limits();
                var closing=balances(c,demand.run(),"CLOSING",reg.root().runId()).maximum();
                Resources protectedAmount=demand.closing() && reg.role()==Role.PRIMARY ? Resources.ZERO : closing;
                var rootExceeded=root.used().plus(demand.resources()).plus(protectedAmount).firstExceededDimension(root.maximum());
                if(rootExceeded.isPresent())return protectedAmount.equals(Resources.ZERO)
                        ?new Reservation.Denied(Refusal.ROOT_LIMIT,rootExceeded):new Reservation.Denied(Refusal.CLOSING_REQUIRED,Optional.empty());
                var protectedRun=demand.closing()?Resources.ZERO:balances(c,demand.run(),"CLOSING_RUN",demand.run().runId()).maximum();
                var runExceeded=run.used().plus(demand.resources()).plus(protectedRun).firstExceededDimension(run.maximum());
                if(runExceeded.isPresent())return protectedRun.equals(Resources.ZERO)
                        ?new Reservation.Denied(Refusal.RUN_LIMIT,runExceeded):new Reservation.Denied(Refusal.CLOSING_REQUIRED,Optional.empty());
                long timeProtection=demand.closing()?0:Math.multiplyExact(closingSeconds(c,reg.root()),MILLIS_PER_SECOND);
                if(Math.addExact(rootActive,timeProtection)>=Math.multiplyExact(limits.rootActiveSeconds(),MILLIS_PER_SECOND)
                        ||Math.addExact(runActive,timeProtection)>=Math.multiplyExact(limits.runActiveSeconds(),MILLIS_PER_SECOND))
                    return new Reservation.Denied(Refusal.CLOSING_REQUIRED,Optional.empty());
            }
            // 两种模式均记录完整七维预留；只计量模式的阶段上限保持真正缺省。
            var dimensions=new ArrayList<BudgetAmount>();
            for(var dimension:Dimension.values())dimensions.add(new BudgetAmount(dimension.name(),
                    demand.resources().value(dimension),stage.cap().map(cap->Math.max(cap.value(dimension),
                    Math.addExact(stage.used().value(dimension),demand.resources().value(dimension)))).orElse(null)));
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
            if(reg.policy() instanceof Policy.Enforced enforced
                    && (activeMillis(c,reg,false)>=Math.multiplyExact(enforced.limits().rootActiveSeconds(),MILLIS_PER_SECOND)
                    ||activeMillis(c,reg,true)>=Math.multiplyExact(enforced.limits().runActiveSeconds(),MILLIS_PER_SECOND)))return false;
            // 预留后可能由主助手进入收尾；尚未发送的普通业务许可不能穿过新的边界。
            if(phase(c,reg.root())==Phase.CLOSING) {
                if (reg.role()!=Role.PRIMARY || !Boolean.TRUE.equals(mapper(c).attemptClosing(tenant(run),run.runId(),attempt))) return false;
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
    /** 参与调用方 SqlSession 的原子结算；调用方必须先持有预算根检查，本方法不提交事务。 */
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
            if(reg.policy() instanceof Policy.ObserveOnly)return false;
            var limits=reg.policy().requireEnforced();
            boolean fits=snapshot.consumed().plus(snapshot.reserved()).plus(demand).plus(snapshot.closing().resources()).fits(limits.root())
                    && snapshot.runConsumed().plus(snapshot.runReserved()).plus(demand).plus(snapshot.closing().resources()).fits(limits.run());
            long protectedMillis=Math.multiplyExact(snapshot.closing().activeSeconds(),MILLIS_PER_SECOND);
            boolean timeFits=Math.addExact(snapshot.activeMillis(),protectedMillis)<Math.multiplyExact(limits.rootActiveSeconds(),MILLIS_PER_SECOND)
                    && Math.addExact(snapshot.runActiveMillis(),protectedMillis)<Math.multiplyExact(limits.runActiveSeconds(),MILLIS_PER_SECOND);
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
        var amount=closing.resources();
        if(reg.policy() instanceof Policy.Enforced enforced) {
            var root=balances(c,run,"ROOT",reg.root().runId());var own=balances(c,run,"RUN",run.runId());
            long rootActive=activeMillis(c,reg,false);long runActive=activeMillis(c,reg,true);
            long requestedActive=Math.multiplyExact(closing.activeSeconds(),MILLIS_PER_SECOND);
            var shortfalls=new java.util.ArrayList<ClosingShortfall>();
            closingShortfalls(shortfalls,BalanceScope.ROOT,root,amount);
            closingShortfalls(shortfalls,BalanceScope.RUN,own,amount);
            closingTimeShortfall(shortfalls,BalanceScope.ROOT,rootActive,requestedActive,Math.multiplyExact(reg.policy().requireEnforced().rootActiveSeconds(),MILLIS_PER_SECOND));
            closingTimeShortfall(shortfalls,BalanceScope.RUN,runActive,requestedActive,Math.multiplyExact(reg.policy().requireEnforced().runActiveSeconds(),MILLIS_PER_SECOND));
            if(!shortfalls.isEmpty()) throw new ClosingInsufficient(phase(c,reg.root()),shortfalls);
            }
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
        if(!row.policyMode.equals(row.rootPolicyMode))throw new IllegalStateException("budget_policy_corrupt");
        var balances=registrationBalances(c,run,root);
        for(var balance:balances)if(!row.policyMode.equals(balance.mode()))throw new IllegalStateException("budget_policy_corrupt");
        Policy policy=switch(row.policyMode) {
            case "ENFORCED" -> {
                if(row.rootActiveSeconds==null || row.runActiveSeconds==null)throw new IllegalStateException("budget_policy_corrupt");
                yield new Policy.Enforced(new Limits(Scenario.valueOf(row.scenario),balances[0].maximum(),
                        balances[1].maximum(),balances[2].maximum(),row.rootActiveSeconds,row.runActiveSeconds,row.sourceVersion));
            }
            case "OBSERVE_ONLY" -> {
                if(row.rootActiveSeconds!=null || row.runActiveSeconds!=null
                        ||java.util.Arrays.stream(balances).anyMatch(balance->balance.cap().isPresent()))
                    throw new IllegalStateException("budget_policy_corrupt");
                yield new Policy.ObserveOnly(Scenario.valueOf(row.scenario),row.sourceVersion);
            }
            default -> throw new IllegalStateException("budget_policy_corrupt");
        };
        var registration=new Registration(run,root,Role.valueOf(row.role),policy);
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
     * 不签发预留或发送许可。SqlSession 限定的租户不可在事务内改变。</p>
     *
     * @param c 已限定 run 所属租户的业务事务
     * @param run 需要查找预算根的完整 Run 身份
     * @return 原预算根；未登记或被当前租户 租户条件隔离时为空
     */
    public Optional<RunRef> root(SqlSession c, RunRef run) {
        Objects.requireNonNull(c, "session");
        Objects.requireNonNull(run, "run");
        var row = registrationIdentity(c,run);
        return row == null ? Optional.empty() : Optional.of(new RunRef(run.tenantId(), row.rootId));
    }
    /** 冻结限额一次集合读取；所有七维及 consumed/reserved 的原有完整校验保持不变。 */
    private Balance[] registrationBalances(SqlSession c,RunRef run,RunRef root) {
        var rows=mapper(c).registrationBalances(Map.of("tenant",tenant(run),"run",run.runId(),"root",root.runId()));
        return new Balance[]{balance(rows.stream().filter(row->"ROOT".equals(row.scopeKind)).toList()),
                balance(rows.stream().filter(row->"RUN".equals(row.scopeKind)).toList()),
                balance(rows.stream().filter(row->"STAGE_BASE".equals(row.scopeKind)).toList())};
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
     * 在调用方 IMMEDIATE 事务核验预算根；不创建连接、不提交事务，也不改变租户身份。
     *
     * <p>涉及预算和运行状态的事务须由 IMMEDIATE 模式取得写入权，再核验预算根和 Run/Attempt；
     * 纯取消不调用本方法。根缺失或被 租户条件隔离时拒绝，不能降级为无预算执行。</p>
     *
     * @param c 已限定根所属租户的业务事务
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
        return balance(mapper(c).balances(tenant(run),kind,id));
    }
    private static Balance balance(java.util.List<BudgetBalanceRow> rows) {
        if(rows.size()!=Dimension.values().length)throw new IllegalStateException("budget_balance_missing");
        long[] maximum=new long[Dimension.values().length],consumed=maximum.clone(),reserved=maximum.clone();
        var dimensions=java.util.EnumSet.noneOf(Dimension.class);int caps=0;String mode=rows.getFirst().policyMode;
        if(!"ENFORCED".equals(mode) && !"OBSERVE_ONLY".equals(mode))throw new IllegalStateException("budget_policy_corrupt");
        for(var row:rows) {
            var dimension=Dimension.valueOf(row.dimension);int i=dimension.ordinal();
            if(!dimensions.add(dimension) || !mode.equals(row.policyMode))throw new IllegalStateException("budget_balance_missing");
            if(row.maximum!=null){maximum[i]=row.maximum;caps++;}
            consumed[i]=row.consumed;reserved[i]=row.reserved;
        }
        if(caps!=0 && caps!=Dimension.values().length)throw new IllegalStateException("budget_policy_corrupt");
        return new Balance(mode,caps==0?Optional.empty():Optional.of(Resources.from(maximum)),Resources.from(consumed),Resources.from(reserved));
    }
    /** 还原 attempt 的原始预留金额，用于判定同标识请求是否一致。 */
    private Resources amounts(SqlSession c,RunRef run,String attempt) {
        long[] values=new long[Dimension.values().length];
        for(var row:mapper(c).amounts(tenant(run),attempt))values[Dimension.valueOf(row.dimension).ordinal()]=row.reserved;
        return Resources.from(values);
    }
    /** 为作用域初始化全部维度；仅用于持有根锁的注册事务。 */
    private void insertBalances(SqlSession c,RunRef run,String kind,String id,Policy policy,Optional<Resources> values) {
        if(mapper(c).insertBalances(Map.of("tenant",tenant(run),"kind",kind,"scope",id,"mode",mode(policy),"amounts",maximums(values)))!=Dimension.values().length)
            throw new IllegalStateException("budget_balance_missing");
    }
    private static String mode(Policy policy) { return policy instanceof Policy.Enforced?"ENFORCED":"OBSERVE_ONLY"; }
    /** 缺省上限只在已标记的只计量策略中保存为 NULL；它不改变真实预留与消耗。 */
    private static java.util.List<BalanceMaximum> maximums(Optional<Resources> resources) {
        var values=new ArrayList<BalanceMaximum>(Dimension.values().length);
        for(var dimension:Dimension.values())values.add(new BalanceMaximum(dimension.name(),resources.map(r->r.value(dimension)).orElse(null)));
        return values;
    }
    private static java.util.List<BalanceMaximum> maximums(Resources resources) { return maximums(Optional.of(resources)); }
    /** 取得当前 SqlSession 的预算映射，不获取新连接或创建新事务。 */
    private static ExecutionBudgetMapper mapper(SqlSession session) { return session.getMapper(ExecutionBudgetMapper.class); }
    /** 在 IMMEDIATE 写事务中执行单个预算原子操作；成功提交，任何运行时失败回滚并保留原始原因。 */
    private <T>T transaction(RunRef run,Work<T> work) {
        String transactionTag=java.util.UUID.randomUUID().toString();
        LOG.log(System.Logger.Level.DEBUG,"预算事务开始 transactionTag={0}",transactionTag);
        try(var session=sessions.openSession(false)) {
            try {
                // 数据源采用 IMMEDIATE；首次访问时取得单机写入所有权。
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
    private record Balance(String mode,Optional<Resources> cap,Resources consumed,Resources reserved){
        Resources maximum(){return cap.orElseThrow(()->new IllegalStateException("budget_limit_absent"));}
        Resources used(){return consumed.plus(reserved);}
    }
    private record Amount(Dimension dimension,long reserved,long reported){}
    private record BudgetAmount(String dimension,long reserved,Long maximum){}
    private record BalanceMaximum(String dimension,Long maximum){}
    private record ReportedAmount(String dimension,long reported){}
}

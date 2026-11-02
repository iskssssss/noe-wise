package com.skillforge.runtime.adapter.jdbc.budget;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式目标迁移、非管理员连接及真实事务验证；恢复只结算证据，不再次发送调用。 */
@Testcontainers(disabledWithoutDocker = false)
class JdbcExecutionBudgetsTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final TenantId OTHER_TENANT = new TenantId("2");
    private static final String VERSION = "test-budget:v1";
    private static final String JDBC_ROLE = "budget_runtime";
    private static final String JDBC_PASSWORD = "budget_test_password";
    private static final Resources CAPACITY = new Resources(8, 4, 8, 4, 1_000, 300, 1_300);
    private static final Resources BASE_STAGE = new Resources(1, 1, 1, 1, 100, 50, 150);
    private static final long ACTIVE_SECONDS = 3_600;
    private static final long CONCURRENT_TIMEOUT_SECONDS = 10;
    private static HikariDataSource source;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("execution_budgets").withUsername("admin").withPassword("admin_password");

    @BeforeAll
    static void schema() throws Exception {
        com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase.migrate(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + JDBC_ROLE + " LOGIN PASSWORD '" + JDBC_PASSWORD
                    + "' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + JDBC_ROLE);
            statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + JDBC_ROLE);
        }
        var configuration = new HikariConfig();
        configuration.setJdbcUrl(POSTGRES.getJdbcUrl());
        configuration.setUsername(JDBC_ROLE);
        configuration.setPassword(JDBC_PASSWORD);
        configuration.setMaximumPoolSize(4);
        source = new HikariDataSource(configuration);
    }

    @AfterAll
    static void closePool() {
        if (source != null) source.close();
    }

    private static org.apache.ibatis.session.SqlSessionFactory sessions() {
        return sessions(source);
    }

    private static org.apache.ibatis.session.SqlSessionFactory sessions(javax.sql.DataSource dataSource) {
        var configuration = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                "budget-shared-transaction-test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource));
        JdbcExecutionBudgets.configure(configuration);
        return new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
    }

    /** 预算受理与所属业务事务共同回滚，不能留下半成品根、Run 或余额。 */
    @Test
    void joinedRegistrationIsVisibleInOwningSessionAndRollsBackWithoutIndependentCommit() {
        var budgets = budgets();
        var run = run("joined-registration-rollback");
        var frozen = registration(run, run, Role.PRIMARY, CAPACITY, ACTIVE_SECONDS);
        try (var session = sessions().openSession(false)) {
            session.getMapper(ExecutionBudgetMapper.class).bindTenant(TENANT.value());
            assertEquals(frozen, budgets.register(session, frozen));
            assertEquals(Optional.of(run), budgets.root(session, run));
            assertEquals(Optional.of(frozen), budgets.registration(session, run));
            session.rollback();
        }
        assertTrue(budgets.find(run).isEmpty());
        assertEquals(frozen, budgets.register(frozen));
    }

    /** 活动时间确认与业务事务共同回滚，不在正常派发之外独立提交。 */
    @Test void joinedActivityConfirmationRollsBackWithTheOwningSqlSession() {
        var budgets = budgets(); var run = run("joined-activity");
        budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        try (var session = sessions().openSession(false)) {
            session.getMapper(ExecutionBudgetMapper.class).bindTenant(TENANT.value());
            budgets.lockRoot(session,run);
            var observation = budgets.recordActivity(session,run,"drive-interval",Instant.EPOCH,Instant.EPOCH.plusSeconds(1));
            assertEquals(1_000,observation.runActiveMillis());
            session.rollback();
        }
        assertEquals(0,budgets.snapshot(run).runActiveMillis());
    }

    /** 根查询只取得原登记身份，根锁由调用方在取得 Run 锁前显式持有。 */
    @Test
    void joinedRootLookupDoesNotReadBalancesAndCannotLockAnotherTenantsRoot() {
        var budgets = budgets();
        var root = run("joined-root-lookup");
        var member = run("joined-root-member");
        budgets.register(registration(root, root, Role.PRIMARY, CAPACITY, ACTIVE_SECONDS));
        budgets.register(registration(member, root, Role.MEMBER, CAPACITY, ACTIVE_SECONDS));
        var sql = new java.util.ArrayList<String>();
        var sourceWithSql = countedDataSource(sql);
        var configuration = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                "budget-lightweight-root-test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), sourceWithSql));
        JdbcExecutionBudgets.configure(configuration);
        var factory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession(false)) {
            session.getMapper(ExecutionBudgetMapper.class).bindTenant(TENANT.value());
            sql.clear();
            assertEquals(Optional.of(root), budgets.root(session, member));
            assertEquals(1, sql.size());
            assertTrue(sql.stream().noneMatch(value -> value.contains("agent_runtime_budget_balances")));
            budgets.lockRoot(session, root);
            assertTrue(budgets.root(session, run("unregistered-root-lookup")).isEmpty());
            var otherTenantRoot = new RunRef(OTHER_TENANT, root.runId());
            assertTrue(budgets.root(session, otherTenantRoot).isEmpty());
            assertEquals("budget_root_missing", assertThrows(IllegalStateException.class,
                    () -> budgets.lockRoot(session, otherTenantRoot)).getMessage());
            session.rollback();
        }
    }

    @Test
    void joinedReservationAndReceiptRollBackWithTheOwningSqlSession() {
        var budgets=budgets();
        var run=run("joined-rollback");
        budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        var request=demand(run,"joined-attempt",Category.BUSINESS_MODEL,40,10,false);
        try(var session=sessions().openSession(false)) {
            var mapper=session.getMapper(ExecutionBudgetMapper.class);
            mapper.bindTenant(TENANT.value());
            assertGranted(budgets.reserve(session,request),AttemptState.RESERVED);
            assertTrue(budgets.markSent(session,run,request.attemptId()));
            budgets.saveResult(session,run,request.attemptId(),Usage.reported(OptionalLong.of(30),OptionalLong.of(5)),"joined-result");
            budgets.settleAmounts(session,budgets.registration(session,run).orElseThrow(),request.attemptId());
            assertEquals("SETTLED",mapper.attemptState(TENANT.value(),run.runId(),request.attemptId()));
            session.rollback();
        }
        assertEquals(Resources.ZERO,budgets.snapshot(run).consumed());
        assertEquals(Resources.ZERO,budgets.snapshot(run).reserved());
        // 同一 attempt 可重新正常准入，证明账本和预算金额随外层事务一起回滚。
        assertGranted(budgets.reserve(request),AttemptState.RESERVED);
    }

    /** 准备期阶段读取只认原租户Run登记与真实根；不加载无人消费的余额和活动或缓存旧阶段。 */
    @Test void phaseReadsRegisteredRootWithoutBalancesActivityOrAuthorizationToDispatch() {
        var sql=new java.util.ArrayList<String>();var budgets=new JdbcExecutionBudgets(countedDataSource(sql));
        var root=run("phase-root");var member=run("phase-member");
        budgets.register(registration(root,root,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        budgets.register(registration(member,root,Role.MEMBER,CAPACITY,ACTIVE_SECONDS));
        sql.clear();
        assertEquals(Phase.BUSINESS,budgets.phase(member));
        assertEquals(2,sql.stream().filter(value->value.startsWith("SELECT")&&!value.contains("set_config")).count());
        assertTrue(sql.stream().noneMatch(value->value.contains("agent_runtime_budget_balances")
                ||value.contains("agent_runtime_budget_activity")||value.contains("FOR UPDATE")));
        budgets.enterClosing(root);sql.clear();
        assertEquals(Phase.CLOSING,budgets.phase(member));
        assertTrue(sql.stream().noneMatch(value->value.contains("agent_runtime_budget_balances")||value.contains("agent_runtime_budget_activity")));
        assertDenied(budgets.reserve(demand(member,"phase-stale-read",Category.BUSINESS_MODEL,1,1,false)),Refusal.CLOSING_REQUIRED);
        assertEquals("budget_scope_missing",assertThrows(IllegalStateException.class,()->budgets.phase(run("phase-unknown"))).getMessage());
        assertEquals("budget_scope_missing",assertThrows(IllegalStateException.class,
                ()->budgets.phase(new RunRef(OTHER_TENANT,member.runId()))).getMessage());
    }

    /** 同事务复用锁和冻结事实，但自己的预留、活动延长与UNKNOWN仍必须改变后续真实判定。 */
    @Test void localBudgetScopeReusesFrozenFactsReadsOwnWritesAndEndsBeforeTheNextTransaction() throws SQLException {
        var sql=new java.util.ArrayList<String>();var counted=countedDataSource(sql);var budgets=new JdbcExecutionBudgets(counted);
        var run=run("local-budget-scope");budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,3));
        var first=demand(run,"local-scope-first",Category.BUSINESS_MODEL,600,200,false);
        try(var session=sessions(counted).openSession(false)) {
            session.getMapper(ExecutionBudgetMapper.class).bindTenant(TENANT.value());sql.clear();
            budgets.participate(session,TENANT,()->{
                budgets.lockRoot(session,budgets.root(session,run).orElseThrow());
                assertEquals(1_000,budgets.recordActivity(session,run,"drive",Instant.EPOCH,Instant.EPOCH.plusSeconds(1)).runActiveMillis());
                assertGranted(budgets.reserve(session,first),AttemptState.RESERVED);
                assertTrue(budgets.markSent(session,run,first.attemptId()));
                assertDenied(budgets.reserve(session,demand(run,"local-scope-over-budget",Category.BUSINESS_MODEL,600,200,false)),Refusal.ROOT_LIMIT);
                assertEquals(1,sql.stream().filter(value->value.contains("FROM agent_runtime_budget_roots") && value.endsWith("FOR UPDATE")).count());
                assertEquals(1,sql.stream().filter(value->value.startsWith("SELECT r.root_id AS")).count());
                assertEquals(1,sql.stream().filter(value->value.startsWith("SELECT scope_kind,dimension,maximum,consumed,reserved")).count());
                assertEquals(2,sql.stream().filter(value->value.contains("SELECT a.started_at,a.confirmed_through")).count());
                session.getMapper(ExecutionBudgetMapper.class).enterClosing(TENANT.value(),run.runId());
                assertDenied(budgets.reserve(session,demand(run,"local-scope-after-phase",Category.BUSINESS_MODEL,1,1,false)),Refusal.CLOSING_REQUIRED);
                assertEquals(3_000,budgets.recordActivity(session,run,"drive",Instant.EPOCH,Instant.EPOCH.plusSeconds(3)).runActiveMillis());
                assertDenied(budgets.reserve(session,demand(run,"local-scope-after-time",Category.BUSINESS_MODEL,1,1,false)),Refusal.ACTIVE_TIME_LIMIT);
                budgets.saveAndSettleResult(session,run,first.attemptId(),Usage.unreported(),"local-scope-unknown");
                assertEquals(1,sql.stream().filter(value->value.startsWith("SELECT scope_kind,dimension,maximum,consumed,reserved")).count());
                assertEquals(4,sql.stream().filter(value->value.contains("SELECT a.started_at,a.confirmed_through")).count());
                return null;
            });
            session.rollback();
        }
        sql.clear();var after=budgets.snapshot(run);
        assertEquals(Resources.ZERO,after.consumed());assertEquals(Resources.ZERO,after.reserved());assertEquals(0,after.runActiveMillis());
        assertEquals(1,sql.stream().filter(value->value.startsWith("SELECT r.root_id AS")).count());
        assertEquals(1,sql.stream().filter(value->value.contains("FROM agent_runtime_budget_roots") && value.endsWith("FOR UPDATE")).count());
        assertGranted(budgets.reserve(first),AttemptState.RESERVED);
        assertTrue(budgets.markSent(run,first.attemptId()));
        budgets.saveResult(run,first.attemptId(),Usage.unreported(),"local-scope-unknown");budgets.settle(run,first.attemptId());
        assertEquals(new Resources(1,0,0,0,0,0,0),budgets.snapshot(run).consumed());
        assertEquals(new Resources(0,0,0,0,600,200,800),budgets.snapshot(run).reserved());
        assertEquals(OptionalLong.empty(),reported(first.attemptId(),Dimension.INPUT_TOKENS));
    }

    /** 保护、允许目标和阶段属于同一真实事务；写后读取根保护并继续计入成员的未知预留。 */
    @Test void outputBudgetDecisionReadsNewProtectionPreservesUnknownAndRollsBackAllFactsOnFailure() throws Exception {
        var budgets=budgets();var root=run("output-decision-root");var member=run("output-decision-member");
        budgets.register(registration(root,root,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        budgets.register(registration(member,root,Role.MEMBER,CAPACITY,ACTIVE_SECONDS));
        var unknown=demand(member,"output-decision-unknown",Category.BUSINESS_MODEL,600,100,false);
        assertGranted(budgets.reserve(unknown),AttemptState.RESERVED);assertTrue(budgets.markSent(member,unknown.attemptId()));
        budgets.saveResult(member,unknown.attemptId(),Usage.unreported(),"output-decision-unknown-result");budgets.settle(member,unknown.attemptId());
        var protection=new Closing(Resources.call(Category.BUSINESS_MODEL,300,50),1);
        assertTrue(budgets.admitClosingAndChoosePhase(root,new ClosingAdmission(protection,protection),Set.of("finalize-target"),Resources.call(Category.BUSINESS_MODEL,200,30)));
        var after=budgets.snapshot(root);assertEquals(Phase.CLOSING,after.phase());assertEquals(protection,after.closing());
        assertEquals(new Resources(0,0,0,0,600,100,700),after.reserved());
        assertEquals(OptionalLong.empty(),reported(unknown.attemptId(),Dimension.TOTAL_TOKENS));
        try(var session=sessions().openSession(false)) {
            session.getMapper(ExecutionBudgetMapper.class).bindTenant(TENANT.value());
            assertTrue(budgets.closingAllowed(session,root,Category.TOOL,"finalize-target"));session.rollback();
        }

        var failure=run("output-decision-rollback");budgets.register(registration(failure,failure,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        var before=budgets.snapshot(failure);var rows=ledgerRows(failure);
        // 运算在保护和允许目标已写入后失败，原事务必须同时撤销它们并清除局部复用状态。
        assertThrows(ArithmeticException.class,()->budgets.admitClosingAndChoosePhase(failure,new ClosingAdmission(protection,protection),Set.of("rollback-target"),
                new Resources(1,0,0,0,Long.MAX_VALUE,0,Long.MAX_VALUE)));
        assertEquals(before,budgets.snapshot(failure));assertEquals(rows,ledgerRows(failure));
        assertFalse(budgets.admitClosingAndChoosePhase(failure,new ClosingAdmission(protection,protection),Set.of("next-target"),Resources.call(Category.BUSINESS_MODEL,20,3)));
        assertEquals(Phase.BUSINESS,budgets.snapshot(failure).phase());
    }

    @Test void measuredClosingProjectionPreservesBusinessRoomButStillClosesAtTheActualLimit() {
        var budgets=budgets();
        var capacity=new Resources(6,128,6,4,200_000,200_000,200_000);
        var ordinary=Resources.call(Category.BUSINESS_MODEL,40_000,2048);
        var spec=new ClosingSpec(2,1,91,32768,List.of(),Set.of("browser-stop"));
        var measured=new ExecutionBudgetScope.ClosingSizing(Category.BUSINESS_MODEL,26_000,2048,Duration.ofSeconds(120)).size(spec);
        var overestimated=new ExecutionBudgetScope.ClosingSizing(Category.BUSINESS_MODEL,40_000,2048,Duration.ofSeconds(120)).size(spec);
        for(long used:List.of(60_000L,75_000L)) {
            var run=run("measured-closing-"+used);
            budgets.register(new Registration(run,run,Role.PRIMARY,new Policy.Enforced(
                    new Limits(Scenario.STANDARD,capacity,capacity,capacity,7200,360,VERSION))));
            complete(budgets,demand(run,"measured-closing-used-"+used,Category.BUSINESS_MODEL,used,0,false));
            var before=budgets.snapshot(run);
            assertFalse(before.runConsumed().plus(ordinary).plus(overestimated.resources()).fits(capacity));
            assertEquals(used==60_000L,before.runConsumed().plus(ordinary).plus(measured.resources()).fits(capacity));
            assertEquals(used==75_000L,budgets.admitClosingAndChoosePhase(run,
                    new ClosingAdmission(measured,measured),Set.of("browser-stop"),ordinary));
            assertEquals(used==60_000L?Phase.BUSINESS:Phase.CLOSING,budgets.snapshot(run).phase());
            assertEquals(measured,budgets.snapshot(run).closing());
        }
    }

    /** 根仍容纳普通调用，但较小Run必须按保留的较大收尾保护判断，不能按本次较小请求误放行。 */
    @Test void outputBudgetDecisionUsesRetainedProtectionForTheSmallerRunLimit() {
        var budgets=budgets();var run=run("output-retained-run-protection");
        var own=new Resources(8,4,8,4,500,300,800);
        budgets.register(new Registration(run,run,Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new Limits(Scenario.TEAM,CAPACITY,own,BASE_STAGE,ACTIVE_SECONDS,ACTIVE_SECONDS,VERSION))));
        complete(budgets,demand(run,"output-retained-run-used",Category.BUSINESS_MODEL,100,0,false));
        var retained=new Closing(Resources.call(Category.BUSINESS_MODEL,400,0),0);
        var smaller=new Closing(Resources.call(Category.BUSINESS_MODEL,100,0),0);
        var next=Resources.call(Category.BUSINESS_MODEL,100,0);budgets.updateClosing(run,retained);
        var before=budgets.snapshot(run);
        assertTrue(before.consumed().plus(before.reserved()).plus(next).plus(retained.resources()).fits(CAPACITY));
        assertTrue(before.runConsumed().plus(before.runReserved()).plus(next).plus(smaller.resources()).fits(own));
        assertFalse(before.runConsumed().plus(before.runReserved()).plus(next).plus(retained.resources()).fits(own));
        assertTrue(budgets.admitClosingAndChoosePhase(run,new ClosingAdmission(smaller,smaller),Set.of(),next));
        var after=budgets.snapshot(run);assertEquals(Phase.CLOSING,after.phase());assertEquals(retained,after.closing());
        assertDenied(budgets.reserve(demand(run,"output-retained-run-ordinary",Category.BUSINESS_MODEL,100,0,false)),Refusal.CLOSING_REQUIRED);
    }

    /** 较小时间请求不能抹掉原保护；Run达到真实受保护期限时须先收尾，根仍有余量也不能继续普通调用。 */
    @Test void outputBudgetDecisionUsesRetainedActiveTimeForTheSmallerRunLimit() {
        var budgets=budgets();var run=run("output-retained-run-time");
        budgets.register(new Registration(run,run,Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new Limits(Scenario.TEAM,CAPACITY,CAPACITY,BASE_STAGE,100,10,VERSION))));
        budgets.recordActivity(run,"retained-time-drive",Instant.EPOCH,Instant.EPOCH.plusSeconds(2));
        var retained=new Closing(Resources.ZERO,8);var smaller=new Closing(Resources.ZERO,1);budgets.updateClosing(run,retained);
        var before=budgets.snapshot(run);
        assertTrue(before.activeMillis()+retained.activeSeconds()*1_000<before.registration().policy().requireEnforced().rootActiveSeconds()*1_000);
        assertTrue(before.runActiveMillis()+smaller.activeSeconds()*1_000<before.registration().policy().requireEnforced().runActiveSeconds()*1_000);
        assertEquals(before.registration().policy().requireEnforced().runActiveSeconds()*1_000,before.runActiveMillis()+retained.activeSeconds()*1_000);
        assertTrue(budgets.admitClosingAndChoosePhase(run,new ClosingAdmission(smaller,smaller),Set.of(),Resources.call(Category.BUSINESS_MODEL,0,0)));
        var after=budgets.snapshot(run);assertEquals(Phase.CLOSING,after.phase());assertEquals(retained,after.closing());
        assertDenied(budgets.reserve(demand(run,"output-retained-time-ordinary",Category.BUSINESS_MODEL,0,0,false)),Refusal.CLOSING_REQUIRED);
    }

    @Test
    void batchOperationsAndClosingChecksKeepConstantSqlAndHeartbeatDoesNotLoadBalances() {
        var sql = new java.util.ArrayList<String>();
        var counted = countedDataSource(sql);
        var budgets = new JdbcExecutionBudgets(counted);
        var run = run("batch-sql");
        budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        assertEquals(6,sql.stream().filter(value->value.startsWith("INSERT INTO agent_runtime_budget_balances")).count());
        sql.clear();
        var member=run("batch-sql-member");budgets.register(registration(member,run,Role.MEMBER,CAPACITY,ACTIVE_SECONDS));
        assertEquals(4,sql.stream().filter(value->value.startsWith("INSERT INTO agent_runtime_budget_balances")).count());
        sql.clear();
        assertEquals(Optional.of(registration(run,run,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS)),budgets.find(run));
        assertEquals(2,sql.stream().filter(value->value.startsWith("SELECT") && !value.contains("set_config")).count());
        assertEquals(1,sql.stream().filter(value->value.startsWith("SELECT scope_kind,dimension,maximum,consumed,reserved")).count());
        sql.clear();
        var demand = demand(run,"batch-attempt",Category.BUSINESS_MODEL,100,20,false);
        assertGranted(budgets.reserve(demand),AttemptState.RESERVED);
        assertEquals(1,sql.stream().filter(value->value.startsWith("UPDATE agent_runtime_budget_balances")).count());
        assertEquals(1,sql.stream().filter(value->value.startsWith("INSERT INTO agent_runtime_budget_attempt_amounts")).count());
        assertEquals(2,sql.stream().filter(value->value.contains("SELECT a.started_at,a.confirmed_through")).count());
        sql.clear();
        assertTrue(budgets.markSent(run,demand.attemptId()));
        assertEquals(1,sql.stream().filter(value->value.startsWith("UPDATE agent_runtime_budget_balances")).count());
        sql.clear();
        budgets.saveResult(run,demand.attemptId(),Usage.reported(OptionalLong.of(80),OptionalLong.of(10)),"batch-result");
        assertEquals(1,sql.stream().filter(value->value.startsWith("SELECT dimension,reported FROM agent_runtime_budget_attempt_amounts")).count());
        assertEquals(1,sql.stream().filter(value->value.startsWith("UPDATE agent_runtime_budget_attempt_amounts b SET reported")).count());
        sql.clear();
        budgets.saveResult(run,demand.attemptId(),Usage.reported(OptionalLong.of(80),OptionalLong.of(10)),"batch-result");
        assertEquals(1,sql.stream().filter(value->value.startsWith("SELECT dimension,reported FROM agent_runtime_budget_attempt_amounts")).count());
        assertEquals(0,sql.stream().filter(value->value.startsWith("UPDATE agent_runtime_budget_attempt_amounts b SET reported")).count());
        budgets.settle(run,demand.attemptId());
        assertEquals(new Resources(1,0,0,0,80,10,90),budgets.snapshot(run).consumed());
        sql.clear();
        budgets.updateClosing(run,new Closing(Resources.call(Category.BUSINESS_MODEL,100,20),1));
        assertEquals(2,sql.stream().filter(value->value.startsWith("UPDATE agent_runtime_budget_balances")).count());
        assertEquals(1,sql.stream().filter(value->value.startsWith("UPDATE agent_runtime_budget_roots SET closing_active_seconds")).count());
        sql.clear();
        budgets.recordActivity(run,"interval",Instant.EPOCH,Instant.EPOCH.plusSeconds(1));
        assertTrue(sql.stream().noneMatch(value->value.contains("agent_runtime_budget_balances")));
        assertThrows(IllegalStateException.class,()->budgets.recordActivity(run,"interval",Instant.EPOCH.plusSeconds(1),Instant.EPOCH.plusSeconds(2)));
        sql.clear();
        assertEquals("budget_closing_cannot_fit",assertThrows(IllegalStateException.class,
                ()->budgets.updateClosing(run,new Closing(Resources.call(Category.BUSINESS_MODEL,950,10),5))).getMessage());
        // 两侧资源诊断均拒绝时也只读取一次 ROOT 和一次 RUN 活动区间。
        assertEquals(2,sql.stream().filter(value->value.contains("SELECT a.started_at,a.confirmed_through")).count());
    }

    /** 初始化SQL实际少写任一维时，先前已写的根、Run和各组余额必须一起撤销。 */
    @Test void partialBalanceBatchCannotCommitAnIncompleteRegistration() throws Exception {
        var budgets=budgets();var run=run("batch-registration-failure");var frozen=registration(run,run,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS);
        try(var connection=adminConnection();var statement=connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION public.skip_registration_dimension() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.scope_id='batch-registration-failure' AND NEW.scope_kind='STAGE' AND NEW.dimension='TOTAL_TOKENS' THEN
                            RETURN NULL;
                        END IF;
                        RETURN NEW;
                    END $$
                    """);
            statement.execute("CREATE TRIGGER skip_registration_dimension BEFORE INSERT ON agent_runtime_budget_balances FOR EACH ROW EXECUTE FUNCTION public.skip_registration_dimension()");
            try {
                assertEquals("budget_balance_missing",assertThrows(IllegalStateException.class,()->budgets.register(frozen)).getMessage());
                assertTrue(budgets.find(run).isEmpty());
                try(var rows=statement.executeQuery("""
                        SELECT (SELECT count(*) FROM agent_runtime_budget_roots WHERE tenant_id='1' AND root_id='batch-registration-failure')
                             + (SELECT count(*) FROM agent_runtime_budget_runs WHERE tenant_id='1' AND run_id='batch-registration-failure')
                             + (SELECT count(*) FROM agent_runtime_budget_balances WHERE tenant_id='1' AND scope_id='batch-registration-failure')
                        """)) { assertTrue(rows.next());assertEquals(0,rows.getLong(1)); }
            } finally {
                statement.execute("DROP TRIGGER skip_registration_dimension ON agent_runtime_budget_balances");
                statement.execute("DROP FUNCTION public.skip_registration_dimension()");
            }
        }
        assertEquals(frozen,budgets.register(frozen));
        complete(budgets,demand(run,"batch-registration-used",Category.BUSINESS_MODEL,100,20,false));
        var pending=demand(run,"batch-registration-pending",Category.BUSINESS_MODEL,40,10,false);assertGranted(budgets.reserve(pending),AttemptState.RESERVED);
        var rows=ledgerRows(run);assertEquals(frozen,budgets.register(frozen));assertEquals(rows,ledgerRows(run));
    }

    /** 两组保护少一维必须拒绝，连同先执行的前任Run保护清除一起回滚，UNKNOWN事实保持原值。 */
    @Test void missingClosingDimensionRollsBackBothProtectionGroupsAndThePreviousOwnersClear() throws Exception {
        for(var scope:List.of("CLOSING","CLOSING_RUN")) {
            var budgets=budgets();var root=run("batch-closing-missing-"+scope);var successor=run(root.runId()+"-successor");
            budgets.register(registration(root,root,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
            budgets.register(registration(successor,root,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
            var unknown=demand(root,"batch-closing-unknown-"+scope,Category.BUSINESS_MODEL,100,20,false);
            assertGranted(budgets.reserve(unknown),AttemptState.RESERVED);assertTrue(budgets.markSent(root,unknown.attemptId()));
            budgets.saveResult(root,unknown.attemptId(),Usage.unreported(),"batch-closing-result-"+scope);budgets.settle(root,unknown.attemptId());
            budgets.updateClosing(root,new Closing(Resources.call(Category.BUSINESS_MODEL,100,10),1));
            try(var connection=adminConnection();var statement=connection.prepareStatement(
                    "DELETE FROM agent_runtime_budget_balances WHERE tenant_id=? AND scope_kind=? AND scope_id=? AND dimension='TOTAL_TOKENS'")) {
                statement.setString(1,TENANT.value());statement.setString(2,scope);statement.setString(3,(scope.equals("CLOSING")?root:successor).runId());
                assertEquals(1,statement.executeUpdate());
            }
            var before=ledgerRows(root);
            assertEquals("budget_balance_missing",assertThrows(IllegalStateException.class,
                    ()->budgets.updateClosing(successor,new Closing(Resources.call(Category.BUSINESS_MODEL,200,20),2))).getMessage());
            assertEquals(before,ledgerRows(root));assertEquals(OptionalLong.empty(),reported(unknown.attemptId(),Dimension.INPUT_TOKENS));
        }
    }

    @Test
    void firstMeasuredRequestExpandsStageWithoutChangingFrozenRegistrationOrReplayBalance() {
        var root = run("first");
        var registration = registration(root, root, Role.PRIMARY, CAPACITY, ACTIVE_SECONDS);
        var budgets = budgets();
        assertTrue(budgets.find(root).isEmpty());
        assertEquals(registration, budgets.register(registration));
        var demand = demand(root, "first-attempt", Category.BUSINESS_MODEL, 400, 60, false);

        assertGranted(budgets.reserve(demand), AttemptState.RESERVED);
        assertEquals(demand.resources(), budgets.snapshot(root).reserved());
        assertEquals(registration, budgets().register(registration));
        assertEquals(Optional.of(registration), budgets().find(root));
        assertGranted(budgets().reserve(demand), AttemptState.RESERVED);
        assertEquals(demand.resources(), budgets.snapshot(root).reserved());

        var conflict = new Demand(root, demand.attemptId(), "different-request", demand.category(),
                demand.resources(), demand.closing());
        assertDenied(budgets.reserve(conflict), Refusal.REQUEST_CONFLICT);
        assertEquals(demand.resources(), budgets.snapshot(root).reserved());
    }

    @Test
    void closingAfterReservationPreventsFirstBusinessSendButKeepsSentEvidenceSettleable() {
        var root=run("closing-send-root");
        var budgets=budgets();
        budgets.register(registration(root,root,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        var sent=demand(root,"before-closing-sent",Category.BUSINESS_MODEL,100,20,false);
        var unsent=demand(root,"before-closing-unsent",Category.BUSINESS_MODEL,100,20,false);
        assertGranted(budgets.reserve(sent),AttemptState.RESERVED);
        assertTrue(budgets.markSent(root,sent.attemptId()));
        assertGranted(budgets.reserve(unsent),AttemptState.RESERVED);
        budgets.enterClosing(root);

        assertFalse(budgets.markSent(root,unsent.attemptId()));
        budgets.saveResult(root,sent.attemptId(),Usage.reported(OptionalLong.of(80),OptionalLong.of(10)),"sent-result");
        budgets.recover(root);
        assertEquals(new Resources(1,0,0,0,80,10,90),budgets.snapshot(root).consumed());
        assertEquals(unsent.resources(),budgets.snapshot(root).reserved());
    }

    @Test
    void concurrentMembersCannotBothReserveBeyondSharedRoot() throws Exception {
        var root = run("concurrent-root");
        var first = run("concurrent-first");
        var second = run("concurrent-second");
        var rootCapacity = new Resources(2, 2, 2, 2, 1_000, 100, 1_100);
        var budgets = budgets();
        for (var registration : List.of(
                registration(root, root, Role.PRIMARY, rootCapacity, ACTIVE_SECONDS),
                registration(first, root, Role.MEMBER, rootCapacity, ACTIVE_SECONDS),
                registration(second, root, Role.MEMBER, rootCapacity, ACTIVE_SECONDS))) {
            budgets.register(registration);
        }
        var firstDemand = demand(first, "concurrent-a", Category.BUSINESS_MODEL, 600, 50, false);
        var secondDemand = demand(second, "concurrent-b", Category.BUSINESS_MODEL, 600, 50, false);
        var start = new CountDownLatch(1);
        List<Reservation> results;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return budgets().reserve(firstDemand); });
            var b = workers.submit(() -> { start.await(); return budgets().reserve(secondDemand); });
            start.countDown();
            results = List.of(a.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    b.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        assertEquals(1, results.stream().filter(Reservation.Granted.class::isInstance).count());
        assertEquals(1, results.stream().filter(result -> result.equals(new Reservation.Denied(
                Refusal.ROOT_LIMIT, Optional.of(Dimension.INPUT_TOKENS)))).count());
        var rootSnapshot = budgets().snapshot(root);
        assertEquals(firstDemand.resources(), rootSnapshot.reserved());
        assertTrue(rootSnapshot.consumed().plus(rootSnapshot.reserved()).fits(rootCapacity));
        assertEquals(rootSnapshot.reserved(), budgets.snapshot(first).runReserved()
                .plus(budgets.snapshot(second).runReserved()));
    }

    @Test
    void savedPartialUsageSurvivesRebuildAndLaterEvidenceSettlesOnlyMissingDimensions() {
        var root = run("usage-root");
        var budgets = budgets();
        budgets.register(registration(root, root, Role.PRIMARY, CAPACITY, ACTIVE_SECONDS));
        var demand = demand(root, "usage-attempt", Category.BUSINESS_MODEL, 400, 80, false);
        assertGranted(budgets.reserve(demand), AttemptState.RESERVED);
        assertTrue(budgets.markSent(root, demand.attemptId()));
        assertFalse(budgets().markSent(root, demand.attemptId()));
        budgets.saveResult(root, demand.attemptId(),
                new Usage(OptionalLong.of(300), OptionalLong.empty(), OptionalLong.empty()), "partial-result");

        var rebuilt = budgets();
        rebuilt.recover(root);
        var partial = rebuilt.snapshot(root);
        assertEquals(new Resources(1, 0, 0, 0, 300, 0, 0), partial.consumed());
        assertEquals(new Resources(0, 0, 0, 0, 0, 80, 480), partial.reserved());
        assertGranted(rebuilt.reserve(demand), AttemptState.RESULT_SAVED);
        assertEquals(partial, rebuilt.snapshot(root));

        var completeUsage = new Usage(OptionalLong.of(300), OptionalLong.of(50), OptionalLong.of(350));
        rebuilt.saveResult(root, demand.attemptId(), completeUsage, "complete-result");
        var afterRestart = budgets();
        afterRestart.recover(root);
        var complete = afterRestart.snapshot(root);
        assertEquals(new Resources(1, 0, 0, 0, 300, 50, 350), complete.consumed());
        assertEquals(Resources.ZERO, complete.reserved());
        assertGranted(afterRestart.reserve(demand), AttemptState.SETTLED);
        afterRestart.saveResult(root, demand.attemptId(), completeUsage, "complete-result");
        budgets().recover(root);
        assertEquals(complete, budgets().snapshot(root));
        assertThrows(IllegalStateException.class, () -> afterRestart.saveResult(root, demand.attemptId(),
                new Usage(OptionalLong.of(301), OptionalLong.of(50), OptionalLong.of(351)), "conflicting-result"));
        assertEquals(complete, budgets().snapshot(root));

        var auxiliary = demand(root, "auxiliary-attempt", Category.AUXILIARY_MODEL, 100, 10, false);
        assertGranted(afterRestart.reserve(auxiliary), AttemptState.RESERVED);
        assertTrue(afterRestart.markSent(root, auxiliary.attemptId()));
        afterRestart.saveResult(root, auxiliary.attemptId(),
                Usage.reported(OptionalLong.of(90), OptionalLong.of(10)), "summary-result");
        budgets().recover(root);
        assertEquals(new Resources(1, 1, 0, 0, 390, 60, 450), budgets().snapshot(root).consumed());
        assertEquals(Resources.ZERO, budgets().snapshot(root).reserved());
    }

    @Test
    void dynamicClosingProtectionCanShrinkButMembersCannotUseOrOwnIt() throws Exception {
        var root = run("closing-root");
        var member = run("closing-member");
        var budgets = budgets();
        budgets.register(registration(root, root, Role.PRIMARY, CAPACITY, ACTIVE_SECONDS));
        budgets.register(registration(member, root, Role.MEMBER, CAPACITY, ACTIVE_SECONDS));
        var larger = new Closing(Resources.call(Category.BUSINESS_MODEL, 300, 50), 30);
        budgets.updateClosing(root, larger);
        assertThrows(IllegalStateException.class, () -> budgets.updateClosing(member, Closing.ZERO));
        var business = demand(member, "closing-member-business", Category.BUSINESS_MODEL, 800, 60, false);
        assertDenied(budgets.reserve(business), Refusal.CLOSING_REQUIRED);
        assertEquals(Resources.ZERO, budgets.snapshot(root).reserved());

        var smaller = new Closing(Resources.call(Category.BUSINESS_MODEL, 100, 20), 10);
        budgets.updateClosing(root, smaller);
        assertEquals(smaller, budgets().snapshot(root).closing());
        assertGranted(budgets.reserve(business), AttemptState.RESERVED);
        budgets.allowClosingTarget(root, "frozen-finalize-target");
        budgets.enterClosing(root);
        assertDenied(budgets.reserve(demand(member, "member-spoof-closing", Category.BUSINESS_MODEL, 10, 1, true)),
                Refusal.CLOSING_REQUIRED);
        assertDenied(budgets.reserve(demand(root, "ordinary-after-closing", Category.BUSINESS_MODEL, 10, 1, false)),
                Refusal.CLOSING_REQUIRED);
        assertGranted(budgets.reserve(demand(root, "actual-closing", Category.BUSINESS_MODEL, 50, 10, true)),
                AttemptState.RESERVED);
        assertEquals(Phase.CLOSING, budgets().snapshot(root).phase());

        try (var session = sessions().openSession(false)) {
            session.getMapper(ExecutionBudgetMapper.class).bindTenant(TENANT.value());
            assertTrue(budgets.closingAllowed(session, root, Category.BUSINESS_MODEL, ""));
            assertTrue(budgets.closingAllowed(session, root, Category.TOOL, "frozen-finalize-target"));
            assertFalse(budgets.closingAllowed(session, root, Category.TOOL, "unapproved-tool"));
            assertFalse(budgets.closingAllowed(session, member, Category.BUSINESS_MODEL, ""));
            session.rollback();
        }
    }

    /** Binding 读到 BUSINESS 后实际阶段推进；当前模型、停止及冻结工具已占账，只准入真实未来需求。 */
    @Test
    void finalizationAdmissionUsesLockedClosingPhaseAfterBusinessSnapshot() {
        var run=run("closing-finalize-phase-change");var budgets=budgets();
        budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,600));
        var business=demand(run,"finalize-business",Category.BUSINESS_MODEL,500,50,false);
        complete(budgets,business);
        var prospective=new ClosingSpec(2,1,120,100,List.of(),Set.of());
        var remaining=new ClosingSpec(1,0,120,100,List.of(),Set.of());
        var sizing=new ExecutionBudgetScope.ClosingSizing(Category.BUSINESS_MODEL,200,20,Duration.ofSeconds(120));
        budgets.updateClosing(run,sizing.size(prospective));
        assertEquals(Phase.BUSINESS,budgets.snapshot(run).phase());
        var binding=new ExecutionBudgetScope.Binding(budgets,run,()->prospective,Optional::empty,()->{
            // 精确发生在 Binding 的快照与账本原子准入之间，不能依赖快照阶段作决定。
            budgets.enterClosing(run);
            complete(budgets,demand(run,"finalize-current-model",Category.BUSINESS_MODEL,200,20,true));
            complete(budgets,demand(run,"finalize-prior-stop",Category.TOOL,0,0,true));
            complete(budgets,demand(run,"finalize-current-tool",Category.TOOL,0,0,true));
            budgets.recordActivity(run,"finalize-activity",Instant.EPOCH,Instant.EPOCH.plusSeconds(250));
            return Optional.of(sizing);
        },()->Duration.ZERO);

        binding.admitClosing(prospective,remaining);

        var snapshot=budgets().snapshot(run);
        assertEquals(Phase.CLOSING,snapshot.phase());
        assertEquals(sizing.size(remaining),snapshot.closing());
        assertEquals(new Resources(2,0,2,0,700,70,770),snapshot.consumed());
        assertEquals(Resources.ZERO,snapshot.reserved());
        assertFalse(snapshot.consumed().plus(sizing.size(prospective).resources()).fits(CAPACITY));
        assertEquals(240,sizing.size(prospective).activeSeconds(),"多轮模型共享原冻结120秒窗口");
        assertTrue(snapshot.activeMillis()+snapshot.closing().activeSeconds()*1000<=600_000);
        // 同一受理点重新核验不增加账本占用，也不重新保护当前模型或工具。
        var admission=new ClosingAdmission(sizing.size(prospective),sizing.size(remaining));
        budgets.admitClosing(run,admission);
        assertEquals(snapshot,budgets().snapshot(run));
    }

    /** 收尾剩余需求仍须容纳全部 UNKNOWN，少一个回执 Token 也不能通过或改写旧保护。 */
    @Test
    void closingAdmissionKeepsUnknownReservationsAndRejectsReceiptShortfallWithoutMutation() throws Exception {
        var run=run("closing-finalize-unknown");var budgets=budgets();
        budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,600));
        complete(budgets,demand(run,"unknown-business",Category.BUSINESS_MODEL,500,50,false));
        var prospective=new Closing(new Resources(2,0,1,0,500,40,540),360);
        budgets.updateClosing(run,prospective);budgets.enterClosing(run);
        var unknown=demand(run,"unknown-current-model",Category.BUSINESS_MODEL,200,20,true);
        assertGranted(budgets.reserve(unknown),AttemptState.RESERVED);
        assertTrue(budgets.markSent(run,unknown.attemptId()));
        budgets.saveResult(run,unknown.attemptId(),Usage.unreported(),"unknown-current-result");
        budgets.recover(run);
        var before=budgets().snapshot(run);
        assertEquals(new Resources(0,0,0,0,200,20,220),before.reserved());
        var exceedsReceiptByOne=new Closing(new Resources(1,0,0,0,301,20,321),240);

        assertAdmissionRejectedWithoutMutation(budgets,run,new ClosingAdmission(prospective,exceedsReceiptByOne));
        assertEquals(before,budgets().snapshot(run));

        var remaining=new Closing(new Resources(1,0,0,0,300,20,320),240);
        budgets.admitClosing(run,new ClosingAdmission(prospective,remaining));
        var admitted=budgets().snapshot(run);
        assertEquals(remaining,admitted.closing());
        assertEquals(before.consumed(),admitted.consumed());
        assertEquals(before.reserved(),admitted.reserved());
        assertEquals(OptionalLong.empty(),reported(unknown.attemptId(),Dimension.INPUT_TOKENS));
        assertEquals(OptionalLong.empty(),reported(unknown.attemptId(),Dimension.OUTPUT_TOKENS));
        budgets().recover(run);
        assertEquals(admitted,budgets().snapshot(run));
        assertFalse(budgets.markSent(run,unknown.attemptId()));
    }

    /** 业务阶段仍保留更大的原保护；成员也不能借新准入入口改写主 Run 保护。 */
    @Test
    void businessAdmissionPreservesLargerProtectionAndRejectsMemberOwnership() {
        var run=run("closing-admission-business");var member=run("closing-admission-member");var budgets=budgets();
        budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,600));
        budgets.register(registration(member,run,Role.MEMBER,CAPACITY,600));
        var original=new Closing(Resources.call(Category.BUSINESS_MODEL,300,50),360);
        budgets.updateClosing(run,original);
        var smaller=new Closing(Resources.call(Category.BUSINESS_MODEL,100,20),120);
        var request=new ClosingAdmission(smaller,Closing.ZERO);
        budgets.admitClosing(run,request);
        assertEquals(original,budgets().snapshot(run).closing());
        var before=budgets().snapshot(run);
        assertEquals("budget_closing_owner_required",assertThrows(IllegalStateException.class,
                ()->budgets.admitClosing(member,request)).getMessage());
        assertEquals(before,budgets().snapshot(run));
    }

    /** 已完成的模型调用不会退款；保护窗口用完后不能靠准入再取得一次最终模型调用。 */
    @Test
    void closingAdmissionRejectsExhaustedFinalModelCountWithoutChangingProtection() throws Exception {
        var run=run("closing-final-model-exhausted");var budgets=budgets();
        var capacity=new Resources(2,4,8,4,1000,300,1300);
        budgets.register(registration(run,run,Role.PRIMARY,capacity,600));
        var prospective=new Closing(new Resources(2,0,1,0,400,40,440),360);
        budgets.updateClosing(run,prospective);budgets.enterClosing(run);
        complete(budgets,demand(run,"final-model-first",Category.BUSINESS_MODEL,100,10,true));
        complete(budgets,demand(run,"final-model-second",Category.BUSINESS_MODEL,100,10,true));
        var before=budgets().snapshot(run);
        var remaining=new Closing(Resources.call(Category.BUSINESS_MODEL,100,10),120);

        assertAdmissionRejectedWithoutMutation(budgets,run,new ClosingAdmission(prospective,remaining));
        assertEquals(before,budgets().snapshot(run));
    }

    /** 即使最终模型资源足够，当前核验及最终模型所需的活动时间仍须完整容纳。 */
    @Test
    void closingAdmissionRejectsInsufficientRemainingActivityWithoutChangingProtection() throws Exception {
        var run=run("closing-final-activity-exhausted");var budgets=budgets();
        budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,600));
        var prospective=new Closing(Resources.call(Category.BUSINESS_MODEL,200,20),360);
        budgets.updateClosing(run,prospective);budgets.enterClosing(run);
        budgets.recordActivity(run,"final-activity",Instant.EPOCH,Instant.EPOCH.plusSeconds(361));
        var remaining=new Closing(Resources.call(Category.BUSINESS_MODEL,100,10),240);
        assertAdmissionRejectedWithoutMutation(budgets,run,new ClosingAdmission(prospective,remaining));
    }

    /** 真实派发后提交精确用量并恢复结算；仅供这些准入样本建立已确认的历史消耗。 */
    private static void complete(JdbcExecutionBudgets budgets,Demand demand) {
        assertGranted(budgets.reserve(demand),AttemptState.RESERVED);
        assertTrue(budgets.markSent(demand.run(),demand.attemptId()));
        budgets.saveResult(demand.run(),demand.attemptId(),Usage.reported(OptionalLong.of(demand.resources().inputTokens()),
                OptionalLong.of(demand.resources().outputTokens())),"result:"+demand.attemptId());
        budgets.recover(demand.run());
    }

    @Test
    void closingRootResourceRejectionKeepsProtectionAndExistingLedgerUnchanged() throws Exception {
        var root=run("closing-root-resource");var member=run("closing-root-resource-member");var budgets=budgets();
        budgets.register(registration(root,root,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        budgets.register(registration(member,root,Role.MEMBER,CAPACITY,ACTIVE_SECONDS));
        budgets.updateClosing(root,new Closing(Resources.call(Category.BUSINESS_MODEL,100,20),2));
        var sent=demand(member,"closing-root-resource-sent",Category.BUSINESS_MODEL,600,100,false);
        assertGranted(budgets.reserve(sent),AttemptState.RESERVED);
        assertTrue(budgets.markSent(member,sent.attemptId()));
        budgets.saveResult(member,sent.attemptId(),Usage.reported(OptionalLong.of(500),OptionalLong.of(90)),"closing-root-resource-result");
        budgets.recover(member);
        var pending=demand(member,"closing-root-resource-pending",Category.BUSINESS_MODEL,300,50,false);
        assertGranted(budgets.reserve(pending),AttemptState.RESERVED);
        var requested=new Closing(Resources.call(Category.BUSINESS_MODEL,250,20),2);
        var before=budgets.snapshot(root);
        assertFalse(before.consumed().plus(before.reserved()).plus(requested.resources()).fits(CAPACITY));
        assertTrue(before.runConsumed().plus(before.runReserved()).plus(requested.resources()).fits(CAPACITY));

        assertClosingRejectedWithoutMutation(budgets,root,requested);
    }

    @Test
    void closingRunResourceRejectionKeepsProtectionAndExistingLedgerUnchanged() throws Exception {
        var run=run("closing-run-resource");var budgets=budgets();
        var runCapacity=new Resources(4,2,4,2,500,150,650);
        budgets.register(new Registration(run,run,Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new Limits(Scenario.TEAM,CAPACITY,runCapacity,BASE_STAGE,ACTIVE_SECONDS,ACTIVE_SECONDS,VERSION))));
        budgets.updateClosing(run,new Closing(Resources.call(Category.BUSINESS_MODEL,100,20),2));
        var sent=demand(run,"closing-run-resource-sent",Category.BUSINESS_MODEL,200,30,false);
        assertGranted(budgets.reserve(sent),AttemptState.RESERVED);
        assertTrue(budgets.markSent(run,sent.attemptId()));
        budgets.saveResult(run,sent.attemptId(),Usage.reported(OptionalLong.of(180),OptionalLong.of(20)),"closing-run-resource-result");
        budgets.recover(run);
        var pending=demand(run,"closing-run-resource-pending",Category.BUSINESS_MODEL,200,30,false);
        assertGranted(budgets.reserve(pending),AttemptState.RESERVED);
        var requested=new Closing(Resources.call(Category.BUSINESS_MODEL,150,20),2);
        var before=budgets.snapshot(run);
        assertTrue(before.consumed().plus(before.reserved()).plus(requested.resources()).fits(CAPACITY));
        assertFalse(before.runConsumed().plus(before.runReserved()).plus(requested.resources()).fits(runCapacity));

        assertClosingRejectedWithoutMutation(budgets,run,requested);
    }

    @Test
    void closingRootActivityRejectionKeepsProtectionAndExistingLedgerUnchanged() throws Exception {
        var root=run("closing-root-activity");var member=run("closing-root-activity-member");var budgets=budgets();
        budgets.register(registration(root,root,Role.PRIMARY,CAPACITY,20));
        budgets.register(registration(member,root,Role.MEMBER,CAPACITY,20));
        budgets.updateClosing(root,new Closing(Resources.call(Category.BUSINESS_MODEL,100,20),2));
        budgets.recordActivity(root,"closing-root-primary",Instant.EPOCH,Instant.EPOCH.plusSeconds(5));
        budgets.recordActivity(member,"closing-root-member",Instant.EPOCH.plusSeconds(5),Instant.EPOCH.plusSeconds(15));
        var before=budgets.snapshot(root);
        assertEquals(15_000,before.activeMillis());assertEquals(5_000,before.runActiveMillis());
        var requested=new Closing(Resources.ZERO,10);
        assertTrue(before.runActiveMillis()+requested.activeSeconds()*1_000<=20_000);
        assertTrue(before.activeMillis()+requested.activeSeconds()*1_000>20_000);

        assertClosingRejectedWithoutMutation(budgets,root,requested);
    }

    @Test
    void closingRunActivityRejectionKeepsProtectionAndExistingLedgerUnchanged() throws Exception {
        var run=run("closing-run-activity");var budgets=budgets();
        budgets.register(new Registration(run,run,Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new Limits(Scenario.TEAM,CAPACITY,CAPACITY,BASE_STAGE,40,20,VERSION))));
        budgets.updateClosing(run,new Closing(Resources.call(Category.BUSINESS_MODEL,100,20),2));
        budgets.recordActivity(run,"closing-run-primary",Instant.EPOCH,Instant.EPOCH.plusSeconds(15));
        var before=budgets.snapshot(run);
        assertEquals(15_000,before.activeMillis());assertEquals(15_000,before.runActiveMillis());
        var requested=new Closing(Resources.ZERO,10);
        assertTrue(before.activeMillis()+requested.activeSeconds()*1_000<=40_000);
        assertTrue(before.runActiveMillis()+requested.activeSeconds()*1_000>20_000);

        assertClosingRejectedWithoutMutation(budgets,run,requested);
    }

    @Test
    void overlappingActivityUsesRootUnionAndRebuildNeverResetsOrChargesPauseGap() {
        var root = run("activity-root");
        var member = run("activity-member");
        var budgets = budgets();
        var activeLimit = 20;
        budgets.register(registration(root, root, Role.PRIMARY, CAPACITY, activeLimit));
        budgets.register(registration(member, root, Role.MEMBER, CAPACITY, activeLimit));
        var started = Instant.parse("2026-10-01T00:00:00Z");
        budgets.recordActivity(root, "primary-first", started, started.plusSeconds(10));
        budgets.recordActivity(member, "member-first", started.plusSeconds(5), started.plusSeconds(15));
        assertEquals(15_000, budgets().snapshot(root).activeMillis());
        assertEquals(10_000, budgets().snapshot(root).runActiveMillis());
        assertEquals(10_000, budgets().snapshot(member).runActiveMillis());
        budgets().recordActivity(root, "primary-first", started, started.plusSeconds(8));
        assertEquals(15_000, budgets().snapshot(root).activeMillis());
        budgets().recordActivity(root, "primary-first", started, started.plusSeconds(12));
        assertEquals(15_000, budgets().snapshot(root).activeMillis());
        assertEquals(12_000, budgets().snapshot(root).runActiveMillis());

        budgets().recordActivity(root, "primary-after-pause", started.plusSeconds(30), started.plusSeconds(35));
        assertEquals(20_000, budgets().snapshot(root).activeMillis());
        assertEquals(17_000, budgets().snapshot(root).runActiveMillis());
        assertEquals(10_000, budgets().snapshot(member).runActiveMillis());
        assertDenied(budgets().reserve(demand(root, "after-active-limit", Category.TOOL, 0, 0, false)),
                Refusal.ACTIVE_TIME_LIMIT);
    }

    /** 确认返回值与写入属于同一根锁事务，重放较早终点也返回该区间实际持久终点。 */
    @Test
    void activityConfirmationReturnsRootAndRunUnionWithActualPersistedEnd() {
        var root=run("observed-activity-root");var member=run("observed-activity-member");var budgets=budgets();
        budgets.register(registration(root,root,Role.PRIMARY,CAPACITY,20));
        budgets.register(registration(member,root,Role.MEMBER,CAPACITY,20));
        var first=budgets.recordActivity(root,"observed-primary",Instant.EPOCH,Instant.EPOCH.plusSeconds(10));
        assertEquals(new ActivityObservation(root,"observed-primary",10_000,10_000,Instant.EPOCH.plusSeconds(10)),first);
        var joined=budgets.recordActivity(member,"observed-member",Instant.EPOCH.plusSeconds(5),Instant.EPOCH.plusSeconds(15));
        assertEquals(new ActivityObservation(member,"observed-member",15_000,10_000,Instant.EPOCH.plusSeconds(15)),joined);
        var earlier=budgets().recordActivity(root,"observed-primary",Instant.EPOCH,Instant.EPOCH.plusSeconds(8));
        assertEquals(new ActivityObservation(root,"observed-primary",15_000,10_000,Instant.EPOCH.plusSeconds(10)),earlier);
        var advanced=budgets.recordActivity(root,"observed-primary",Instant.EPOCH,Instant.EPOCH.plusSeconds(12));
        assertEquals(new ActivityObservation(root,"observed-primary",15_000,12_000,Instant.EPOCH.plusSeconds(12)),advanced);
    }

    /** 旧预留不能绕过发送时根或成员时间耗尽；发送拒绝不计次、不退款，也不清除原 UNKNOWN。 */
    @Test
    void markSentRechecksRootAndRunTimeWithoutMutatingReservationsOrUnknownAmounts() throws Exception {
        for(boolean rootExpired:List.of(true,false)) {
            String suffix=rootExpired?"root":"run";
            var root=run("send-time-root-"+suffix);var member=run("send-time-member-"+suffix);var budgets=budgets();
            budgets.register(registration(root,root,Role.PRIMARY,CAPACITY,20));
            budgets.register(new Registration(member,root,Role.MEMBER, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new Limits(Scenario.TEAM,CAPACITY,CAPACITY,
                    BASE_STAGE,20,rootExpired?20:10,VERSION))));
            var unknown=demand(member,"send-time-unknown-"+suffix,Category.BUSINESS_MODEL,100,10,false);
            assertGranted(budgets.reserve(unknown),AttemptState.RESERVED);
            assertTrue(budgets.markSent(member,unknown.attemptId()));
            budgets.saveResult(member,unknown.attemptId(),Usage.unreported(),"send-time-unknown-result-"+suffix);
            budgets.recover(member);
            var pending=demand(member,"send-time-pending-"+suffix,Category.BUSINESS_MODEL,200,20,false);
            assertGranted(budgets.reserve(pending),AttemptState.RESERVED);
            budgets.recordActivity(rootExpired?root:member,"send-time-activity-"+suffix,Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(rootExpired?20:10));
            var before=budgets.snapshot(member);var rows=ledgerRows(root);

            assertFalse(budgets.markSent(member,pending.attemptId()));

            assertEquals(before,budgets().snapshot(member));
            assertEquals(rows,ledgerRows(root));
            assertEquals(new Resources(1,0,0,0,300,30,330),before.runReserved());
            assertEquals(OptionalLong.empty(),reported(pending.attemptId(),Dimension.BUSINESS_MODELS));
            assertEquals(OptionalLong.empty(),reported(unknown.attemptId(),Dimension.INPUT_TOKENS));
            assertGranted(budgets.reserve(pending),AttemptState.RESERVED);
        }
    }

    @Test
    void identicalRootSelectorsRemainTenantScopedUnderRealDatabaseRls() throws Exception {
        var root = run("tenant-root");
        var foreignRoot = new RunRef(OTHER_TENANT, root.runId());
        var budgets = budgets();
        budgets.register(registration(root, root, Role.PRIMARY, CAPACITY, ACTIVE_SECONDS));
        assertTrue(budgets.find(foreignRoot).isEmpty());
        budgets.register(registration(foreignRoot, foreignRoot, Role.PRIMARY, CAPACITY, ACTIVE_SECONDS));
        var first = demand(root, "same-attempt", Category.TOOL, 0, 0, false);
        var second = demand(foreignRoot, "same-attempt", Category.AUXILIARY_MODEL, 100, 20, false);
        assertGranted(budgets.reserve(first), AttemptState.RESERVED);
        assertGranted(budgets.reserve(second), AttemptState.RESERVED);
        assertEquals(first.resources(), budgets.snapshot(root).reserved());
        assertEquals(second.resources(), budgets.snapshot(foreignRoot).reserved());

        try (var connection = source.getConnection(); var scope = connection.createStatement()) {
            connection.setAutoCommit(false);
            scope.execute("SELECT set_config('app.tenant_id','1',true)");
            try (var rows = scope.executeQuery("SELECT count(*) FROM agent_runtime_budget_roots WHERE tenant_id='2'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1));
            }
            assertThrows(SQLException.class, () -> scope.execute("INSERT INTO agent_runtime_budget_roots"
                    + "(tenant_id,root_id,scenario,source_version,active_limit_seconds)"
                    + " VALUES('2','forbidden','STANDARD','test',60)"));
            connection.rollback();
        }
    }

    @Test
    void registrationKeepsZeroDimensionsAndRejectsMissingBalanceInEachFrozenScope() throws Exception {
        for (String scope : List.of("ROOT", "RUN", "STAGE_BASE")) {
            var run = run("missing-" + scope);
            var registration = new Registration(run,run,Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new Limits(Scenario.TEAM,Resources.ZERO,Resources.ZERO,Resources.ZERO,60,60,VERSION)));
            var budgets = budgets();
            budgets.register(registration);
            assertEquals(Optional.of(registration),budgets.find(run));
            try (var connection = adminConnection(); var statement = connection.prepareStatement(
                    "DELETE FROM agent_runtime_budget_balances WHERE tenant_id=? AND scope_kind=? AND scope_id=? AND dimension='TOTAL_TOKENS'")) {
                statement.setString(1,TENANT.value());statement.setString(2,scope);statement.setString(3,run.runId());
                assertEquals(1,statement.executeUpdate());
            }
            assertEquals("budget_balance_missing",assertThrows(IllegalStateException.class,()->budgets.find(run)).getMessage());
        }
    }

    @Test
    void registrationStillChecksConsumedAndReservedAlthoughOnlyMaximumIsReturned() throws Exception {
        var run = run("negative-registration");
        var budgets = budgets();
        budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        // 正式 schema 已拒绝负数；此处显式模拟损坏存储，检查读取不能丢弃原完整还原校验。
        for (String column : List.of("maximum", "consumed", "reserved")) {
            String constraint = "agent_runtime_budget_balances_" + column + "_check";
            try (var connection = adminConnection(); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE agent_runtime_budget_balances DROP CONSTRAINT " + constraint);
                try {
                    statement.executeUpdate("UPDATE agent_runtime_budget_balances SET " + column + "=-1 WHERE tenant_id='1' AND scope_kind='RUN' AND scope_id='negative-registration' AND dimension='TOOLS'");
                    assertEquals("budget_resources_negative",assertThrows(IllegalArgumentException.class,()->budgets.find(run)).getMessage());
                } finally {
                    statement.executeUpdate("UPDATE agent_runtime_budget_balances SET " + column + "=" + (column.equals("maximum") ? CAPACITY.tools() : 0)
                            + " WHERE tenant_id='1' AND scope_kind='RUN' AND scope_id='negative-registration' AND dimension='TOOLS'");
                    statement.execute("ALTER TABLE agent_runtime_budget_balances ADD CONSTRAINT " + constraint + " CHECK (" + column + ">=0)");
                }
            }
        }
    }

    @Test
    void reportedBatchKeepsMissingDistinctFromZeroAndRollsBackConflictBeforeFillingOtherDimensions() throws Exception {
        var run = run("zero-usage");
        var budgets = budgets();
        budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        var demand = demand(run,"zero-usage-attempt",Category.BUSINESS_MODEL,100,20,false);
        assertGranted(budgets.reserve(demand),AttemptState.RESERVED);
        assertTrue(budgets.markSent(run,demand.attemptId()));
        budgets.saveResult(run,demand.attemptId(),Usage.unreported(),"unknown");
        assertEquals(OptionalLong.empty(),reported(demand.attemptId(),Dimension.INPUT_TOKENS));
        budgets.saveResult(run,demand.attemptId(),new Usage(OptionalLong.of(0),OptionalLong.empty(),OptionalLong.empty()),"zero-input");
        assertEquals(OptionalLong.of(0),reported(demand.attemptId(),Dimension.INPUT_TOKENS));
        assertEquals(OptionalLong.empty(),reported(demand.attemptId(),Dimension.OUTPUT_TOKENS));
        assertThrows(IllegalStateException.class,()->budgets.saveResult(run,demand.attemptId(),
                new Usage(OptionalLong.of(1),OptionalLong.of(5),OptionalLong.of(6)),"conflict"));
        assertEquals(OptionalLong.empty(),reported(demand.attemptId(),Dimension.OUTPUT_TOKENS));
        assertEquals(OptionalLong.empty(),reported(demand.attemptId(),Dimension.TOTAL_TOKENS));
        assertThrows(IllegalArgumentException.class,()->budgets.saveResult(run,demand.attemptId(),
                new Usage(OptionalLong.of(0),OptionalLong.of(-1),OptionalLong.empty()),"negative"));
        budgets.saveResult(run,demand.attemptId(),new Usage(OptionalLong.of(0),OptionalLong.of(0),OptionalLong.of(0)),"known-zero");
        budgets.settle(run,demand.attemptId());
        assertEquals(new Resources(1,0,0,0,0,0,0),budgets.snapshot(run).consumed());
        assertEquals(Resources.ZERO,budgets.snapshot(run).reserved());
    }

    @Test
    void reportedBatchSqlFailureRollsBackEveryAmountReceiptAndAttemptState() throws Exception {
        var run = run("reported-sql-failure");
        var budgets = budgets();
        budgets.register(registration(run,run,Role.PRIMARY,CAPACITY,ACTIVE_SECONDS));
        var demand = demand(run,"reported-sql-failure-attempt",Category.BUSINESS_MODEL,100,20,false);
        assertGranted(budgets.reserve(demand),AttemptState.RESERVED);
        assertTrue(budgets.markSent(run,demand.attemptId()));
        var before = budgets.snapshot(run);
        try (var connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION public.fail_reported_batch() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.attempt_id='reported-sql-failure-attempt' AND NEW.dimension='OUTPUT_TOKENS' THEN
                            RAISE EXCEPTION 'forced_reported_batch_failure';
                        END IF;
                        RETURN NEW;
                    END $$
                    """);
            statement.execute("CREATE TRIGGER fail_reported_batch BEFORE UPDATE ON agent_runtime_budget_attempt_amounts "
                    + "FOR EACH ROW EXECUTE FUNCTION public.fail_reported_batch()");
            try {
                var failure = assertThrows(IllegalStateException.class,()->budgets.saveResult(run,demand.attemptId(),
                        Usage.reported(OptionalLong.of(80),OptionalLong.of(10)),"failed-receipt"));
                assertEquals("budget_storage_failed",failure.getMessage());
                // 框架可增加异常封装层，但真实 SQL 故障原因必须保留，且事务回滚断言不变。
                Throwable sqlFailure=failure;
                while(sqlFailure!=null && !(sqlFailure instanceof SQLException))sqlFailure=sqlFailure.getCause();
                assertInstanceOf(SQLException.class,sqlFailure);
                assertTrue(sqlFailure.getMessage().contains("forced_reported_batch_failure"));
                for (var dimension : List.of(Dimension.INPUT_TOKENS,Dimension.OUTPUT_TOKENS,Dimension.TOTAL_TOKENS))
                    assertEquals(OptionalLong.empty(),reported(demand.attemptId(),dimension));
                assertEquals(before,budgets.snapshot(run));
                assertGranted(budgets.reserve(demand),AttemptState.SENT);
                try (var receipt = statement.executeQuery("SELECT count(*) FROM agent_runtime_budget_result_receipts WHERE tenant_id='1' AND attempt_id='reported-sql-failure-attempt'")) {
                    assertTrue(receipt.next());assertEquals(0,receipt.getLong(1));
                }
            } finally {
                statement.execute("DROP TRIGGER fail_reported_batch ON agent_runtime_budget_attempt_amounts");
                statement.execute("DROP FUNCTION public.fail_reported_batch()");
            }
        }
        budgets.saveResult(run,demand.attemptId(),Usage.reported(OptionalLong.of(80),OptionalLong.of(10)),"successful-receipt");
        budgets.settle(run,demand.attemptId());
        assertEquals(new Resources(1,0,0,0,80,10,90),budgets.snapshot(run).consumed());
    }

    private static java.sql.Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
    }

    /** 在实际连接上记录预编译 SQL，不改变事务、RLS 或预算写入。 */
    private static javax.sql.DataSource countedDataSource(List<String> sql) {
        var loader = JdbcExecutionBudgetsTest.class.getClassLoader();
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
                    try {
                        var value = method.invoke(source, args);
                        if (!method.getName().equals("getConnection")) return value;
                        var connection = (java.sql.Connection) value;
                        return java.lang.reflect.Proxy.newProxyInstance(loader,
                                new Class<?>[]{java.sql.Connection.class}, (p, call, parameters) -> {
                                    if (call.getName().equals("prepareStatement"))
                                        sql.add(((String) parameters[0]).replaceAll("\\s+", " ").trim());
                                    try { return call.invoke(connection, parameters); }
                                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                                });
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private static void assertClosingRejectedWithoutMutation(JdbcExecutionBudgets budgets,RunRef run,Closing requested) throws SQLException {
        var before=budgets.snapshot(run);var rows=ledgerRows(before.registration().root());
        assertEquals("budget_closing_cannot_fit",assertThrows(IllegalStateException.class,
                ()->budgets.updateClosing(run,requested)).getMessage());
        assertEquals(before,budgets().snapshot(run));
        // 同时覆盖根 closing、Run 局部保护、余额、attempt 状态、用量、回执与活动区间。
        assertEquals(rows,ledgerRows(before.registration().root()));
    }

    /** 原子准入被拒时，根与 Run 保护、UNKNOWN、attempt、用量、回执及活动事实全部保持原值。 */
    private static void assertAdmissionRejectedWithoutMutation(JdbcExecutionBudgets budgets,RunRef run,ClosingAdmission requested) throws SQLException {
        var before=budgets.snapshot(run);var rows=ledgerRows(before.registration().root());
        assertEquals("budget_closing_cannot_fit",assertThrows(IllegalStateException.class,
                ()->budgets.admitClosing(run,requested)).getMessage());
        assertEquals(before,budgets().snapshot(run));
        assertEquals(rows,ledgerRows(before.registration().root()));
    }

    private static List<String> ledgerRows(RunRef root) throws SQLException {
        try(var connection=adminConnection();var statement=connection.prepareStatement("""
                WITH related_runs AS (
                    SELECT * FROM agent_runtime_budget_runs WHERE tenant_id=? AND root_id=?
                ), related_attempts AS (
                    SELECT a.* FROM agent_runtime_budget_attempts a JOIN related_runs r USING (tenant_id,run_id)
                )
                SELECT 'roots:' || row_to_json(t)::text FROM agent_runtime_budget_roots t
                    WHERE (tenant_id,root_id) IN (SELECT tenant_id,root_id FROM related_runs)
                UNION ALL SELECT 'runs:' || row_to_json(t)::text FROM related_runs t
                UNION ALL SELECT 'balances:' || row_to_json(t)::text FROM agent_runtime_budget_balances t
                    WHERE (tenant_id,scope_id) IN (SELECT tenant_id,run_id FROM related_runs)
                UNION ALL SELECT 'attempts:' || row_to_json(t)::text FROM related_attempts t
                UNION ALL SELECT 'amounts:' || row_to_json(t)::text FROM agent_runtime_budget_attempt_amounts t
                    WHERE (tenant_id,attempt_id) IN (SELECT tenant_id,attempt_id FROM related_attempts)
                UNION ALL SELECT 'receipts:' || row_to_json(t)::text FROM agent_runtime_budget_result_receipts t
                    WHERE (tenant_id,attempt_id) IN (SELECT tenant_id,attempt_id FROM related_attempts)
                UNION ALL SELECT 'activity:' || row_to_json(t)::text FROM agent_runtime_budget_activity t
                    WHERE (tenant_id,run_id) IN (SELECT tenant_id,run_id FROM related_runs)
                UNION ALL SELECT 'targets:' || row_to_json(t)::text FROM agent_runtime_budget_closing_targets t
                    WHERE (tenant_id,run_id) IN (SELECT tenant_id,run_id FROM related_runs)
                ORDER BY 1
                """)) {
            statement.setString(1,root.tenantId().value());statement.setString(2,root.runId());
            try(var rows=statement.executeQuery()) {
                var values=new java.util.ArrayList<String>();
                while(rows.next())values.add(rows.getString(1));
                assertFalse(values.isEmpty());return List.copyOf(values);
            }
        }
    }

    private static OptionalLong reported(String attempt,Dimension dimension) throws SQLException {
        try (var connection=adminConnection();var statement=connection.prepareStatement(
                "SELECT reported FROM agent_runtime_budget_attempt_amounts WHERE tenant_id=? AND attempt_id=? AND dimension=?")) {
            statement.setString(1,TENANT.value());statement.setString(2,attempt);statement.setString(3,dimension.name());
            try(var rows=statement.executeQuery()) {
                assertTrue(rows.next());long value=rows.getLong(1);
                return rows.wasNull()?OptionalLong.empty():OptionalLong.of(value);
            }
        }
    }

    private static JdbcExecutionBudgets budgets() { return new JdbcExecutionBudgets(source); }

    /** 首个资源超限在真实根锁事务中报告，拒绝不改变冻结额度、预留或已消费事实。 */
    @Test void resourceDenialsIdentifyEveryRootAndRunDimensionWithoutMutation() {
        var generous = new Resources(8, 8, 8, 8, 100, 100, 200);
        for (boolean rootLimited : List.of(true, false)) for (var dimension : Dimension.values()) {
            var run = run("precise-limit-" + rootLimited + "-" + dimension);
            long[] values = {8, 8, 8, 8, 100, 100, 200};
            values[dimension.ordinal()] = dimension == Dimension.TOTAL_TOKENS ? 1 : 0;
            if (dimension == Dimension.TOTAL_TOKENS) { values[4] = 1; values[5] = 1; }
            var limited = Resources.from(values);
            var root = rootLimited ? limited : generous;
            var budgets = budgets();
            budgets.register(new Registration(run, run, Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new Limits(Scenario.STANDARD, root, limited, limited, ACTIVE_SECONDS, ACTIVE_SECONDS, VERSION))));
            var category = switch (dimension) {
                case AUXILIARY_MODELS -> Category.AUXILIARY_MODEL;
                case TOOLS -> Category.TOOL;
                case KNOWLEDGE -> Category.KNOWLEDGE;
                default -> Category.BUSINESS_MODEL;
            };
            var before = budgets.snapshot(run);
            var denied = assertInstanceOf(Reservation.Denied.class,
                    budgets.reserve(demand(run, run.runId() + "-attempt", category, 1, 1, false)));
            assertEquals(rootLimited ? Refusal.ROOT_LIMIT : Refusal.RUN_LIMIT, denied.reason());
            assertEquals(Optional.of(dimension), denied.exhaustedDimension());
            assertEquals(before, budgets.snapshot(run));
        }
    }

    private static RunRef run(String id) { return new RunRef(TENANT, id); }

    @Test void closingModelWindowPersistsUnionAcrossAttemptsAndReportsOriginalLockedShortfalls() {
        var run=run("shared-closing-window");var ledger=budgets();
        ledger.register(registration(run,run,Role.PRIMARY,CAPACITY,300));
        var spec=new ClosingSpec(2,1,120,0,List.of(),Set.of());
        var sizing=new ExecutionBudgetScope.ClosingSizing(Category.BUSINESS_MODEL,20,10,Duration.ofSeconds(120));
        var protection=sizing.size(spec);
        assertFalse(ledger.admitClosingAndChoosePhase(run,new ClosingAdmission(protection,protection),Set.of(),Resources.call(Category.BUSINESS_MODEL,20,10)));
        assertEquals(240,ledger.snapshot(run).closing().activeSeconds());
        ledger.recordActivity(run,"ordinary",Instant.EPOCH,Instant.EPOCH.plusSeconds(60));
        ledger.enterClosing(run);
        ledger.recordActivity(run,ExecutionBudgetScope.CLOSING_MODEL_INTERVAL_PREFIX+"first",Instant.EPOCH.plusSeconds(60),Instant.EPOCH.plusSeconds(90));
        ledger.recordActivity(run,ExecutionBudgetScope.CLOSING_MODEL_INTERVAL_PREFIX+"first",Instant.EPOCH.plusSeconds(60),Instant.EPOCH.plusSeconds(100));
        ledger.recordActivity(run,ExecutionBudgetScope.CLOSING_MODEL_INTERVAL_PREFIX+"second",Instant.EPOCH.plusSeconds(110),Instant.EPOCH.plusSeconds(130));
        ledger.recordActivity(run,ExecutionBudgetScope.CLOSING_MODEL_INTERVAL_PREFIX+"overlap",Instant.EPOCH.plusSeconds(80),Instant.EPOCH.plusSeconds(120));
        var member=run("shared-closing-member");ledger.register(registration(member,run,Role.MEMBER,CAPACITY,300));
        ledger.recordActivity(member,ExecutionBudgetScope.CLOSING_MODEL_INTERVAL_PREFIX+"member",Instant.EPOCH.plusSeconds(130),Instant.EPOCH.plusSeconds(150));
        var unknown=demand(run,"shared-closing-unknown",Category.BUSINESS_MODEL,20,10,true);
        assertGranted(ledger.reserve(unknown),AttemptState.RESERVED);assertTrue(ledger.markSent(run,unknown.attemptId()));
        var before=budgets().snapshot(run);assertEquals(70_000,before.closingModelActiveMillis());
        assertEquals(150_000,before.activeMillis());assertEquals(130_000,before.runActiveMillis());
        var remaining=sizing.size(spec,before.closingModelActiveMillis());assertEquals(170,remaining.activeSeconds());
        var rejected=assertThrows(ClosingInsufficient.class,()->ledger.admitClosing(run,new ClosingAdmission(remaining,remaining)));
        assertEquals(Phase.CLOSING,rejected.phase());
        var time=rejected.shortfalls().stream().filter(fact->fact.scope()==BalanceScope.ROOT&&fact.dimension().equals("ACTIVE_MILLIS")).findFirst().orElseThrow();
        assertEquals(320_000,time.required());assertEquals(300_000,time.available());assertEquals(20_000,time.deficit());
        assertEquals(150_000,time.consumed());assertEquals(0,time.reserved());
        ledger.recover(run);assertEquals(before,budgets().snapshot(run));
        assertEquals(50_000,sizing.calculation(spec,before.closingModelActiveMillis()).modelWindowMillis());
    }

    private static Registration registration(RunRef run, RunRef root, Role role,
                                             Resources capacity, long activeSeconds) {
        return new Registration(run, root, role, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new Limits(Scenario.TEAM, capacity, capacity,
                BASE_STAGE, activeSeconds, activeSeconds, VERSION)));
    }

    private static Demand demand(RunRef run, String attempt, Category category,
                                 long input, long output, boolean closing) {
        return new Demand(run, attempt, "fingerprint:" + attempt, category,
                Resources.call(category, input, output), closing);
    }

    private static void assertGranted(Reservation reservation, AttemptState state) {
        assertEquals(state, assertInstanceOf(Reservation.Granted.class, reservation).permit().state());
    }

    private static void assertDenied(Reservation reservation, Refusal reason) {
        var denied = assertInstanceOf(Reservation.Denied.class, reservation);
        assertEquals(reason, denied.reason());
        assertEquals(reason == Refusal.ROOT_LIMIT || reason == Refusal.RUN_LIMIT, denied.exhaustedDimension().isPresent());
    }
}

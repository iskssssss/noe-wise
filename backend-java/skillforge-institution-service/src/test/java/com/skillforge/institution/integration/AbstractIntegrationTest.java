package com.skillforge.institution.integration;
import com.skillforge.core.shared.config.SnowflakeIds;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.billing.persistence.PlanDO;
import com.skillforge.core.foundation.persistence.TenantDO;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.annotation.DirtiesContext;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 真实依赖集成测试基类，CI拥有容器，S03仅借用父分配的新PostgreSQL/Redis fixture。
 *
 * <p>启动 pgvector 与 Redis 容器，并覆盖数据源与缓存配置，供 RLS、
 * 知识库检索与计费状态机等集成测试复用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = AbstractIntegrationTest.OwnedContainersCleanup.class,
        inheritListeners = true, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public abstract class AbstractIntegrationTest {
    /** 平台子进程与机构精确管理realm共享的测试验签密钥，独立于机构JWT密钥。 */
    protected static final String TEST_PLATFORM_JWT_SECRET = "synthetic-independent-platform-jwt-secret-20261022";
    /** 任一外部参数出现即进入受控模式，部分参数不得回退并额外启动容器。 */
    private static final boolean EXTERNAL=System.getenv().keySet().stream().anyMatch(key->key.startsWith("SKILLFORGE_S03_EXTERNAL_"));

    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.core.foundation.access.FoundationAccess testFoundationAccess;
    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.core.foundation.access.FoundationOrganizationService testFoundationOrganization;
    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.core.foundation.persistence.TenantMemberMapper testTenantMembers;
    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.core.foundation.persistence.PlatformMemberMapper testPlatformMembers;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.crypto.password.PasswordEncoder testPasswords;

    /** 初始化真实首位机构管理员；旧role字段本身不构成任何授权。 */
    protected void initializeTenantAdministrator(String tenantId, long memberId) {
        testFoundationOrganization.initializeTenant(tenantId, Long.toString(memberId));
    }

    /** 在本类Testcontainers创建账号与当前Foundation建制，不从预期反推权限。 */
    protected long createTestTenantAdministrator(String tenantId, String username, String password) {
        var member = new com.skillforge.core.foundation.persistence.TenantMemberDO();
        member.setId(SnowflakeIds.nextId());
        member.setTenantId(Long.parseLong(tenantId));
        member.setUsername(username); member.setRole("admin"); member.setPermissions(List.of());
        member.setStatus("active"); member.setPasswordHash(testPasswords.encode(password));
        member.setCreatedAt(Instant.now()); testTenantMembers.insert(member);
        initializeTenantAdministrator(tenantId, member.getId());
        return member.getId();
    }

    /** 仅在本类的独立平台账号体系创建一次真实管理员，后续方法复用同一账号。 */
    protected long ensureTestPlatformAdministrator(String username, String password) {
        var existing = testPlatformMembers.selectOne(new LambdaQueryWrapper<com.skillforge.core.foundation.persistence.PlatformMemberDO>()
                .eq(com.skillforge.core.foundation.persistence.PlatformMemberDO::getUsername, username));
        if (existing != null) {
            testFoundationAccess.snapshot(new com.skillforge.core.foundation.access.FoundationAccess.Subject(
                    true, null, existing.getId().toString()));
            return existing.getId();
        }
        var member = new com.skillforge.core.foundation.persistence.PlatformMemberDO();
        member.setId(SnowflakeIds.nextId()); member.setUsername(username); member.setStatus("active");
        member.setPasswordHash(testPasswords.encode(password));
        member.setCreatedAt(Instant.now()); member.setUpdatedAt(member.getCreatedAt());
        boolean initialized = testFoundationOrganization.initializePlatformAdministrator(
                member.getId().toString(), () -> testPlatformMembers.insert(member));
        if (!initialized) throw new AssertionError("isolated platform fixture already has another account");
        return member.getId();
    }

    /** pgvector PostgreSQL 容器，使用与生产一致的基础镜像。 */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge")
            .withUsername("skillforge")
            .withPassword("skillforge_dev");

    /** Redis 容器，使用 Redis 7 基础镜像。 */
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /**
     * 将测试容器地址注入 Spring 数据源与缓存配置。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("skillforge.secret-key", () -> "synthetic-integration-master-key-32-bytes");
        registry.add("skillforge.management-jwt-secret-key", () -> TEST_PLATFORM_JWT_SECRET);
        if(EXTERNAL) {
            String url=external("JDBC_URL"),user=external("DB_USER"),password=external("DB_PASSWORD");
            String redisHost=external("REDIS_HOST");int redisPort=Integer.parseInt(external("REDIS_PORT"));
            if(redisPort<1||redisPort>65535)throw new IllegalStateException("外部Redis端口非法");
            verifyExternalFixture(url,user,password);
            registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->user);registry.add("spring.datasource.password",()->password);
            registry.add("spring.data.redis.host",()->redisHost);registry.add("spring.data.redis.port",()->redisPort);
            // 父已在新库用受控migration owner完成正式全链；业务Spring连接不能承担提权迁移。
            registry.add("spring.flyway.enabled",()->false);
        } else {
            POSTGRES.start();REDIS.start();
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        }
        // 不在基类关闭 workers.auto-startup。Spring 先登记子类 DynamicPropertySource 再登记
        // 超类，后写覆盖先写；基类恒 false 会让子类的 true 失效，worker 根本不会 start。
    }

    /** 外部连接必须是受限角色、父已迁移的新库及同lane随机token；不借旧库或特权业务连接。 */
    private static void verifyExternalFixture(String url,String user,String password) {
        String lane=external("FIXTURE_LANE");if(!lane.matches("[a-z][a-z0-9_]{0,31}"))throw new IllegalStateException("外部fixture lane非法");
        var fixture=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,user,password));
        String database=fixture.queryForObject("SELECT current_database()",String.class);
        if(database==null||!database.matches("sf_s03_"+lane+"_[a-z0-9_]+"))throw new IllegalStateException("外部fixture必须使用父新建的lane数据库");
        if(Boolean.TRUE.equals(fixture.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)))throw new IllegalStateException("外部业务连接不得绕过RLS");
        if(!Integer.valueOf(1).equals(fixture.queryForObject("SELECT count(*) FROM public.s03_host_fixture_guard WHERE lane=? AND token=?",Integer.class,lane,external("FIXTURE_TOKEN"))))throw new IllegalStateException("外部fixture token/lane不匹配");
        if(!Boolean.TRUE.equals(fixture.queryForObject("SELECT EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE success AND version=?)",Boolean.class,external("SCHEMA_VERSION"))))throw new IllegalStateException("外部fixture缺父绑定的正式完整迁移链");
    }
    /** 部分外部变量缺失即拒绝；异常只包含参数名。 */
    private static String external(String suffix){String name="SKILLFORGE_S03_EXTERNAL_"+suffix;String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("缺少外部fixture参数 "+name);return value;}
    /**
     * Spring afterTestClass按order逆序执行：先DirtiesContext关闭Host，再释放其依赖。
     * JUnit @AfterAll早于SpringExtension.afterAll，不能承担容器释放；外部借用资源仍由父夹具回收。
     */
    public static final class OwnedContainersCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() {
            return DirtiesContextTestExecutionListener.ORDER - 2;
        }

        /** 测试失败或上下文初始化失败同样回收；一个容器失败不能跳过另一个，不重新加载上下文。 */
        @Override public void afterTestClass(TestContext testContext) {
            if (EXTERNAL) return;
            Throwable failure = null;
            try { REDIS.stop(); } catch (Throwable redisFailure) { failure = redisFailure; }
            try { POSTGRES.stop(); } catch (Throwable postgresFailure) {
                if (failure == null) failure = postgresFailure; else failure.addSuppressed(postgresFailure);
            }
            if (failure != null) throw new IllegalStateException("owned_integration_container_cleanup_failed", failure);
        }
    }

    /**
     * 确保测试库中存在免费套餐（认证服务注册时依赖）。
     *
     * @param planMapper 套餐表映射器
     * @return 免费套餐
     */
    protected PlanDO ensureFreePlan(PlanMapper planMapper) {
        PlanDO existing = planMapper.selectOne(
                new LambdaQueryWrapper<PlanDO>().eq(PlanDO::getPlanCode, "free"));
        if (existing != null) {
            return existing;
        }
        PlanDO plan = new PlanDO();
        plan.setId(SnowflakeIds.nextId());
        plan.setPlanCode("free");
        plan.setDisplayName("免费版");
        plan.setQuotaApiCallsPerMonth(100);
        plan.setQuotaTokensIncluded(0);
                plan.setQuotaMaxKnowledgeBases(5);
        plan.setQuotaMaxMembers(5);
        plan.setQuotaMaxChannels(3);
        plan.setRateLimitPerMinute(120);
        plan.setSloAvailabilityTarget(new BigDecimal("0.999"));
        plan.setIsActive(true);
        plan.setCreatedAt(Instant.now());
        plan.setUpdatedAt(Instant.now());
        planMapper.insert(plan);
        return plan;
    }

    /**
     * 直接创建测试租户，避免认证流程依赖 Sa-Token 上下文。
     *
     * @param tenantMapper 租户表映射器
     * @param planMapper   套餐表映射器
     * @param slug         租户唯一标识
     * @return 租户 ID
     */
    protected String createTenant(TenantMapper tenantMapper, PlanMapper planMapper, String slug) {
        PlanDO plan = ensureFreePlan(planMapper);
        TenantDO tenant = new TenantDO();
        tenant.setId(SnowflakeIds.nextId());
        tenant.setName("测试租户");
        tenant.setSlug(slug);
        tenant.setPlanId(plan.getId());
        tenant.setStatus("active");
        tenant.setDeploymentMode("cloud");
        tenant.setSettings(Map.of());
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        tenantMapper.insert(tenant);
        return tenant.getId().toString();
    }

    /**
     * 在同一 JDBC 事务内绑定 {@code app.tenant_id} 再计数。Runtime 表启用 FORCE RLS，
     * 自动提交时 SET LOCAL 在下一条SQL前已失效，不能将不可见行误判为 Run 未完成。
     */
    protected static long queryCount(JdbcTemplate jdbc, String tenantId, String sql, Object... args) {
        Long value = jdbc.execute((ConnectionCallback<Long>) connection -> tenantRead(connection, tenantId, bound -> {
            try (PreparedStatement statement = bound.prepareStatement(sql)) {
                for (int index = 0; index < args.length; index++) {
                    statement.setObject(index + 1, args[index]);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return 0L;
                    }
                    return rows.getLong(1);
                }
            }
        }));
        return value == null ? 0L : value;
    }

    /** 同事务绑定租户后读取诊断行；列以小写键返回。 */
    protected static List<Map<String, Object>> queryRows(
            JdbcTemplate jdbc, String tenantId, String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.execute((ConnectionCallback<List<Map<String, Object>>>) connection -> tenantRead(connection, tenantId, bound -> {
            try (PreparedStatement statement = bound.prepareStatement(sql)) {
                for (int index = 0; index < args.length; index++) {
                    statement.setObject(index + 1, args[index]);
                }
                try (ResultSet result = statement.executeQuery()) {
                    List<Map<String, Object>> collected = new ArrayList<>();
                    int columns = result.getMetaData().getColumnCount();
                    while (result.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int column = 1; column <= columns; column++) {
                            row.put(result.getMetaData().getColumnLabel(column).toLowerCase(),
                                    result.getObject(column));
                        }
                        collected.add(row);
                    }
                    return collected;
                }
            }
        }));
        return rows == null ? List.of() : rows;
    }

    /**
     * 自动提交连接只拥有本次短读事务：成功提交、异常回滚，最后恢复原autoCommit。
     * 已处于事务的连接只借用，不提交、不回滚或改变autoCommit，异常交由原事务拥有者处理。
     * 租户始终使用事务级绑定，不在连接池留下会话级tenant设置。
     */
    private static <T> T tenantRead(Connection connection,String tenantId,ConnectionCallback<T> read) throws SQLException {
        boolean owned=connection.getAutoCommit();Throwable failure=null;
        try {
            if(owned)connection.setAutoCommit(false);
            try(PreparedStatement bind=connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                bind.setString(1,tenantId);bind.execute();
            }
            T value=read.doInConnection(connection);
            if(owned)connection.commit();
            return value;
        } catch(SQLException|RuntimeException|Error rejected) {
            failure=rejected;
            if(owned)try { connection.rollback(); } catch(SQLException rollback) { rejected.addSuppressed(rollback); }
            throw rejected;
        } finally {
            if(owned)try { connection.setAutoCommit(true); }
            catch(SQLException restore) { if(failure==null)throw restore;failure.addSuppressed(restore); }
        }
    }

    /** 直调应用入口时显式提供成员认证；成员与会话权限仍由真实数据库核验。 */
    protected <T> T withAuthenticatedMember(long memberId, java.util.function.Supplier<T> action) {
        cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff.setModelBox(
                new cn.dev33.satoken.context.mock.SaRequestForMock(),
                new cn.dev33.satoken.context.mock.SaResponseForMock(),
                new cn.dev33.satoken.context.mock.SaStorageForMock());
        try {
            cn.dev33.satoken.stp.StpUtil.switchTo(memberId);
            String tenantId = com.skillforge.common.context.TenantContext.getTenantId();
            if (tenantId == null || tenantId.isBlank()) throw new AssertionError("test member requires explicit tenant");
            return testFoundationAccess.withSubject(
                    new com.skillforge.core.foundation.access.FoundationAccess.Subject(
                            false, tenantId, Long.toString(memberId)), action);
        } finally {
            cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff.clearModelBox();
        }
    }

    /** 当前 Spring 上下文里的 Runtime worker 必须已按 auto-startup 真正 start。 */
    protected static void requireRunningRuntimeWorkers(
            ApplicationContext context, AgentRuntimeProperties properties) {
        if (properties == null || !properties.getWorkers().isAutoStartup()) {
            throw new AssertionError("agent-runtime.workers.auto-startup must be true");
        }
        var workers = context.getBeansOfType(com.skillforge.institution.runtime.AgentRuntimeExecutionWorker.class);
        if (workers.size() != 1 || !workers.values().iterator().next().isRunning()) {
            throw new AssertionError("unique typed AgentRuntimeExecutionWorker must be running after application startup");
        }
    }
}

package com.skillforge.core.foundation.catalog.entitlements;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 完整迁移及非超级用户真实PG验证直属容量、catalog读写、竞争和回滚；不代替总部开通HTTP授权验收。 */
@Testcontainers(disabledWithoutDocker=false)
class SubInstitutionQuotaPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("sub_institution_quota").withUsername("skillforge").withPassword("synthetic_owner_password");
    private static final String PLAN="3030001",HQ="3030011",OTHER="3030022";
    private static JdbcTemplate owner;
    private static JdbcTemplate database;
    private static EntitlementMapper mapper;
    private static TransactionTemplate transaction;
    private final AtomicReference<FoundationAccess.Subject> actor=new AtomicReference<>();
    private EntitlementService entitlements;
    private ResourceQuota quota;

    /** 业务连接始终NOSUPERUSER/NOBYPASSRLS，正式Mapper与拦截器消费完整迁移后的列。 */
    @BeforeAll static void initialize() throws Exception {
        var administrator=source("skillforge","synthetic_owner_password");
        Flyway.configure().dataSource(administrator).locations("classpath:db/migration").load().migrate();
        owner=new JdbcTemplate(administrator);
        owner.execute("CREATE ROLE sub_institution_quota_test LOGIN PASSWORD 'synthetic_quota_password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO sub_institution_quota_test");
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO sub_institution_quota_test");
        var application=source("sub_institution_quota_test","synthetic_quota_password");database=new JdbcTemplate(application);
        assertThat(database.queryForMap("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user"))
                .containsEntry("rolsuper",false).containsEntry("rolbypassrls",false);
        var config=new Configuration(new Environment("sub-institution-quota",new SpringManagedTransactionFactory(),application));
        config.setMapUnderscoreToCamelCase(true);config.setLocalCacheScope(LocalCacheScope.STATEMENT);config.addInterceptor(new TenantRlsInterceptor());
        try(var xml=new ClassPathResource("mapper/foundation/catalog/EntitlementMapper.xml").getInputStream()){
            new XMLMapperBuilder(xml,config,"mapper/foundation/catalog/EntitlementMapper.xml",config.getSqlFragments()).parse();
        }
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(EntitlementMapper.class);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(application));
    }

    /** 仅重置独占测试库的本类机构；不把历史关系或其他租户当作可清理业务数据。 */
    @BeforeEach void fixture() {
        owner.update("UPDATE nw_tenants SET parent_tenant_id=NULL WHERE id BETWEEN 3030011 AND 3030099");
        owner.update("DELETE FROM nw_tenants WHERE id BETWEEN 3030011 AND 3030099");
        owner.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target) VALUES(3030001,'sub-quota','子机构容量测试',0.99) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_billing_plans SET is_active=true,quota_max_sub_institutions=NULL,foundation_quota_revision=1 WHERE id=3030001");
        insert(owner,Long.parseLong(HQ),null,"active");insert(owner,Long.parseLong(OTHER),null,"active");
        tenantActor();var access=mock(FoundationAccess.class);
        when(access.currentSubject()).thenAnswer(call->actor.get());
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(),List.of(),1));
        var scope=new CatalogScope(access);entitlements=new EntitlementService(mapper,access,scope);
        quota=new ResourceQuota(mapper,scope,mock(DirectDeploymentScope.class));
    }
    /** 每个测试与竞争线程分别负责清理可信Tenant绑定。 */ @AfterEach void clear(){TenantContext.clear();}

    /** 停用直属仍占容量，后代与别的总部不占；套餐NULL不限、0禁止，机构NULL覆盖恢复继承。 */
    @Test void catalogReadsWritesAndCountsDirectChildrenWithoutInventedDefaults() {
        platformActor();assertThat(entitlements.planLimits(PLAN).subInstitutions()).isNull();
        assertThatThrownBy(()->entitlements.planLimits("3030999")).isInstanceOf(ApiException.class);
        assertThat(tx(()->entitlements.inspect(HQ)).overrides().subInstitutions()).isNull();
        tenantActor();tx(()->{quota.requireCapacity(HQ,ResourceKind.SUB_INSTITUTION,1);return null;});
        insert(owner,3030033L,HQ,"suspended");insert(owner,3030044L,"3030033","active");insert(owner,3030055L,OTHER,"active");
        var initial=tx(()->entitlements.inspect(null));
        assertThat(initial.usage().stream().filter(u->u.kind()==ResourceKind.SUB_INSTITUTION).findFirst().orElseThrow().used()).isEqualTo("1");
        platformActor();var plan=tx(()->entitlements.planQuota(PLAN));
        tx(()->entitlements.savePlanQuota(new SavePlanQuota(PLAN,limits(1L),plan.revision())));
        assertThat(entitlements.planLimits(PLAN).subInstitutions()).isEqualTo(1L);
        var inherited=tx(()->entitlements.inspect(HQ));assertThat(inherited.limits().subInstitutions()).isEqualTo(1L);
        var zero=tx(()->entitlements.saveTenantQuota(new SaveTenantQuota(HQ,limits(0L),inherited.revision())));
        assertThat(zero.overrides().subInstitutions()).isZero();assertThat(zero.limits().subInstitutions()).isZero();
        tenantActor();assertThatThrownBy(()->tx(()->{quota.requireCapacity(HQ,ResourceKind.SUB_INSTITUTION,1);return null;}))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("resource_quota_exceeded"));
        assertThatThrownBy(()->tx(()->{quota.requireCapacity(OTHER,ResourceKind.SUB_INSTITUTION,1);return null;})).isInstanceOf(ApiException.class);
        platformActor();var restored=tx(()->entitlements.saveTenantQuota(new SaveTenantQuota(HQ,limits(null),zero.revision())));
        assertThat(restored.overrides().subInstitutions()).isNull();assertThat(restored.limits().subInstitutions()).isEqualTo(1L);
        var current=tx(()->entitlements.planQuota(PLAN));tx(()->entitlements.savePlanQuota(new SavePlanQuota(PLAN,limits(null),current.revision())));
        assertThat(tx(()->entitlements.inspect(HQ)).limits().subInstitutions()).isNull();
        assertThatThrownBy(()->tx(()->entitlements.savePlanQuota(new SavePlanQuota(PLAN,limits(-1L),0)))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->tx(()->entitlements.savePlanQuota(new SavePlanQuota(PLAN,limits(2147483648L),0)))).isInstanceOf(ApiException.class);
        tenantActor();tx(()->{quota.requireCapacity(HQ,ResourceKind.SUB_INSTITUTION,1);return null;});
    }

    /** 两个创建事务竞争最后一个直属名额，只能提交一项关系；失败不生成独立容量计数。 */
    @Test void concurrentCreationsCannotPassTheSameLastCapacity() throws Exception {
        platformActor();tx(()->entitlements.savePlanQuota(new SavePlanQuota(PLAN,limits(1L),1)));tenantActor();
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->compete(3030061L,ready,start));var second=pool.submit(()->compete(3030062L,ready,start));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created","resource_quota_exceeded");
        }
        assertThat(mapper.used(HQ,"SUB_INSTITUTION")).isEqualTo(1L);
    }

    /** 同事务业务写入失败释放真实名额；自动提交检查仍拒绝，随后合法创建可使用原名额。 */
    @Test void failedCreationRollsBackRelationAndDoesNotConsumeCapacity() {
        platformActor();tx(()->entitlements.savePlanQuota(new SavePlanQuota(PLAN,limits(1L),1)));tenantActor();
        assertThatThrownBy(()->tx(()->{quota.requireCapacity(HQ,ResourceKind.SUB_INSTITUTION,1);
            insert(database,3030063L,HQ,"active");throw new IllegalStateException("controlled_after_insert_failure");}))
                .isInstanceOf(IllegalStateException.class).hasMessage("controlled_after_insert_failure");
        assertThat(mapper.used(HQ,"SUB_INSTITUTION")).isZero();
        assertThatThrownBy(()->quota.requireCapacity(HQ,ResourceKind.SUB_INSTITUTION,1)).isInstanceOf(IllegalStateException.class);
        tx(()->{quota.requireCapacity(HQ,ResourceKind.SUB_INSTITUTION,1);insert(database,3030063L,HQ,"active");return null;});
        assertThat(mapper.used(HQ,"SUB_INSTITUTION")).isEqualTo(1L);
    }

    /** 正式创建事务内锁容量后写直属关系，不使用测试锁串行化生产SQL。 */
    private String compete(long id,CountDownLatch ready,CountDownLatch start) throws Exception {
        TenantContext.setTenantId(HQ);ready.countDown();
        try {if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("并发起点超时");
            return tx(()->{quota.requireCapacity(HQ,ResourceKind.SUB_INSTITUTION,1);insert(database,id,HQ,"active");return "created";});
        }catch(ApiException failure){return failure.getCode();}finally{TenantContext.clear();}
    }
    /** 只切换本测试的可信主体，不授予总部其他Tenant的数据权限。 */
    private void tenantActor(){actor.set(new FoundationAccess.Subject(false,HQ,"101"));TenantContext.setTenantId(HQ);}
    /** 平台维护额度仍通过正式EntitlementService和CAS。 */
    private void platformActor(){actor.set(new FoundationAccess.Subject(true,null,"901"));TenantContext.clear();}
    /** 全量配额更新只为本测试关注项设值；其余允许NULL的项目明确不限/继承。 */
    private static Limits limits(Long subInstitutions){return new Limits(null,null,null,null,null,null,null,subInstitutions);}
    /** 测试造关系事实仅用于容量边界；成员/凭证和GI15管理授权不在此生成。 */
    private static void insert(JdbcTemplate jdbc,long id,String parent,String status){jdbc.update(
            "INSERT INTO nw_tenants(id,name,slug,plan_id,status,parent_tenant_id) VALUES(?,?,?,3030001,?,CAST(? AS bigint))",
            id,"quota-"+id,"quota-"+id,status,parent);}
    /** 真实Spring事务持有配额行锁直到创建写入提交或回滚。 */
    private static <T>T tx(Supplier<T> work){return transaction.execute(status->work.get());}
    /** 连接固定独占测试容器，密码均为本类合成夹具。 */
    private static PGSimpleDataSource source(String user,String password){var source=new PGSimpleDataSource();source.setURL(PG.getJdbcUrl());source.setUser(user);source.setPassword(password);return source;}
}

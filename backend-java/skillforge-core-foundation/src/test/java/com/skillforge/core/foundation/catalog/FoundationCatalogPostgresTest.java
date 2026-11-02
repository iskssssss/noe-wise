package com.skillforge.core.foundation.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.dictionary.DictionaryContracts;
import com.skillforge.core.foundation.catalog.dictionary.DictionaryMapper;
import com.skillforge.core.foundation.catalog.dictionary.DictionaryService;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts;
import com.skillforge.core.foundation.catalog.entitlements.DirectDeploymentScope;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementMapper;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementService;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceKind;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.catalog.navigation.ControlledRoutes;
import com.skillforge.core.foundation.catalog.navigation.NavigationContracts;
import com.skillforge.core.foundation.catalog.navigation.NavigationMapper;
import com.skillforge.core.foundation.catalog.navigation.NavigationService;
import com.skillforge.core.foundation.catalog.preferences.PreferenceContracts;
import com.skillforge.core.foundation.catalog.preferences.PreferenceMapper;
import com.skillforge.core.foundation.catalog.preferences.PreferenceService;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实catalog迁移、MyBatis、普通数据库角色和事务竞争；JWT组合验收由双宿主测试负责。 */
@Testcontainers(disabledWithoutDocker=false)
class FoundationCatalogPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("foundation_catalog_test");
    private static SqlSessionTemplate sql;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactions;
    private final AtomicReference<FoundationAccess.Subject> subject=new AtomicReference<>();
    private FoundationAccess access;
    private DictionaryService dictionaries;
    private NavigationService navigation;
    private PreferenceService preferences;
    private EntitlementService entitlements;
    private FeatureGate features;
    private ResourceQuota quota;

    /** 最小宿主表只作夹具；catalog及容量结构执行真实迁移，导航种子消费当前代码目录。 */
    @BeforeAll static void initialize() throws Exception {
        var container=PG.getContainerInfo();
        System.out.println("CATALOG_FIXTURE_RESOURCE id="+PG.getContainerId()
                +" created="+container.getCreated()+" image="+container.getImageId()
                +" session="+container.getConfig().getLabels().get("org.testcontainers.sessionId"));
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var statement=connection.createStatement()) {
            statement.execute("""
                CREATE ROLE skillforge LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS;
                GRANT USAGE ON SCHEMA public TO skillforge;
                CREATE TABLE nw_billing_plans(id bigint PRIMARY KEY,plan_code text NOT NULL,display_name text NOT NULL,is_active boolean NOT NULL,
                    quota_max_knowledge_bases integer,quota_max_members integer,quota_max_channels integer,features jsonb,updated_at timestamptz);
                CREATE TABLE nw_tenants(id bigint PRIMARY KEY,name text NOT NULL,plan_id bigint NOT NULL REFERENCES nw_billing_plans(id),status text NOT NULL,
                    override_quota_max_workflows integer,override_quota_max_knowledge_bases integer,override_quota_max_members integer,override_quota_max_channels integer,
                    parent_tenant_id bigint REFERENCES nw_tenants(id),updated_at timestamptz);
                INSERT INTO nw_billing_plans VALUES(9007199254740993,'catalog-test','目录套餐',true,NULL,NULL,NULL,'{}',now());
                INSERT INTO nw_tenants VALUES(11,'第一机构',9007199254740993,'active',NULL,NULL,NULL,NULL,NULL,now()),(22,'第二机构',9007199254740993,'active',NULL,NULL,NULL,NULL,NULL,now());
                CREATE TABLE nw_agents(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,UNIQUE(tenant_id,id));
                CREATE TABLE nw_workflow_containers(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,UNIQUE(tenant_id,id));
                -- catalog 只消费 V332 归属关系的三列读取契约；完整资源包约束由 WorkflowTestIsolationDatabaseTest 验证。
                CREATE TABLE nw_workflow_test_sessions(tenant_id bigint NOT NULL,agent_id bigint NOT NULL,container_id bigint NOT NULL,
                    PRIMARY KEY(tenant_id,agent_id),UNIQUE(tenant_id,container_id),
                    FOREIGN KEY(tenant_id,agent_id) REFERENCES nw_agents(tenant_id,id),
                    FOREIGN KEY(tenant_id,container_id) REFERENCES nw_workflow_containers(tenant_id,id));
                ALTER TABLE nw_workflow_test_sessions ENABLE ROW LEVEL SECURITY;
                ALTER TABLE nw_workflow_test_sessions FORCE ROW LEVEL SECURITY;
                CREATE POLICY catalog_workflow_test_tenant ON nw_workflow_test_sessions
                    USING(tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
                CREATE TABLE nw_knowledge_bases(id bigint PRIMARY KEY,tenant_id bigint NOT NULL);
                CREATE TABLE nw_tenant_members(id bigint PRIMARY KEY,tenant_id bigint NOT NULL);
                CREATE TABLE nw_channels(id bigint PRIMARY KEY,tenant_id bigint NOT NULL);
                CREATE TABLE nw_foundation_files(id bigint PRIMARY KEY,tenant_id bigint,size_bytes bigint NOT NULL,state text NOT NULL);
                CREATE TABLE nw_foundation_admin_jobs(id bigint PRIMARY KEY,tenant_id bigint,state text NOT NULL);
                """);
            for(String path:List.of("db/migration/V276__foundation_catalog.sql","db/migration/V303__sub_institution_resource_quota.sql")) {
                try(var migration=FoundationCatalogPostgresTest.class.getClassLoader().getResourceAsStream(path)) {
                    assertNotNull(migration,path);statement.execute(new String(migration.readAllBytes(),StandardCharsets.UTF_8));
                }
            }
            // 本套件验证当前导航行为，不加载Application等其他领域的历史表链；完整种子迁移由宿主Flyway验收。
            statement.execute("DELETE FROM nw_foundation_navigation_nodes");
            try(var insert=connection.prepareStatement("""
                    INSERT INTO nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by)
                    VALUES(?,?,?,?,?,true,'fixture','fixture')
                    """)) {
                for(boolean platform:new boolean[]{true,false}) {
                    int sortOrder=0;
                    for(var route:ControlledRoutes.list(platform)) {
                        insert.setString(1,platform?"platform":"tenant_template");
                        insert.setString(2,route.key().replace('.','_'));
                        insert.setString(3,route.title());insert.setString(4,route.key());
                        insert.setInt(5,sortOrder+=10);insert.addBatch();
                    }
                }
                insert.executeBatch();
            }
            statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO skillforge");
        }
        var source=new PGSimpleDataSource();source.setURL(PG.getJdbcUrl());source.setUser("skillforge");source.setPassword("test-only");
        var configuration=new Configuration(new Environment("catalog",new SpringManagedTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(new TenantRlsInterceptor());
        for(String name:List.of("DictionaryMapper","NavigationMapper","PreferenceMapper","EntitlementMapper")) {
            String path="mapper/foundation/catalog/"+name+".xml";
            try(var resource=FoundationCatalogPostgresTest.class.getClassLoader().getResourceAsStream(path)) {
                assertNotNull(resource);new XMLMapperBuilder(resource,configuration,path,configuration.getSqlFragments()).parse();
            }
        }
        sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));jdbc=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);
    }

    /** 用数据库清理合法夹具，不依赖用例执行顺序。 */
    @BeforeEach void reset() throws Exception {
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var statement=connection.createStatement()) {
            statement.execute("""
                TRUNCATE nw_foundation_dictionary_references,nw_foundation_dictionary_values,nw_foundation_dictionaries,
                    nw_foundation_tenant_branding,nw_foundation_personal_preferences,nw_foundation_tenant_features,nw_foundation_navigation_owners,
                    nw_workflow_test_sessions,nw_agents,nw_workflow_containers,nw_knowledge_bases,nw_tenant_members,nw_channels,nw_foundation_files,nw_foundation_admin_jobs;
                DELETE FROM nw_foundation_navigation_nodes WHERE owner_key LIKE 'tenant:%';
                UPDATE nw_foundation_features SET enabled=true;
                UPDATE nw_foundation_plan_features SET enabled=true,revision=1;
                UPDATE nw_tenants SET plan_id=9007199254740993;
                DELETE FROM nw_billing_plans WHERE id<>9007199254740993;
                UPDATE nw_billing_plans SET is_active=true,quota_max_agents=1,quota_max_workflows=2,quota_max_knowledge_bases=2,
                    quota_max_members=2,quota_max_channels=2,quota_file_bytes=100,quota_max_admin_tasks=2,quota_max_sub_institutions=NULL,foundation_quota_revision=1;
                UPDATE nw_tenants SET status='active',override_quota_max_agents=NULL,override_quota_max_workflows=NULL,
                    override_quota_max_knowledge_bases=NULL,override_quota_max_members=NULL,override_quota_max_channels=NULL,
                    override_quota_file_bytes=NULL,override_quota_max_admin_tasks=NULL,override_quota_max_sub_institutions=NULL,parent_tenant_id=NULL,foundation_quota_revision=1;
                """);
        }
        subject.set(new FoundationAccess.Subject(false,"11","101"));TenantContext.setTenantId("11");
        access=mock(FoundationAccess.class);when(access.currentSubject()).thenAnswer(i->subject.get());
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of("test"),List.of("foundation.agents.read","foundation.preferences.manage"),1));
        var scope=new CatalogScope(access);var deployments=mock(DirectDeploymentScope.class);
        features=new FeatureGate(sql.getMapper(EntitlementMapper.class),scope,deployments);dictionaries=new DictionaryService(sql.getMapper(DictionaryMapper.class),access,scope,features);
        navigation=new NavigationService(sql.getMapper(NavigationMapper.class),access,scope);preferences=new PreferenceService(sql.getMapper(PreferenceMapper.class),access,scope);
        entitlements=new EntitlementService(sql.getMapper(EntitlementMapper.class),access,scope);quota=new ResourceQuota(sql.getMapper(EntitlementMapper.class),scope,deployments);
    }

    /** 线程退出清理身份，不依赖连接池或之前用例的残留租户。 */
    @AfterEach void clear() {TenantContext.clear();}

    /** 稳定值的引用外键、停用选择、CAS与跨租户伪造共同执行真实SQL。 */
    @Test void referencesDisableCasAndRlsProtectDictionaryLifecycle() {
        tx(()->dictionaries.save(new DictionaryContracts.SaveDictionary("status","状态",true,0)));
        var value=tx(()->dictionaries.saveValue(new DictionaryContracts.SaveValue("status","open","开放",0,true,0)));
        tx(()->{dictionaries.reference("status","open","agent","a-1");return null;});
        var blocked=assertThrows(ApiException.class,()->tx(()->{dictionaries.delete(new DictionaryContracts.Delete("status","open",value.revision()));return null;}));
        assertEquals("dictionary_referenced",blocked.getCode());
        tx(()->dictionaries.saveValue(new DictionaryContracts.SaveValue("status","open","已开放",0,false,value.revision())));
        assertTrue(tx(()->dictionaries.options("status")).isEmpty());assertEquals("open",tx(()->dictionaries.listValues("status")).getFirst().code());
        assertThrows(ApiException.class,()->tx(()->dictionaries.saveValue(new DictionaryContracts.SaveValue("status","open","覆盖旧值",0,true,value.revision()))));
        TenantContext.setTenantId("22");
        assertTrue(sql.getMapper(DictionaryMapper.class).values("tenant:11","status").isEmpty());
        assertThrows(org.springframework.dao.DataAccessException.class,()->tx(()->sql.getMapper(DictionaryMapper.class).insertDictionary("tenant:11","11",new DictionaryContracts.SaveDictionary("forged","伪造",true,0),"101")));
        TenantContext.setTenantId("11");
        tx(()->{dictionaries.unreference("agent","a-1");var current=dictionaries.listValues("status").getFirst();dictionaries.delete(new DictionaryContracts.Delete("status","open",current.revision()));return null;});
        assertTrue(tx(()->dictionaries.listValues("status")).isEmpty());
    }

    /** 拒绝动作在任何持久化前发生，不以角色名称放行。 */
    @Test void rejectedDictionaryPermissionHasNoWriteSideEffect() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"denied","拒绝",false)).when(access).require("foundation.dictionary.manage");
        assertThrows(ApiException.class,()->tx(()->dictionaries.save(new DictionaryContracts.SaveDictionary("forbidden","禁止",true,0))));
        assertTrue(sql.getMapper(DictionaryMapper.class).dictionaries("tenant:11").isEmpty());
    }

    /** 树结构不接受任意路由、环或深度扩大；当前菜单只显示snapshot内真实权限。 */
    @Test void controlledRoutesPermissionFilteringAndEmptyTreeInitializationRemainStable() {
        var visible=navigation.current();assertTrue(visible.stream().anyMatch(m->m.routeKey().equals("agents")));assertFalse(visible.stream().anyMatch(m->m.routeKey().equals("knowledge")));
        assertFalse(visible.stream().anyMatch(m->m.routeKey().equals("agent_deployments")),"普通Agent读取权限不能隐含公共部署读取动作");
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of("test"),
                List.of("foundation.agents.read","foundation.preferences.manage","tenant.agent_deployments.read"),2));
        var deploymentMenu=navigation.current().stream().filter(m->m.routeKey().equals("agent_deployments")).findFirst().orElseThrow();
        assertEquals("/admin/agent-deployments",deploymentMenu.path());
        assertEquals("tenant.agent_deployments.read",deploymentMenu.permission());
        assertTrue(navigation.routes().stream().noneMatch(r->List.of("applications","application_usage").contains(r.key())));
        assertThrows(ApiException.class,()->tx(()->navigation.save(new NavigationContracts.SaveNode("removed",null,"已移除应用用量","application_usage",0,true,0))));
        assertThrows(ApiException.class,()->tx(()->navigation.save(new NavigationContracts.SaveNode("evil",null,"恶意","../../component",0,true,0))));
        var group=tx(()->navigation.save(new NavigationContracts.SaveNode("group",null,"组",null,0,true,0)));
        tx(()->navigation.save(new NavigationContracts.SaveNode("child","group","子组",null,0,true,0)));
        assertThrows(ApiException.class,()->tx(()->navigation.save(new NavigationContracts.SaveNode("group","child","组",null,0,true,group.revision()))));
        tx(()->{navigation.delete(new NavigationContracts.DeleteNode("child",1));navigation.delete(new NavigationContracts.DeleteNode("group",1));for(var node:navigation.list()) navigation.delete(new NavigationContracts.DeleteNode(node.code(),node.revision()));return null;});
        assertTrue(navigation.list().isEmpty());assertTrue(navigation.current().isEmpty(),"删除全部自有节点不能复活模板");
        assertTrue(sql.getMapper(NavigationMapper.class).initialized("tenant:11"));
    }

    /** 展示配置CAS保持null清除含义，本人键与机构范围都受RLS保护。 */
    @Test void brandingAndPersonalPreferencesPreserveScopeAndCas() {
        assertEquals("第一机构",preferences.branding().displayName());
        var brand=tx(()->preferences.saveBranding(new PreferenceContracts.SaveBranding("机构品牌","https://example.com/logo.png","#abcdef","a@example.com",null,0)));
        var cleared=tx(()->preferences.saveBranding(new PreferenceContracts.SaveBranding("机构品牌",null,"#abcdef",null,null,brand.revision())));
        assertNull(cleared.logoUrl());assertThrows(ApiException.class,()->tx(()->preferences.saveBranding(new PreferenceContracts.SaveBranding("旧修改",null,"#abcdef",null,null,brand.revision()))));
        assertThrows(ApiException.class,()->tx(()->preferences.savePersonal(new PreferenceContracts.SavePersonal("dark","en-US",50,true,0))));
        tx(()->preferences.savePersonal(new PreferenceContracts.SavePersonal("dark","zh-CN",50,true,0)));
        assertEquals("dark",preferences.personal().theme());
        subject.set(new FoundationAccess.Subject(false,"22","202"));TenantContext.setTenantId("22");
        assertEquals("system",preferences.personal().theme());assertEquals("第二机构",preferences.branding().displayName());
        assertNull(sql.getMapper(PreferenceMapper.class).branding("11"));
    }

    /** 功能现行撤权、精确ID、覆盖清除与文件/任务真实计数都走数据库。 */
    @Test void currentFeatureOverridesQuotaInheritanceAndRealUsageAreAuthoritative() {
        assertTrue(features.allowed("agents"));
        subject.set(new FoundationAccess.Subject(true,null,"901"));TenantContext.clear();
        assertEquals("9007199254740993",entitlements.plans().getFirst().id());
        tx(()->{entitlements.saveTenantGrant(new EntitlementContracts.SaveTenantGrant("11","agents",false,0));return null;});
        assertFalse(features.allowed("11","agents"));
        tx(()->{entitlements.saveTenantGrant(new EntitlementContracts.SaveTenantGrant("11","agents",null,1));return null;});
        assertTrue(features.allowed("11","agents"));
        tx(()->entitlements.saveTenantQuota(new EntitlementContracts.SaveTenantQuota("11",new EntitlementContracts.Limits(0L,null,null,null,null,null,null,null),1)));
        subject.set(new FoundationAccess.Subject(false,"11","101"));TenantContext.setTenantId("11");
        assertEquals("resource_quota_exceeded",assertThrows(ApiException.class,()->tx(()->{quota.requireCapacity(ResourceKind.AGENT,1);return null;})).getCode());
        assertThrows(ApiException.class,()->tx(()->{quota.requireCapacity("22",ResourceKind.AGENT,1);return null;}));
        assertThrows(IllegalStateException.class,()->quota.requireCapacity(ResourceKind.AGENT,1));
        jdbc.update("INSERT INTO nw_foundation_files VALUES(1,11,50,'ready'),(2,11,20,'deleting'),(3,11,80,'deleted')");
        jdbc.update("INSERT INTO nw_foundation_admin_jobs VALUES(1,11,'queued'),(2,11,'running'),(3,11,'succeeded')");
        var inspected=tx(()->entitlements.inspect(null));
        assertEquals("70",inspected.usage().stream().filter(u->u.kind()==ResourceKind.FILE_BYTES).findFirst().orElseThrow().used());
        assertEquals("2",inspected.usage().stream().filter(u->u.kind()==ResourceKind.ADMIN_TASK).findFirst().orElseThrow().used());
        assertThrows(ApiException.class,()->tx(()->{quota.requireCapacity(ResourceKind.FILE_BYTES,31);return null;}));
        assertThrows(ApiException.class,()->tx(()->{quota.requireCapacity(ResourceKind.ADMIN_TASK,1);return null;}));
    }

    /** V332 测试归属排除同时影响 Agent/Workflow 容量，普通资源仍受当前真实名额限制。 */
    @Test void workflowTestResourcesDoNotConsumeOrdinaryAgentOrWorkflowQuota() throws Exception {
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var statement=connection.createStatement()) {
            statement.execute("""
                    INSERT INTO nw_agents VALUES(301,11),(303,22);
                    INSERT INTO nw_workflow_containers VALUES(401,11),(403,22);
                    INSERT INTO nw_workflow_test_sessions VALUES(11,301,401),(22,303,403);
                    """);
        }
        assertEquals(0,sql.getMapper(EntitlementMapper.class).used("11","AGENT"));
        assertEquals(0,sql.getMapper(EntitlementMapper.class).used("11","WORKFLOW"));
        tx(()->{quota.requireCapacity(ResourceKind.AGENT,1);jdbc.update("INSERT INTO nw_agents VALUES(302,11)");return null;});
        assertEquals(1,sql.getMapper(EntitlementMapper.class).used("11","AGENT"));
        var blocked=assertThrows(ApiException.class,()->tx(()->{quota.requireCapacity(ResourceKind.AGENT,1);return null;}));
        assertEquals("resource_quota_exceeded",blocked.getCode());
    }

    /** 同时创建竞争最后一个名额：租户与套餐行锁保持到写入提交，恰好一个成功。 */
    @Test void concurrentCreatorsCannotExceedTheRealResourceLimit() throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->createCompetingAgent(1,ready,start));var second=executor.submit(()->createCompetingAgent(2,ready,start));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            var outcomes=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter("created"::equals).count());assertEquals(1,outcomes.stream().filter("resource_quota_exceeded"::equals).count());
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM nw_agents WHERE tenant_id=11",Integer.class));
    }

    /** 旧机构资料入口切换套餐必须使已读取的配额CAS失效，同值保存不会伪造修订。 */
    @Test void planSwitchInvalidatesQuotaCasOnlyWhenThePlanActuallyChanges() {
        subject.set(new FoundationAccess.Subject(true,null,"901"));TenantContext.clear();
        jdbc.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,is_active) VALUES(902,'another-plan','另一套餐',true)");
        jdbc.update("UPDATE nw_tenants SET plan_id=902 WHERE id=11");
        assertEquals(2,jdbc.queryForObject("SELECT foundation_quota_revision FROM nw_tenants WHERE id=11",Integer.class));
        jdbc.update("UPDATE nw_tenants SET plan_id=902 WHERE id=11");
        assertEquals(2,jdbc.queryForObject("SELECT foundation_quota_revision FROM nw_tenants WHERE id=11",Integer.class));
        var unlimited=new EntitlementContracts.Limits(null,null,null,null,null,null,null,null);
        assertThrows(ApiException.class,()->tx(()->entitlements.saveTenantQuota(new EntitlementContracts.SaveTenantQuota("11",unlimited,1))));
    }

    /** 明确竞争起点，线程结束清理租户，不用固定sleep制造锁竞争。 */
    private String createCompetingAgent(long id,CountDownLatch ready,CountDownLatch start) throws Exception {
        TenantContext.setTenantId("11");ready.countDown();
        try {assertTrue(start.await(10,TimeUnit.SECONDS));return tx(()->{quota.requireCapacity(ResourceKind.AGENT,1);jdbc.update("INSERT INTO nw_agents VALUES(?,11)",id);return "created";});}
        catch(ApiException error) {return error.getCode();}
        finally {TenantContext.clear();}
    }
    /** 数据库服务直接构造时显式提供真实Spring事务，验证锁不在检查后提前释放。 */
    private static <T> T tx(Supplier<T> operation) {return new TransactionTemplate(transactions).execute(status->operation.get());}
}

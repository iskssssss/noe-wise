package com.skillforge.platform.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.mapper.AgentTemplateMapper;
import com.skillforge.core.agent.dto.request.*;
import com.skillforge.core.agent.dto.query.AgentTemplateQuery;
import com.skillforge.core.agent.dto.response.AdminAgentTemplateItemResponse;
import com.skillforge.core.agent.model.*;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.platform.service.AgentTemplateAdminService;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 独立完整迁移的真实PG验证定义锁、持久原回执、审计原子性及RLS，不把Mock分支当事务证据。 */
@Testcontainers(disabledWithoutDocker=true)
class AgentDefinitionLifecycleDatabaseTest {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("definition_lifecycle").withUsername("skillforge").withPassword("synthetic_test_password");
    private static JdbcTemplate owner;
    private static AgentTemplateMapper mapper;
    private static AgentTemplateMapper restrictedMapper;
    private static AuditService restrictedAudit;
    private static DriverManagerDataSource restrictedSource;
    private static AgentTemplateService templates;
    private static PlatformModelCatalogService models;
    private static OperationalSettingsService settings;
    private static AgentTemplateAdminService service;
    private static TransactionTemplate tx;

    /**
     * 平台事务沿正式宿主使用专用NOSUPERUSER/BYPASSRLS角色，真实写入平台审计。
     * 机构隔离探针另用NOBYPASSRLS角色；不为测试放宽审计策略或模拟审计写入。
     */
    @BeforeAll static void initialize() throws Exception {
        var administrator=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(administrator).locations("classpath:db/migration").load().migrate();owner=new JdbcTemplate(administrator);
        owner.execute("CREATE ROLE definition_lifecycle_test LOGIN PASSWORD 'synthetic_restricted_password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("CREATE ROLE definition_lifecycle_platform_test LOGIN PASSWORD 'synthetic_platform_password' NOSUPERUSER BYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO definition_lifecycle_test,definition_lifecycle_platform_test");
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO definition_lifecycle_test,definition_lifecycle_platform_test");
        var application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),"definition_lifecycle_platform_test","synthetic_platform_password");
        var restricted=new DriverManagerDataSource(DATABASE.getJdbcUrl(),"definition_lifecycle_test","synthetic_restricted_password");
        restrictedSource=restricted;
        assertThat(new JdbcTemplate(application).queryForMap("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user"))
                .containsEntry("rolsuper",false).containsEntry("rolbypassrls",true);
        assertThat(new JdbcTemplate(restricted).queryForMap("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user"))
                .containsEntry("rolsuper",false).containsEntry("rolbypassrls",false);
        var sessions=mybatis(application);mapper=sessions.getMapper(AgentTemplateMapper.class);
        var restrictedSessions=mybatis(restricted);restrictedMapper=restrictedSessions.getMapper(AgentTemplateMapper.class);
        restrictedAudit=new AuditService(restrictedSessions.getMapper(AuditLogMapper.class));
        var json=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        models=mock(PlatformModelCatalogService.class);
        var model=mock(PlatformModelConfig.class);
        when(model.maxOutputTokens()).thenReturn(2048);when(model.timeoutSeconds()).thenReturn(120);
        when(models.requireEnabled("1","LLM")).thenReturn(model);
        settings=mock(OperationalSettingsService.class);
        when(settings.read("calls")).thenReturn(new OperationalSettingsService.Snapshot(1,Map.of("chat_timeout_seconds",120)));
        templates=new AgentTemplateService(models,mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json,settings);
        ReflectionTestUtils.setField(templates,"baseMapper",mapper);
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true,null,"7"));
        service=new AgentTemplateAdminService(new com.skillforge.core.agent.AgentDefinitionLifecycleService(templates,mock(PlatformManagementService.class),access,json,new AuditService(sessions.getMapper(AuditLogMapper.class)),mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class)));
        tx=new TransactionTemplate(new DataSourceTransactionManager(application));
        owner.execute("CREATE FUNCTION reject_definition_test_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.resource_type='agent_definition' THEN RAISE EXCEPTION 'controlled_definition_audit_failure'; END IF; RETURN NEW; END $$");
    }
    /** 两种正式数据库角色使用同一真实Mapper/租户拦截器，角色能力差异不由Mock或SQL策略特例模拟。 */
    private static SqlSessionTemplate mybatis(DriverManagerDataSource source) throws Exception {
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(new TenantRlsInterceptor());
        var paging=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        paging.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.POSTGRE_SQL));
        configuration.addInterceptor(paging);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("definition-lifecycle",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),source));
        configuration.addMapper(AuditLogMapper.class);
        try(var input=new ClassPathResource("mapper/AgentTemplateMapper.xml").getInputStream()){
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,configuration,"mapper/AgentTemplateMapper.xml",configuration.getSqlFragments()).parse();
        }
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
    }
    /** 本类独占容器的数据基线，TRUNCATE不改变外部环境或已部署库。 */
    @BeforeEach void fixture(){owner.execute("TRUNCATE nw_agent_templates CASCADE");TenantContext.clear();}
    /** 每个请求完成必须还原线程租户。 */ @AfterEach void clear(){TenantContext.clear();}

    /** 资格在真实COUNT/分页前过滤，201项较新草稿不能遮住第二页已发布源；受限角色仍排除其他owner。 */
    @Test void distributionAndNameFilterBeforePagingAcrossMoreThanTwoHundredDefinitions() {
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'共享源机构','source-seven',1),(8,'受让机构','recipient-eight',1) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_tenants SET status='active' WHERE id IN(7,8)");
        var json=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var tenantTemplates=new AgentTemplateService(models,mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json,settings);
        ReflectionTestUtils.setField(tenantTemplates,"baseMapper",restrictedMapper);
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","9"));
        var lifecycle=new com.skillforge.core.agent.AgentDefinitionLifecycleService(tenantTemplates,mock(PlatformManagementService.class),access,json,restrictedAudit,
                mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        var tenantTx=new TransactionTemplate(new DataSourceTransactionManager(restrictedSource));TenantContext.setTenantId("7");
        var seed=write(tenantTx,()->lifecycle.create(new CreateAgentTemplateRequest("needle-zero",null,List.of(),null,null,"free",0,
                definition("private-list-source"),AgentDistributionPolicy.AUTHORIZED_DEPLOY),"list-seed"));
        write(tenantTx,()->lifecycle.publish(seed.id(),1,"list-publish"));
        owner.update("UPDATE nw_agent_templates SET created_at='2019-01-01T00:00:00Z' WHERE id=?",seed.id());
        owner.update("""
                INSERT INTO nw_agent_templates(id,template_key,name,version,definition,price_type,price_cents,install_count,
                    status,distribution_policy,owner_kind,owner_tenant_id,draft_revision,has_unpublished_changes,published_at,created_at,updated_at)
                SELECT 700000+g,'list-page-'||g,CASE WHEN g=223 THEN 'other-eligible' ELSE 'needle-'||g END,
                    version,definition,price_type,price_cents,0,
                    CASE WHEN g BETWEEN 21 AND 221 THEN 'draft' WHEN g=222 THEN 'deprecated' ELSE 'published' END,
                    CASE WHEN g=225 THEN 'OPEN_INSTALL' ELSE 'AUTHORIZED_DEPLOY' END,
                    CASE WHEN g=225 THEN 'platform' ELSE 'tenant' END,
                    CASE WHEN g=225 THEN NULL WHEN g=224 THEN 8 ELSE 7 END,
                    1,g BETWEEN 21 AND 221,
                    CASE WHEN g BETWEEN 21 AND 221 THEN NULL ELSE published_at END,
                    CASE WHEN g BETWEEN 21 AND 221 THEN TIMESTAMPTZ '2021-01-01' ELSE TIMESTAMPTZ '2020-01-01'+g*INTERVAL '1 second' END,
                    now()
                FROM nw_agent_templates CROSS JOIN generate_series(1,225) AS entries(g) WHERE id=?
                """,seed.id());
        for(var published:mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentTemplateDO>()
                .between(AgentTemplateDO::getId,700001L,700225L).isNotNull(AgentTemplateDO::getPublishedAt))) {
            templates.appendPublication(published);
        }

        var first=write(tenantTx,()->lifecycle.list(new AgentTemplateQuery(1,20,"published",null," needle ",AgentDistributionPolicy.AUTHORIZED_DEPLOY)));
        var second=write(tenantTx,()->lifecycle.list(new AgentTemplateQuery(2,20,"published",null," needle ",AgentDistributionPolicy.AUTHORIZED_DEPLOY)));
        assertThat(first.total()).isEqualTo(21);assertThat(second.total()).isEqualTo(21);
        assertThat(first.items()).hasSize(20);assertThat(second.items()).extracting(AdminAgentTemplateItemResponse::id).containsExactly(seed.id());
        var combined=new ArrayList<>(first.items());combined.addAll(second.items());
        assertThat(combined).extracting(AdminAgentTemplateItemResponse::id).doesNotHaveDuplicates();
        assertThat(combined).allSatisfy(item->{assertThat(item.status()).isEqualTo("published");assertThat(item.distributionPolicy()).isEqualTo(AgentDistributionPolicy.AUTHORIZED_DEPLOY);assertThat(item.ownerTenantId()).isEqualTo(7L);});
        assertThat(write(tenantTx,()->lifecycle.list(new AgentTemplateQuery(1,20,"published",null,null,AgentDistributionPolicy.AUTHORIZED_DEPLOY))).total()).isEqualTo(22);
        assertThat(write(tenantTx,()->lifecycle.list(new AgentTemplateQuery(1,20,"published",null,"needle",AgentDistributionPolicy.OPEN_INSTALL))).total()).isZero();
        assertThat(write(tenantTx,()->lifecycle.list(new AgentTemplateQuery(1,20,"published",null,null,null))).total()).isEqualTo(22);
        assertThat(write(tenantTx,()->lifecycle.list(new AgentTemplateQuery(1,200,null,null,null,null))).total()).isEqualTo(224);
    }

    /** 同key并发创建只落一个对象和一项审计，两个调用都拿到同一原响应。 */
    @Test void concurrentCreateCommitsOneDefinitionAndOneOriginalReceipt() throws Exception {
        var results=concurrent(()->write(()->service.create(create(null,null),"create")),()->write(()->service.create(create("free",0),"create")));
        assertThat(results.get(0)).isEqualTo(results.get(1));assertThat(count("nw_agent_templates")).isEqualTo(1);
        assertThat(count("nw_agent_definition_operations")).isEqualTo(1);assertThat(audits(results.get(0).id())).isEqualTo(1);
    }

    /** 四种生命周期同键并发只提交一次，真实行锁承担串行化，没有测试专用互斥器。 */
    @ParameterizedTest @ValueSource(strings={"update","publish","deprecate","restore"})
    void concurrentSameOperationReturnsOneDurableResult(String operation) throws Exception {
        var c=write(()->service.create(create(null,null),"create"));
        if(Set.of("deprecate","restore").contains(operation))write(()->service.publish(c.id(),1,"initial-publish"));
        long receipts=count("nw_agent_definition_operations"),audit=audits(c.id());
        Supplier<AdminAgentTemplateItemResponse> operationCall=()->write(()->operation(operation,c.id(),1,"same"));
        var results=concurrent(operationCall,operationCall);
        assertThat(results.get(0)).isEqualTo(results.get(1));assertThat(count("nw_agent_definition_operations")).isEqualTo(receipts+1);
        assertThat(audits(c.id())).isEqualTo(audit+1);
        assertThat(templates.getById(c.id()).getDraftRevision()).isEqualTo("publish".equals(operation)?1:2);
        assertThat(publications(c.id())).isEqualTo("update".equals(operation)?0:1);
    }

    /** 两个竞争者用同key提交不同内容，落库一次而另一个准确409。 */
    @Test void concurrentDifferentPayloadRejectsWithoutOverwritingWinner() throws Exception {
        var c=write(()->service.create(create(null,null),"create"));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->compete(start,c.id(),"one"));var second=pool.submit(()->compete(start,c.id(),"two"));start.countDown();
            var values=List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
            assertThat(values).filteredOn(v->v instanceof AdminAgentTemplateItemResponse).hasSize(1);
            assertThat(values).filteredOn(v->v instanceof ApiException e&&e.getStatus().value()==409).hasSize(1);
        }
        assertThat(templates.getById(c.id()).getDraftRevision()).isEqualTo(2);assertThat(count("nw_agent_definition_operations")).isEqualTo(2);
    }

    /** 后续全部状态变化不能改写create/edit/publish/deprecate/restore的任一原安全响应。 */
    @Test void originalsSurviveLaterLifecycleAndRestorationDoesNotMovePublishedPointer() {
        var c=write(()->service.create(create(null,null),"create"));var u=write(()->service.update(c.id(),1,update("changed"),"update"));
        var p=write(()->service.publish(c.id(),2,"publish"));var d=write(()->service.deprecate(c.id(),2,"down"));
        var r=write(()->service.restore(c.id(),3,"1.0.0","restore"));assertThat(r.version()).isEqualTo("1.0.0");
        var current=write(()->service.publish(c.id(),4,"publish-next"));assertThat(current.version()).isEqualTo("1.1.0");
        assertThat(write(()->service.create(create("free",0),"create"))).isEqualTo(c);
        assertThat(write(()->service.update(c.id(),1,update("changed"),"update"))).isEqualTo(u);
        assertThat(write(()->service.publish(c.id(),2,"publish"))).isEqualTo(p);
        assertThat(write(()->service.deprecate(c.id(),2,"down"))).isEqualTo(d);
        assertThat(write(()->service.restore(c.id(),3,"1.0.0","restore"))).isEqualTo(r);
        assertThat(templates.getById(c.id()).getVersion()).isEqualTo("1.1.0");assertThat(templates.getById(c.id()).getStatus()).isEqualTo("published");
        assertThat(publications(c.id())).isEqualTo(2);assertThat(count("nw_agent_definition_operations")).isEqualTo(6);
    }

    /** 真实MyBatis恢复必须把旧版NULL重新写回，并保留恢复前发布头、恢复原回执和新版本NULL快照。 */
    @Test void restoringNullableMetadataClearsNewValuesAndFreezesNullInNextPublication() {
        var c=write(()->service.create(new CreateAgentTemplateRequest("helper",null,List.of(),null,null,"free",0,
                definition("first"),AgentDistributionPolicy.OPEN_INSTALL),"create-null"));
        write(()->service.publish(c.id(),1,"publish-null"));
        var filled=new UpdateAgentTemplateRequest(null,"category-new",null,"description-new","https://example.test/new.svg",null,null,definition("second"),null);
        write(()->service.update(c.id(),1,filled,"fill"));
        var second=write(()->service.publish(c.id(),2,"publish-filled"));
        assertThat(second.version()).isEqualTo("1.1.0");assertThat(second.category()).isEqualTo("category-new");

        var restored=write(()->service.restore(c.id(),2,"1.0.0","restore-null"));
        assertThat(restored.category()).isNull();assertThat(restored.description()).isNull();assertThat(restored.iconUrl()).isNull();
        var draft=templates.getById(c.id());
        assertThat(draft.getCategory()).isNull();assertThat(draft.getDescription()).isNull();assertThat(draft.getIconUrl()).isNull();
        assertThat(draft.getVersion()).isEqualTo("1.1.0");assertThat(draft.getPublishedAt()).isEqualTo(second.publishedAt());
        assertThat(templates.currentPublished(c.id()).getCategory()).isEqualTo("category-new");
        assertThat(publications(c.id())).isEqualTo(2);

        var next=write(()->service.publish(c.id(),3,"publish-restored"));
        assertThat(next.version()).isEqualTo("1.2.0");var frozen=templates.currentPublished(c.id());
        assertThat(frozen.getCategory()).isNull();assertThat(frozen.getDescription()).isNull();assertThat(frozen.getIconUrl()).isNull();
        assertThat(frozen.getDefinition().systemPrompt()).isEqualTo("first");
        write(()->service.update(c.id(),3,filled,"edit-after-restore"));
        long receipts=count("nw_agent_definition_operations"),audit=audits(c.id());
        assertThat(write(()->service.restore(c.id(),2,"1.0.0","restore-null"))).isEqualTo(restored);
        assertThat(templates.getById(c.id()).getCategory()).isEqualTo("category-new");
        assertThat(count("nw_agent_definition_operations")).isEqualTo(receipts);assertThat(audits(c.id())).isEqualTo(audit);
        assertThat(templates.currentPublished(c.id()).getVersion()).isEqualTo("1.2.0");assertThat(publications(c.id())).isEqualTo(3);
    }

    /** 原响应写入后审计失败，五个操作的业务行/发布/回执均真实回滚；原key未被消费。 */
    @ParameterizedTest @ValueSource(strings={"create","update","publish","deprecate","restore"})
    void auditFailureRollsBackAllFactsAndLeavesKeyAvailable(String operation) {
        Long id=null;
        if(!"create".equals(operation)) {
            id=write(()->service.create(create(null,null),"seed")).id();
            if(Set.of("deprecate","restore").contains(operation)){Long target=id;write(()->service.publish(target,1,"seed-publish"));}
        }
        Long target=id;Map<String,Object> before=id==null?null:state(id);
        long definitions=count("nw_agent_templates"),receipts=count("nw_agent_definition_operations"),versions=count("nw_agent_template_publications");
        owner.execute("CREATE TRIGGER reject_definition_audit BEFORE INSERT ON nw_audit_logs FOR EACH ROW EXECUTE FUNCTION reject_definition_test_audit()");
        try {assertThatThrownBy(()->write(()->"create".equals(operation)?service.create(create(null,null),"failure"):operation(operation,target,1,"failure"))).isInstanceOf(RuntimeException.class);}
        finally {owner.execute("DROP TRIGGER reject_definition_audit ON nw_audit_logs");}
        assertThat(count("nw_agent_templates")).isEqualTo(definitions);assertThat(count("nw_agent_definition_operations")).isEqualTo(receipts);assertThat(count("nw_agent_template_publications")).isEqualTo(versions);
        if(id!=null)assertThat(state(id)).isEqualTo(before);
        assertThat(write(()->"create".equals(operation)?service.create(create(null,null),"failure"):operation(operation,target,1,"failure"))).isNotNull();
    }

    /** 发布与编辑的真实排序只产生合法结果：先发布再编辑可均成功，先编辑则旧发布CAS冲突。 */
    @Test void publishAndEditRaceCannotPublishAnotherDraft() throws Exception {
        var c=write(()->service.create(create(null,null),"seed"));var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var edit=pool.submit(()->{await(start);return write(()->service.update(c.id(),1,update("next"),"edit"));});
            var publish=pool.submit(()->{await(start);try{return (Object)write(()->service.publish(c.id(),1,"publish"));}catch(ApiException conflict){return conflict;}});
            start.countDown();edit.get(30,TimeUnit.SECONDS);Object result=publish.get(30,TimeUnit.SECONDS);
            assertThat(templates.getById(c.id()).getDraftRevision()).isEqualTo(2);
            if(result instanceof ApiException conflict){assertThat(conflict.getStatus().value()).isEqualTo(409);assertThat(publications(c.id())).isZero();}
            else {assertThat(publications(c.id())).isEqualTo(1);assertThat(templates.currentPublished(c.id()).getDefinition().systemPrompt()).isEqualTo("first");}
        }
    }

    /** 平台原回执不可改写，机构RLS下不可读取或插入。 */
    @Test void operationReceiptsAreImmutableAndInvisibleInInstitutionScope() {
        var c=write(()->service.create(create(null,null),"create"));
        assertThatThrownBy(()->owner.update("UPDATE nw_agent_definition_operations SET fingerprint=repeat('a',64) WHERE definition_id=?",c.id())).isInstanceOf(RuntimeException.class);
        TenantContext.setTenantId("7");
        assertThat(restrictedMapper.definitionOperation("platform_member:7","create","new","create")).isNull();
        assertThatThrownBy(()->restrictedMapper.appendDefinitionOperation(new AgentDefinitionOperationRow("platform_member:7","create","new","forbidden",
                "a".repeat(64),c.id(),"{\"definition_id\":\""+c.id()+"\"}"))).isInstanceOf(RuntimeException.class);
        TenantContext.clear();
        assertThatThrownBy(()->restrictedAudit.record(null,"platform_member:7","platform.agent_definitions.create","agent_definition",c.id().toString(),
                "受限角色不得写平台操作审计",Map.of())).hasStackTraceContaining("row-level security");
        assertThat(write(()->service.create(create(null,null),"create"))).isEqualTo(c);
    }

    @Test void institutionSaveReceiptsAreReadOnlyOriginalActorScopedAndOwnerIsolated() {
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'共享源机构','source-seven',1),(8,'其他机构','recipient-eight',1) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_tenants SET status='active' WHERE id IN(7,8)");
        var json=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var tenantTemplates=new AgentTemplateService(models,mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json,settings);
        ReflectionTestUtils.setField(tenantTemplates,"baseMapper",restrictedMapper);
        var subject=new java.util.concurrent.atomic.AtomicReference<>(new FoundationAccess.Subject(false,"7","9"));
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenAnswer(call->subject.get());
        var lifecycle=new com.skillforge.core.agent.AgentDefinitionLifecycleService(tenantTemplates,mock(PlatformManagementService.class),access,json,restrictedAudit,
                mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        var tenantTx=new TransactionTemplate(new DataSourceTransactionManager(restrictedSource));TenantContext.setTenantId("7");
        var request=new CreateAgentTemplateRequest("original",null,List.of(),null,null,"free",0,definition("first"),AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        var created=write(tenantTx,()->lifecycle.create(request,"create-key"));
        var updated=write(tenantTx,()->lifecycle.update(created.id(),1,new UpdateAgentTemplateRequest("updated",null,null,null,null,null,null,null,null),"update-key"));
        write(tenantTx,()->lifecycle.update(created.id(),2,new UpdateAgentTemplateRequest("later",null,null,null,null,null,null,null,null),"later-key"));
        var other=write(tenantTx,()->lifecycle.create(request,"other-key"));
        long receipts=count("nw_agent_definition_operations"),audits=audits(created.id());
        assertThat(write(tenantTx,()->lifecycle.update(created.id(),1,new UpdateAgentTemplateRequest("updated",null,null,null,null,null,null,null,null),"update-key"))).isEqualTo(updated);
        assertThatThrownBy(()->write(tenantTx,()->lifecycle.update(created.id(),1,new UpdateAgentTemplateRequest("different",null,null,null,null,null,null,null,null),"update-key")))
                .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.getStatus().value()).isEqualTo(409);assertThat(e.getCode()).isNotEqualTo("AGENT_DEFINITION_REVISION_CONFLICT");});
        assertThatThrownBy(()->write(tenantTx,()->lifecycle.update(created.id(),1,new UpdateAgentTemplateRequest("stale",null,null,null,null,null,null,null,null),"stale-key")))
                .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.getStatus().value()).isEqualTo(409);assertThat(e.getCode()).isEqualTo("AGENT_DEFINITION_REVISION_CONFLICT");});
        assertThat(restrictedMapper.definitionOperation("tenant:7:member:9","update",created.id().toString(),"stale-key")).isNull();
        assertThat(tenantTemplates.getById(created.id()).getDraftRevision()).isEqualTo(3);
        assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("create","create-key",null))).isEqualTo(created);
        assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("update","update-key",created.id()))).isEqualTo(updated);
        assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("update","missing",created.id()))).isNull();
        assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("update","update-key",other.id()))).isNull();
        assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("create","update-key",null))).isNull();
        assertThatThrownBy(()->write(tenantTx,()->lifecycle.tenantSaveReceipt("create","create-key",created.id()))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(400));
        assertThatThrownBy(()->write(tenantTx,()->lifecycle.tenantSaveReceipt("update","update-key",null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(400));
        subject.set(new FoundationAccess.Subject(false,"7","10"));
        assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("create","create-key",null))).isNull();
        assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("update","update-key",created.id()))).isNull();
        subject.set(new FoundationAccess.Subject(false,"8","9"));TenantContext.setTenantId("8");
        assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("create","create-key",null))).isNull();
        assertThatThrownBy(()->write(tenantTx,()->lifecycle.tenantSaveReceipt("update","update-key",created.id()))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(404));
        subject.set(new FoundationAccess.Subject(false,"7","9"));TenantContext.setTenantId("7");
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"禁止读取")).when(access).require("tenant.agent_definitions.read");
        assertThatThrownBy(()->write(tenantTx,()->lifecycle.tenantSaveReceipt("create","create-key",null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(403));
        assertThat(count("nw_agent_definition_operations")).isEqualTo(receipts);assertThat(audits(created.id())).isEqualTo(audits);
    }

    @Test void uncommittedSaveReceiptReturnsUnknownWithoutBlockingThenReadsCommittedResult() throws Exception {
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'共享源机构','source-seven',1) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_tenants SET status='active' WHERE id=7");
        var json=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var tenantTemplates=new AgentTemplateService(models,mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json,settings);
        ReflectionTestUtils.setField(tenantTemplates,"baseMapper",restrictedMapper);
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","9"));
        var lifecycle=new com.skillforge.core.agent.AgentDefinitionLifecycleService(tenantTemplates,mock(PlatformManagementService.class),access,json,restrictedAudit,
                mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        var tenantTx=new TransactionTemplate(new DataSourceTransactionManager(restrictedSource));
        var request=new CreateAgentTemplateRequest("in-flight",null,List.of(),null,null,"free",0,definition("first"),AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        var written=new CountDownLatch(1);var allowCommit=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var saving=pool.submit(()->{
                TenantContext.setTenantId("7");
                try {return write(tenantTx,()->{var result=lifecycle.create(request,"in-flight-key");written.countDown();await(allowCommit);return result;});}
                finally {TenantContext.clear();}
            });
            try {
                assertThat(written.await(10,TimeUnit.SECONDS)).isTrue();
                var lookup=pool.submit(()->{TenantContext.setTenantId("7");try{return write(tenantTx,()->lifecycle.tenantSaveReceipt("create","in-flight-key",null));}finally{TenantContext.clear();}});
                assertThat(lookup.get(3,TimeUnit.SECONDS)).isNull();
            } finally {allowCommit.countDown();}
            var committed=saving.get(10,TimeUnit.SECONDS);
            TenantContext.setTenantId("7");
            assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("create","in-flight-key",null))).isEqualTo(committed);
            assertThat(owner.queryForObject("SELECT count(*) FROM nw_agent_definition_operations WHERE request_key='in-flight-key'",Long.class)).isEqualTo(1);
            assertThat(audits(committed.id())).isEqualTo(1);
        }
    }

    @Test void institutionSaveReceiptSeparatesServicePrincipalsAndChecksCurrentScope() {
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'共享源机构','source-seven',1),(8,'其他机构','recipient-eight',1) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_tenants SET status='active' WHERE id IN(7,8)");
        var json=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var tenantTemplates=new AgentTemplateService(models,mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json,settings);
        ReflectionTestUtils.setField(tenantTemplates,"baseMapper",restrictedMapper);
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","9"));
        var integrations=mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class);
        doAnswer(call->{var identity=(com.skillforge.common.context.ServicePrincipalContext.Identity)call.getArgument(0);String scope=call.getArgument(1);
            if(!identity.scopes().contains(scope))throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"缺少权限");return null;}).when(integrations).requirePrincipal(any(),anyString());
        var lifecycle=new com.skillforge.core.agent.AgentDefinitionLifecycleService(tenantTemplates,mock(PlatformManagementService.class),access,json,restrictedAudit,integrations);
        var tenantTx=new TransactionTemplate(new DataSourceTransactionManager(restrictedSource));TenantContext.setTenantId("7");
        var request=new CreateAgentTemplateRequest("service",null,List.of(),null,null,"free",0,definition("first"),AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        try {
            com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity("101","7","1",1,Set.of("tenant.agent_definitions.create","tenant.agent_definitions.read")));
            var created=write(tenantTx,()->lifecycle.create(request,"service-key"));
            assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("create","service-key",null))).isEqualTo(created);
            com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity("101","7","1",2,Set.of("tenant.agent_definitions.read")));
            assertThat(write(tenantTx,()->lifecycle.tenantFenceSave("create","service-fence",null)).state()).isEqualTo("FENCED");
            assertThat(write(tenantTx,()->lifecycle.tenantFenceSave("update","service-update-fence",created.id())).state()).isEqualTo("FENCED");
            assertThatThrownBy(()->write(tenantTx,()->lifecycle.create(request,"service-fence"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(403));
            com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity("102","7","1",1,Set.of("tenant.agent_definitions.read")));
            assertThat(write(tenantTx,()->lifecycle.tenantSaveReceipt("create","service-key",null))).isNull();
            assertThat(write(tenantTx,()->lifecycle.tenantSaveResolution("create","service-fence",null)).state()).isEqualTo("UNKNOWN");
            com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity("101","7","1",2,Set.of()));
            assertThatThrownBy(()->write(tenantTx,()->lifecycle.tenantSaveReceipt("create","service-key",null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(403));
            assertThatThrownBy(()->write(tenantTx,()->lifecycle.tenantFenceSave("create","no-scope",null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(403));
            com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity("101","8","1",1,Set.of("tenant.agent_definitions.read")));
            assertThatThrownBy(()->write(tenantTx,()->lifecycle.tenantSaveReceipt("create","service-key",null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(403));
        } finally {com.skillforge.common.context.ServicePrincipalContext.clear();}
    }

    @Test void saveFencePreventsLateCreateAndUpdateAndRemainsActorScoped() {
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'共享源机构','source-seven',1),(8,'其他机构','recipient-eight',1) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_tenants SET status='active' WHERE id IN(7,8)");
        var json=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var tenantTemplates=new AgentTemplateService(models,mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json,settings);
        ReflectionTestUtils.setField(tenantTemplates,"baseMapper",restrictedMapper);
        var subject=new java.util.concurrent.atomic.AtomicReference<>(new FoundationAccess.Subject(false,"7","9"));
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenAnswer(call->subject.get());
        var lifecycle=new com.skillforge.core.agent.AgentDefinitionLifecycleService(tenantTemplates,mock(PlatformManagementService.class),access,json,restrictedAudit,
                mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        var tenantTx=new TransactionTemplate(new DataSourceTransactionManager(restrictedSource));TenantContext.setTenantId("7");
        var request=new CreateAgentTemplateRequest("fenced",null,List.of(),null,null,"free",0,definition("first"),AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        assertThat(write(tenantTx,()->lifecycle.tenantSaveResolution("create","create-fence",null)).state()).isEqualTo("UNKNOWN");
        assertThat(write(tenantTx,()->lifecycle.tenantFenceSave("create","create-fence",null)).state()).isEqualTo("FENCED");
        assertThat(write(tenantTx,()->lifecycle.tenantFenceSave("create","create-fence",null)).state()).isEqualTo("FENCED");
        assertThat(write(tenantTx,()->lifecycle.tenantSaveResolution("create","create-fence",null)).state()).isEqualTo("FENCED");
        assertThatThrownBy(()->write(tenantTx,()->lifecycle.create(request,"create-fence")))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("AGENT_DEFINITION_SAVE_FENCED"));
        assertThat(count("nw_agent_templates")).isZero();assertThat(count("nw_agent_definition_operations")).isZero();
        subject.set(new FoundationAccess.Subject(false,"7","10"));
        assertThat(write(tenantTx,()->lifecycle.tenantSaveResolution("create","create-fence",null)).state()).isEqualTo("UNKNOWN");
        subject.set(new FoundationAccess.Subject(false,"8","9"));TenantContext.setTenantId("8");
        assertThat(write(tenantTx,()->lifecycle.tenantSaveResolution("create","create-fence",null)).state()).isEqualTo("UNKNOWN");
        assertThat(restrictedMapper.definitionSaveFenced(7,"tenant:7:member:9","create","new","create-fence")).isFalse();
        assertThatThrownBy(()->restrictedMapper.fenceDefinitionSave(7,"tenant:7:member:9","create","new","foreign-insert",null)).isInstanceOf(RuntimeException.class);
        subject.set(new FoundationAccess.Subject(false,"7","9"));TenantContext.setTenantId("7");
        var created=write(tenantTx,()->lifecycle.create(request,"created"));
        assertThat(write(tenantTx,()->lifecycle.tenantFenceSave("create","created",null)).receipt()).isEqualTo(created);
        assertThat(write(tenantTx,()->lifecycle.tenantSaveResolution("create","created",null)).state()).isEqualTo("COMMITTED");
        long audits=audits(created.id());
        assertThat(write(tenantTx,()->lifecycle.tenantFenceSave("update","update-fence",created.id())).state()).isEqualTo("FENCED");
        assertThatThrownBy(()->write(tenantTx,()->lifecycle.update(created.id(),1,update("late"),"update-fence")))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("AGENT_DEFINITION_SAVE_FENCED"));
        assertThat(tenantTemplates.getById(created.id()).getDraftRevision()).isEqualTo(1);
        assertThat(audits(created.id())).isEqualTo(audits);
        assertThat(write(tenantTx,()->lifecycle.update(created.id(),1,update("next"),"new-key")).draftRevision()).isEqualTo(2);
        assertThatThrownBy(()->write(tenantTx,()->lifecycle.create(new CreateAgentTemplateRequest("different",null,List.of(),null,null,"free",0,definition("other"),AgentDistributionPolicy.AUTHORIZED_DEPLOY),"created")))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(409));
    }

    /** 写入已持有原键锁时，显式封存须等待事务并读取提交的原回执。 */
    @Test void saveFenceWaitsForOriginalCommitAndReturnsItsReceipt() throws Exception {
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'共享源机构','source-seven',1) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_tenants SET status='active' WHERE id=7");
        var json=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var tenantTemplates=new AgentTemplateService(models,mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json,settings);
        ReflectionTestUtils.setField(tenantTemplates,"baseMapper",restrictedMapper);
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","9"));
        var lifecycle=new com.skillforge.core.agent.AgentDefinitionLifecycleService(tenantTemplates,mock(PlatformManagementService.class),access,json,restrictedAudit,
                mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        var tenantTx=new TransactionTemplate(new DataSourceTransactionManager(restrictedSource));
        var request=new CreateAgentTemplateRequest("pending",null,List.of(),null,null,"free",0,definition("first"),AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        var written=new CountDownLatch(1);var allowCommit=new CountDownLatch(1);var started=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var saving=pool.submit(()->{TenantContext.setTenantId("7");try{return write(tenantTx,()->{var result=lifecycle.create(request,"race-key");written.countDown();await(allowCommit);return result;});}finally{TenantContext.clear();}});
            try {
                assertThat(written.await(10,TimeUnit.SECONDS)).isTrue();
                var fencing=pool.submit(()->{TenantContext.setTenantId("7");try{started.countDown();return write(tenantTx,()->lifecycle.tenantFenceSave("create","race-key",null));}finally{TenantContext.clear();}});
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->fencing.get(500,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                allowCommit.countDown();var original=saving.get(10,TimeUnit.SECONDS);var result=fencing.get(10,TimeUnit.SECONDS);
                assertThat(result.state()).isEqualTo("COMMITTED");assertThat(result.receipt()).isEqualTo(original);
            } finally {allowCommit.countDown();}
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_agent_definition_save_fences WHERE request_key='race-key'",Long.class)).isZero();
    }

    /** 封存事务先拿锁时，晚到写入必须等提交后拒绝，同一原键没有业务副作用。 */
    @Test void originalSaveWaitsForFenceCommitAndCannotWriteAfterward() throws Exception {
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'共享源机构','source-seven',1) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_tenants SET status='active' WHERE id=7");
        var json=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var tenantTemplates=new AgentTemplateService(models,mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json,settings);
        ReflectionTestUtils.setField(tenantTemplates,"baseMapper",restrictedMapper);
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","9"));
        var lifecycle=new com.skillforge.core.agent.AgentDefinitionLifecycleService(tenantTemplates,mock(PlatformManagementService.class),access,json,restrictedAudit,
                mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        var tenantTx=new TransactionTemplate(new DataSourceTransactionManager(restrictedSource));
        var request=new CreateAgentTemplateRequest("late",null,List.of(),null,null,"free",0,definition("first"),AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        var fenced=new CountDownLatch(1);var allowCommit=new CountDownLatch(1);var started=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var fencing=pool.submit(()->{TenantContext.setTenantId("7");try{return write(tenantTx,()->{var result=lifecycle.tenantFenceSave("create","fence-first",null);fenced.countDown();await(allowCommit);return result;});}finally{TenantContext.clear();}});
            try {
                assertThat(fenced.await(10,TimeUnit.SECONDS)).isTrue();
                var saving=pool.submit(()->{TenantContext.setTenantId("7");try{started.countDown();return write(tenantTx,()->lifecycle.create(request,"fence-first"));}finally{TenantContext.clear();}});
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->saving.get(500,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                allowCommit.countDown();assertThat(fencing.get(10,TimeUnit.SECONDS).state()).isEqualTo("FENCED");
                assertThatThrownBy(()->saving.get(10,TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,error->
                        assertThat(error.getCause()).isInstanceOfSatisfying(ApiException.class,cause->assertThat(cause.getCode()).isEqualTo("AGENT_DEFINITION_SAVE_FENCED")));
            } finally {allowCommit.countDown();}
        }
        assertThat(count("nw_agent_templates")).isZero();assertThat(count("nw_agent_definition_operations")).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_agent_definition_save_fences WHERE request_key='fence-first'",Long.class)).isEqualTo(1);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> repeated=()->{TenantContext.setTenantId("7");try{await(start);return write(tenantTx,()->lifecycle.tenantFenceSave("create","parallel-fence",null)).state();}finally{TenantContext.clear();}};
            var first=pool.submit(repeated);var second=pool.submit(repeated);start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo("FENCED");assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo("FENCED");
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_agent_definition_save_fences WHERE request_key='parallel-fence'",Long.class)).isEqualTo(1);
    }

    /** 编辑锁先提交时返回原回执；封存先拿锁但回滚时，原编辑仍能提交。 */
    @Test void updateFenceSerializesWithOriginalAndRollbackDoesNotBlockWrite() throws Exception {
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'共享源机构','source-seven',1) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_tenants SET status='active' WHERE id=7");
        var json=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var tenantTemplates=new AgentTemplateService(models,mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json,settings);
        ReflectionTestUtils.setField(tenantTemplates,"baseMapper",restrictedMapper);
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","9"));
        var lifecycle=new com.skillforge.core.agent.AgentDefinitionLifecycleService(tenantTemplates,mock(PlatformManagementService.class),access,json,restrictedAudit,
                mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        var tenantTx=new TransactionTemplate(new DataSourceTransactionManager(restrictedSource));TenantContext.setTenantId("7");
        var request=new CreateAgentTemplateRequest("original",null,List.of(),null,null,"free",0,definition("first"),AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        var created=write(tenantTx,()->lifecycle.create(request,"update-seed"));TenantContext.clear();
        var updated=new CountDownLatch(1);var finishUpdate=new CountDownLatch(1);var started=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var saving=pool.submit(()->{TenantContext.setTenantId("7");try{return write(tenantTx,()->{var result=lifecycle.update(created.id(),1,update("second"),"update-race");updated.countDown();await(finishUpdate);return result;});}finally{TenantContext.clear();}});
            try {
                assertThat(updated.await(10,TimeUnit.SECONDS)).isTrue();
                var fencing=pool.submit(()->{TenantContext.setTenantId("7");try{started.countDown();return write(tenantTx,()->lifecycle.tenantFenceSave("update","update-race",created.id()));}finally{TenantContext.clear();}});
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->fencing.get(500,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                finishUpdate.countDown();var original=saving.get(10,TimeUnit.SECONDS);var result=fencing.get(10,TimeUnit.SECONDS);
                assertThat(result.state()).isEqualTo("COMMITTED");assertThat(result.receipt()).isEqualTo(original);
            } finally {finishUpdate.countDown();}
            var fenced=new CountDownLatch(1);var finishFence=new CountDownLatch(1);var writeStarted=new CountDownLatch(1);
            var rollback=pool.submit(()->{TenantContext.setTenantId("7");try{return tenantTx.execute(status->{var result=lifecycle.tenantFenceSave("update","rollback-key",created.id());fenced.countDown();await(finishFence);status.setRollbackOnly();return result;});}finally{TenantContext.clear();}});
            try {
                assertThat(fenced.await(10,TimeUnit.SECONDS)).isTrue();
                var late=pool.submit(()->{TenantContext.setTenantId("7");try{writeStarted.countDown();return write(tenantTx,()->lifecycle.update(created.id(),2,update("after-rollback"),"rollback-key"));}finally{TenantContext.clear();}});
                assertThat(writeStarted.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->late.get(500,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                finishFence.countDown();assertThat(rollback.get(10,TimeUnit.SECONDS).state()).isEqualTo("FENCED");
                assertThat(late.get(10,TimeUnit.SECONDS).draftRevision()).isEqualTo(3);
            } finally {finishFence.countDown();}
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_agent_definition_save_fences WHERE request_key IN ('update-race','rollback-key')",Long.class)).isZero();
        assertThat(audits(created.id())).isEqualTo(3);
    }

    /** 机构生命周期在真实受限连接下复用唯一engine：原回执、NULL恢复、owner隔离、不可变历史及停用守卫。 */
    @Test void institutionLifecycleUsesOwnReceiptsAndDoesNotDiscloseSourceDraftToRecipient() {
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'共享源机构','source-seven',1),(8,'受让机构','recipient-eight',1) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_tenants SET status='active' WHERE id IN(7,8)");
        var tenantJson=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var sourceTemplates=new AgentTemplateService(models,mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),tenantJson,settings);
        ReflectionTestUtils.setField(sourceTemplates,"baseMapper",restrictedMapper);
        var subject=new java.util.concurrent.atomic.AtomicReference<>(new FoundationAccess.Subject(false,"7","9"));
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenAnswer(call->subject.get());
        var lifecycle=new com.skillforge.core.agent.AgentDefinitionLifecycleService(sourceTemplates,mock(PlatformManagementService.class),access,tenantJson,restrictedAudit,
                mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        var tenantTx=new TransactionTemplate(new DataSourceTransactionManager(restrictedSource));TenantContext.setTenantId("7");
        var request=new CreateAgentTemplateRequest("shared",null,List.of(),null,null,"free",0,definition("private-source-prompt"),AgentDistributionPolicy.AUTHORIZED_DEPLOY);
        var created=write(tenantTx,()->lifecycle.create(request,"tenant-create"));
        assertThat(created.ownerTenantId()).isEqualTo(7L);write(tenantTx,()->lifecycle.publish(created.id(),1,"tenant-publish"));
        write(tenantTx,()->lifecycle.update(created.id(),1,new UpdateAgentTemplateRequest(null,"new-category",null,"new-description","new-icon",null,null,definition("private-new-prompt"),null),"tenant-edit"));
        write(tenantTx,()->lifecycle.publish(created.id(),2,"tenant-publish-next"));
        var restored=write(tenantTx,()->lifecycle.restore(created.id(),2,"1.0.0","tenant-restore"));
        assertThat(restored.category()).isNull();assertThat(restored.description()).isNull();assertThat(restored.iconUrl()).isNull();assertThat(restored.version()).isEqualTo("1.1.0");
        write(tenantTx,()->lifecycle.update(created.id(),3,update("later-private-prompt"),"tenant-edit-later"));
        assertThat(write(tenantTx,()->lifecycle.restore(created.id(),2,"1.0.0","tenant-restore"))).isEqualTo(restored);
        assertThat(restrictedMapper.definitionOperation("tenant:7:member:9","restore",created.id().toString(),"tenant-restore")).isNotNull();
        var safe=tenantJson.valueToTree(sourceTemplates.publicView(sourceTemplates.currentPublished(created.id())));
        assertThat(safe.has("definition")).isFalse();assertThat(safe.toString()).doesNotContain("system_prompt","private-new-prompt","execution_config","model_config_id");
        assertThat(sourceTemplates.publications(created.id())).hasSize(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE tenant_id=7 AND resource_type='agent_definition' AND resource_id=?",Long.class,created.id().toString())).isEqualTo(6L);
        subject.set(new FoundationAccess.Subject(false,"8","9"));TenantContext.setTenantId("8");
        assertThatThrownBy(()->lifecycle.detail(created.id())).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus().value()).isEqualTo(404));
        assertThat(restrictedMapper.definitionOperation("tenant:7:member:9","restore",created.id().toString(),"tenant-restore")).isNull();
        assertThatThrownBy(()->owner.update("UPDATE nw_agent_templates SET owner_tenant_id=8 WHERE id=?",created.id())).isInstanceOf(RuntimeException.class);
        owner.update("UPDATE nw_tenants SET status='suspended' WHERE id=7");
        assertThatThrownBy(()->sourceTemplates.requireCurrentDefinitionAvailable(created.id())).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus().value()).isEqualTo(403));
    }

    /** 捕获真实业务409，其他存储错误不能伪装成允许的竞争结果。 */
    private Object compete(CountDownLatch start,Long id,String prompt){await(start);try{return write(()->service.update(id,1,update(prompt),"same-key"));}catch(ApiException conflict){return conflict;}}
    /** 两个独立事务同时进入服务，不为生产锁提供额外测试锁。 */
    private List<AdminAgentTemplateItemResponse> concurrent(Supplier<AdminAgentTemplateItemResponse> first,Supplier<AdminAgentTemplateItemResponse> second)throws Exception {
        var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{await(start);return first.get();});var b=pool.submit(()->{await(start);return second.get();});start.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}
    }
    /** 测试入口拥有外层真实事务，正式服务实例不依赖测试专用构造器或运行分支。 */
    private static <T>T write(Supplier<T> work){return write(tx,work);}
    /** 平台及机构连接分别加入自己的真实事务，不能借平台连接绕过机构RLS。 */
    private static <T>T write(TransactionTemplate transaction,Supplier<T> work){return transaction.execute(status->work.get());}
    /** 声明同一动作的业务命令，参数指纹由被测服务正式产生。 */
    private static AdminAgentTemplateItemResponse operation(String operation,Long id,long expected,String key){return switch(operation){case "update"->service.update(id,expected,update("changed"),key);case "publish"->service.publish(id,expected,key);case "deprecate"->service.deprecate(id,expected,key);case "restore"->service.restore(id,expected,"1.0.0",key);default->throw new IllegalArgumentException(operation);};}
    /** 当前持久化状态用于失败前后的完整字段比较。 */
    private static Map<String,Object> state(Long id){return owner.queryForMap("SELECT * FROM nw_agent_templates WHERE id=?",id);}
    /** 只计本类独占库中的安全回执或业务事实。 */
    private static long count(String table){return owner.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    /** 按稳定定义查询本次实际审计事实。 */
    private static long audits(Long id){return owner.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE resource_type='agent_definition' AND resource_id=?",Long.class,id.toString());}
    /** 发布版本数量不从当前草稿推断。 */
    private static long publications(Long id){return owner.queryForObject("SELECT count(*) FROM nw_agent_template_publications WHERE template_id=?",Long.class,id);}
    /** 并发夹具拥有有界等待，异常或中断不能继续伪装成正常竞争。 */
    private static void await(CountDownLatch latch){try{if(!latch.await(20,TimeUnit.SECONDS))throw new AssertionError("并发起点超时");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}
    /** 明确默认值与缺省值对应同一实际创建内容。 */
    private static CreateAgentTemplateRequest create(String price,Integer cents){return new CreateAgentTemplateRequest(" helper ",null,null,"description",null,price,cents,definition("first"),AgentDistributionPolicy.OPEN_INSTALL);}
    /** 定义整体替换保留其余展示元数据。 */
    private static UpdateAgentTemplateRequest update(String prompt){return new UpdateAgentTemplateRequest(null,null,null,null,null,null,null,definition(prompt),null);}
    /** 不包含网络能力的可发布定义，目录边界由可信固定fixture提供。 */
    private static AgentTemplateDefinition definition(String prompt){return new AgentTemplateDefinition(prompt,"role","1",null,List.of(),List.of(),List.of());}
}

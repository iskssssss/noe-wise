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
        templates=new AgentTemplateService(mock(PlatformModelCatalogService.class),mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json);
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
        var tenantTemplates=new AgentTemplateService(mock(PlatformModelCatalogService.class),mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),json);
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

    /** 机构生命周期在真实受限连接下复用唯一engine：原回执、NULL恢复、owner隔离、不可变历史及停用守卫。 */
    @Test void institutionLifecycleUsesOwnReceiptsAndDoesNotDiscloseSourceDraftToRecipient() {
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'共享源机构','source-seven',1),(8,'受让机构','recipient-eight',1) ON CONFLICT(id) DO NOTHING");
        owner.update("UPDATE nw_tenants SET status='active' WHERE id IN(7,8)");
        var tenantJson=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var sourceTemplates=new AgentTemplateService(mock(PlatformModelCatalogService.class),mock(CapabilityCatalogService.class),mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class),tenantJson);
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

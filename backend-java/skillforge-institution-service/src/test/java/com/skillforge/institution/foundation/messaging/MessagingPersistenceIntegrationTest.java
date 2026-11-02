package com.skillforge.institution.foundation.messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationRecipientDirectory;
import com.skillforge.core.foundation.messaging.application.NotificationService;
import com.skillforge.core.foundation.messaging.application.NotificationWorker;
import com.skillforge.core.foundation.messaging.application.NotificationHistoryCleanupScheduler;
import com.skillforge.core.foundation.messaging.config.MessagingConfiguration;
import com.skillforge.core.foundation.messaging.contract.NotificationCommands;
import com.skillforge.core.foundation.messaging.integration.NotificationMailTransport;
import com.skillforge.core.foundation.messaging.integration.SmtpNotificationMailTransport;
import com.skillforge.core.foundation.messaging.persistence.MessagingMapper;
import com.skillforge.core.foundation.application.CryptoService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** 真实完整迁移、非绕过RLS数据库角色、生产Spring装配和XML验证；SMTP网络协议另有本机TLS测试。 */
class MessagingPersistenceIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    /** S03显式外部模式只借用父分配的新库，正常CI仍拥有自己的容器。 */
    private static final String EXTERNAL_URL=System.getenv().keySet().stream().anyMatch(key->key.startsWith("SKILLFORGE_S03_EXTERNAL_"))?required("SKILLFORGE_S03_EXTERNAL_JDBC_URL"):null;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate admin;
    private static FoundationAccess access;
    private static FoundationRecipientDirectory directory;
    private static NotificationService service;
    private static MessagingMapper mapper;
    private static CryptoService crypto;
    private static DataSourceTransactionManager transactions;

    /** 复用真正MessagingConfiguration，测试不以手动new服务代替生产装配。 */
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    @Import(MessagingConfiguration.class)
    static class ProductionAssembly { }

    /** 完整迁移只应用于隔离容器，业务连接NOSUPERUSER/NOBYPASSRLS。 */
    @BeforeAll static void schema() throws Exception {
        if(EXTERNAL_URL==null) DATABASE.start();
        String url=EXTERNAL_URL==null?DATABASE.getJdbcUrl():EXTERNAL_URL;
        var root=new DriverManagerDataSource(url,EXTERNAL_URL==null?DATABASE.getUsername():required("SKILLFORGE_S03_EXTERNAL_ADMIN_USER"),EXTERNAL_URL==null?DATABASE.getPassword():required("SKILLFORGE_S03_EXTERNAL_ADMIN_PASSWORD")); admin=new JdbcTemplate(root);
        if(EXTERNAL_URL==null) admin.execute("CREATE ROLE skillforge NOSUPERUSER NOBYPASSRLS");
        else verifyExternalFixture();
        Flyway.configure().dataSource(root).locations("classpath:db/migration").load().migrate();
        String appUser=EXTERNAL_URL==null?"messaging_test":required("SKILLFORGE_S03_EXTERNAL_DB_USER");
        String appPassword=EXTERNAL_URL==null?"test-only-password":required("SKILLFORGE_S03_EXTERNAL_DB_PASSWORD");
        if(EXTERNAL_URL==null) {
            admin.execute("CREATE ROLE messaging_test LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS");
            admin.execute("GRANT USAGE ON SCHEMA public TO messaging_test");
            admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO messaging_test");
            admin.execute("GRANT EXECUTE ON FUNCTION foundation_messaging_due_scopes() TO messaging_test");
        // 隔离角色显式模拟scheduler调用者；生产不授PUBLIC，也不提升机构角色RLS能力。
            admin.execute("GRANT EXECUTE ON FUNCTION public.foundation_messaging_cleanup_scopes() TO messaging_test");
        }
        var application=new DriverManagerDataSource(url,appUser,appPassword);
        assertFalse(new JdbcTemplate(application).queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
        transactions=new DataSourceTransactionManager(application);
        var configuration=new org.apache.ibatis.session.Configuration(); configuration.setMapUnderscoreToCamelCase(true); configuration.addInterceptor(new TenantRlsInterceptor());
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(application); factory.setConfiguration(configuration); factory.setMapperLocations(new ClassPathResource("mapper/MessagingMapper.xml")); factory.afterPropertiesSet();
        var sessionFactory=factory.getObject();
        var properties=new SkillForgeProperties(); properties.setSecretKey("synthetic-messaging-integration-master-key-32"); crypto=new CryptoService(properties);
        access=mock(FoundationAccess.class); directory=mock(FoundationRecipientDirectory.class);
        context=new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class,()->application); context.registerBean(SqlSessionFactory.class,()->sessionFactory);
        context.registerBean(PlatformTransactionManager.class,()->transactions); context.registerBean(FoundationAccess.class,()->access);
        context.registerBean(FoundationRecipientDirectory.class,()->directory); context.registerBean(CryptoService.class,()->crypto);
        context.registerBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules()); context.register(ProductionAssembly.class); context.refresh();
        service=context.getBean(NotificationService.class); mapper=context.getBean(MessagingMapper.class);
        assertTrue(AopUtils.isAopProxy(service)); assertInstanceOf(SmtpNotificationMailTransport.class,context.getBean(NotificationMailTransport.class)); assertNotNull(context.getBean(NotificationWorker.class));
        admin.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target,is_active) VALUES(701,'messaging-test','消息测试',0.99,true)");
        admin.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'消息机构','messaging-seven',701),(8,'其他机构','messaging-eight',701)");
    }
    /** 独立清理本领域夹具，不修改现有业务库。 */
    @BeforeEach void reset() {
        TenantContext.setTenantId("7"); org.mockito.Mockito.reset(access,directory);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","11"));
        when(directory.resolve(any(),eq("all"),anyList())).thenReturn(List.of(new FoundationRecipientDirectory.Recipient("11","member@example.test","测试成员")));
        admin.execute("DROP TRIGGER IF EXISTS messaging_reject_outbox ON nw_foundation_notification_outbox");
        admin.execute("TRUNCATE nw_foundation_notification_attempts,nw_foundation_notification_outbox,nw_foundation_notifications,nw_foundation_announcements,nw_foundation_notification_templates,nw_foundation_mailboxes,nw_foundation_messaging_work_scopes,nw_foundation_messaging_retention CASCADE");
    }
    /** 不把租户线程绑定留给后续测试。 */
    @AfterEach void clear() { TenantContext.clear(); }
    /** 释放真实Spring持久化装配。 */
    @AfterAll static void close() { if(context!=null) context.close(); if(EXTERNAL_URL==null) DATABASE.stop(); }

    /** 外部资源必须同时符合新库名、父安装的lane/token标识；缺值不得回退到旧DB或新容器。 */
    private static void verifyExternalFixture() {
        String database=admin.queryForObject("SELECT current_database()",String.class);
        assertTrue(database!=null&&database.matches("sf_s03_host_messaging_[a-z0-9_]+"),"外部模式必须是父新建的消息专属库");
        assertEquals(1,admin.queryForObject("SELECT count(*) FROM public.s03_host_fixture_guard WHERE lane='host_messaging' AND token=?",Integer.class,required("SKILLFORGE_S03_EXTERNAL_FIXTURE_TOKEN")));
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM public.nw_foundation_notifications",Integer.class),"外部lane仅可消费一次；已有业务fixture不得重置或自动重放");
    }
    /** 外部凭据由受控进程环境传入，不输出值。 */
    private static String required(String name) { String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("缺少外部fixture参数 "+name);return value; }

    /** 受控目录从空tenant领取两机构；生产scheduler逐scope退休且保留调查/未读/UNKNOWN。 */
    @Test void cleanupDirectoryAndProductionSchedulerReachBothTenantsWithoutCallerBypass() {
        var application=new JdbcTemplate(transactions.getDataSource());
        assertFalse(application.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
        assertTrue(application.queryForObject("SELECT has_function_privilege(current_user,'public.foundation_messaging_cleanup_scopes()','EXECUTE')",Boolean.class));
        assertTrue(admin.queryForObject("SELECT r.rolsuper OR r.rolbypassrls FROM pg_proc p JOIN pg_roles r ON r.oid=p.proowner WHERE p.oid='public.foundation_messaging_cleanup_scopes()'::regprocedure",Boolean.class));
        assertTrue(admin.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) acl WHERE p.oid='public.foundation_messaging_cleanup_scopes()'::regprocedure AND acl.grantee=0 AND acl.privilege_type='EXECUTE')",Boolean.class));

        String firstAnnouncement=service.publish(new NotificationCommands.Publish("七标题","七正文","all",List.of(),false,"cleanup-seven"));
        String first=service.inbox(null,20,false).items().getFirst().id();service.markRead(first);
        String protectedAnnouncement=service.publish(new NotificationCommands.Publish("调查","调查正文","all",List.of(),false,"cleanup-protected"));
        String protectedMessage=service.inbox(null,20,false).items().getFirst().id();service.markRead(protectedMessage);service.protectMessage(protectedMessage,true);
        String unreadAnnouncement=service.publish(new NotificationCommands.Publish("未读","未读正文","all",List.of(),false,"cleanup-unread"));
        configured();String unknown=service.enqueue(security("cleanup-unknown","unknown-cleanup-token"));
        var uncertain=new NotificationWorker(mapper,(mailbox,recipient,subject,body,before)->{
            before.run();throw new IllegalStateException("unknown after dispatch");
        },crypto,transactions,access);
        assertTrue(uncertain.processOne("tenant:7"));

        TenantContext.setTenantId("8");
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"8","12"));
        when(directory.resolve(any(),eq("all"),anyList())).thenReturn(List.of(new FoundationRecipientDirectory.Recipient("12","foreign@example.test","其他成员")));
        String secondAnnouncement=service.publish(new NotificationCommands.Publish("八标题","八正文","all",List.of(),false,"cleanup-eight"));
        String second=service.inbox(null,20,false).items().getFirst().id();service.markRead(second);
        assertEquals(2,admin.queryForObject("SELECT count(*) FROM nw_foundation_messaging_retention WHERE retention_days=30",Integer.class));
        admin.update("UPDATE nw_foundation_notifications SET created_at=now()-interval '40 days',read_at=CASE WHEN read_at IS NULL THEN NULL ELSE now()-interval '40 days' END");
        admin.update("UPDATE nw_foundation_announcements SET created_at=now()-interval '40 days'");
        admin.update("UPDATE nw_foundation_notification_outbox SET updated_at=now()-interval '40 days'");

        TenantContext.clear();
        assertEquals(java.util.Set.of("tenant:7","tenant:8"),java.util.Set.copyOf(mapper.historyCleanupScopes()));
        assertNull(TenantContext.getTenantId());
        admin.update("UPDATE nw_foundation_messaging_retention SET next_cleanup_at=now()-interval '1 second'");
        TenantContext.setTenantId("8");
        context.getBean(NotificationHistoryCleanupScheduler.class).cleanup();
        assertEquals("8",TenantContext.getTenantId());
        assertEquals(2,admin.queryForObject("SELECT count(*) FROM nw_foundation_notifications WHERE id IN(?,?) AND retired_at IS NOT NULL",Integer.class,first,second));
        assertEquals(2,admin.queryForObject("SELECT count(*) FROM nw_foundation_announcements WHERE id IN(?,?) AND body_cipher IS NULL AND retired_at IS NOT NULL",Integer.class,firstAnnouncement,secondAnnouncement));
        assertEquals(2,admin.queryForObject("SELECT count(*) FROM nw_foundation_announcements WHERE id IN(?,?) AND body_cipher IS NOT NULL AND retired_at IS NULL",Integer.class,protectedAnnouncement,unreadAnnouncement));
        assertTrue(admin.queryForObject("SELECT investigation_protected AND retired_at IS NULL FROM nw_foundation_notifications WHERE id=?",Boolean.class,protectedMessage));
        assertTrue(admin.queryForObject("SELECT n.email_body_cipher IS NOT NULL AND n.retired_at IS NULL AND o.status='unknown' FROM nw_foundation_notifications n JOIN nw_foundation_notification_outbox o ON o.scope_key=n.scope_key AND o.notification_id=n.id WHERE n.id=?",Boolean.class,unknown));
        assertNull(mapper.message("tenant:7",first));
        assertNotNull(mapper.message("tenant:8",second));
        assertFalse(application.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
    }

    /** 默认未配置SMTP保存blocked；一次性token只有密文，公开查询不回显。 */
    @Test void securityTokenIsEncryptedDeduplicatedAndBlockedWithoutSmtp() throws Exception {
        var command=security("request-1","test-sensitive-token"); String id=service.enqueue(command);
        assertEquals(id,service.enqueue(command)); assertEquals(1,count("nw_foundation_notifications"));
        var row=mapper.message("tenant:7",id); assertNull(row.title()); assertNull(row.body());
        assertFalse(row.emailBodyCipher().contains("test-sensitive-token")); assertTrue(crypto.decrypt(row.emailBodyCipher()).contains("test-sensitive-token"));
        var delivery=service.deliveries(null,20).items().getFirst(); assertEquals("blocked",delivery.status());
        assertFalse(context.getBean(ObjectMapper.class).writeValueAsString(delivery).contains("test-sensitive-token"));
        assertThrows(ApiException.class,()->service.enqueue(security("request-1","other-token")));
        assertThrows(ApiException.class,()->service.enqueue(new NotificationCommands.Enqueue("tenant:8",null,"member@example.test","foreign","security.invitation",command.variables(),false,true)));
        TenantContext.setTenantId("8"); assertNull(mapper.message("tenant:7",id)); assertTrue(mapper.deliveries("tenant:7",null,20).isEmpty());
    }

    /** 调用方回滚和outbox插入失败都不能留下通知或模板半成品。 */
    @Test void outboxAndNotificationParticipateInTheCallerTransaction() {
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status->{ service.enqueue(security("rollback","rollback-token")); throw new IllegalStateException("业务失败"); }));
        assertEquals(0,count("nw_foundation_notifications")); assertEquals(0,count("nw_foundation_notification_templates"));
        admin.execute("CREATE OR REPLACE FUNCTION messaging_reject_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture rejection'; END $$");
        admin.execute("CREATE TRIGGER messaging_reject_outbox BEFORE INSERT ON nw_foundation_notification_outbox FOR EACH ROW EXECUTE FUNCTION messaging_reject_outbox()");
        assertThrows(RuntimeException.class,()->service.enqueue(security("reject","rollback-token")));
        assertEquals(0,count("nw_foundation_notifications")); assertEquals(0,count("nw_foundation_notification_outbox"));
    }

    /** 同键并发受理只产生一个通知；幂等锁与唯一约束实际运行在PostgreSQL。 */
    @Test void concurrentEnqueueHasOneWinnerAndReplaysTheSameMessage() throws Exception {
        try(var executor=Executors.newFixedThreadPool(6)) {
            List<Callable<String>> operations=java.util.stream.IntStream.range(0,6).mapToObj(index->(Callable<String>)()->{ TenantContext.setTenantId("7"); try { return service.enqueue(security("race","race-token")); } finally { TenantContext.clear(); } }).toList();
            var ids=new java.util.HashSet<String>(); for(var future:executor.invokeAll(operations)) ids.add(future.get(20,TimeUnit.SECONDS));
            assertEquals(1,ids.size()); assertEquals(1,count("nw_foundation_notifications")); assertEquals(1,count("nw_foundation_notification_outbox"));
        }
    }

    /** 公告按目录冻结收件人；已读仅自身，撤回隐藏消息并取消尚未派发邮件。 */
    @Test void announcementReadAndWithdrawalProtectMemberAndDeliveryBoundaries() {
        configured(); String announcement=service.publish(new NotificationCommands.Publish("测试公告","公告正文","all",List.of(),true,"publish-1"));
        assertEquals(announcement,service.publish(new NotificationCommands.Publish("测试公告","公告正文","all",List.of(),true,"publish-1")));
        assertEquals(1,service.unread()); String id=service.inbox(null,20,false).items().getFirst().id();
        service.markRead(id); service.markRead(id); assertEquals(0,service.unread());
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","12")); assertTrue(service.inbox(null,20,false).items().isEmpty()); assertThrows(ApiException.class,()->service.markRead(id));
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","11")); service.withdraw(announcement);
        assertTrue(service.inbox(null,20,false).items().isEmpty()); assertEquals("cancelled",service.deliveries(null,20).items().getFirst().status());
        verify(directory).resolve(any(),eq("all"),eq(List.of()));
    }
    /** 共享公告正文只落一份密文，两个recipients及两次SMTP都解析同一精确公告身份。 */
    @Test void announcementBodyIsStoredOnceAndSharedByAuthorizedConsumers() throws Exception {
        configured();int recipients=System.getenv("SKILLFORGE_S03_EXTERNAL_MEASURE_DIR")==null?2:256;
        var audience=java.util.stream.IntStream.range(0,recipients).mapToObj(index->new FoundationRecipientDirectory.Recipient(Integer.toString(index+11),"member-"+index+"@example.test","受控成员")).toList();
        when(directory.resolve(any(),eq("all"),anyList())).thenReturn(audience);
        String body="唯一公告正文🙂".repeat(System.getenv("SKILLFORGE_S03_EXTERNAL_MEASURE_DIR")==null?1:256);
        String window=null;
        if(System.getenv("SKILLFORGE_S03_EXTERNAL_MEASURE_DIR")!=null) {
            assertNotNull(EXTERNAL_URL);window="announcement-"+UUID.randomUUID();
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            String controls=new ObjectMapper().writeValueAsString(Map.of("scenario","shared_announcement","recipients",recipients,"body_utf8_bytes",body.getBytes(StandardCharsets.UTF_8).length,"body_sha256",digest,"channels","in_app_and_email","threads",1,"excludes","setup_mailbox_and_fixture_writes","input_binding",required("SKILLFORGE_S03_EXTERNAL_INPUT_BINDING")));
            admin.queryForObject("SELECT s03_host_measure.begin_window(?,?::jsonb)",Object.class,window,controls);
        }
        String announcement=service.publish(new NotificationCommands.Publish("标题",body,"all",List.of(),true,"shared-body"));
        assertEquals(recipients,count("nw_foundation_notifications"));assertEquals(recipients,count("nw_foundation_notification_outbox"));
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM nw_foundation_notifications WHERE title IS NOT NULL OR body IS NOT NULL OR email_body_cipher IS NOT NULL OR email_subject_cipher IS NOT NULL",Integer.class));
        String cipher=admin.queryForObject("SELECT body_cipher FROM nw_foundation_announcements WHERE id=?",String.class,announcement);
        assertEquals(body,crypto.decrypt(cipher));assertEquals(body,service.inbox(null,20,false).items().getFirst().body());
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","12"));assertEquals(body,service.inbox(null,20,false).items().getFirst().body());
        if(window!=null) {
            var document=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(admin.queryForObject("SELECT s03_host_measure.end_window(?)::text",String.class,window));
            document.put("source_role","candidate_production_publish_with_fixture_recipient_directory");document.put("announcement_cipher_bytes",cipher.getBytes(StandardCharsets.UTF_8).length);
            document.put("recipient_body_column_bytes",admin.queryForObject("SELECT COALESCE(sum(COALESCE(octet_length(body),0)+COALESCE(octet_length(email_body_cipher),0)+COALESCE(octet_length(email_subject_cipher),0)),0) FROM nw_foundation_notifications",Long.class));
            Path output=Path.of(required("SKILLFORGE_S03_EXTERNAL_MEASURE_DIR"));Files.createDirectories(output);Files.writeString(output.resolve("host-announcement-256.json"),document.toPrettyString(),StandardCharsets.UTF_8);
        }
        // 邮件效果使用受控回执验证引用路径；网络SMTP协议属于另一个真实TLS组合入口。
        AtomicInteger sends=new AtomicInteger();var worker=new NotificationWorker(mapper,(mailbox,recipient,subject,content,before)->{assertEquals(body,content);before.run();sends.incrementAndGet();return new NotificationMailTransport.Result("accepted",null);},crypto,transactions,access);
        for(int i=0;i<recipients;i++)assertTrue(worker.processOne("tenant:7"));assertEquals(recipients,sends.get());
    }
    /** 共享正文引用摘要失配在派发前拒绝，禁止回退旧逐收件人副本或当前模板。 */
    @Test void announcementDigestMismatchRejectsBeforeSmtp(){configured();String announcement=service.publish(new NotificationCommands.Publish("标题","原正文","all",List.of(),true,"digest-guard"));admin.update("UPDATE nw_foundation_announcements SET body_digest=? WHERE id=?","a".repeat(64),announcement);AtomicInteger sends=new AtomicInteger();var worker=new NotificationWorker(mapper,(mailbox,recipient,subject,body,before)->{sends.incrementAndGet();return new NotificationMailTransport.Result("accepted",null);},crypto,transactions,access);assertTrue(worker.processOne("tenant:7"));assertEquals(0,sends.get());assertEquals("rejected",service.deliveries(null,20).items().getFirst().status());}
    /** 默认30日保留；调查保护和UNKNOWN优先于TTL，终态材料退休后原幂等键不重新派发。 */
    @Test void historyRetentionProtectsUnknownAndInvestigationAndPreservesTombstone(){configured();String first=service.enqueue(security("retention-accepted","retention-token"));assertEquals(30,service.historyRetention().days());var accepted=new NotificationWorker(mapper,(mailbox,recipient,subject,body,before)->{before.run();return new NotificationMailTransport.Result("accepted",null);},crypto,transactions,access);assertTrue(accepted.processOne("tenant:7"));String unknown=service.enqueue(security("retention-unknown","unknown-retention-token"));var uncertain=new NotificationWorker(mapper,(mailbox,recipient,subject,body,before)->{before.run();throw new IllegalStateException("unknown after dispatch");},crypto,transactions,access);assertTrue(uncertain.processOne("tenant:7"));admin.update("UPDATE nw_foundation_notifications SET created_at=now()-interval '5 days'");admin.update("UPDATE nw_foundation_notification_outbox SET updated_at=now()-interval '5 days'");assertEquals(0,service.cleanupHistory("tenant:7"));service.setHistoryRetention(1);service.protectMessage(first,true);assertEquals(0,service.cleanupHistory("tenant:7"));service.protectMessage(first,false);assertEquals(3,service.cleanupHistory("tenant:7"));assertNotNull(mapper.message("tenant:7",unknown).emailBodyCipher());assertNull(mapper.message("tenant:7",first).emailBodyCipher());assertEquals(first,service.enqueue(security("retention-accepted","retention-token")));assertEquals(2,count("nw_foundation_notifications"));assertEquals(2,count("nw_foundation_notification_outbox"));assertEquals(1,count("nw_foundation_notification_attempts"));assertFalse(accepted.processOne("tenant:7"));assertThrows(ApiException.class,()->service.protectMessage(first,true));}

    /** 当前权限撤回在服务边界拒绝，禁止依赖只读前端按钮隐藏。 */
    @Test void revokedPermissionRejectsBeforeWritingAndWrongRealmIsForbidden() {
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"revoked")).when(access).require("foundation.messaging.publish");
        assertThrows(ApiException.class,()->service.publish(new NotificationCommands.Publish("标题","正文","all",List.of(),false,"denied")));
        assertEquals(0,count("nw_foundation_announcements")); assertThrows(ApiException.class,()->service.requireHost(true));
    }

    /** worker提交派发CAS后才能调用外部协议，多worker竞争不重复派发。 */
    @Test void workerLeaseEnsuresOneDispatchAndOnlyRecordsSmtpAcceptance() throws Exception {
        configured(); service.enqueue(security("deliver","delivery-token")); AtomicInteger sends=new AtomicInteger();
        NotificationMailTransport transport=(mailbox,recipient,subject,body,before)->{ assertTrue(body.contains("delivery-token")); before.run(); sends.incrementAndGet(); return new NotificationMailTransport.Result("accepted",null); };
        var worker=new NotificationWorker(mapper,transport,crypto,transactions,access);
        try(var executor=Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> operations=java.util.stream.IntStream.range(0,4).mapToObj(index->(Callable<Boolean>)()->{ TenantContext.setTenantId("7"); try { return worker.processOne("tenant:7"); } finally { TenantContext.clear(); } }).toList();
            for(var future:executor.invokeAll(operations)) future.get(20,TimeUnit.SECONDS);
        }
        assertEquals(1,sends.get()); var delivery=service.deliveries(null,20).items().getFirst(); assertEquals("accepted",delivery.status()); assertEquals(1,delivery.attempts()); assertEquals(1,count("nw_foundation_notification_attempts"));
        assertFalse(worker.processOne("tenant:7"));
    }

    /** 派发失回执或失租约维持unknown，只有明确核验后才可以重试确定未接受结果。 */
    @Test void unknownNeverAutomaticallyRetriesAndExpiredDispatchIsRecoveredAsUnknown() {
        configured(); service.enqueue(security("unknown","unknown-token"));
        var worker=new NotificationWorker(mapper,(mailbox,recipient,subject,body,before)->{ before.run(); throw new IllegalStateException("connection lost after dispatch"); },crypto,transactions,access);
        assertTrue(worker.processOne("tenant:7")); var delivery=service.deliveries(null,20).items().getFirst(); assertEquals("unknown",delivery.status());
        assertThrows(ApiException.class,()->service.retry(delivery.id())); assertFalse(worker.processOne("tenant:7"));
        assertThrows(ApiException.class,()->service.reconcile(delivery.id(),"rejected",""));
        service.reconcile(delivery.id(),"rejected","邮件服务器工单TEST-1确认未接受"); service.retry(delivery.id());
        new TransactionTemplate(transactions).executeWithoutResult(status->{ var lease=mapper.claim("tenant:7","expired-test",60); assertNotNull(lease); assertEquals(1,mapper.dispatch("tenant:7",lease.id(),lease.leaseToken())); });
        admin.update("UPDATE nw_foundation_notification_outbox SET lease_until=now()-interval '1 second' WHERE id=?",delivery.id());
        assertFalse(worker.processOne("tenant:7")); assertEquals("unknown",service.deliveries(null,20).items().getFirst().status());
    }

    /** 邮箱停用实时阻止旧冻结配置发送，凭据查询只呈现配置状态。 */
    @Test void disablingMailboxBlocksFrozenPendingMailAndSecretsNeverAppearInMailboxView() throws Exception {
        configured(); service.enqueue(security("disabled","disabled-token")); service.configureMailbox(new NotificationCommands.Mailbox(null,null,null,null,null,null,false));
        var worker=new NotificationWorker(mapper,(mailbox,recipient,subject,body,before)->{ fail("停用配置不得外发"); return null; },crypto,transactions,access);
        assertTrue(worker.processOne("tenant:7")); assertEquals("blocked",service.deliveries(null,20).items().getFirst().status());
        assertFalse(context.getBean(ObjectMapper.class).writeValueAsString(service.mailbox()).contains("test-smtp-password"));
    }

    /** 构造有效配置只写入测试邮箱，测试传输从不连接此外部地址。 */
    private static void configured() { service.configureMailbox(new NotificationCommands.Mailbox("smtp.example.test",587,"test-user","test-smtp-password","notification@example.test","starttls",true)); }
    /** 固定安全模板变量，由已确认账号业务调用，无HTTP token输出。 */
    private static NotificationCommands.Enqueue security(String key,String token) { return new NotificationCommands.Enqueue("tenant:7",null,"member@example.test",key,"security.invitation",Map.of("username","测试账号","token",token,"expires_at","2026-10-11T18:00:00+08:00","tenant_slug","messaging-seven"),false,true); }
    /** 仅测试管理员直接计数确认原子持久化事实。 */
    private static long count(String table) { return admin.queryForObject("SELECT count(*) FROM "+table,Long.class); }
}

package com.skillforge.institution.media;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.attachment.SessionAttachmentBodyMapper;
import com.skillforge.institution.attachment.SessionAttachmentBodyStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实正文引用/RLS，验证使用意图、来源图和活跃未用媒体保护及明确产品会话删除。 */
@Testcontainers(disabledWithoutDocker = true)
class SessionMediaMapperTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void oneBodySupportsExactMediaReferencesAndSafeUnusedMediaReclamation() throws Exception {
        try (var source = new SingleConnectionDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY)");
            jdbc.execute("INSERT INTO nw_tenants VALUES(7),(8)");
            jdbc.execute("CREATE TABLE nw_chat_sessions(session_id varchar(64) NOT NULL,tenant_id bigint NOT NULL)");
            jdbc.execute("INSERT INTO nw_chat_sessions VALUES('session',7),('other',8)");
            jdbc.execute("CREATE TABLE agent_runtime_sessions(tenant_id text,session_id text,status text)");
            jdbc.execute("CREATE TABLE agent_runtime_runs(tenant_id text,session_id text,status text)");
            var migrations = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V241__chat_session_attachments.sql"),
                    new ClassPathResource("db/migration/V255__session_image_media.sql"),
                    new ClassPathResource("db/migration/V261__document_session_attachment_limits.sql"),
                    new ClassPathResource("db/migration/V284__session_media_attachment_body_storage.sql"));
            // V284 的 PL/pgSQL 体含分号；每个迁移作为完整脚本交给 PostgreSQL。
            migrations.setSeparator(org.springframework.jdbc.datasource.init.ScriptUtils.EOF_STATEMENT_SEPARATOR);
            migrations.execute(source);
            jdbc.execute("CREATE ROLE media_reader NOSUPERUSER");
            jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON nw_chat_session_attachments,nw_chat_attachment_bodies,nw_chat_sessions,nw_tenants,agent_runtime_sessions,agent_runtime_runs TO media_reader");
            jdbc.execute("SET ROLE media_reader");
            jdbc.execute("SELECT set_config('app.tenant_id','7',false)");
            TenantContext.setTenantId("7");
            var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
            factory.setMapperLocations(new ClassPathResource("mapper/SessionMediaMapper.xml"),
                    new ClassPathResource("mapper/SessionAttachmentMapper.xml"),new ClassPathResource("mapper/SessionAttachmentBodyMapper.xml"));
            var session = new SqlSessionTemplate(factory.getObject());
            var media = session.getMapper(SessionMediaMapper.class);
            var bodyMapper = session.getMapper(SessionAttachmentBodyMapper.class);
            var bodies = new SessionAttachmentBodyStore(bodyMapper,0,0);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            byte[] bytes = {1,2,3};
            String id = UUID.randomUUID().toString();
            var row = new SessionMediaRow(id,"7","child","session",null,"图",3,SessionMediaImages.digest(bytes),"image/png",1,1,bytes,null,"source-v1",Instant.now().minus(2,ChronoUnit.DAYS));
            transactions.executeWithoutResult(status -> { bodies.register("7","session",row.sha256(),bytes); assertEquals(1,media.insert(row)); });
            String variantId=UUID.randomUUID().toString();
            var variant = new SessionMediaRow(variantId,"7","child","session",null,"变体",3,row.sha256(),"image/png",1,1,bytes,id,"resize-v1",row.createdAt());
            transactions.executeWithoutResult(status -> { bodies.register("7","session",row.sha256(),bytes); assertEquals(1,media.insert(variant)); });
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM nw_chat_attachment_bodies",Integer.class));
            assertArrayEquals(bytes,media.readBytes("7","child",id).content());
            assertNull(media.find("7","other",id)); assertNotNull(media.findProduct("7","session",id));
            assertNull(media.find("7","session",id));
            assertEquals(0, transactions.execute(status -> { bodies.lockTenant("7"); return session.getMapper(com.skillforge.institution.attachment.SessionAttachmentMapper.class).clearExpiredContent("7",100); }).intValue());
            jdbc.execute("SELECT set_config('app.tenant_id','8',false)");
            assertNull(media.find("7","child",id)); assertNull(bodyMapper.find("7",row.sha256(),3));
            jdbc.execute("SELECT set_config('app.tenant_id','7',false)");
            jdbc.update("INSERT INTO agent_runtime_sessions VALUES('7','child','ACTIVE')");
            assertEquals(0, transactions.execute(status -> { bodies.lockTenant("7"); return bodyMapper.reclaimUnreferenced("7",100); }).intValue());
            jdbc.update("DELETE FROM agent_runtime_sessions");
            jdbc.update("INSERT INTO agent_runtime_runs VALUES('7','child','SUSPENDED')");
            assertEquals(0, transactions.execute(status -> { bodies.lockTenant("7"); return bodyMapper.reclaimUnreferenced("7",100); }).intValue());
            jdbc.update("DELETE FROM agent_runtime_runs");
            assertEquals(1, transactions.execute(status -> { bodies.lockTenant("7"); return media.pin("7","child",variantId); }).intValue());
            assertEquals(0, transactions.execute(status -> { bodies.lockTenant("7"); return media.pin("7","child",variantId); }).intValue());
            assertEquals(0, transactions.execute(status -> { bodies.lockTenant("7"); return bodyMapper.reclaimUnreferenced("7",100); }).intValue());
            String orphanId=UUID.randomUUID().toString();
            var orphan = new SessionMediaRow(orphanId,"7","unused","session",null,"未使用",3,row.sha256(),"image/png",1,1,bytes,null,"source-v1",row.createdAt());
            transactions.executeWithoutResult(status -> { bodies.register("7","session",row.sha256(),bytes); media.insert(orphan); });
            assertNotNull(media.find("7","unused",orphanId), "活跃会话未使用上传也保留引用，不能只按年龄回收");
            assertEquals(0, transactions.execute(status -> { bodies.lockTenant("7"); return bodyMapper.reclaimUnreferenced("7",100); }).intValue());
            assertEquals(0, transactions.execute(status -> { bodies.lockTenant("7"); return media.insert(orphan); }).intValue());
            transactions.executeWithoutResult(status -> { bodies.lockTenant("7"); jdbc.update("DELETE FROM nw_chat_sessions WHERE tenant_id=7"); assertEquals(1,bodyMapper.reclaimUnreferenced("7",100)); });
            assertNull(media.find("7","child",id)); assertNull(media.find("7","child",variantId));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM nw_chat_attachment_bodies",Integer.class));
        } finally { TenantContext.clear(); }
    }
}

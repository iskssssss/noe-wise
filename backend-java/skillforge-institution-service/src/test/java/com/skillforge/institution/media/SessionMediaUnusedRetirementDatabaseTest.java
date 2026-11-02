package com.skillforge.institution.media;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.institution.attachment.SessionAttachmentBodyMapper;
import com.skillforge.institution.attachment.SessionAttachmentBodyStore;
import com.skillforge.institution.integration.AbstractIntegrationTest;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionStorePort;
import com.skillforge.runtime.adapter.jdbc.session.SessionMediaHistoricalLifecycleFixture;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S03父预迁移的新受限PG/Redis上验证专属资源门禁，使用真实SessionStore codec/幂等回执与Spring事务。
 * 未分配外部fixture时明确跳过；不回退另建容器，不用返回true的owner/引用stub。
 */
@EnabledIfEnvironmentVariable(named="SKILLFORGE_S03_EXTERNAL_FIXTURE_LANE",matches="media")
class SessionMediaUnusedRetirementDatabaseTest extends AbstractIntegrationTest {
    @Autowired private DataSource source;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private SessionMediaService media;
    @Autowired private SessionMediaMapper mapper;
    @Autowired private SessionMediaReclamation reclamation;
    @Autowired private SessionAttachmentBodyStore bodies;
    @Autowired private SessionAttachmentBodyMapper bodyMapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private String tenant,session;
    private long agent;
    private SessionOwner owner;
    private SessionStorePort store;
    private SessionStorePort.CreateSession create;
    private SessionStorePort.ChangeSessionStatus activate,close;
    private SessionMediaMapper.UnusedUpload candidate;
    private com.skillforge.runtime.api.media.MediaRef normalized;
    private byte[] png;

    /** 正式角色与完整迁移由基类guard检查，所有fixture只创建本例私有租户/会话。 */
    @BeforeEach void fixture() throws Exception {
        jdbc=new JdbcTemplate(source);transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        assertFalse(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
        assertTrue(jdbc.queryForObject("SELECT to_regprocedure('public.agent_runtime_chat_unused_media_owner_gate(text,text,text,uuid)') IS NOT NULL",Boolean.class));
        tenant=createTenant(tenants,plans,"media-unused-"+UUID.randomUUID());TenantContext.setTenantId(tenant);
        session="unused-"+UUID.randomUUID();long user=SnowflakeIds.nextId();agent=SnowflakeIds.nextId();
        transactions.executeWithoutResult(state->{bind();
            jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?::bigint)",user,tenant);
            // 本例只验证存储生命周期，创建真实私有草稿Agent，不伪造发布或调用模型。
            jdbc.update("INSERT INTO nw_agents(id,agent_key,tenant_id,name) VALUES(?,?,?::bigint,?)",agent,"media-unused-agent-"+agent,tenant,"未用媒体测试助手");
            insertProductSession(session,user);});
        owner=SessionOwner.user(new SessionRef(new TenantId(tenant),session),SessionTarget.agent(new AgentId(Long.toString(agent))),new EndUserId(user));
        store=new JdbcSessionStorePort(source);Instant old=Instant.now().minus(Duration.ofDays(31));
        var trace=new TraceEnvelope("media-unused-trace","media-unused-span",new InputId("media-unused-create"),Optional.empty());
        create=new SessionStorePort.CreateSession(owner,"chat","media-unused-scope","media-unused-persona",new InputId("media-unused-create"),trace,old);
        activate=new SessionStorePort.ChangeSessionStatus(owner,SessionStorePort.SessionStatus.CREATED,SessionStorePort.SessionStatus.ACTIVE,1,new InputId("media-unused-active"),trace,old.plusSeconds(1));
        close=new SessionStorePort.ChangeSessionStatus(owner,SessionStorePort.SessionStatus.ACTIVE,SessionStorePort.SessionStatus.CLOSED,2,new InputId("media-unused-close"),trace,old.plusSeconds(2));
        // 正式store使用DB storageNow，old命令时间不会让刚创建的CLOSED事实变旧。
        SessionMediaHistoricalLifecycleFixture.persist(source,create,activate,close,old);
        var historical=store.find(new SessionStorePort.SessionLookup(owner)).orElseThrow();
        assertEquals(SessionStorePort.SessionStatus.CLOSED,historical.status());
        assertEquals(old.plusSeconds(2),historical.updatedAt());
        assertEquals(3,historical.sessionRevision());
        var image=new BufferedImage(3,2,BufferedImage.TYPE_INT_ARGB);image.setRGB(1,1,0x80ff0000);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);png=bytes.toByteArray();
        normalized=media.register(owner.sessionRef(),"stable-upload","图",png);
        // 新合成fixture模拟已保存31天的未用上传；不更改现存环境、不让生产时钟回退。
        transactions.executeWithoutResult(state->{bind();jdbc.update("UPDATE nw_chat_session_attachments SET created_at=? WHERE tenant_id::text=? AND session_id=?",Timestamp.from(old),tenant,session);});
        candidate=reclamation.page(tenant,null).getFirst();
    }
    /** ThreadLocal身份在每例结束清除；外部PG/Redis由父拥有，不停止借用资源。 */
    @AfterEach void clear(){TenantContext.clear();}

    /** 无关生命周期回执继续精确重放，图片仅去正文FK并保原身份；迟到同键不能重绑或新写孤立body。 */
    @Test void unusedResourceRetiresWhileUnrelatedExactLifecycleReceiptsRemainReplayable(){
        var original=mutationReceipts();var originalCreate=createReceipt();
        assertEquals(2,original.size());long before=bodyMapper.tenantBytes(tenant);
        assertEquals(1,originalCreate.size());
        // 当前闭包契约仅重放仍匹配当前revision的CAS；旧ACTIVE请求不得重新打开CLOSED会话。
        assertInstanceOf(SessionStorePort.SessionCasResult.Conflict.class,store.compareAndSet(activate));
        var retired=reclamation.retire(tenant,candidate);assertEquals("retired",retired.result());assertEquals(2,retired.releasedRows());
        assertEquals(0,bodyMapper.tenantBytes(tenant));assertTrue(before>0);
        assertEquals(original,mutationReceipts());assertEquals(originalCreate,createReceipt());
        assertInstanceOf(SessionStorePort.SessionCreateResult.Replayed.class,store.create(create));
        assertInstanceOf(SessionStorePort.SessionCasResult.Conflict.class,store.compareAndSet(activate));
        assertInstanceOf(SessionStorePort.SessionCasResult.Replayed.class,store.compareAndSet(close));
        assertEquals(3,store.find(new SessionStorePort.SessionLookup(owner)).orElseThrow().sessionRevision());
        assertEquals(2,count("SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id::text=? AND session_id=? AND body_format IS NULL AND reclaimed_at IS NOT NULL AND name IS NULL",tenant,session));
        assertThrows(ApiException.class,()->media.register(owner.sessionRef(),"stable-upload","图",png));
        assertEquals(0,bodyMapper.tenantBytes(tenant));
        assertEquals(2,count("SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id::text=? AND session_id=?",tenant,session));
        assertThrows(ApiException.class,()->media.reference(owner.sessionRef(),normalized.id()));
    }

    /** 已pin规范化后代保护原图，即使root自身为空pin且没有Run。 */
    @Test void pinnedDescendantProtectsWholeSourceFamily(){
        media.reference(owner.sessionRef(),normalized.id());
        assertThrows(org.springframework.dao.DataAccessException.class,()->transactions.executeWithoutResult(state->{bind();jdbc.update("UPDATE nw_chat_session_attachments SET referenced_at=NULL WHERE tenant_id::text=? AND id=?::uuid",tenant,normalized.id());}));
        assertEquals(0,reclamation.retire(tenant,candidate).releasedRows());
        assertArrayEquals(png,media.read(owner.sessionRef(),normalized));
        assertEquals(2,count("SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id::text=? AND session_id=? AND body_format IS NOT NULL",tenant,session));
    }

    /** 有效来源后代不能仅因自身pin为空被误删；所有未知/裁剪转换保守保护。 */
    @Test void effectiveNonUploadDescendantProtectsRoot(){
        String sha=SessionMediaImages.digest(png);
        transactions.executeWithoutResult(state->{bind();bodies.register(tenant,session,sha,png);
            assertEquals(1,mapper.insert(new SessionMediaRow(UUID.randomUUID().toString(),tenant,session,session,null,"裁剪",png.length,sha,"image/png",3,2,png,candidate.id(),"crop-v1:0:0:3:2",Instant.now())));});
        assertEquals(0,reclamation.retire(tenant,candidate).releasedRows());
        assertEquals(3,count("SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id::text=? AND session_id=? AND body_format IS NOT NULL",tenant,session));
    }

    /** 只有产品会话、没有任何可靠Runtime终态owner的上传不能按年龄删除。 */
    @Test void missingRuntimeOwnerRemainsProtected(){
        String product="no-owner-"+UUID.randomUUID();
        transactions.executeWithoutResult(state->{bind();insertProductSession(product,owner.requireUser().value());});
        var logical=new SessionRef(new TenantId(tenant),product);media.register(logical,"no-owner-upload","图",png);
        transactions.executeWithoutResult(state->{bind();jdbc.update("UPDATE nw_chat_session_attachments SET created_at=? WHERE tenant_id::text=? AND session_id=?",Timestamp.from(Instant.now().minus(Duration.ofDays(31))),tenant,product);});
        var noOwner=reclamation.page(tenant,null).stream().filter(row->row.productSessionId().equals(product)).findFirst().orElseThrow();
        assertEquals("runtime_protected",reclamation.retire(tenant,noOwner).result());
        assertEquals(2,count("SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id::text=? AND session_id=? AND body_format IS NOT NULL",tenant,product));
    }

    /** 真实pin事务先持tenant锁，GC持owner等待；精确owner探针证明锁链，pin提交后闭包保护。 */
    @Test void concurrentPinCommitWinsBeforeGcMetadataDecision() throws Exception {
        long originalBytes=bodyMapper.tenantBytes(tenant);
        var pinned=new CountDownLatch(1);var commit=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(3);
        var pinPid=new CompletableFuture<Integer>();var gcPid=new CompletableFuture<Integer>();var ownerPid=new CompletableFuture<Integer>();
        try{
            var binding=executor.submit(()->{TenantContext.setTenantId(tenant);try{transactions.executeWithoutResult(state->{bind();pinPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));media.reference(owner.sessionRef(),normalized.id());pinned.countDown();try{assertTrue(commit.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}});}finally{TenantContext.clear();}});
            assertTrue(pinned.await(10,TimeUnit.SECONDS));
            var retiring=executor.submit(()->{TenantContext.setTenantId(tenant);try{return transactions.execute(state->{bind();jdbc.execute("SET LOCAL application_name='unused-media-retirement-test-gc'");gcPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));return reclamation.retire(tenant,candidate);});}finally{TenantContext.clear();}});
            int pinBackend=pinPid.get(10,TimeUnit.SECONDS),gcBackend=gcPid.get(10,TimeUnit.SECONDS);
            assertTrue(awaitLockBlockedBy(gcBackend,pinBackend),"真实GC事务等待本例尚未提交的pin事务");
            // 只探测同一owner行的真实互斥机制，不把查询文本截断或Future完成状态当作CAS受理证据。
            var ownerProbe=executor.submit(()->{TenantContext.setTenantId(tenant);try{return transactions.execute(state->{bind();jdbc.execute("SET LOCAL application_name='unused-media-retirement-test-owner'");ownerPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));return jdbc.queryForObject("SELECT end_user_id FROM public.agent_runtime_session_owners WHERE tenant_id=? AND target_kind=? AND target_id=? AND session_id=? AND end_user_id=? FOR UPDATE",Long.class,tenant,owner.target().kind().name(),owner.target().id(),session,owner.requireUser().value());});}finally{TenantContext.clear();}});
            int ownerBackend=ownerPid.get(10,TimeUnit.SECONDS);
            assertTrue(awaitLockBlockedBy(ownerBackend,gcBackend),"GC等待pin提交时仍持本例精确owner行锁");
            commit.countDown();binding.get(20,TimeUnit.SECONDS);
            var retired=retiring.get(20,TimeUnit.SECONDS);assertEquals(0,retired.releasedRows());assertEquals(0,retired.reclaimedBodies());
            assertEquals(owner.requireUser().value(),ownerProbe.get(20,TimeUnit.SECONDS).longValue());
            assertArrayEquals(png,media.read(owner.sessionRef(),normalized));assertEquals(originalBytes,bodyMapper.tenantBytes(tenant));
            assertEquals(2,count("SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id::text=? AND session_id=? AND runtime_session_id=? AND body_format IS NOT NULL AND reclaimed_at IS NULL",tenant,session,owner.sessionRef().sessionId()));
            // 锁探针仅证明owner互斥；正式store的当前CLOSED精确Replay在竞争结束后另行核对。
            assertInstanceOf(SessionStorePort.SessionCasResult.Replayed.class,store.compareAndSet(close));
        }finally{commit.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
    }

    /** 用同库实际PID及阻塞者证明等待边，不依赖可能截断的活动查询文本或固定延时。 */
    private boolean awaitLockBlockedBy(int waiter,int blocker){
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<deadline){
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid=? AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)))",Boolean.class,waiter,blocker)))return true;
            Thread.yield();
        }
        return false;
    }

    /** 在已绑定的真实事务中使用本例Agent与EndUser，产品会话明确只有Agent目标。 */
    private void insertProductSession(String product,long user){
        jdbc.update("INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,team_id,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?::bigint,?,?,NULL,'native',?,?)",SnowflakeIds.nextId(),product,tenant,user,agent,"end_user:"+user,"tenant:"+tenant);
    }
    /** 完整不可变CAS记录、原规范命令字节及fingerprint在回收前后逐列相等。 */
    private List<Map<String,Object>> mutationReceipts(){return rows("SELECT *,md5(command_data) AS command_bytes_hash FROM agent_runtime_session_mutations WHERE tenant_id=? AND session_id=? ORDER BY output_revision",tenant,session);}
    /** Create规范命令、fingerprint及当前CLOSED闭包均不能因无关图片回收而改变。 */
    private List<Map<String,Object>> createReceipt(){return rows("SELECT *,md5(create_command_data) AS create_command_bytes_hash FROM agent_runtime_sessions WHERE tenant_id=? AND session_id=?",tenant,session);}
    /** 当前事务绑定可信租户，JdbcTemplate查询不能依赖上一连接的会话残留。 */
    private void bind(){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant);}
    /** 在绑定租户的真实事务内读取精确不可变回执。 */
    private List<Map<String,Object>> rows(String query,Object...args){return transactions.execute(state->{bind();return jdbc.queryForList(query,args);});}
    /** 完整查询分母，缺租户绑定或空结果不被冒充删除通过。 */
    private long count(String query,Object...args){return transactions.execute(state->{bind();return jdbc.queryForObject(query,Long.class,args);});}
}

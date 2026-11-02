package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.contract.ChatInputReference;
import com.skillforge.core.chat.contract.ChatReferenceView;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.chat.persistence.ChatMessageMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.chat.persistence.ChatMessageDO;
import com.skillforge.core.chat.session.SessionMailboxService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实迁移和 SQL 证明消息版本、typed 引用以及已提交游标的事务可见性。 */
class ChatPublicPersistenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired ChatMessageMapper messages;
    @Autowired SessionOutboxMapper outbox;
    @Autowired SessionMailboxService mailbox;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void committedCursorSkipsUncommittedAndRolledBackOutputsAndReconnectReadsEachSuccessorOnce() throws Exception {
        var fixture=fixture();
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            bind(fixture);append(fixture,1,"first-event");
        });
        var inserted=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var writer=workers.submit(()->{
                TenantContext.setTenantId(Long.toString(fixture.tenant()));
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status->{
                        bind(fixture);append(fixture,2,"second-event");inserted.countDown();
                        try { assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                    });
                } finally {TenantContext.clear();}
            });
            try {
                assertTrue(inserted.await(5,java.util.concurrent.TimeUnit.SECONDS));
                new TransactionTemplate(transactions).executeWithoutResult(status->{
                    bind(fixture);
                    assertEquals("first-event",outbox.committedCursor(fixture.tenant(),fixture.session()));
                    assertTrue(mailbox.listOutputsAfter(Long.toString(fixture.tenant()),fixture.session(),"first-event",10).isEmpty());
                });
            } finally {release.countDown();}
            writer.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            bind(fixture);
            assertEquals("second-event",outbox.committedCursor(fixture.tenant(),fixture.session()));
            var replay=mailbox.listOutputsAfter(Long.toString(fixture.tenant()),fixture.session(),"first-event",10);
            assertEquals(List.of("second-event"),replay.stream().map(value->value.getEventId()).toList());
            assertTrue(mailbox.listOutputsAfter(Long.toString(fixture.tenant()),fixture.session(),"second-event",10).isEmpty());
            append(fixture,3,"rolled-back-event");status.setRollbackOnly();
        });
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            bind(fixture);assertEquals("second-event",outbox.committedCursor(fixture.tenant(),fixture.session()));
            assertTrue(mailbox.listOutputsAfter(Long.toString(fixture.tenant()),fixture.session(),"second-event",10).isEmpty());
        });
    }

    @Test void messageReferencesRoundTripAndOnlyRealContentChangesAdvanceVersion() {
        var fixture=fixture();
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            bind(fixture);
            var row=new ChatMessageDO();
            row.setId(SnowflakeIds.nextId());row.setTenantId(fixture.tenant());row.setSessionId(fixture.session());
            row.setEndUserId(fixture.user());row.setMessageId("versioned-message");row.setRole("assistant");
            row.setContent("订单已汇总");row.setMetadata(Map.of("phase","final"));
            var source=new ChatInputReference.DeliverableRevision("revision-selector",ChatInputReference.INPUT_PURPOSE);
            row.setReferences(List.of(new ChatReferenceView("订单报告",source)));
            messages.upsertById(row);
            var first=messages.selectById(row.getId());
            assertEquals(1,first.getMessageVersion());assertEquals(row.getReferences(),first.getReferences());
            messages.upsertById(row);
            assertEquals(1,messages.selectById(row.getId()).getMessageVersion());
            row.setContent("订单已汇总并核对");messages.upsertById(row);
            assertEquals(2,messages.selectById(row.getId()).getMessageVersion());
            row.setReferences(List.of(new ChatReferenceView("已核对的订单报告",source)));messages.upsertById(row);
            assertEquals(3,messages.selectById(row.getId()).getMessageVersion());
            messages.upsertById(row);assertEquals(3,messages.selectById(row.getId()).getMessageVersion());
            assertEquals(first.getCreatedAt(),messages.selectById(row.getId()).getCreatedAt());
            String otherSession=Long.toString(SnowflakeIds.nextId());
            jdbc.update("INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,origin_kind,actor_ref,responsibility_ref) SELECT ?,?,tenant_id,end_user_id,agent_id,origin_kind,actor_ref,responsibility_ref FROM nw_chat_sessions WHERE session_id=?",
                    SnowflakeIds.nextId(),otherSession,fixture.session());
            row.setSessionId(otherSession);row.setContent("错误会话不能覆盖原消息");
            assertEquals(0,messages.upsertById(row));
            assertEquals("订单已汇总并核对",messages.selectById(row.getId()).getContent());
        });
    }

    private void append(Fixture f,long sequence,String event) {
        jdbc.update("INSERT INTO nw_chat_session_outbox(id,tenant_id,session_id,event_id,session_sequence,event_type) VALUES(?,?,?,?,?,'message.delta')",
                SnowflakeIds.nextId(),f.tenant(),f.session(),event,sequence);
    }
    private void bind(Fixture f) { jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,Long.toString(f.tenant())); }
    private Fixture fixture() {
        long tenant=Long.parseLong(createTenant(tenants,plans,"public-contract-"+UUID.randomUUID()));
        TenantContext.setTenantId(Long.toString(tenant));
        long user=SnowflakeIds.nextId(),agent=SnowflakeIds.nextId();String session=Long.toString(SnowflakeIds.nextId());
        jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,tenant);
        jdbc.update("INSERT INTO nw_agents(id,agent_key,tenant_id,name,status,published_at) VALUES(?,?,?,'合成测试助手','published',now())",agent,"test-agent-"+agent,tenant);
        jdbc.update("INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?,?,?,'native',?,?)",SnowflakeIds.nextId(),session,tenant,user,agent,"end_user:"+user,"tenant:"+tenant);
        return new Fixture(tenant,user,session);
    }
    private record Fixture(long tenant,long user,String session) { }
}

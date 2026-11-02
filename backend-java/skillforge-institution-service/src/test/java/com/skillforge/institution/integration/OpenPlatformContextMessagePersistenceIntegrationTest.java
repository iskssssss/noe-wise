package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.*;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.chat.context.*;

import com.skillforge.institution.runtime.WorkflowTestRuntimePolicy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 复用真实PG/Redis fixture；身份夹具不代替HTTP授权验收，事务与同来源锁均走生产组件。 */
class OpenPlatformContextMessagePersistenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired TenantMapper tenants; @Autowired PlanMapper plans; @Autowired EndUserMapper users;
    @Autowired AgentMapper agents; @Autowired SessionService sessionService;
    @Autowired ServicePrincipalService principals; @Autowired InstitutionIntegrationMapper integrations;
    @Autowired MemoryService memory; @Autowired OpenPlatformContextMessageMapper locks;
    @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager manager;

    @Test @Timeout(90)
    void rollbackNeverPublishesHotContextAndConcurrentSourceHasOneArchive() throws Exception {
        String marker="context-"+SnowflakeIds.nextId();
        String tenant=createTenant(tenants,plans,marker);
        String integration="context-integration";
        var issued=TenantContext.callWithTenant(tenant,()->{
            integrations.registerIntegration(Long.parseLong(tenant),integration);
            return principals.issue(tenant,integration,Set.of("sessions.write"));
        });
        var principal=new ServicePrincipalContext.Identity(issued.principal().getPrincipalId(),tenant,integration,
                issued.principal().getCredentialVersion(),Set.of("sessions.write"));
        var session=TenantContext.callWithTenant(tenant,()->{
            var user=new EndUserDO();user.setId(SnowflakeIds.nextId());user.setTenantId(Long.parseLong(tenant));
            user.setStatus("active");user.setCreatedAt(Instant.now());user.setFirstSeenAt(Instant.now());user.setLastActiveAt(Instant.now());users.insert(user);
            var agent=new AgentDO();agent.setId(SnowflakeIds.nextId());agent.setTenantId(Long.parseLong(tenant));agent.setAgentKey(marker);
            agent.setName(marker);agent.setStatus("published");agent.setSystemPrompt("事务验证");agent.setTemplateVersion("1.0");agent.setRoleDescription("事务验证");agents.insert(agent);
            var previous=ServicePrincipalContext.get();
            try {
                ServicePrincipalContext.set(principal);
                return sessionService.getOrCreateOpenPlatform(tenant,integration,marker,marker,"new",user.getId(),agent.getId());
            } finally {if(previous==null)ServicePrincipalContext.clear();else ServicePrincipalContext.set(previous);}
        });
        String sessionId=session.getSessionId();
        var access=mock(OpenPlatformSessionAccessService.class);var sessions=mock(SessionService.class);var tests=mock(WorkflowTestRuntimePolicy.class);
        var current=new OpenPlatformAccessService.Access(principal);
        when(access.require("sessions.write")).thenReturn(current);when(access.session(current,sessionId)).thenReturn(session);
        var service=new OpenPlatformContextMessageService(access,sessions,tests,memory,locks,json);
        var command=new OpenPlatformContextMessageService.Command(sessionId,"original-source","","已完成的业务事实");
        var transactions=new TransactionTemplate(manager);String key=tenant+":session:"+sessionId+":messages";
        TenantContext.runWithTenant(tenant,()->transactions.executeWithoutResult(status->{
            var receipt=service.append(command);assertFalse(receipt.replayed());
            assertEquals(0L,redis.opsForList().size(key));
            assertNotNull(memory.findByMessageId(tenant,receipt.messageId()));status.setRollbackOnly();
        }));
        assertEquals(0L,redis.opsForList().size(key));
        assertEquals(0L,count(tenant,sessionId));
        var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            Callable<OpenPlatformContextMessageService.Receipt> append=()->{
                gate.await();return TenantContext.callWithTenant(tenant,()->transactions.execute(status->service.append(command)));
            };
            var first=executor.submit(append);var second=executor.submit(append);gate.countDown();
            var one=first.get(30,TimeUnit.SECONDS);var two=second.get(30,TimeUnit.SECONDS);
            assertEquals(one.messageId(),two.messageId());assertNotEquals(one.replayed(),two.replayed());
            assertEquals(1L,count(tenant,sessionId));assertEquals(1L,redis.opsForList().size(key));
            assertTrue(redis.opsForList().range(key,0,-1).getFirst().contains("context_only"));
            TenantContext.runWithTenant(tenant,()->assertThrows(ApiException.class,()->transactions.execute(status->
                    service.append(new OpenPlatformContextMessageService.Command(sessionId,"original-source","","替换事实")))));
            assertEquals(1L,count(tenant,sessionId));assertEquals(1L,redis.opsForList().size(key));
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private long count(String tenant,String session) {
        return TenantContext.callWithTenant(tenant,()->jdbc.queryForObject("SELECT count(*) FROM nw_chat_messages WHERE tenant_id=? AND session_id=?",Long.class,Long.parseLong(tenant),session));
    }
}

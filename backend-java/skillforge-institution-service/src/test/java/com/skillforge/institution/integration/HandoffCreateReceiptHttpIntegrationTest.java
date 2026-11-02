package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.persistence.*;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** 隔离 PG 与真实管理员 HTTP，验证创建和原回执，不调用模型。 */
@TestPropertySource(properties={"skillforge.handoff-reply-delay-ms=3600000","skillforge.session-outbox-cron=-","agent-runtime.workers.auto-startup=false"})
class HandoffCreateReceiptHttpIntegrationTest extends AbstractIntegrationTest {
    @Autowired TenantMapper tenants; @Autowired PlanMapper plans; @Autowired EndUserMapper users;
    @Autowired AgentMapper agents; @Autowired SessionMapper sessions; @Autowired ObjectMapper json;
    @Autowired FoundationAccess access; @Autowired FoundationOrganizationService organization;
    @Autowired com.skillforge.institution.service.HandoffApplicationService handoffs;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource dataSource;
    @org.springframework.boot.test.mock.mockito.SpyBean com.skillforge.institution.ws.AgentSessionRegistry notifications;
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    @org.junit.jupiter.api.AfterEach void clearTenant() { TenantContext.clear(); }
    @Test void originalReceiptAndReplyCanBeLocatedByCommand() throws Exception {
        String suffix=Long.toString(SnowflakeIds.nextId()), slug="handoff-http-"+suffix;
        String tenant=createTenant(tenants,plans,slug); TenantContext.setTenantId(tenant);
        long admin=createTestTenantAdministrator(tenant,"handoff-admin","SyntheticHandoff123");
        var user=new EndUserDO();user.setId(SnowflakeIds.nextId());user.setTenantId(Long.parseLong(tenant));user.setDisplayName("验收用户");user.setStatus("active");users.insert(user);
        var agent=new AgentDO();agent.setId(SnowflakeIds.nextId());agent.setTenantId(Long.parseLong(tenant));agent.setAgentKey("handoff-"+suffix);agent.setName("验收助手");agent.setTemplateVersion("1.0");agent.setSystemPrompt("测试");agent.setRoleDescription("测试");agent.setStatus("published");agents.insert(agent);
        var session=new SessionDO();session.setId(SnowflakeIds.nextId());session.setSessionId("handoff-http-"+session.getId());session.setTenantId(Long.parseLong(tenant));session.setEndUserId(user.getId());session.setAgentId(agent.getId());session.setOriginKind("native");session.setActorRef("end_user:"+user.getId());session.setResponsibilityRef("tenant:"+tenant);session.setMessageCount(0);sessions.insert(session);
        access.withSubject(new FoundationAccess.Subject(false,tenant,Long.toString(admin)),()->{organization.registerSessionOwnership(session.getSessionId());return null;});
        String token=call(null,"POST","/api/v1/auth/login",Map.of("tenant_slug",slug,"username","handoff-admin","password","SyntheticHandoff123"),200).path("access_token").asText();
        String command=UUID.randomUUID().toString(), base="/api/v1/handoff/tickets";
        Map<String,Object> body=Map.of("command_id",command,"session_id",session.getSessionId(),"reason","user_request");
        var createRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+base))
                .header("Content-Type","application/json").header("Authorization","Bearer "+token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var left=http.sendAsync(createRequest,HttpResponse.BodyHandlers.ofString());
        var right=http.sendAsync(createRequest,HttpResponse.BodyHandlers.ofString());
        var leftResponse=left.join();var rightResponse=right.join();
        assertEquals(200,leftResponse.statusCode(),leftResponse.body());assertEquals(200,rightResponse.statusCode(),rightResponse.body());
        var first=json.readTree(leftResponse.body()).path("data");String id=first.path("id").asText();
        assertEquals(id,json.readTree(rightResponse.body()).path("data").path("id").asText());
        org.mockito.Mockito.verify(notifications,org.mockito.Mockito.times(1)).notifyTicketChanged(tenant,id);
        assertEquals("pending",first.path("status").asText());assertEquals(session.getSessionId(),first.path("session_id").asText());
        assertEquals(id,call(token,"POST",base,body,200).path("id").asText());
        call(token,"POST",base,Map.of("command_id",command,"session_id",session.getSessionId(),"reason","other"),409);
        var receipt=call(token,"GET",base+"/create-receipt?command_id="+command,null,200);
        assertEquals(id,receipt.path("id").asText());assertEquals(command,receipt.path("command_id").asText());
        call(token,"GET",base+"/create-receipt?command_id="+UUID.randomUUID(),null,404);
        String acceptCommand=UUID.randomUUID().toString();
        var accept=Map.of("ticket_id",id,"expected_revision",1,"command_id",acceptCommand);
        var acceptedAction=call(token,"POST",base+"/accept",accept,200);
        assertEquals(acceptCommand,acceptedAction.path("command_id").asText());
        assertEquals("accept",acceptedAction.path("action").asText());assertEquals("assigned",acceptedAction.path("status").asText());
        assertEquals(id,call(token,"GET",base+"/action-receipt?ticket_id="+id+"&command_id="+acceptCommand,null,200).path("ticket_id").asText());
        assertEquals("assigned",call(token,"POST",base+"/accept",accept,200).path("status").asText());
        assertTrue(call(token,"GET",base+"/action-receipt?ticket_id="+id+"&command_id="+UUID.randomUUID(),null,200).isNull());
        call(token,"POST",base+"/accept",Map.of("ticket_id",id,"expected_revision",2,"command_id",acceptCommand),409);
        String reply=UUID.randomUUID().toString();
        assertEquals("accepted",call(token,"POST",base+"/replies",Map.of("ticket_id",id,"expected_revision",2,"command_id",reply,"content","验收回复"),200).path("status").asText());
        assertEquals("accepted",call(token,"GET",base+"/reply-receipt?ticket_id="+id+"&command_id="+reply,null,200).path("status").asText());
        assertEquals("accepted",call(token,"POST",base+"/replies",Map.of("ticket_id",id,"expected_revision",2,"command_id",reply,"content","验收回复"),200).path("status").asText());
        String stale=UUID.randomUUID().toString();
        var staleRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+base+"/replies"))
                .header("Content-Type","application/json").header("Authorization","Bearer "+token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "ticket_id",id,"expected_revision",2,"command_id",stale,"content","过期回复")))).build();
        var staleLeft=http.sendAsync(staleRequest,HttpResponse.BodyHandlers.ofString());
        var staleRight=http.sendAsync(staleRequest,HttpResponse.BodyHandlers.ofString());
        var rejected=staleLeft.join();var rejectedAgain=staleRight.join();
        assertEquals(409,rejected.statusCode());assertEquals("HANDOFF_REPLY_NOT_ACCEPTED",json.readTree(rejected.body()).path("code").asText());
        assertEquals(409,rejectedAgain.statusCode());assertEquals("HANDOFF_REPLY_NOT_ACCEPTED",json.readTree(rejectedAgain.body()).path("code").asText());
        call(token,"GET",base+"/reply-receipt?ticket_id="+id+"&command_id="+stale,null,404);
        var futureRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+base+"/replies"))
                .header("Content-Type","application/json").header("Authorization","Bearer "+token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "ticket_id",id,"expected_revision",100,"command_id",UUID.randomUUID().toString(),"content","未来修订")))).build();
        var future=http.send(futureRequest,HttpResponse.BodyHandlers.ofString());
        assertEquals(409,future.statusCode());assertNotEquals("HANDOFF_REPLY_NOT_ACCEPTED",json.readTree(future.body()).path("code").asText());
        String delayed=UUID.randomUUID().toString();
        var delayedRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+base+"/replies"))
                .header("Content-Type","application/json").header("Authorization","Bearer "+token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "ticket_id",id,"expected_revision",3,"command_id",delayed,"content","等待锁的原回复")))).build();
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(var bind=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){bind.setString(1,tenant);bind.execute();}
            int locker;
            try(var query=connection.createStatement();var result=query.executeQuery("SELECT pg_backend_pid()")){assertTrue(result.next());locker=result.getInt(1);}
            try(var update=connection.prepareStatement("UPDATE nw_human_handoff_tickets SET revision=revision+1 WHERE tenant_id=? AND ticket_id=? AND revision=3")) {
                update.setLong(1,Long.parseLong(tenant));update.setString(2,id);assertEquals(1,update.executeUpdate());
            }
            var waitingFirst=http.sendAsync(delayedRequest,HttpResponse.BodyHandlers.ofString());
            var waitingSecond=http.sendAsync(delayedRequest,HttpResponse.BodyHandlers.ofString());
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);long waiting=0;
            try(var observer=dataSource.getConnection()) {
                while(System.nanoTime()<deadline) {
                    try(var query=observer.prepareStatement("WITH RECURSIVE blocked(pid) AS (SELECT ?::int UNION SELECT a.pid FROM pg_stat_activity a JOIN blocked b ON b.pid=ANY(pg_blocking_pids(a.pid))) SELECT count(*) FROM blocked b JOIN pg_stat_activity a ON a.pid=b.pid WHERE a.datname=current_database() AND a.wait_event_type='Lock' AND lower(a.query) LIKE '%select ticket_id from nw_human_handoff_tickets%'")) {
                        query.setInt(1,locker);try(var result=query.executeQuery()){result.next();waiting=result.getLong(1);}
                    }
                    if(waiting>=2)break;
                    java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100));
                }
            }
            assertTrue(waiting>=2,"两个原请求必须真实等待同一工单行锁，实际阻塞链长度="+waiting
                    +"，请求已完成="+waitingFirst.isDone()+"/"+waitingSecond.isDone());connection.commit();
            for(var result:java.util.List.of(waitingFirst.join(),waitingSecond.join())) {
                assertEquals(409,result.statusCode());assertEquals("HANDOFF_REPLY_NOT_ACCEPTED",json.readTree(result.body()).path("code").asText());
            }
        }
        call(token,"GET",base+"/reply-receipt?ticket_id="+id+"&command_id="+delayed,null,404);
        var late=http.send(delayedRequest,HttpResponse.BodyHandlers.ofString());
        assertEquals(409,late.statusCode());assertEquals("HANDOFF_REPLY_NOT_ACCEPTED",json.readTree(late.body()).path("code").asText());
        var conflictRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+base+"/replies"))
                .header("Content-Type","application/json").header("Authorization","Bearer "+token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "ticket_id",id,"expected_revision",2,"command_id",reply,"content","其他正文")))).build();
        var conflict=http.send(conflictRequest,HttpResponse.BodyHandlers.ofString());
        assertEquals(409,conflict.statusCode());assertNotEquals("HANDOFF_REPLY_NOT_ACCEPTED",json.readTree(conflict.body()).path("code").asText());
        call(token,"GET",base+"/reply-receipt?ticket_id="+id+"&command_id="+UUID.randomUUID(),null,404);
        String resolveCommand=UUID.randomUUID().toString();
        long currentRevision=call(token,"GET",base+"/detail?ticket_id="+id,null,200).path("ticket").path("revision").asLong();
        var resolution=Map.of("ticket_id",id,"expected_revision",currentRevision,"command_id",resolveCommand,"payload",Map.of("resolution_note","已核实"));
        assertEquals("resolved",call(token,"POST",base+"/resolve",resolution,200).path("status").asText());
        assertEquals("resolve",call(token,"GET",base+"/action-receipt?ticket_id="+id+"&command_id="+resolveCommand,null,200).path("action").asText());
        assertEquals("resolved",call(token,"POST",base+"/resolve",resolution,200).path("status").asText());
        String rolledBack=UUID.randomUUID().toString();
        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
            access.withSubject(new FoundationAccess.Subject(false,tenant,Long.toString(admin)),()->handoffs.create(Long.parseLong(tenant),
                    new com.skillforge.core.chat.dto.handoff.CreateHandoffTicketRequest("user_request",null,session.getSessionId(),null,null,rolledBack)));
            status.setRollbackOnly();return null;
        });
        call(token,"GET",base+"/create-receipt?command_id="+rolledBack,null,404);
        String foreignSlug="handoff-foreign-"+suffix;
        TenantContext.clear();String foreign=createTenant(tenants,plans,foreignSlug);TenantContext.setTenantId(foreign);
        createTestTenantAdministrator(foreign,"foreign-admin","SyntheticHandoff123");
        String foreignToken=call(null,"POST","/api/v1/auth/login",Map.of("tenant_slug",foreignSlug,"username","foreign-admin","password","SyntheticHandoff123"),200).path("access_token").asText();
        call(foreignToken,"GET",base+"/create-receipt?command_id="+command,null,404);
        call(foreignToken,"GET",base+"/action-receipt?ticket_id="+id+"&command_id="+acceptCommand,null,404);
    }
    private JsonNode call(String token,String method,String path,Object body,int expected) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),response.body());
        return json.readTree(response.body()).path("data");
    }
}

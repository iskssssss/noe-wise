package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
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
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** 隔离管理员、产品 Session 和浏览器；不发送会话消息或调用模型。 */
@Tag("knowledge-create-browser")
@TestPropertySource(properties={"skillforge.handoff-reply-delay-ms=3600000","skillforge.session-outbox-cron=-","agent-runtime.workers.auto-startup=false"})
class HandoffCreateBrowserE2ETest extends AbstractIntegrationTest {
    private static final KnowledgeBrowserServer FRONTEND=KnowledgeBrowserServer.startIsolated("handoff-create-browser-");
    @DynamicPropertySource static void origins(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins",FRONTEND::baseUrl); }
    @AfterAll static void closeFrontend(){FRONTEND.close();}
    @AfterEach void clear(){TenantContext.clear();}
    @Autowired TenantMapper tenants; @Autowired PlanMapper plans; @Autowired EndUserMapper users;
    @Autowired AgentMapper agents; @Autowired SessionMapper sessions; @Autowired ObjectMapper json;
    @Autowired FoundationAccess access; @Autowired FoundationOrganizationService organization; @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @Test void exactTicketAndManualClaim() throws Exception {
        String suffix=Long.toString(SnowflakeIds.nextId()),slug="handoff-browser-"+suffix;
        String tenant=createTenant(tenants,plans,slug),password="SyntheticHandoffBrowser123";TenantContext.setTenantId(tenant);
        long adminId=createTestTenantAdministrator(tenant,"handoff-browser-admin",password);
        var user=new EndUserDO();user.setId(SnowflakeIds.nextId());user.setTenantId(Long.parseLong(tenant));user.setDisplayName("验收用户");user.setStatus("active");users.insert(user);
        var agent=new AgentDO();agent.setId(SnowflakeIds.nextId());agent.setTenantId(Long.parseLong(tenant));agent.setAgentKey("handoff-browser-"+suffix);agent.setName("验收助手");agent.setTemplateVersion("1.0");agent.setSystemPrompt("测试");agent.setRoleDescription("测试");agent.setStatus("published");agents.insert(agent);
        var session=new SessionDO();session.setId(SnowflakeIds.nextId());session.setSessionId("handoff-browser-"+session.getId());session.setTenantId(Long.parseLong(tenant));session.setEndUserId(user.getId());session.setAgentId(agent.getId());session.setOriginKind("native");session.setActorRef("end_user:"+user.getId());session.setResponsibilityRef("tenant:"+tenant);session.setMessageCount(0);sessions.insert(session);
        access.withSubject(new FoundationAccess.Subject(false,tenant,Long.toString(adminId)),()->{organization.registerSessionOwnership(session.getSessionId());return null;});
        FRONTEND.bindBackend("http://127.0.0.1:"+port);
        Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin"),evidence=FRONTEND.evidence();
        var command=new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),admin.resolve("node_modules/@playwright/test/cli.js").toString(),"test","--config=playwright.handoff-create.config.ts");
        command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",evidence.toString());env.put("E2E_TENANT_SLUG",slug);env.put("E2E_USERNAME","handoff-browser-admin");env.put("E2E_PASSWORD",password);env.put("E2E_SESSION_ID",session.getSessionId());
        Process process=command.start();
        try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
            while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}
            owned.capture();assertFalse(process.isAlive(),"浏览器验收超时："+evidence);assertEquals(0,process.exitValue(),()->"浏览器验收失败："+evidence);
        }
        var receipt=json.readTree(Files.readString(evidence.resolve("handoff-create-receipt.json"))).path("data");
        String ticket=receipt.path("id").asText();assertFalse(ticket.isBlank());assertEquals(session.getSessionId(),receipt.path("session_id").asText());
        var rows=queryRows(jdbc,tenant,"SELECT ticket_id,session_id,status,assigned_to,create_command_id FROM nw_human_handoff_tickets WHERE tenant_id=? AND ticket_id=?",Long.parseLong(tenant),ticket);
        assertEquals(1,rows.size());assertEquals(session.getSessionId(),rows.getFirst().get("session_id"));assertEquals("assigned",rows.getFirst().get("status"));assertEquals(adminId,((Number)rows.getFirst().get("assigned_to")).longValue());
        assertEquals(receipt.path("command_id").asText(),rows.getFirst().get("create_command_id"));
    }
}

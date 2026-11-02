package com.skillforge.institution.integration;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.channel.mapper.ChannelAgentBindingMapper;
import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.model.ChannelAgentBindingDO;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.institution.service.HandoffApplicationService;
import com.skillforge.institution.service.HandoffReplyMapper;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.SessionHumanAssistance;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** 隔离 PG/Redis、随机后端/双前端端口的单次人工协助浏览器旅程，不调用模型。 */
@TestPropertySource(properties={"skillforge.handoff-reply-delay-ms=1000", "skillforge.session-outbox-cron=*/1 * * * * *",
        "agent-runtime.workers.auto-startup=false"})
class HandoffHumanAssistanceBrowserE2ETest extends AbstractIntegrationTest {
    private static final int ADMIN_PORT=availablePort(), CHAT_PORT=availablePort();
    private static final String PASSWORD="HandoffBrowser123", USERNAME="handoff-browser-admin";
    @DynamicPropertySource static void browserOrigins(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins",()->"http://127.0.0.1:"+ADMIN_PORT+",http://127.0.0.1:"+CHAT_PORT);
    }
    @Autowired TenantMapper tenants; @Autowired PlanMapper plans; @Autowired TenantMemberMapper members;
    @Autowired AgentMapper agents; @Autowired ChannelMapper channels; @Autowired ChannelAgentBindingMapper bindings;
    @Autowired SessionMapper sessions; @Autowired PasswordEncoder passwords; @Autowired ObjectMapper json;
    @Autowired SessionLogPort log; @Autowired HandoffReplyMapper replies; @Autowired HandoffApplicationService handoffs;
    @Autowired FoundationAccess access; @Autowired FoundationOrganizationService organization;
    @Autowired AgentPublicationService publications;
    @LocalServerPort int port;

    @Test void realBrowserCreatesRepliesRestoresBothClientsAndResolvesTicket() throws Exception {
        String run=Long.toString(SnowflakeIds.nextId()), slug="handoff-browser-"+run;
        String tenant=createTenant(tenants,plans,slug), token="handoff-browser-token-"+UUID.randomUUID();
        TenantContext.setTenantId(tenant);
        try {
            long owner=Long.parseLong(tenant);
            var member=new TenantMemberDO();member.setId(SnowflakeIds.nextId());member.setTenantId(owner);
            member.setUsername(USERNAME);member.setRole("admin");member.setPermissions(List.of());member.setStatus("active");
            member.setPasswordHash(passwords.encode(PASSWORD));member.setCreatedAt(Instant.now());members.insert(member);
            organization.initializeTenant(tenant,member.getId().toString());
            var subject=new FoundationAccess.Subject(false,tenant,member.getId().toString());
            var agent=new AgentDO();agent.setId(SnowflakeIds.nextId());agent.setTenantId(owner);agent.setAgentKey("handoff-browser-"+run);
            agent.setName("Handoff Browser Agent");agent.setStatus("published");agent.setSystemPrompt("浏览器验收不调用模型");
            agent.setTemplateVersion("1.0");agent.setRoleDescription("人工协助验收助手");agents.insert(agent);
            access.withSubject(subject,()->{organization.registerResource("agent",agent.getId().toString());publications.publish(agent);return null;});
            var channel=new ChannelDO();channel.setId(SnowflakeIds.nextId());channel.setTenantId(owner);
            channel.setName("Handoff isolated browser");channel.setChannelType("openapi");channel.setStatus("active");
            channel.setChannelTokenHash(TokenHash.sha256Hex(token));channel.setConfig(Map.of());channels.insert(channel);
            var binding=new ChannelAgentBindingDO();binding.setId(SnowflakeIds.nextId());binding.setTenantId(owner);
            binding.setChannelId(channel.getId());binding.setAgentId(agent.getId());binding.setIsDefault(true);
            binding.setPriority(0);binding.setTrafficPercentage(100);bindings.insert(binding);
            Path root=repoRoot(), admin=root.resolve("frontend-admin");
            Path cli=admin.resolve("node_modules/@playwright/test/cli.js");
            assertTrue(Files.isRegularFile(cli),"验收仅使用现有 Playwright，不安装依赖");
            Path evidence=Path.of("build/runtime-e2e/handoff-browser-"+run).toAbsolutePath();Files.createDirectories(evidence);
            var command=new ProcessBuilder(nodeExecutable(),cli.toString(),"test","--config=playwright.handoff.config.ts");
            command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright-vite.log").toFile());
            var env=command.environment();env.remove("E2E_BASE_URL");env.remove("SDK4_LIVE_MODEL_KEY");env.remove("E2E_LIVE_MODEL");
            env.put("E2E_FRONTEND_PORT",Integer.toString(ADMIN_PORT));env.put("E2E_CHAT_PORT",Integer.toString(CHAT_PORT));
            env.put("E2E_BACKEND_URL","http://127.0.0.1:"+port);env.put("E2E_TENANT_SLUG",slug);
            env.put("E2E_USERNAME",USERNAME);env.put("E2E_PASSWORD",PASSWORD);env.put("E2E_AGENT_ID",agent.getId().toString());
            env.put("E2E_CHANNEL_TOKEN",token);env.put("E2E_RUN_ID",run);env.put("E2E_ARTIFACT_DIR",evidence.toString());
            Process process=command.start();
            // try-with-resources 将清理失败作为 suppressed 保留，不能覆盖浏览器的原始断言。
            try(var owned=new OwnedBrowserProcesses(process.toHandle())) {
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);boolean finished=false;
                while(System.nanoTime()<deadline){owned.capture();if(process.waitFor(200,TimeUnit.MILLISECONDS)){finished=true;break;}}
                owned.capture();assertTrue(finished,"浏览器旅程超出5分钟；证据="+evidence);
                String output=Files.readString(evidence.resolve("playwright-vite.log"),StandardCharsets.UTF_8);
                assertEquals(0,process.exitValue(),"浏览器失败；证据="+evidence+"\n"+output.substring(Math.max(0,output.length()-7000)));
            }
            var receipt=json.readTree(Files.readString(evidence.resolve("browser-receipt.json"),StandardCharsets.UTF_8));
            String sessionId=receipt.path("sessionId").asText(), ticketId=receipt.path("ticketId").asText(), commandId=receipt.path("commandId").asText();
            var session=sessions.selectOne(new LambdaQueryWrapper<SessionDO>().eq(SessionDO::getTenantId,owner).eq(SessionDO::getSessionId,sessionId));
            assertNotNull(session);assertEquals(channel.getId(),session.getChannelId());
            assertEquals(receipt.path("user").asLong(),session.getEndUserId());
            var sourceOwner=SessionOwner.user(new SessionRef(new TenantId(tenant),sessionId),new AgentId(agent.getId().toString()),new EndUserId(session.getEndUserId()));
            var facts=log.readAfter(new SessionLogPort.ReadSessionLog(sourceOwner,0,100));
            var human=facts.stream().filter(SessionHumanAssistance::supports).toList();assertEquals(1,human.size());
            assertEquals(commandId,SessionHumanAssistance.content(human.getFirst()).commandId());
            assertEquals(receipt.path("text").asText(),SessionHumanAssistance.content(human.getFirst()).text());
            assertTrue(facts.stream().noneMatch(e->e.runRef().isPresent()),"浏览器人工旅程不得创建模型Run");
            var reply=replies.find(owner,commandId);assertNotNull(reply);assertEquals("committed",reply.getStatus());
            assertEquals(human.getFirst().eventId().value(),reply.getSourceEventId());
            var ticket=access.withSubject(subject,()->handoffs.require(owner,ticketId));assertEquals("resolved",ticket.getStatus());
            assertEquals(receipt.path("note").asText(),ticket.getResolutionNote());assertEquals(0,receipt.path("chatSubmissions").asInt());
            Files.writeString(evidence.resolve("database-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "sessionId",sessionId,"ticketId",ticketId,"commandId",commandId,"sourceEventId",reply.getSourceEventId(),
                    "sourceSequence",reply.getSourceSequence(),"humanFactCount",human.size(),"replyStatus",reply.getStatus(),
                    "ticketStatus",ticket.getStatus(),"modelRunCount",0)),StandardCharsets.UTF_8);
        } finally {TenantContext.clear();}
    }
    private static String nodeExecutable(){String configured=System.getenv("NODE_BINARY");return configured==null||configured.isBlank()?"node":configured;}
    private static Path repoRoot(){Path path=Path.of("").toAbsolutePath();while(path!=null){if(Files.isRegularFile(path.resolve("frontend-admin/package.json")))return path;path=path.getParent();}throw new IllegalStateException("repository_root_missing");}
    private static int availablePort(){try(var socket=new ServerSocket(0)){return socket.getLocalPort();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    /** 只保存本次直接创建的进程与其已确认后代，绝不按名称或全局 PID 扩大清理。 */
    private static final class OwnedBrowserProcesses implements AutoCloseable {
        private final Set<ProcessHandle> owned=new LinkedHashSet<>();
        private final Set<Long> snapshotWarnings=new HashSet<>();
        OwnedBrowserProcesses(ProcessHandle root){owned.add(root);}
        void capture(){
            for(var handle:List.copyOf(owned)) {
                if(!handle.isAlive())continue;
                try(var descendants=handle.descendants()){descendants.forEach(owned::add);}
                catch(RuntimeException failure){
                    // Windows 的进程退出竞态可能让 Toolhelp 快照暂不可用；下一次轮询重读。
                    if(!"snapshot not available".equals(failure.getMessage()))throw failure;
                    if(snapshotWarnings.add(handle.pid()))System.err.println("Owned browser process snapshot unavailable; pid="+handle.pid()+"; subsequent polling retries capture");
                }
            }
        }
        @Override public void close(){
            var failures=new ArrayList<RuntimeException>();captureForCleanup(failures);
            destroy(false,failures);awaitExit(5,failures);destroy(true,failures);awaitExit(10,failures);
            var alive=owned.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
            if(!alive.isEmpty()||!failures.isEmpty()) {
                var failure=new AssertionError("验收自有子树清理未完成；仍存活PID="+alive);
                failures.forEach(failure::addSuppressed);throw failure;
            }
        }
        private void destroy(boolean force,List<RuntimeException> failures){
            for(var handle:List.copyOf(owned).reversed())if(handle.isAlive())try {
                if(force)handle.destroyForcibly();else handle.destroy();
            }catch(RuntimeException failure){failures.add(failure);}
        }
        private void captureForCleanup(List<RuntimeException> failures){
            try{capture();}catch(RuntimeException failure){
                if(failures.stream().noneMatch(previous->previous.getClass()==failure.getClass()
                        &&Objects.equals(previous.getMessage(),failure.getMessage())))failures.add(failure);
            }
        }
        private void awaitExit(int seconds,List<RuntimeException> failures){
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(owned.stream().anyMatch(ProcessHandle::isAlive)&&System.nanoTime()<end){captureForCleanup(failures);LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));}
        }
    }
}

package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.group.GroupBindingService;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实记忆、治理页面及相同归属的 Runtime 查询。 */
class MemoryDirectoryLimitBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("memory-directory-limit-");
    @DynamicPropertySource static void origin(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl); }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @Autowired SessionLogPort sessionLog;
    @Autowired MemoryRuntime memoryRuntime;
    @Autowired JdbcMemoryPort memoryStore;
    @Autowired GroupBindingService groups;
    @Autowired DataSource dataSource;

    @Test void oldestFactOutsideFirstHundredRemainsRuntimeSearchable() throws Exception {
        var owner = SessionOwner.user(new SessionRef(new TenantId(tenantId), sessionId),
                new AgentId(agentId), new EndUserId(endUserId));
        String target = "特殊航线核验标记" + SnowflakeIds.nextId();
        TenantContext.setTenantId(tenantId);
        MemoryFact oldest;
        try {
            oldest = createFact(owner, target);
            for (int i = 0; i < 100; i++) createFact(owner, "常规偏好样本" + i + "-" + SnowflakeIds.nextId());
            var execution = new MemoryInjectionProgramFixture(dataSource, memoryRuntime, memoryStore, groups, owner, target);
            execution.drive();
            assertEquals(RunStatus.COMPLETED, execution.store().load(execution.identity.runRef()).orElseThrow().status());
            assertEquals(1, execution.toolCalls());
            assertTrue(objectMapper.writeValueAsString(execution.toolResult()).contains(oldest.ref().factId()), "同一事实应由真实 Runtime 检索到");
            Path evidence=Path.of("build","runtime-e2e","memory-directory-limit-"+SnowflakeIds.nextId()).toAbsolutePath();
            Path browser=evidence.resolve("playwright"); Files.createDirectories(browser);
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writeValueAsString(Map.of(
                    "factId",oldest.ref().factId(),"target",target,"runId",execution.identity.runRef().runId(),
                    "toolCalls",execution.toolCalls(),"status","COMPLETED")), StandardCharsets.UTF_8);
            Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path cli=admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
            FRONTEND.bindBackend(baseUrl());
            var command=new ProcessBuilder(cli.toString(),"test","e2e/memory-directory-limit-real.spec.ts");
            command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env=command.environment(); env.put("E2E_BASE_URL",FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR",browser.toString());
            env.put("E2E_TENANT_SLUG",fixtureTenantSlug()); env.put("E2E_PASSWORD",PASSWORD); env.put("E2E_TARGET_TEXT",target);
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process=command.start();
            try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4);
                while(process.isAlive() && System.nanoTime()<deadline) { owned.capture(); process.waitFor(200,TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(process.isAlive(),"浏览器验收超时："+evidence);
                assertEquals(0,process.exitValue(),"浏览器验收失败："+evidence+"\n"+Files.readString(evidence.resolve("playwright.log")));
            }
            var facts=objectMapper.readTree(Files.readString(browser.resolve("directory-baseline.json")));
            String mode=System.getenv().getOrDefault("E2E_MEMORY_DIRECTORY_MODE","candidate");
            assertEquals(mode,facts.path("mode").asText()); assertEquals(100,facts.path("listCount").asInt());
            assertEquals("candidate".equals(mode),facts.path("targetVisible").asBoolean());
        } finally { TenantContext.clear(); }
    }

    private MemoryFact createFact(SessionOwner owner, String predicate) {
        String suffix=Long.toString(SnowflakeIds.nextId()), eventId="memory-limit-source-"+suffix;
        InputId input=new InputId("memory-limit-input-"+suffix); Instant now=Instant.now();
        var trace=new TraceEnvelope("memory-limit-trace-"+suffix,"memory-limit-span-"+suffix,input,Optional.empty());
        var target=new SessionProjectionTarget(owner,input,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty());
        var source=new SessionEventDraft(new EventId(eventId),"inbound.message","v1",eventId,trace,
                new TypedPayload("input.v1","{\"extraParameters\":{},\"text\":\"合成记忆样本\"}"),now);
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class,sessionLog.append(new SessionLogPort.AppendSessionEvent(target,source)));
        var draft=new MemoryPort.MemoryFactDraft(new MemoryOwner.UserAgent(owner.sessionRef().tenantId(),owner.requireUser(),owner.target().requireAgent()),
                new MemoryTypeRef("preference"),new RetentionClassRef("standard"),"v2","user:"+owner.requireUser().value(),predicate,
                new TypedPayload("skillforge.memory.preference.v2",com.skillforge.core.agent.memory.MemoryContent.explicit(
                        objectMapper.createObjectNode().put("value",predicate),"preference").toString()),
                List.of(new EventId(eventId)),owner.sessionRef(),Optional.empty(),Optional.empty(),"memory-limit-fixture-v1",
                now,Optional.empty(),Optional.empty(),0.95,0.6,false,Optional.empty(),FactStatus.ACTIVE);
        var runRef=new RunRef(owner.sessionRef().tenantId(),"memory-limit-run-"+suffix);
        var run=new RunIdentity(owner,owner.target().requireAgent(),input,runRef,trace,new ResultRef("memory-limit-cause-"+suffix),
                new RunRequester.EndUser(owner.requireUser()),Optional.empty(),new RunOriginRef(owner.sessionRef().tenantId(),"execution-input:"+runRef.runId()));
        var identity=new VerifiedIdentity(new PrincipalRef.SystemPrincipal(owner.sessionRef().tenantId(),"memory-limit-fixture"),
                owner,Set.of("memory:extract"),1,new ResultRef("memory-limit-verified-"+suffix));
        var context=new PortCallContext(run,identity,new InvocationControl(Instant.now().plusSeconds(30),()->false));
        return assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class,memoryRuntime.mutate(
                new MemoryRuntime.MutationCall(context,new MemoryRuntime.Mutation.Create(new IdempotencyKey("memory-limit-create-"+suffix),draft),now))).fact();
    }
}

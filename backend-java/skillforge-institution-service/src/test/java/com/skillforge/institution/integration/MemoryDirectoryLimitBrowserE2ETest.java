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
        if (System.getenv("E2E_MEMORY_RESTORE") != null) { decayedFactGovernanceAndRuntime(); return; }
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

    private void decayedFactGovernanceAndRuntime() throws Exception {
        String mode = System.getenv("E2E_MEMORY_RESTORE");
        assertTrue(Set.of("baseline", "candidate").contains(mode));
        var owner = SessionOwner.user(new SessionRef(new TenantId(tenantId), sessionId), new AgentId(agentId), new EndUserId(endUserId));
        String target = "衰减记忆恢复标记" + SnowflakeIds.nextId();
        Path evidence = Path.of("build", "runtime-e2e", "memory-decayed-restore-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright"); Files.createDirectories(browser);
        TenantContext.setTenantId(tenantId);
        try {
            MemoryFact original = createFact(owner, target);
            String reviewTarget = "待审核拒绝标记" + SnowflakeIds.nextId();
            MemoryFact pending = createFact(owner, reviewTarget, FactStatus.PENDING_CONFIRM, true);
            assertEquals(FactStatus.PENDING_CONFIRM, pending.status()); assertTrue(pending.reviewRequired());
            String suffix = Long.toString(SnowflakeIds.nextId());
            InputId input = new InputId("memory-decay-input-" + suffix);
            var trace = new TraceEnvelope("memory-decay-trace-" + suffix, "memory-decay-span-" + suffix, input, Optional.empty());
            var runRef = new RunRef(owner.sessionRef().tenantId(), "memory-decay-run-" + suffix);
            var run = new RunIdentity(owner, owner.target().requireAgent(), input, runRef, trace,
                    new ResultRef("memory-decay-cause-" + suffix), new RunRequester.System(
                            new PrincipalRef.SystemPrincipal(owner.sessionRef().tenantId(), "memory-governance")), Optional.empty(),
                    new RunOriginRef(owner.sessionRef().tenantId(), "execution-input:" + runRef.runId()));
            var identity = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(owner.sessionRef().tenantId(), "memory-governance"),
                    owner, Set.of("memory:manage"), 1, new ResultRef("memory-decay-verified-" + suffix));
            var context = new PortCallContext(run, identity, new InvocationControl(Instant.now().plusSeconds(30), () -> false));
            var decayed = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, memoryRuntime.mutate(new MemoryRuntime.MutationCall(context,
                    new MemoryRuntime.Mutation.Transition(new IdempotencyKey("memory-decay-" + suffix), original.ref(), original.revision(), original.scope(), original.type(),
                            FactStatus.DECAYED, "memory_restore_fixture_decay"), Instant.now()))).fact();
            assertEquals(FactStatus.DECAYED, decayed.status()); assertEquals(original.revision() + 1, decayed.revision());
            var before = new MemoryInjectionProgramFixture(dataSource, memoryRuntime, memoryStore, groups, owner, target);
            before.drive();
            assertEquals(RunStatus.COMPLETED, before.store().load(before.identity.runRef()).orElseThrow().status());
            assertEquals(1, before.toolCalls());
            assertFalse(objectMapper.writeValueAsString(before.toolResult()).contains(original.ref().factId()), "衰减事实不能进入新运行检索结果");
            Files.writeString(evidence.resolve("before-runtime.json"), objectMapper.writeValueAsString(Map.of("factId", original.ref().factId(), "revision", decayed.revision(),
                    "status", decayed.status(), "runId", before.identity.runRef().runId(), "toolResult", before.toolResult())));
            Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path cli = admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
            FRONTEND.bindBackend(baseUrl());
            var command = new ProcessBuilder(cli.toString(), "test", "e2e/memory-directory-limit-real.spec.ts");
            command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
            env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_TARGET_TEXT", target);
            env.put("E2E_MEMORY_FACT_ID", original.ref().factId()); env.put("E2E_MEMORY_REVISION", Long.toString(decayed.revision()));
            env.put("E2E_MEMORY_REVIEW_TARGET", reviewTarget); env.put("E2E_MEMORY_REVIEW_FACT_ID", pending.ref().factId());
            env.put("E2E_MEMORY_REVIEW_REVISION", Long.toString(pending.revision()));
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
                assertEquals(0, process.exitValue(), "浏览器验收失败：" + evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            }
            var facts = objectMapper.readTree(Files.readString(browser.resolve("memory-restore.json")));
            assertEquals(original.ref().factId(), facts.path("factId").asText()); assertEquals(mode, facts.path("mode").asText());
            var rows = queryRows(jdbc, tenantId, "SELECT h.fact_id,h.current_revision,r.status FROM agent_runtime_memory_fact_heads h JOIN agent_runtime_memory_fact_revisions r ON r.tenant_id=h.tenant_id AND r.fact_id=h.fact_id AND r.revision=h.current_revision WHERE h.tenant_id=? AND h.fact_id=?", tenantId, original.ref().factId());
            Files.writeString(evidence.resolve("database-state.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows));
            assertEquals(1, rows.size());
            assertEquals("baseline".equals(mode) ? "DECAYED" : "ACTIVE", rows.getFirst().get("status"));
            assertEquals("baseline".equals(mode) ? decayed.revision() : decayed.revision() + 1, ((Number) rows.getFirst().get("current_revision")).longValue());
            var rejected = queryRows(jdbc, tenantId, "SELECT h.fact_id,h.current_revision,r.status FROM agent_runtime_memory_fact_heads h JOIN agent_runtime_memory_fact_revisions r ON r.tenant_id=h.tenant_id AND r.fact_id=h.fact_id AND r.revision=h.current_revision WHERE h.tenant_id=? AND h.fact_id=?", tenantId, pending.ref().factId());
            assertEquals(1, rejected.size()); assertEquals("REJECTED", rejected.getFirst().get("status"));
            assertEquals(pending.revision() + 1, ((Number) rejected.getFirst().get("current_revision")).longValue());
            Files.writeString(evidence.resolve("rejected-database-state.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rejected));
            if ("candidate".equals(mode)) {
                var after = new MemoryInjectionProgramFixture(dataSource, memoryRuntime, memoryStore, groups, owner, target);
                after.drive(); assertEquals(RunStatus.COMPLETED, after.store().load(after.identity.runRef()).orElseThrow().status());
                assertEquals(1, after.toolCalls());
                assertTrue(objectMapper.writeValueAsString(after.toolResult()).contains(original.ref().factId()), "恢复后新运行须检索到原事实");
                Files.writeString(evidence.resolve("after-runtime.json"), objectMapper.writeValueAsString(Map.of("factId", original.ref().factId(), "runId", after.identity.runRef().runId(), "toolResult", after.toolResult())));
            }
        } finally { TenantContext.clear(); }
    }

    private MemoryFact createFact(SessionOwner owner, String predicate) {
        return createFact(owner, predicate, FactStatus.ACTIVE, false);
    }

    private MemoryFact createFact(SessionOwner owner, String predicate, FactStatus status, boolean reviewRequired) {
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
                now,Optional.empty(),Optional.empty(),0.95,0.6,reviewRequired,Optional.empty(),status);
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

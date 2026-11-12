package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.group.GroupBindingService;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 纠正回执丢失后只读核对与下一次记忆检索。 */
class MemoryCorrectionUnknownBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("memory-correction-unknown-");
    @DynamicPropertySource static void origin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @Autowired SessionLogPort sessionLog;
    @Autowired MemoryRuntime memoryRuntime;
    @Autowired JdbcMemoryPort memoryStore;
    @Autowired GroupBindingService groups;
    @Autowired DataSource dataSource;

    @Test void lostCorrectionResponseRequiresReadOnlyStatusCheck() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_MEMORY_CORRECTION_UNKNOWN", "candidate");
        assertTrue(Set.of("baseline", "candidate").contains(mode));
        var owner = SessionOwner.user(new SessionRef(new TenantId(tenantId), sessionId), new AgentId(agentId), new EndUserId(endUserId));
        String suffix = Long.toString(SnowflakeIds.nextId());
        String query = "记忆纠正主题" + suffix, oldText = "旧记忆正文" + suffix, newText = "新记忆正文" + suffix;
        Path evidence = Path.of("build/runtime-e2e/memory-correction-unknown-" + suffix).toAbsolutePath();
        Path browser = evidence.resolve("playwright"); Files.createDirectories(browser);
        System.out.println("MEMORY_CORRECTION_UNKNOWN_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated Spring/PostgreSQL/browser/governance HTTP, SessionLog source event, MemoryRuntime, JDBC Store, AgentExecutionProgram and ExecutionCoordinator. ModelPort responses are controlled in-process by MemoryInjectionProgramFixture; this is not an HTTP model or external supplier test. Trace disabled to exclude credentials.");
        TenantContext.setTenantId(tenantId);
        try {
            MemoryFact original = createFact(owner, query, oldText);
            assertEquals(FactStatus.ACTIVE, original.status());
            Files.writeString(evidence.resolve("original-fact.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(original));
            FRONTEND.bindBackend(baseUrl()); Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path cli = admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
            var command = new ProcessBuilder(cli.toString(), "test", "e2e/memory-correction-unknown-real.spec.ts");
            command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = command.environment();
            env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
            env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
            env.put("E2E_MEMORY_CORRECTION_UNKNOWN", mode); env.put("E2E_MEMORY_FACT_ID", original.ref().factId());
            env.put("E2E_MEMORY_REVISION", Long.toString(original.revision())); env.put("E2E_MEMORY_QUERY", query);
            env.put("E2E_MEMORY_OLD_TEXT", oldText); env.put("E2E_MEMORY_NEW_TEXT", newText);
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(process.isAlive(), "记忆纠正浏览器超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            }
            var facts = objectMapper.readTree(Files.readString(browser.resolve("browser-result.json")));
            assertEquals(mode, facts.path("mode").asText()); assertEquals(original.ref().factId(), facts.path("factId").asText());
            var rows = queryRows(jdbc, tenantId, "SELECT h.fact_id,h.current_revision,r.status FROM agent_runtime_memory_fact_heads h JOIN agent_runtime_memory_fact_revisions r ON r.tenant_id=h.tenant_id AND r.fact_id=h.fact_id AND r.revision=h.current_revision WHERE h.tenant_id=? AND h.fact_id=?", tenantId, original.ref().factId());
            assertEquals(1, rows.size());
            assertEquals("ARCHIVED", rows.getFirst().get("status"));
            assertEquals(original.revision() + 1, ((Number) rows.getFirst().get("current_revision")).longValue());
            String replacementId = facts.path("replacement").path("ref").path("fact_id").asText();
            {
                assertFalse(replacementId.isBlank()); assertNotEquals(original.ref().factId(), replacementId);
                assertEquals(original.ref().factId(), facts.path("replacement").path("derived_from").path("fact_id").asText());
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_memory_fact_heads h JOIN agent_runtime_memory_fact_revisions r ON r.tenant_id=h.tenant_id AND r.fact_id=h.fact_id AND r.revision=h.current_revision WHERE h.tenant_id=? AND h.fact_id=? AND r.status='ACTIVE'", tenantId, replacementId));
            }
            var execution = new MemoryInjectionProgramFixture(dataSource, memoryRuntime, memoryStore, groups, owner, query);
            String actualRun = execution.identity.runRef().runId();
            try {
                execution.drive();
                assertEquals(RunStatus.COMPLETED, execution.store().load(execution.identity.runRef()).orElseThrow().status());
                assertEquals(1, execution.toolCalls()); assertEquals(2, execution.modelCalls());
                String result = objectMapper.writeValueAsString(execution.toolResult());
                String nextModel = objectMapper.writeValueAsString(execution.nextModelRequest());
                assertFalse(result.contains(original.ref().factId()), "已遗忘或归档的原事实不得被检索");
                assertFalse(nextModel.contains(oldText), "旧正文不得进入下一模型请求");
                assertTrue(result.contains(replacementId)); assertTrue(nextModel.contains(newText));
                Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "runId", actualRun, "mode", mode, "originalFactId", original.ref().factId(), "replacementFactId", replacementId,
                        "toolCalls", execution.toolCalls(), "modelCalls", execution.modelCalls(), "toolResult", execution.toolResult(), "nextModelRequest", execution.nextModelRequest())));
            } finally {
                Files.writeString(evidence.resolve("model-requests.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(execution.modelRequests()));
                Files.writeString(evidence.resolve("runtime-database.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "run", queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, actualRun),
                        "actions", queryRows(jdbc, tenantId, "SELECT action_id,kind,status FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal LIMIT 10", tenantId, actualRun),
                        "attempts", queryRows(jdbc, tenantId, "SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? LIMIT 10", tenantId, actualRun),
                        "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? LIMIT 10", tenantId, actualRun))));
            }
        } finally {
            try {
                Files.writeString(evidence.resolve("memory-database.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "heads", queryRows(jdbc, tenantId, "SELECT h.fact_id,h.current_revision,r.status FROM agent_runtime_memory_fact_heads h JOIN agent_runtime_memory_fact_revisions r ON r.tenant_id=h.tenant_id AND r.fact_id=h.fact_id AND r.revision=h.current_revision WHERE h.tenant_id=? LIMIT 10", tenantId),
                        "revisions", queryRows(jdbc, tenantId, "SELECT fact_id,revision,status FROM agent_runtime_memory_fact_revisions WHERE tenant_id=? ORDER BY fact_id,revision LIMIT 20", tenantId))));
            } finally { TenantContext.clear(); }
        }
    }

    private MemoryFact createFact(SessionOwner owner, String predicate, String text) {
        String suffix = Long.toString(SnowflakeIds.nextId()), eventId = "memory-correction-source-" + suffix;
        InputId input = new InputId("memory-correction-input-" + suffix); Instant now = Instant.now();
        var trace = new TraceEnvelope("memory-correction-trace-" + suffix, "memory-correction-span-" + suffix, input, Optional.empty());
        var target = new SessionProjectionTarget(owner, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var source = new SessionEventDraft(new EventId(eventId), "inbound.message", "v1", eventId, trace,
                new TypedPayload("input.v1", objectMapper.createObjectNode().put("text", text).set("extraParameters", objectMapper.createObjectNode()).toString()), now);
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class, sessionLog.append(new SessionLogPort.AppendSessionEvent(target, source)));
        var draft = new MemoryPort.MemoryFactDraft(new MemoryOwner.UserAgent(owner.sessionRef().tenantId(), owner.requireUser(), owner.target().requireAgent()),
                new MemoryTypeRef("preference"), new RetentionClassRef("standard"), "v2", "user:" + owner.requireUser().value(), predicate,
                new TypedPayload("skillforge.memory.preference.v2", com.skillforge.core.agent.memory.MemoryContent.explicit(
                        objectMapper.createObjectNode().put("text", text), "preference").toString()),
                List.of(new EventId(eventId)), owner.sessionRef(), Optional.empty(), Optional.empty(), "memory-correction-fixture-v1",
                now, Optional.empty(), Optional.empty(), 0.95, 0.6, false, Optional.empty(), FactStatus.ACTIVE);
        var runRef = new RunRef(owner.sessionRef().tenantId(), "memory-correction-create-" + suffix);
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, runRef, trace, new ResultRef("memory-correction-cause-" + suffix),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(owner.sessionRef().tenantId(), "execution-input:" + runRef.runId()));
        var identity = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(owner.sessionRef().tenantId(), "memory-correction-fixture"),
                owner, Set.of("memory:extract"), 1, new ResultRef("memory-correction-verified-" + suffix));
        var context = new PortCallContext(run, identity, new InvocationControl(Instant.now().plusSeconds(30), () -> false));
        return assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, memoryRuntime.mutate(
                new MemoryRuntime.MutationCall(context, new MemoryRuntime.Mutation.Create(new IdempotencyKey("memory-correction-create-" + suffix), draft), now))).fact();
    }
}

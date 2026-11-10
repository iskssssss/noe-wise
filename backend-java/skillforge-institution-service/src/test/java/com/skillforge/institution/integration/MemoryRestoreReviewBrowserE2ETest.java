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

/** 待确认共享记忆的恢复不代替审核；真实检索按已确认状态进入模型。 */
class MemoryRestoreReviewBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("memory-restore-review-");

    @DynamicPropertySource static void origin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @Autowired SessionLogPort sessionLog;
    @Autowired MemoryRuntime memoryRuntime;
    @Autowired JdbcMemoryPort memoryStore;
    @Autowired GroupBindingService groups;
    @Autowired DataSource dataSource;

    @Test void restorePreservesPendingSharingReviewBeforeRuntimeRecall() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_MEMORY_RESTORE_REVIEW", "candidate");
        assertTrue(Set.of("baseline", "candidate").contains(mode));
        boolean baseline = "baseline".equals(mode);
        var owner = SessionOwner.user(new SessionRef(new TenantId(tenantId), sessionId), new AgentId(agentId), new EndUserId(endUserId));
        String suffix = Long.toString(SnowflakeIds.nextId());
        String query = "共享审核恢复主题" + suffix, text = "待确认共享记忆正文" + suffix;
        Path evidence = Path.of("build/runtime-e2e/memory-restore-review-" + suffix).toAbsolutePath();
        Files.createDirectories(evidence);
        System.out.println("MEMORY_RESTORE_REVIEW_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated Spring/PostgreSQL/browser/governance HTTP, committed SessionLog source, MemoryRuntime, JDBC Store, AgentExecutionProgram, ExecutionCoordinator and search_memory handler. ModelPort responses are controlled in-process by MemoryInjectionProgramFixture, not an HTTP model or external supplier. Browser stages run sequentially around actual Runtime recall; no runtime or memory state is mocked. Credentials, trace and video excluded.");
        TenantContext.setTenantId(tenantId);
        try {
            MemoryFact original = createPendingFact(owner, query, text, evidence);
            assertEquals(MemoryScope.USER, original.scope());
            assertEquals(FactStatus.PENDING_CONFIRM, original.status());
            assertTrue(original.reviewRequired());
            write(evidence.resolve("original-fact.json"), original);
            FRONTEND.bindBackend(baseUrl());
            runBrowser(evidence, "restore", mode, original, original.revision(), query, text);
            var restored = objectMapper.readTree(Files.readString(evidence.resolve("restore/browser-result.json")));
            assertEquals(original.ref().factId(), restored.path("factId").asText());
            assertEquals(original.revision() + 2, restored.path("revision").asLong());
            assertEquals(baseline ? "ACTIVE" : "PENDING_CONFIRM", restored.path("restored").path("status").asText());
            write(evidence.resolve("memory-after-restore.json"), memoryState(original.ref().factId()));
            recall(evidence.resolve("after-restore"), owner, query, text, original.ref().factId(), baseline);
            if (!baseline) {
                runBrowser(evidence, "review", mode, original, original.revision() + 2, query, text);
                var reviewed = objectMapper.readTree(Files.readString(evidence.resolve("review/browser-result.json")));
                assertEquals(original.revision() + 5, reviewed.path("revision").asLong());
                assertEquals("ACTIVE", reviewed.path("restored").path("status").asText());
                assertTrue(reviewed.path("restored").path("review_required").asBoolean());
                recall(evidence.resolve("after-review-restore"), owner, query, text, original.ref().factId(), true);
            }
            var state = memoryState(original.ref().factId());
            var expected = baseline ? List.of("PENDING_CONFIRM", "FORGOTTEN", "ACTIVE")
                    : List.of("PENDING_CONFIRM", "FORGOTTEN", "PENDING_CONFIRM", "ACTIVE", "FORGOTTEN", "ACTIVE");
            assertEquals(expected.size(), state.path("revisions").size());
            for (int index = 0; index < expected.size(); index++) {
                var revision = state.path("revisions").get(index);
                assertEquals(original.revision() + index, revision.path("revision").asLong());
                assertEquals(expected.get(index), revision.path("status").asText());
                assertEquals("USER", revision.path("scope").asText());
                assertEquals(owner.sessionRef().sessionId(), revision.path("source_session_id").asText());
            }
            write(evidence.resolve("result.json"), Map.of("mode", mode, "factId", original.ref().factId(), "reviewBeforeRecall", !baseline,
                    "runtimeRuns", baseline ? 1 : 2, "expectedRevisions", expected));
        } finally {
            try {
                write(evidence.resolve("memory-database.json"), Map.of(
                        "heads", queryRows(jdbc, tenantId, "SELECT h.fact_id,h.current_revision,h.scope,r.status FROM agent_runtime_memory_fact_heads h JOIN agent_runtime_memory_fact_revisions r ON r.tenant_id=h.tenant_id AND r.fact_id=h.fact_id AND r.revision=h.current_revision WHERE h.tenant_id=? LIMIT 10", tenantId),
                        "revisions", queryRows(jdbc, tenantId, "SELECT fact_id,revision,status,scope,source_session_id,content_revision FROM agent_runtime_memory_fact_revisions WHERE tenant_id=? ORDER BY fact_id,revision LIMIT 20", tenantId)));
            } finally { TenantContext.clear(); }
        }
    }

    private void runBrowser(Path evidence, String stage, String mode, MemoryFact fact, long revision, String query, String text) throws Exception {
        Path stageEvidence = evidence.resolve(stage); Files.createDirectories(stageEvidence);
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "e2e/memory-restore-review-real.spec.ts", "--output", stageEvidence.resolve("playwright-output").toString());
        command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(stageEvidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", stageEvidence.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_MEMORY_RESTORE_REVIEW", mode); env.put("E2E_MEMORY_RESTORE_STAGE", stage);
        env.put("E2E_MEMORY_FACT_ID", fact.ref().factId()); env.put("E2E_MEMORY_REVISION", Long.toString(revision));
        env.put("E2E_MEMORY_QUERY", query); env.put("E2E_MEMORY_TEXT", text);
        env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture();
            write(stageEvidence.resolve("memory-state.json"), memoryState(fact.ref().factId()));
            assertFalse(process.isAlive(), "记忆恢复浏览器超时：" + stageEvidence);
            assertEquals(0, process.exitValue(), stageEvidence + "\n" + Files.readString(stageEvidence.resolve("playwright.log")));
        }
    }

    private void recall(Path evidence, SessionOwner owner, String query, String text, String factId, boolean expected) throws Exception {
        Files.createDirectories(evidence);
        var execution = new MemoryInjectionProgramFixture(dataSource, memoryRuntime, memoryStore, groups, owner, query);
        String actualRun = execution.identity.runRef().runId();
        try {
            execution.drive();
            write(evidence.resolve("runtime-result.json"), Map.of("runId", actualRun, "expectedRecall", expected,
                    "toolCalls", execution.toolCalls(), "modelCalls", execution.modelCalls(),
                    "toolResult", execution.toolResult(), "nextModelRequest", execution.nextModelRequest()));
            assertEquals(RunStatus.COMPLETED, execution.store().load(execution.identity.runRef()).orElseThrow().status());
            assertEquals(1, execution.toolCalls()); assertEquals(2, execution.modelCalls());
            String result = objectMapper.writeValueAsString(execution.toolResult());
            String nextModel = objectMapper.writeValueAsString(execution.nextModelRequest());
            assertEquals(expected, result.contains(factId), "真实检索结果须遵循共享确认状态");
            assertEquals(expected, nextModel.contains(text), "下一模型请求的正文必须来自允许召回的事实");
        } finally {
            write(evidence.resolve("model-requests.json"), execution.modelRequests());
            write(evidence.resolve("runtime-database.json"), Map.of(
                    "run", queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? LIMIT 1", tenantId, actualRun),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.action_id,a.kind,a.status,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal LIMIT 10", tenantId, actualRun),
                    "attempts", queryRows(jdbc, tenantId, "SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? LIMIT 10", tenantId, actualRun),
                    "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? LIMIT 10", tenantId, actualRun)));
        }
    }

    private com.fasterxml.jackson.databind.JsonNode memoryState(String factId) {
        return objectMapper.valueToTree(Map.of(
                "heads", queryRows(jdbc, tenantId, "SELECT fact_id,current_revision,scope FROM agent_runtime_memory_fact_heads WHERE tenant_id=? AND fact_id=? LIMIT 1", tenantId, factId),
                "revisions", queryRows(jdbc, tenantId, "SELECT fact_id,revision,status,scope,source_session_id,content_revision FROM agent_runtime_memory_fact_revisions WHERE tenant_id=? AND fact_id=? ORDER BY revision LIMIT 10", tenantId, factId)));
    }

    private MemoryFact createPendingFact(SessionOwner owner, String predicate, String text, Path evidence) throws Exception {
        String suffix = Long.toString(SnowflakeIds.nextId()), eventId = "memory-restore-source-" + suffix;
        InputId input = new InputId("memory-restore-input-" + suffix); Instant now = Instant.now();
        var trace = new TraceEnvelope("memory-restore-trace-" + suffix, "memory-restore-span-" + suffix, input, Optional.empty());
        var target = new SessionProjectionTarget(owner, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var source = new SessionEventDraft(new EventId(eventId), "inbound.message", "v1", eventId, trace,
                new TypedPayload("input.v1", objectMapper.createObjectNode().put("text", text).set("extraParameters", objectMapper.createObjectNode()).toString()), now);
        var appended = sessionLog.append(new SessionLogPort.AppendSessionEvent(target, source));
        write(evidence.resolve("source-event.json"), Map.of("target", target, "draft", source, "appendResult", appended));
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class, appended);
        var draft = new MemoryPort.MemoryFactDraft(new MemoryOwner.User(owner.sessionRef().tenantId(), owner.requireUser()),
                new MemoryTypeRef("preference"), new RetentionClassRef("standard"), "v2", "user:" + owner.requireUser().value(), predicate,
                new TypedPayload("skillforge.memory.preference.v2", com.skillforge.core.agent.memory.MemoryContent.explicit(
                        objectMapper.createObjectNode().put("text", text), "preference").toString()),
                List.of(new EventId(eventId)), owner.sessionRef(), Optional.empty(), Optional.empty(), "memory-restore-fixture-v1",
                now, Optional.empty(), Optional.empty(), 0.95, 0.6, true, Optional.empty(), FactStatus.PENDING_CONFIRM);
        var runRef = new RunRef(owner.sessionRef().tenantId(), "memory-restore-create-" + suffix);
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, runRef, trace, new ResultRef("memory-restore-cause-" + suffix),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(owner.sessionRef().tenantId(), "execution-input:" + runRef.runId()));
        var identity = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(owner.sessionRef().tenantId(), "memory-restore-fixture"),
                owner, Set.of("memory:extract"), 1, new ResultRef("memory-restore-verified-" + suffix));
        var context = new PortCallContext(run, identity, new InvocationControl(Instant.now().plusSeconds(30), () -> false));
        return assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, memoryRuntime.mutate(
                new MemoryRuntime.MutationCall(context, new MemoryRuntime.Mutation.Create(new IdempotencyKey("memory-restore-create-" + suffix), draft), now))).fact();
    }

    private void write(Path path, Object value) throws Exception {
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}

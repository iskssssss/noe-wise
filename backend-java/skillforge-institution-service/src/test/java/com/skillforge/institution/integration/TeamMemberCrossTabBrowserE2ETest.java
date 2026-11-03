package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 团队目录浏览器流程与同一发布团队的运行时验收。 */
class TeamMemberCrossTabBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("team-member-cross-tab-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @Autowired AgentMapper fixtureAgents;
    @Autowired TenantMemberMapper fixtureMembers;
    @Autowired FoundationAccess fixtureAccess;
    @Autowired FoundationOrganizationService fixtureOrganization;
    @Autowired AgentRunBindings runBindings;

    @Test void publishedMemberAppearsInTeamAndPublishedTeamRuns() throws Exception {
        login();
        long draftId = SnowflakeIds.nextId();
        TenantContext.setTenantId(tenantId);
        try {
            var member = fixtureMembers.selectOne(new LambdaQueryWrapper<TenantMemberDO>()
                    .eq(TenantMemberDO::getTenantId, Long.parseLong(tenantId))
                    .eq(TenantMemberDO::getUsername, "runtime-e2e-admin"));
            assertNotNull(member);
            var draft = new AgentDO();
            draft.setId(draftId); draft.setTenantId(Long.parseLong(tenantId));
            draft.setAgentKey("team-member-" + draftId); draft.setName("团队目录新成员");
            draft.setSystemPrompt("团队目录验收成员"); draft.setRoleDescription("团队成员");
            draft.setStatus("draft"); draft.setConfig(Map.of());
            draft.setCreatedAt(Instant.now()); draft.setUpdatedAt(Instant.now());
            fixtureAccess.withSubject(new FoundationAccess.Subject(false, tenantId, member.getId().toString()), () -> {
                fixtureAgents.insert(draft);
                fixtureOrganization.registerResource("agent", Long.toString(draftId));
                return null;
            });
        } finally { TenantContext.clear(); }
        Path evidence = Path.of("build", "runtime-e2e", "team-member-cross-tab-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright"); Files.createDirectories(browser);
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        Path cli = admin.resolve("node_modules/@playwright/test/cli.js"); assertTrue(Files.isRegularFile(cli));
        FRONTEND.bindBackend(baseUrl());
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), cli.toString(), "test", "e2e/team-member-cross-tab-real.spec.ts", "--headed");
        command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_USERNAME", "runtime-e2e-admin"); env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_PRIMARY_AGENT_ID", agentId); env.put("E2E_DRAFT_AGENT_ID", Long.toString(draftId));
        env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            String output = Files.readString(evidence.resolve("playwright.log"));
            assertEquals(0, process.exitValue(), "浏览器验收失败：" + evidence + "\n" + output);
        }
        var facts = objectMapper.readTree(Files.readString(browser.resolve("team-member-baseline.json")));
        String teamId = facts.path("teamId").asText(); long revision = facts.path("teamRevision").asLong();
        String mode = System.getenv().getOrDefault("E2E_TEAM_MEMBER_MODE", "candidate");
        assertEquals(mode, facts.path("mode").asText());
        assertFalse(teamId.isBlank()); assertTrue(revision > 0);
        assertEquals("baseline".equals(mode) ? 1 : 0, facts.path("manualRefreshes").asInt());
        assertEquals(1, countRuntime("SELECT count(*) FROM nw_agent_team_versions WHERE tenant_id=CAST(? AS bigint) AND team_id=? AND revision=? AND definition::text LIKE ? AND definition::text LIKE ?", tenantId, teamId, revision, "%" + agentId + "%", "%" + draftId + "%"));
        var session = send("/api/v1/chat/sessions", "POST", Map.of("team_id", teamId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body());
        sessionId = successData(session).path("session_id").asText(); assertFalse(sessionId.isBlank());
        var body = new LinkedHashMap<>(chatPayload("请简短说明当前能力。")); body.remove("agent_id"); body.put("team_id", teamId);
        var capture = postSse(body);
        assertEquals(200, capture.statusCode()); assertTrue(capture.events().contains("execution.completed"), capture.raw());
        assertFalse(capture.events().contains("error"), capture.raw());
        String actualRun = "run:" + runId;
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun, agentId, sessionId));
        assertTrue(countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun) > 0);
        assertEquals(1, countRuntime("SELECT count(*) FROM nw_chat_sessions WHERE tenant_id=CAST(? AS bigint) AND session_id=? AND team_id=?", tenantId, sessionId, teamId));
        var binding = runBindings.find(new RunRef(new TenantId(tenantId), actualRun)).orElseThrow();
        var studio = binding.scope().studio().orElseThrow();
        assertEquals(teamId, studio.id()); assertEquals(revision, studio.revision());
        assertTrue(binding.scope().members().containsKey(Long.toString(draftId)));
        Files.writeString(evidence.resolve("team-runtime.json"), objectMapper.writeValueAsString(Map.of("teamId", teamId, "revision", revision, "sessionId", sessionId, "runId", actualRun, "primaryAgentId", agentId, "memberAgentId", Long.toString(draftId))));
    }
}

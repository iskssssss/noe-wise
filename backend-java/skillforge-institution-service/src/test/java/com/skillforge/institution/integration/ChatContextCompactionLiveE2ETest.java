package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.systemagent.SystemAgentService;
import com.skillforge.core.agent.systemagent.SystemAgentTypes;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.chat.contract.ChatToolSchemaContributor;
import com.skillforge.core.model.ModelSelections;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实聊天入口与摘要用途，历史是隔离数据库中的合成会话，不代表自然发生的 37 轮交互。 */
class ChatContextCompactionLiveE2ETest extends ChatExecutionChainE2ETest {
    @Autowired PlatformModelCatalogService models;
    @Autowired SystemAgentService systemAgents;
    @Autowired PlatformTransactionManager transactions;

    @Test @Tag("runtime-latency") @Tag("live")
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_CONTEXT_CHAIN", matches = "1")
    @EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    void liveHistoryTriggersPublishedSummaryAndCompletes() throws Exception {
        TenantContext.setTenantId(tenantId);
        try {
            var model = models.create(new ModelWrite(null, "Context live fixture", "LLM", true,
                    "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                    "https://api.deepseek.com", "deepseek-flash", System.getenv("SDK4_LIVE_MODEL_KEY"),
                    8192, 65536, 8192, 0.2, "DISABLED", 120, null, null, null, null, null, "CNY"), "integration-fixture");
            models.publish(model.id(), model.revision(), "integration-fixture");
            models.select(tenantId, new ModelSelections(model.id(), null, null));
            var current = systemAgents.detail("900000000000000006");
            var draft = current.draft();
            var changed = systemAgents.update(current.id(), current.revision(), new SystemAgentTypes.Config(
                    draft.name(), draft.description(), draft.systemPrompt(), model.id(), draft.timeoutSeconds(),
                    draft.maxBusinessModelCalls(), draft.mounts()), "integration-fixture");
            systemAgents.transition(changed.id(), changed.revision(), "publish", "integration-fixture");
        } finally { TenantContext.clear(); }

        login();
        var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revised.statusCode(), revised.body());
        agentId = successData(revised).path("id").asText();
        var updated = send("/api/v1/agents/update", "PATCH", Map.of("agent_id", agentId,
                "payload", Map.of("execution_config", Map.of())), accessToken);
        assertEquals(200, updated.statusCode(), updated.body());
        var definitions = successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"));
        long definition = 0;
        for (var item : definitions) if ("tool:read_context_reference".equals(item.path("capability_key").asText())) definition = item.path("id").asLong();
        assertTrue(definition > 0, "原文读取工具未发布");
        var reader = new PlatformToolCatalog(new ChatToolSchemaContributor()).entries().stream()
                .filter(item -> "read_context_reference".equals(item.name())).findFirst().orElseThrow();
        var versions = successData(get("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definition));
        long version = 0;
        for (var item : versions) if (reader.publishedVersionKey().equals(item.path("version_key").asText())) version = item.path("id").asLong();
        assertTrue(version > 0);
        String deploymentKey = "context-reader-" + runId;
        var deployment = send("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", deploymentKey,
                "tool_version_id", version, "provider_type", "PLATFORM", "provider_ref", "read_context_reference",
                "status", "ACTIVE", "environment", "TEST"), accessToken);
        assertEquals(201, deployment.statusCode(), deployment.body());
        var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                "capability_type", "TOOL", "capability_definition_id", Long.toString(definition), "capability_version_id", Long.toString(version),
                "tool_config", Map.of("tool_deployment_id", deploymentKey, "tool_deployment_revision", successData(deployment).path("revision").asLong()), "priority", 0)), accessToken);
        assertEquals(201, mounted.statusCode(), mounted.body());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body());
        sessionId = successData(session).path("session_id").asText();

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
            for (int i = 0; i < 37; i++) {
                String detail = ("交付范围包含资料核验、阶段计划、责任分工、验收记录和风险说明。" + i + "；").repeat(17);
                for (int side = 0; side < 2; side++) {
                    String role = side == 0 ? "user" : "assistant";
                    String text = side == 0 && i == 0 ? "项目代号 LANTERN-731，已确认交付日期 2027-05-18。" + detail : detail;
                    jdbc.update("INSERT INTO nw_chat_messages(id,tenant_id,session_id,end_user_id,message_id,role,content,metadata,created_at) VALUES(?,?,?,?,?,?,?,?::jsonb,now() - interval '200 seconds' + (? * interval '1 second'))",
                            SnowflakeIds.nextId(), Long.parseLong(tenantId), sessionId, endUserId, "history-" + i + "-" + side, role, text, "{}", i * 2 + side);
                }
            }
        });
        var accepted = send("/api/v1/chat", "POST", chatPayload("请根据已确认资料简短说明项目代号和交付日期；如不能确定请说明。"), accessToken);
        assertEquals(202, accepted.statusCode(), accepted.body());
        String actualRun = successData(accepted).path("execution_id").asText();
        var capture = observeSseUntil(sessionId, null, actualRun, Set.of("execution.completed", "error"));
        assertTrue(capture.events().contains("execution.completed"), capture.raw());
        var rows = queryRows(jdbc, tenantId, "SELECT canonical_json AS payload FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='agent.context-compaction-attempt' ORDER BY event_sequence LIMIT 10", tenantId, actualRun);
        assertFalse(rows.isEmpty(), "没有记录自动压缩尝试");
        var attempt = objectMapper.readTree(rows.getFirst().get("payload").toString());
        assertEquals("AVAILABLE", attempt.path("summaryStatus").asText());
        assertEquals("SUMMARY_AVAILABLE", attempt.path("summaryReason").asText());
        assertEquals(0, attempt.path("failures").asInt(-1));
        assertTrue(attempt.path("invocationRef").asText().startsWith("system-agent-invocation:"));
        assertTrue(countRuntime("SELECT count(*) FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='agent.context-material'", tenantId, actualRun) > 0);
        var requests = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' ORDER BY a.action_ordinal LIMIT 10", tenantId, actualRun);
        assertFalse(requests.isEmpty());
        var codec = new AgentExecutionCodec(new CodecLimits(8388608, 100000, 128));
        var first = requests.getFirst();
        var frozen = ModelRequestTestMaterials.decode(codec, new TypedPayload(first.get("schema_id").toString(), first.get("canonical_json").toString()), jdbc, tenantId);
        assertTrue(frozen.request().messages().stream().anyMatch(message -> message.messageId().startsWith("execution-context-reference:")
                && message.textContent().contains("\"kind\":\"SUMMARY\"")), "摘要未进入冻结模型请求");
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun));
        Path evidence = Path.of("build", "reports", "context-compaction-live");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve(runId + ".json"), objectMapper.writeValueAsString(Map.of(
                "run", actualRun, "model", "deepseek-flash", "history_rounds", 37,
                "summary_status", attempt.path("summaryStatus").asText(), "summary_reason", attempt.path("summaryReason").asText(),
                "cooldown_failures", attempt.path("failures").asInt(), "summary_invocation", attempt.path("invocationRef").asText(),
                "frozen_summary_present", true, "run_status", "COMPLETED")));
    }
}

package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 真实机构 HTTP/PG 链，明确区分受控模型与 deepseek-flash。 */
class ToolProgressE2ETest extends ChatExecutionWaitingE2ETest {
    private void mountMixedSkill() throws Exception {
        agentId = successData(send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken)).path("id").asText();
        String drafts = "/api/v1/capabilities/skill-drafts";
        var definition = successData(send("/api/v1/capabilities/definitions", "POST", Map.of("capability_type", "SKILL",
                "capability_key", "progress-skill-" + com.skillforge.core.shared.config.SnowflakeIds.nextId(),
                "name", "progress-mixed", "description", "隔离资源读取验证"), accessToken)).path("id").asText();
        var draft = successData(send(drafts, "POST", Map.of("definition_id", definition), accessToken));
        var files = new java.util.LinkedHashMap<String, String>();
        for (var entry : Map.of("SKILL.md", "---\nname: progress-mixed\ndescription: Read frozen reference documents for synthetic query\n---\n按需读取参考资料。",
                "references/guide.md", "FROZEN_GUIDE_REFERENCE", "references/detail.md", "FROZEN_DETAIL_REFERENCE").entrySet())
            files.put(entry.getKey(), java.util.Base64.getEncoder().encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        var body = Map.of("draft_id", draft.path("id").asText(), "version_key", "1.0.0", "files", files,
                "model_invocable", true, "user_invocable", false, "activation_mode", "ALWAYS", "required_capabilities", List.of());
        var saved = httpClient.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl() + drafts))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .header("If-Match", draft.path("revision").asText()).PUT(java.net.http.HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, saved.statusCode(), saved.body()); draft = successData(saved);
        var valid = send(drafts + "/validate", "POST", Map.of("draft_id", draft.path("id").asText(), "revision", draft.path("revision").asLong()), accessToken);
        assertTrue(successData(valid).path("valid").asBoolean(), valid.body());
        var definitionView = successData(send("/api/v1/capabilities/definitions/detail?definitionId=" + definition, "GET", null, accessToken));
        var published = send(drafts + "/publish", "POST", Map.of("draft_id", draft.path("id").asText(), "revision", draft.path("revision").asLong(),
                "definition_revision", definitionView.path("revision").asLong()), accessToken);
        var version = successData(published).path("version_id").asText(); assertFalse(version.isBlank(), published.body());
        var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of("capability_type", "SKILL",
                "capability_definition_id", definition, "capability_version_id", version,
                "skill_config", Map.of("activation_mode", "ALWAYS", "token_budget", 8000), "priority", 1, "status", "ACTIVE")), accessToken);
        assertEquals(201, mounted.statusCode(), mounted.body());
        assertEquals(200, send("/api/v1/agents/update", "PATCH", Map.of("agent_id", agentId,
                "payload", Map.of("execution_config", Map.of("max_business_model_calls", 12, "max_tool_calls", 8))), accessToken).statusCode());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        sessionId = successData(send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken)).path("session_id").asText();
    }

    @Test @EnabledIfEnvironmentVariable(named = "SKILLFORGE_PROGRESS_CONTROLLED", matches = "1")
    void changedMixedResourceContinuesToFinalModel() throws Exception { controlledMixed(true); }

    @Test @EnabledIfEnvironmentVariable(named = "SKILLFORGE_PROGRESS_CONTROLLED", matches = "1")
    void unchangedMixedResourceStopsWithoutExtraToolAction() throws Exception { controlledMixed(false); }

    private void controlledMixed(boolean changed) throws Exception {
        try (var server = new RuntimeExecutionMcpWaitServer(objectMapper)) {
            server.start(); login(); mountMcpWaitTool(server, false); mountMixedSkill();
            String ticket = "mixed-" + com.skillforge.core.shared.config.SnowflakeIds.nextId();
            var script = server.register(ticket, RuntimeExecutionMcpWaitServer.Mode.READ_QUERY);
            provider.enableMixedMcp(ticket, changed);
            var accepted = send("/api/v1/chat", "POST", chatPayload("查询受控业务数据并阅读已挂载参考资料。"), accessToken);
            assertEquals(202, accepted.statusCode(), accepted.body()); var run = successData(accepted).path("execution_id").asText();
            var capture = observeSseUntil(sessionId, null, run, Set.of("execution.completed", "error"));
            assertTrue(capture.events().contains("execution.completed"), capture.raw());
            var state = snapshot(run, "controlled-mixed");
            assertEquals(changed ? "COMPLETED" : "TERMINATED", state.get("status"), state + "\n" + capture.raw() + "\n" + server.requests());
            assertEquals(changed ? 6L : 5L, state.get("modelActions")); assertEquals(1L, state.get("toolActions"));
            assertEquals(changed ? 6 : 5, provider.requestCount()); assertEquals(1, script.calls.get());
            assertEquals("QUERY-7", state.get("toolRendered")); assertEquals(changed ? "" : "STOP", state.get("terminalLevel"));
            var location = System.getenv("SKILLFORGE_PROGRESS_EVIDENCE_DIR");
            if (location != null && !location.isBlank()) {
                var directory = Path.of(location); Files.createDirectories(directory);
                Files.writeString(directory.resolve(changed ? "mixed-changed.json" : "mixed-same.json"), objectMapper.writeValueAsString(state), StandardCharsets.UTF_8);
            }
        }
    }

    @Test @Tag("live")
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_PROGRESS_LIVE", matches = "1")
    @EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    void deepseekFlashObservesMixedQueryAndFrozenResource() throws Exception {
        try (var server = new RuntimeExecutionMcpWaitServer(objectMapper)) {
            server.start(); login(); mountMcpWaitTool(server, false); mountMixedSkill();
            String ticket = "live-mixed-" + com.skillforge.core.shared.config.SnowflakeIds.nextId();
            var script = server.register(ticket, RuntimeExecutionMcpWaitServer.Mode.READ_QUERY);
            var accepted = send("/api/v1/chat", "POST", chatPayload("请查询已挂载的受控只读业务工具，ticket=" + ticket
                    + "，并读取已启用 Skill 的 references/guide.md。查询参数已确定，二者可同轮调用；根据实际结果回答。"), accessToken);
            assertEquals(202, accepted.statusCode(), accepted.body()); var run = successData(accepted).path("execution_id").asText();
            Path evidence = Path.of(System.getenv("SKILLFORGE_PROGRESS_EVIDENCE_DIR")); Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("live-mixed-identity.json"), objectMapper.writeValueAsString(Map.of("session", sessionId, "run", run)), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            try {
                var capture = observeSseUntil(sessionId, null, run, Set.of("execution.completed", "error"));
                Files.writeString(evidence.resolve("live-mixed-sse.txt"), capture.raw(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            } finally {
                var state = new java.util.LinkedHashMap<>(snapshot(run, "live"));
                state.put("controlledMcpCalls", script.calls.get()); state.put("controlledModelCalls", provider.requestCount());
                Files.writeString(evidence.resolve("live-mixed-state.json"), objectMapper.writeValueAsString(state), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW);
            }
        }
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_PROGRESS_CONTROLLED", matches = "1")
    void controlledModelStopsAfterFiveRepeatedReadConsumptions() throws Exception {
        provider.enableRepeatedTemplate(); login(); mountGenericTemplateTool();
        var accepted = send("/api/v1/chat", "POST", chatPayload("使用已挂载模板工具处理本次合成输入。"), accessToken);
        assertEquals(202, accepted.statusCode(), accepted.body());
        var run = successData(accepted).path("execution_id").asText();
        var capture = observeSseUntil(sessionId, null, run, Set.of("execution.completed", "error"));
        assertTrue(capture.events().contains("execution.completed"), capture.raw());
        var state = snapshot(run, "controlled");
        assertEquals("TERMINATED", state.get("status")); assertEquals("FINISHED", state.get("phase"));
        assertEquals(5L, state.get("modelActions")); assertEquals(1L, state.get("toolActions"));
        assertEquals(5, provider.requestCount());
        @SuppressWarnings("unchecked") var levels = (List<String>) state.get("levels");
        assertEquals(List.of("CLEAR", "CLEAR", "CLEAR", "WARN", "WARN"), levels);
        assertEquals("STOP", state.get("terminalLevel")); assertEquals("REPEAT-7", state.get("toolRendered"));
    }

    @Test @Tag("live")
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_PROGRESS_LIVE", matches = "1")
    @EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    void deepseekFlashNewIsolatedRunRecordsObservedActionsAndFrozenJudgements() throws Exception {
        login(); mountGenericTemplateTool();
        agentId = successData(send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken)).path("id").asText();
        assertEquals(200, send("/api/v1/agents/update", "PATCH", Map.of("agent_id", agentId,
                "payload", Map.of("execution_config", Map.of("max_business_model_calls", 12, "max_tool_calls", 8))), accessToken).statusCode());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        sessionId = successData(send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken)).path("session_id").asText();
        var accepted = send("/api/v1/chat", "POST", chatPayload("请用模板工具渲染模板 REPEAT-{{number}}，数据为 number=7。请只根据实际结果回答。"), accessToken);
        assertEquals(202, accepted.statusCode(), accepted.body());
        var run = successData(accepted).path("execution_id").asText();
        Path evidence = Path.of(System.getenv("SKILLFORGE_PROGRESS_EVIDENCE_DIR")); Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("live-identity.json"), objectMapper.writeValueAsString(Map.of("session", sessionId, "run", run)), StandardCharsets.UTF_8);
        try {
            var capture = observeSseUntil(sessionId, null, run, Set.of("execution.completed", "error"));
            Files.writeString(evidence.resolve("live-sse.txt"), capture.raw(), StandardCharsets.UTF_8);
        } finally {
            var state = snapshot(run, "live");
            Files.writeString(evidence.resolve("live-state.json"), objectMapper.writeValueAsString(state), StandardCharsets.UTF_8);
        }
    }

    private Map<String, Object> snapshot(String run, String kind) throws Exception {
        var state = queryRows(jdbc, tenantId, "SELECT status,phase,context_ref FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? LIMIT 1", tenantId, run).getFirst();
        var attempts = queryRows(jdbc, tenantId, "SELECT a.action_ordinal,a.kind,a.target_handle,t.attempt_id,t.result_kind,t.adopted,m.schema_id,m.canonical_json,res.schema_id AS result_schema,res.canonical_json AS result_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=t.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal LIMIT 50", tenantId, run);
        var codec = new AgentExecutionCodec(new CodecLimits(262144, 20000, 64)); var levels = new ArrayList<String>();
        var modelTurns = new ArrayList<Map<String, Object>>();
        for (var row : attempts) if ("MODEL".equals(row.get("kind"))) {
            var frozen = ModelRequestTestMaterials.decode(codec, new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
            var progress = frozen.progress().orElseThrow(); levels.add(objectMapper.readTree(progress.canonicalJson()).path("level").asText());
            if ("live".equals(kind)) assertEquals("deepseek-flash", row.get("target_handle"));
            if (row.get("result_schema") != null) {
                var outcome = codec.decodeModelOutcome(new TypedPayload(row.get("result_schema").toString(), row.get("result_json").toString()));
                if (outcome instanceof com.skillforge.runtime.api.port.ModelPort.ModelResult.Completed completed && completed.assistantTurn().isPresent()) {
                    var calls = new ArrayList<Map<String, Object>>();
                    for (var call : completed.assistantTurn().orElseThrow().toolCalls())
                        calls.add(Map.of("slot", calls.size() + 1, "callId", call.callId(), "name", call.wireName(),
                                "resourceArguments", "read_skill_resource".equals(call.wireName()) ? call.argumentsJson() : ""));
                    modelTurns.add(Map.of("ordinal", row.get("action_ordinal"), "finishReason", completed.finishReasonCode(), "calls", calls));
                }
            }
        }
        String toolRendered = "";
        for (var row : attempts) if ("TOOL".equals(row.get("kind")) && row.get("result_schema") != null) {
            var outcome = codec.decodeToolOutcome(new TypedPayload(row.get("result_schema").toString(), row.get("result_json").toString()));
            if (outcome instanceof com.skillforge.runtime.api.port.ToolPortal.ToolOutcome.Succeeded success)
                toolRendered = objectMapper.readTree(success.observation().canonicalJson()).path("structuredContent").path("rendered").asText();
        }
        String terminalLevel = "";
        var contexts = queryRows(jdbc, tenantId, "SELECT schema_id,canonical_json FROM agent_runtime_execution_materials WHERE tenant_id=? AND reference=? LIMIT 1", tenantId, state.get("context_ref"));
        if (!contexts.isEmpty()) {
            var context = objectMapper.readTree(contexts.getFirst().get("canonical_json").toString());
            if ("tool-progress-stop".equals(context.path("type").asText())) terminalLevel = objectMapper.readTree(context.path("progress").path("canonicalJson").asText()).path("level").asText();
        }
        var safeAttempts = attempts.stream().map(row -> Map.of("ordinal", row.get("action_ordinal"), "kind", row.get("kind"), "target", row.get("target_handle"),
                "attempt", row.get("attempt_id"), "resultKind", row.get("result_kind") == null ? "UNKNOWN" : row.get("result_kind"), "adopted", row.get("adopted"))).toList();
        return Map.ofEntries(Map.entry("run", run), Map.entry("session", sessionId), Map.entry("status", state.get("status")), Map.entry("phase", state.get("phase")),
                Map.entry("modelActions", attempts.stream().filter(row -> "MODEL".equals(row.get("kind"))).count()),
                Map.entry("toolActions", attempts.stream().filter(row -> "TOOL".equals(row.get("kind"))).count()),
                Map.entry("levels", levels), Map.entry("terminalLevel", terminalLevel), Map.entry("attempts", safeAttempts),
                Map.entry("toolRendered", toolRendered), Map.entry("modelTurns", modelTurns));
    }
}

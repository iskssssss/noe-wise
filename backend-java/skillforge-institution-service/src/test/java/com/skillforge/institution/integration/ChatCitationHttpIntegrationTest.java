package com.skillforge.institution.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 合成知识经真实 HTTP、Runtime、PG；受控脚本与真实供应商各自独立验收。 */
@Tag("runtime-latency")
class ChatCitationHttpIntegrationTest extends ChatExecutionChainE2ETest {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext applicationContext;
    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.core.chat.session.SessionMailboxService mailbox;
    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.skillforge.institution.runtime.AgentRuntimeExecutionProjection projection;
    @Test
    void institutionHostAssemblesCitationAndSharedTrajectory() {
        assertEquals(1, applicationContext.getBeansOfType(com.skillforge.core.chat.trajectory.ChatCitationReadService.class).size());
        assertNotNull(applicationContext.getBean(com.skillforge.core.chat.trajectory.TrajectoryProjectionService.class));
    }
    @Test @Timeout(240)
    void onlyPublishedCitationOfOriginalVersionIsReadableWithCurrentKnowledgeAuthority() throws Exception {
        login();
        var knowledge = prepareKnowledge();
        String kb = knowledge.kb(), doc = knowledge.doc(), version = knowledge.version(), handle = knowledge.handle();
        try (var provider = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            provider.start(); TenantContext.runWithTenant(tenantId, () -> configureTenantModel(provider.endpoint()));
            var script = provider.register("citation-" + SnowflakeIds.nextId(), RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
            script.requireKnowledge(handle, kb, version, "验收关键词");
            String run = successData(send("/api/v1/chat", "POST", chatPayload(script.prompt()), accessToken)).path("execution_id").asText();
            await().atMost(Duration.ofSeconds(120)).until(() -> countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED'", tenantId, run) == 1);
            var events = queryRows(jdbc, tenantId, "SELECT event_id,canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, run);
            assertEquals(1, events.size());
            String message = events.getFirst().get("event_id").toString();
            var completion = objectMapper.readTree(events.getFirst().get("canonical_json").toString());
            String citation = completion.path("evidence").get(0).path("citationId").asText();
            String endpoint = "/api/v1/chat/citations/content?session_id=" + sessionId + "&message_id=" + message + "&citation_id=" + citation;
            var first = send(endpoint, "GET", null, accessToken);
            assertEquals("no-store", first.headers().firstValue("Cache-Control").orElse(""));
            var content = successData(first);
            assertEquals(version, content.path("source_version").asText());
            assertTrue(content.path("text").asText().contains("验收关键词"));
            assertEquals(404, send(endpoint.replace(citation, "kffffffffffffffff"), "GET", null, accessToken).statusCode());
            for (int index = 0; index < 101; index++) appendMessage("later-" + index, "later-" + index, 1, "后续消息", List.of(), false);
            appendMessage("same-version", message, 1, "同版本迟到内容", List.of(), false);
            assertEquals(content.path("text").asText(), successData(send(endpoint, "GET", null, accessToken)).path("text").asText());
            String other = successData(send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken)).path("session_id").asText();
            assertEquals(404, send(endpoint.replace("session_id=" + sessionId, "session_id=" + other), "GET", null, accessToken).statusCode());
            var changed = successData(send("/api/v1/knowledge/documents/update", "PATCH", Map.of("doc_id", doc,
                    "payload", Map.of("title", "修改后的标题", "content", "后续版本中的新正文", "expected_source_version", version)), accessToken));
            assertNotEquals(version, changed.path("source_version").asText());
            var historical = successData(send(endpoint, "GET", null, accessToken));
            assertEquals(version, historical.path("source_version").asText());
            assertEquals(content.path("text").asText(), historical.path("text").asText());
            revokeKnowledgeRead();
            assertEquals(403, send(endpoint, "GET", null, accessToken).statusCode());
            var item = completion.path("evidence").get(0);
            appendMessage("higher-unsafe", message, 2, completion.path("text").asText(), List.of(Map.of(
                    "citation_id", citation, "item_id", item.path("itemId").asText(), "source_id", item.path("sourceId").asText(),
                    "source_version", version, "label", "知识依据 1")), true);
            assertEquals(404, send(endpoint, "GET", null, accessToken).statusCode());
        }
    }

    /** 提交事实先于投影时，引用读取必须补齐后读取，而不是受旧事务快照遮蔽。 */
    @Test @Timeout(240)
    void citationReadBackfillsPreviouslyUnprojectedCompletion() throws Exception {
        login(); var knowledge = prepareKnowledge();
        org.mockito.Mockito.doNothing().when(projection).appendBorrowed(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(projection).reconcile(org.mockito.ArgumentMatchers.any(com.skillforge.runtime.api.SessionRef.class));
        try (var provider = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            provider.start(); TenantContext.runWithTenant(tenantId, () -> configureTenantModel(provider.endpoint()));
            var script = provider.register("citation-gap-" + SnowflakeIds.nextId(), RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
            script.requireKnowledge(knowledge.handle(), knowledge.kb(), knowledge.version(), "验收关键词");
            String run = successData(send("/api/v1/chat", "POST", chatPayload(script.prompt()), accessToken)).path("execution_id").asText();
            await().atMost(Duration.ofSeconds(120)).until(() -> countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED'", tenantId, run) == 1);
            var events = queryRows(jdbc, tenantId, "SELECT event_id,canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, run);
            assertEquals(1, events.size()); String message = events.getFirst().get("event_id").toString();
            String citation = objectMapper.readTree(events.getFirst().get("canonical_json").toString()).path("evidence").get(0).path("citationId").asText();
            var before = new java.util.concurrent.atomic.AtomicReference<java.util.Optional<com.skillforge.core.chat.session.SessionOutboxEntry>>();
            TenantContext.runWithTenant(tenantId, () -> before.set(mailbox.message(tenantId, sessionId, message)));
            assertTrue(before.get().isEmpty(), "模拟缺口必须在补齐前真实存在");
            org.mockito.Mockito.reset(projection);
            String endpoint = "/api/v1/chat/citations/content?session_id=" + sessionId + "&message_id=" + message + "&citation_id=" + citation;
            var result = successData(send(endpoint, "GET", null, accessToken));
            assertEquals(knowledge.version(), result.path("source_version").asText());
            assertTrue(result.path("text").asText().contains("验收关键词"));
        } finally { org.mockito.Mockito.reset(projection); }
    }

    private record KnowledgeFixture(String kb, String doc, String version, String handle) { }

    private KnowledgeFixture prepareKnowledge() throws Exception {
        String kb = successData(send("/api/v1/knowledge/bases", "POST", Map.of("name", "引用测试知识库",
                "description", "合成内容", "indexing_mode", "economical", "retrieval_mode", "full_text", "rerank_enabled", false), accessToken)).path("id").asText();
        var uploaded = upload(kb);
        String doc = uploaded.path("document_id").asText();
        String version = uploaded.path("source_version").asText();
        var draft = successData(send("/api/v1/knowledge/bases/revisions", "POST", Map.of("kb_id", kb), accessToken));
        String revision = draft.path("revision").asText();
        successData(send("/api/v1/knowledge/bases/revisions/ready", "POST", Map.of("kb_id", kb, "revision", revision,
                "payload", Map.of("source_versions", List.of(version), "policy", Map.of("model_invocable", true,
                        "user_invocable", false, "allowed_operators", List.of("KEYWORD"), "freshness_policy", "PINNED", "citation_required", true))), accessToken));
        var published = successData(send("/api/v1/knowledge/bases/revisions/publish", "POST", Map.of("kb_id", kb, "revision", revision), accessToken));
        var capability = successData(send("/api/v1/capabilities/versions/detail?versionId=" + published.path("capability_version_id").asText(), "GET", null, accessToken));
        agentId = successData(send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken)).path("id").asText();
        var mount = successData(send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                "capability_type", "KNOWLEDGE_SOURCE", "capability_definition_id", capability.path("definition_id").asText(),
                "capability_version_id", published.path("capability_version_id").asText(), "priority", 0,
                "knowledge_config", Map.of("mode", "PINNED_REVISION", "pinned_revision_id", published.path("id").asText()))), accessToken));
        String handle = "knowledge:" + mount.path("mounts").get(0).path("mount_id").asText();
        successData(send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken));
        sessionId = successData(send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken)).path("session_id").asText();
        return new KnowledgeFixture(kb, doc, version, handle);
    }

    /** 单次真实供应商运行；失败或未知状态均保留原事实，不在测试内重试。 */
    @Test @Tag("live") @Timeout(240)
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    void liveDeepSeekUsesCitedSyntheticEvidence() throws Exception {
        login();
        var knowledge = prepareKnowledge();
        String run = successData(send("/api/v1/chat", "POST", chatPayload("请先使用知识检索工具搜索验收关键词，再根据真实检索结果回答，并标注实际使用的知识依据。"), accessToken)).path("execution_id").asText();
        System.out.println("CITATION_LIVE_RUN " + run);
        await().atMost(Duration.ofSeconds(120)).until(() -> countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status IN ('COMPLETED','FAILED','CANCELLED','TERMINATED')", tenantId, run) == 1);
        var facts = queryRows(jdbc, tenantId, "SELECT status FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, run);
        assertEquals("COMPLETED", facts.getFirst().get("status"), "实际运行未完成，保留原结果，不重发");
        var events = queryRows(jdbc, tenantId, "SELECT event_id,canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, run);
        assertEquals(1, events.size());
        var completion = objectMapper.readTree(events.getFirst().get("canonical_json").toString());
        assertFalse(completion.path("evidence").isEmpty(), "模型未实际引用知识，不能声明通过");
        String citation = completion.path("evidence").get(0).path("citationId").asText();
        String endpoint = "/api/v1/chat/citations/content?session_id=" + sessionId + "&message_id=" + events.getFirst().get("event_id") + "&citation_id=" + citation;
        var actual = successData(send(endpoint, "GET", null, accessToken));
        assertEquals(knowledge.version(), actual.path("source_version").asText());
        assertTrue(actual.path("text").asText().contains("验收关键词"));
        assertTrue(countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND target_handle='deepseek-flash'", tenantId, run) >= 1);
        assertTrue(countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='KNOWLEDGE' AND status='SUCCEEDED'", tenantId, run) >= 1);
        System.out.println("CITATION_LIVE_COMPLETED " + run);
    }

    /** 向测试专属会话追加公开快照，检验历史消息窗口和同 ID 版本折叠。 */
    private void appendMessage(String event, String message, int version, String content, List<?> citations, boolean unsafe) {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("message_id", message); data.put("message_version", version); data.put("role", "assistant");
        data.put("content", content); data.put("state", "completed"); data.put("citations", citations);
        if (unsafe) data.put("unsafe", "api_key=real-secret-value");
        TenantContext.runWithTenant(tenantId, () -> mailbox.appendOutput(tenantId, sessionId, "fixture:" + event,
                "message.delta", Map.of("event", "message.delta", "data", data)));
    }

    private JsonNode upload(String kb) throws Exception {
        String boundary = "CitationFixture" + SnowflakeIds.nextId();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"citation.txt\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n原始版本\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/knowledge/bases/documents/upload?kb_id=" + kb + "&request_id=" + java.util.UUID.randomUUID()))
                .timeout(Duration.ofSeconds(90)).header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray("验收关键词：这是一段用于验证引用的合成原文。".getBytes(StandardCharsets.UTF_8)),
                        HttpRequest.BodyPublishers.ofByteArray(suffix))).build();
        var receipt = successData(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
        assertEquals("SUCCEEDED", receipt.path("status").asText());
        return receipt.path("document");
    }

    /** 仅在此测试新建租户内撤销角色的知识读取动作，保留原会话读取动作。 */
    private void revokeKnowledgeRead() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            boolean auto = connection.getAutoCommit(); connection.setAutoCommit(false);
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                bind.setString(1, tenantId); bind.execute();
                try (var statement = connection.prepareStatement("UPDATE nw_foundation_roles SET administrator=false WHERE scope_key=? AND administrator=true")) {
                    statement.setString(1, tenantId); assertTrue(statement.executeUpdate() > 0);
                }
                try (var statement = connection.prepareStatement("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) SELECT scope_key,id,'foundation.sessions.read' FROM nw_foundation_roles WHERE scope_key=? AND code='tenant_admin' ON CONFLICT DO NOTHING")) {
                    statement.setString(1, tenantId); statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("DELETE FROM nw_foundation_role_permissions WHERE scope_key=? AND permission_code='foundation.resource.knowledge.read'")) {
                    statement.setString(1, tenantId); statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(auto); }
            return null;
        });
    }
}

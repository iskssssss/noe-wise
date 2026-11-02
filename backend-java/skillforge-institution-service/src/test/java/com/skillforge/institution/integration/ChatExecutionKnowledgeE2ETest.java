package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.runtime.AgentRuntimeExecutionConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** 复用已验新Chain的账号/PG/Provider/产品HTTP，只增加知识公开发布与typed Knowledge事实断言。 */
@Tag("runtime-latency")
@Import({ChatRuntimeChannelE2ETest.LoopbackEvents.class, DeterministicModelCatalogConfiguration.class,
        AgentRuntimeExecutionConfiguration.class})
class ChatExecutionKnowledgeE2ETest extends ChatExecutionChainE2ETest {
    /** 实际关键词证据进入下一模型且引用原itemId；重复产品输入不重检索或追加外调。 */
    @Test
    void publishedKeywordKnowledgeFeedsActualEvidenceAndCitationWithoutOldLedger() throws Exception {
        Path evidence = Path.of("build", "reports", "runtime-execution-knowledge", "keyword-" + SnowflakeIds.nextId());
        Files.createDirectories(evidence);
        var report = new LinkedHashMap<String, Object>(); report.put("outcome", "FAILED");
        report.put("scenario", "2 real model HTTP calls + 1 PostgreSQL KEYWORD knowledge retrieval; exact actual item citation");
        String actualRun = "run:" + runId;
        try (var provider = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            provider.start(); TenantContext.setTenantId(tenantId);
            try { configureTenantModel(provider.endpoint()); } finally { TenantContext.clear(); }
            login();
            String sentinel = "runtimeknowledge" + SnowflakeIds.nextId();
            var published = new RuntimeExecutionKnowledgeFixture(objectMapper, httpClient, baseUrl(), accessToken).publish(runId, sentinel);
            String handle = mountKnowledge(published);
            var script = provider.register("knowledge-" + SnowflakeIds.nextId(), RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
            script.requireKnowledge(handle, published.kbId(), published.sourceVersion(), sentinel);
            report.put("run", actualRun); report.put("session", sessionId); report.put("knowledge", published); report.put("handle", handle);
            try {
                var body = chatPayload(script.prompt());
                var capture = postSse(body);
                Files.writeString(evidence.resolve("complete.sse.txt"), capture.raw(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                assertEquals(200, capture.statusCode()); assertTrue(capture.events().contains("execution.completed"), capture.raw());
                assertFalse(capture.events().contains("error"), capture.raw()); assertTrue(capture.raw().contains(script.finalAnswer()), capture.raw());
                long limit = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (script.snapshot().requests().size() < 2 && System.nanoTime() < limit) Thread.sleep(10);
                var snapshot = script.snapshot();
                assertEquals(2, snapshot.requestCount()); assertEquals(2, snapshot.requests().size());
                assertFalse(snapshot.verifiedKnowledgeItemIds().isEmpty(), snapshot.toString());
                assertEquals(0, snapshot.verifiedToolResults());
                for (var request : snapshot.requests()) { assertEquals(200, request.httpStatus()); assertEquals("", request.failureReason()); }
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? "
                        + "AND status='COMPLETED' AND phase='FINISHED' AND lease_claim_id IS NULL AND decisions=2 AND knowledge_calls=1 AND tool_calls=0", tenantId, actualRun));
                assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='KNOWLEDGE' AND status='SUCCEEDED'", tenantId, actualRun));
                assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
                assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED' AND result_ref IS NOT NULL", tenantId, actualRun));
                assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
                var selected = queryRows(jdbc, tenantId, "SELECT m.canonical_json FROM agent_runtime_execution_actions a "
                        + "JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt "
                        + "JOIN agent_runtime_execution_materials m ON m.tenant_id=t.tenant_id AND m.reference=t.result_ref "
                        + "WHERE a.tenant_id=? AND a.run_id=? AND a.kind='KNOWLEDGE' AND t.adopted", tenantId, actualRun);
                assertEquals(1, selected.size());
                var bundle = bundle(objectMapper.readTree(selected.getFirst().get("canonical_json").toString()));
                assertNotNull(bundle, "选定Knowledge Attempt必须保存真实EvidenceBundle，不由SSE或用户文本拼出");
                assertTrue(bundle.path("items").isArray()); assertFalse(bundle.path("items").isEmpty());
                for (var item : bundle.path("items")) {
                    assertEquals(published.kbId(), item.path("sourceId").asText());
                    assertEquals(published.sourceVersion(), item.path("sourceVersion").asText());
                    assertEquals("KEYWORD", item.path("operator").asText());
                    assertTrue(objectMapper.readTree(item.path("content").path("canonicalJson").asText()).path("text").asText().contains(sentinel));
                }
                for (var step : bundle.path("plan").path("steps")) assertEquals(sentinel, step.path("query").asText());
                for (String id : snapshot.verifiedKnowledgeItemIds()) assertTrue(java.util.stream.StreamSupport.stream(bundle.path("items").spliterator(), false)
                        .anyMatch(item -> id.equals(item.path("itemId").asText())), "引用必须来自本次真实Bundle");
                report.put("selectedBundle", bundle);
                var completed = queryRows(jdbc, tenantId, "SELECT canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, actualRun);
                assertEquals(1, completed.size());
                var savedCitations = objectMapper.readTree(completed.getFirst().get("canonical_json").toString()).path("evidence");
                assertEquals(snapshot.verifiedKnowledgeItemIds().size(), savedCitations.size());
                for (var citation : savedCitations) {
                    assertTrue(snapshot.verifiedKnowledgeItemIds().contains(citation.path("itemId").asText()));
                    assertEquals(bundle.path("bundleId").asText(), citation.path("bundleId").asText());
                    assertEquals(published.kbId(), citation.path("sourceId").asText());
                    assertEquals(published.sourceVersion(), citation.path("sourceVersion").asText());
                }
                var publicCitations = citations(capture, script.finalAnswer());
                assertPublicCitations(publicCitations, snapshot.verifiedKnowledgeItemIds(), published);
                var history = successData(get("/api/v1/chat/history?session_id=" + sessionId));
                var historyAnswers = java.util.stream.StreamSupport.stream(history.path("messages").spliterator(), false)
                        .filter(message -> "assistant".equals(message.path("role").asText()) && script.finalAnswer().equals(message.path("content").asText())).toList();
                assertEquals(1, historyAnswers.size(), history.toString());
                assertEquals(publicCitations, historyAnswers.getFirst().path("metadata").path("citations"));
                var replay = send("/api/v1/chat", "POST", body, accessToken);
                assertEquals(202, replay.statusCode(), replay.body()); assertTrue(successData(replay).path("replayed").asBoolean());
                assertEquals(actualRun, successData(replay).path("execution_id").asText());
                assertEquals(2, script.snapshot().requestCount());
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='KNOWLEDGE'", tenantId, actualRun));
                for (String table : List.of("agent_runtime_runs", "agent_runtime_actions", "agent_runtime_session_events", "nw_runtime_session_knowledge_observations",
                        "nw_runtime_session_knowledge_operations")) assertEquals(0, countRuntime("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND run_id=?", tenantId, actualRun), table);
                assertEquals(0, countRuntime("SELECT count(*) FROM nw_runtime_knowledge_results WHERE tenant_id=? AND action_id IN "
                        + "(SELECT action_id FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=?)", tenantId, tenantId, actualRun),
                        "Knowledge原结果只能由新Store材料引用持有，不写旧二份结果表");
                report.put("outcome", "PASS");
            } finally { report.put("provider", script.snapshot()); }
        } catch (Exception | AssertionError failed) { report.put("failureType", failed.getClass().getName()); throw failed; }
        finally {
            Files.writeString(evidence.resolve("report.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("RUNTIME_EXECUTION_KNOWLEDGE_REPORT " + evidence.resolve("report.json").toAbsolutePath());
        }
    }

    /** 正式挂载精确已发布revision，原schema/当前权限仍由Registry和Sources核验。 */
    private String mountKnowledge(RuntimeExecutionKnowledgeFixture.Published published) throws Exception {
        var revise = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revise.statusCode(), revise.body()); agentId = successData(revise).path("id").asText();
        var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of("capability_type", "KNOWLEDGE_SOURCE",
                "capability_definition_id", Long.toString(published.definitionId()), "capability_version_id", Long.toString(published.capabilityVersionId()), "priority", 0,
                "knowledge_config", Map.of("mode", "PINNED_REVISION", "pinned_revision_id", Long.toString(published.revisionId())))), accessToken);
        assertEquals(201, mounted.statusCode(), mounted.body());
        var mountSet = successData(mounted); assertEquals(1, mountSet.path("mounts").size());
        String mount = mountSet.path("mounts").get(0).path("mount_id").asText(); assertFalse(mount.isBlank());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText(); assertFalse(sessionId.isBlank());
        return "knowledge:" + mount;
    }

    /** SSE只读取最终实际assistant消息的公开引用，不能在任意文本中搜索itemId放行。 */
    private JsonNode citations(SseCapture capture, String finalAnswer) throws Exception {
        String event = null;
        for (String line : capture.raw().split("\\R")) {
            if (line.startsWith("event:")) event = line.substring(6).trim();
            else if (line.startsWith("data:") && "message.delta".equals(event)) {
                var message = objectMapper.readTree(line.substring(5).trim());
                if ("assistant".equals(message.path("role").asText()) && finalAnswer.equals(message.path("content").asText())) return message.path("citations");
            }
        }
        throw new AssertionError("最终实际assistant SSE消息缺少引用");
    }

    private static void assertPublicCitations(JsonNode citations, List<String> ids, RuntimeExecutionKnowledgeFixture.Published published) {
        assertTrue(citations.isArray()); assertEquals(ids.size(), citations.size());
        for (var citation : citations) {
            assertTrue(ids.contains(citation.path("item_id").asText()));
            assertEquals(published.kbId(), citation.path("source_id").asText());
            assertEquals(published.sourceVersion(), citation.path("source_version").asText());
            assertFalse(citation.has("result_ref")); assertFalse(citation.has("bundle_id")); assertFalse(citation.has("provenance_refs"));
        }
    }

    /** 只解码选定Attempt的实际typed payload，正文canonicalJson来自明确协议字段。 */
    private JsonNode bundle(JsonNode value) throws Exception {
        if (value == null) return null;
        if (value.isObject() && value.has("bundleId") && value.has("knowledgeRequestId") && value.path("items").isArray()) return value;
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                var found = "canonicalJson".equals(field.getKey()) && field.getValue().isTextual()
                        ? bundle(objectMapper.readTree(field.getValue().asText())) : bundle(field.getValue());
                if (found != null) return found;
            }
        } else if (value.isArray()) for (var child : value) { var found = bundle(child); if (found != null) return found; }
        return null;
    }
}

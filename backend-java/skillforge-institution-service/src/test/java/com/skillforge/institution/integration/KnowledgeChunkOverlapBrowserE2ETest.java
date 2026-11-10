package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.KnowledgePortal;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 正式上传与分块、真实页面检索及 Runtime 引用的长段边界验收。 */
class KnowledgeChunkOverlapBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final String QUERY = "跨段检索凭证";
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("knowledge-overlap-");

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        registry.add("skillforge.elasticsearch-enabled", () -> false);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void longParagraphRemainsSearchableAcrossChunkBoundary() throws Exception {
        String mode = System.getenv("E2E_KNOWLEDGE_OVERLAP");
        assertTrue("baseline".equals(mode) || "candidate".equals(mode), "E2E_KNOWLEDGE_OVERLAP 必须指定 baseline 或 candidate");
        boolean baseline = "baseline".equals(mode);
        login();
        Path evidence = Path.of("build/runtime-e2e/knowledge-overlap-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright"); Files.createDirectories(browser);
        String content = "甲".repeat(97) + QUERY + "乙".repeat(80);
        Files.writeString(evidence.resolve("source.txt"), content);
        var published = new RuntimeExecutionKnowledgeFixture(objectMapper, httpClient, baseUrl(), accessToken)
                .publish(runId, QUERY, content, Map.of("indexing_mode", "economical", "retrieval_mode", "full_text",
                        "chunk_size", 100, "chunk_overlap", 20), evidence);
        var configuration = get("/api/v1/knowledge/bases/detail?kbId=" + published.kbId());
        Files.writeString(evidence.resolve("configuration.json"), configuration.body());
        assertEquals(100, successData(configuration).path("chunk_size").asInt());
        assertEquals(20, successData(configuration).path("chunk_overlap").asInt());
        var chunksResponse = get("/api/v1/knowledge/documents/chunks?docId=" + published.documentId() + "&page=1&pageSize=100");
        Files.writeString(evidence.resolve("chunks.json"), chunksResponse.body());
        var chunks = successData(chunksResponse).path("items");
        assertTrue(chunks.size() > 1);
        int containing = 0;
        for (var chunk : chunks) {
            assertTrue(chunk.path("content").asText().length() <= 100);
            assertEquals(published.sourceVersion(), chunk.path("source_version").asText());
            if (chunk.path("content").asText().contains(QUERY)) containing++;
        }
        assertEquals(baseline, containing == 0);
        FRONTEND.bindBackend(baseUrl());
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        Path cli = admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
        var command = new ProcessBuilder(cli.toString(), "test", "e2e/knowledge-chunk-overlap-real.spec.ts");
        command.directory(admin.toFile()); command.redirectErrorStream(true);
        command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_KNOWLEDGE_ID", published.kbId()); env.put("E2E_KNOWLEDGE_SOURCE_VERSION", published.sourceVersion());
        env.put("E2E_KNOWLEDGE_OVERLAP", mode);
        env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "知识浏览器超时：" + evidence);
            assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
        }
        var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revised.statusCode(), revised.body()); agentId = successData(revised).path("id").asText();
        var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                "capability_type", "KNOWLEDGE_SOURCE", "capability_definition_id", Long.toString(published.definitionId()),
                "capability_version_id", Long.toString(published.capabilityVersionId()), "priority", 0,
                "knowledge_config", Map.of("mode", "FOLLOW_ACTIVE"))), accessToken);
        Files.writeString(evidence.resolve("mount.json"), mounted.body());
        assertEquals(201, mounted.statusCode(), mounted.body());
        String mount = successData(mounted).path("mounts").get(0).path("mount_id").asText();
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
        String actualRun = "run:" + runId;
        try (var model = new OverlapModel("knowledge:" + mount)) {
            model.server.start(); TenantContext.setTenantId(tenantId);
            try { configureTenantModel("http://127.0.0.1:" + model.server.getAddress().getPort()); }
            finally { TenantContext.clear(); }
            try {
                var capture = postSse(chatPayload("查询知识库中的" + QUERY + "并引用原文"));
                Files.writeString(evidence.resolve("runtime-sse.txt"), capture.raw());
                assertEquals(200, capture.statusCode()); assertTrue(capture.events().contains("execution.completed"), capture.raw());
                assertEquals(2, model.requests.size());
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun));
                var materials = queryRows(jdbc, tenantId, "SELECT req.schema_id AS request_schema,req.canonical_json AS request_json,res.schema_id AS result_schema,res.canonical_json AS result_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='KNOWLEDGE' AND a.status='SUCCEEDED' AND t.adopted AND t.result_kind='SUCCEEDED'", tenantId, actualRun);
                Files.writeString(evidence.resolve("runtime-materials.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(materials));
                assertEquals(1, materials.size()); var row = materials.getFirst();
                var properties = applicationContext.getBean(AgentRuntimeProperties.class);
                var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
                var request = codec.decodeKnowledgeRequest(new TypedPayload(row.get("request_schema").toString(), row.get("request_json").toString()));
                assertEquals(QUERY, assertInstanceOf(com.skillforge.runtime.api.KnowledgeOperation.Search.class, request.request().operation()).query());
                assertEquals("knowledge:" + mount, request.target().handle().value());
                var outcome = assertInstanceOf(KnowledgePortal.KnowledgeOutcome.Retrieved.class,
                        codec.decodeKnowledgeOutcome(new TypedPayload(row.get("result_schema").toString(), row.get("result_json").toString())));
                assertEquals(baseline, outcome.bundle().items().isEmpty());
                assertNotNull(model.received); assertEquals(outcome.bundle().items().size(), model.received.path("items").size());
                for (var item : outcome.bundle().items()) {
                    assertEquals(published.kbId(), item.sourceId()); assertEquals(published.sourceVersion(), item.sourceVersion());
                    assertTrue(objectMapper.readTree(item.content().canonicalJson()).path("text").asText().contains(QUERY));
                }
                if (!baseline) {
                    JsonNode message = null;
                    for (String line : capture.raw().split("\\R")) {
                        if (!line.startsWith("data:")) continue;
                        var event = objectMapper.readTree(line.substring(5));
                        if (event.path("citations").isArray() && !event.path("citations").isEmpty()) message = event;
                    }
                    assertNotNull(message, "真实命中必须生成可读取引用");
                    Files.writeString(evidence.resolve("runtime-message.json"), message.toPrettyString());
                    var citation = message.path("citations").get(0);
                    assertEquals(published.sourceVersion(), citation.path("source_version").asText());
                    var source = get("/api/v1/chat/citations/content?session_id=" + sessionId + "&message_id="
                            + URLEncoder.encode(message.path("message_id").asText(), StandardCharsets.UTF_8)
                            + "&citation_id=" + citation.path("citation_id").asText());
                    Files.writeString(evidence.resolve("runtime-citation.json"), source.body());
                    assertEquals(200, source.statusCode(), source.body());
                    assertEquals(published.sourceVersion(), successData(source).path("source_version").asText());
                    assertTrue(successData(source).path("text").asText().contains(QUERY));
                }
            } finally {
                Files.writeString(evidence.resolve("model-requests.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model.requests));
                Files.writeString(evidence.resolve("runtime-state.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "runs", queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, actualRun),
                        "actions", queryRows(jdbc, tenantId, "SELECT a.action_id,a.kind,a.status,t.result_kind,t.adopted,m.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials m ON m.tenant_id=t.tenant_id AND m.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal", tenantId, actualRun))));
            }
        }
    }

    /** 模型只请求检索并引用实际 TOOL 回传，基线空结果同样正常收尾。 */
    private final class OverlapModel implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        volatile JsonNode received;

        OverlapModel(String handle) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    var request = objectMapper.readTree(exchange.getRequestBody()); requests.add(request.deepCopy());
                    int ordinal = requests.size();
                    Object delta; String finish;
                    if (ordinal == 1) {
                        boolean declared = false;
                        for (var tool : request.path("tools")) if ("knowledge_query".equals(tool.path("function").path("name").asText())) declared = true;
                        if (!declared) throw new java.io.IOException("knowledge_query_missing");
                        var args = Map.of("knowledge_handle", handle, "query", QUERY, "commentary", "检索跨段短语");
                        delta = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "overlap-query", "type", "function",
                                "function", Map.of("name", "knowledge_query", "arguments", objectMapper.writeValueAsString(args)))));
                        finish = "tool_calls";
                    } else if (ordinal == 2) {
                        String callId = null;
                        for (var message : request.path("messages")) for (var call : message.path("tool_calls")) {
                            if (!"knowledge_query".equals(call.path("function").path("name").asText())) continue;
                            var args = objectMapper.readTree(call.path("function").path("arguments").asText());
                            if (!handle.equals(args.path("knowledge_handle").asText()) || !QUERY.equals(args.path("query").asText()) || callId != null)
                                throw new java.io.IOException("knowledge_query_identity_mismatch");
                            callId = call.path("id").asText();
                        }
                        if (callId == null) throw new java.io.IOException("knowledge_query_call_missing");
                        for (var message : request.path("messages")) if ("tool".equals(message.path("role").asText())
                                && callId.equals(message.path("tool_call_id").asText())) received = objectMapper.readTree(message.path("content").asText());
                        if (received == null || !received.path("items").isArray()) throw new java.io.IOException("knowledge_result_missing");
                        var answer = new StringBuilder("KNOWLEDGE_OVERLAP_OBSERVED ");
                        if (received.path("items").isEmpty()) answer.append("未检索到完整短语");
                        for (var item : received.path("items")) answer.append(item.path("text").asText()).append("[[cite:").append(item.path("citationId").asText()).append("]]");
                        delta = Map.of("role", "assistant", "content", answer.toString()); finish = "stop";
                    } else throw new java.io.IOException("unexpected_model_call");
                    var chunk = Map.of("id", "overlap-model", "object", "chat.completion.chunk", "model", request.path("model").asText(),
                            "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)),
                            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15));
                    byte[] body = ("data: " + objectMapper.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
        }
        @Override public void close() { server.stop(0); }
    }
}

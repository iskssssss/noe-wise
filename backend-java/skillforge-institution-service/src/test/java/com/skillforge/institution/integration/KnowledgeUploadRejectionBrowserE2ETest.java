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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实写锁拒绝、上传回执与重新上传发布的窄闭环。 */
@org.springframework.context.annotation.Import(KnowledgeRebuildProgressBrowserE2ETest.CombinedCatalog.class)
class KnowledgeUploadRejectionBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("knowledge-upload-rejection-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @org.springframework.beans.factory.annotation.Autowired com.skillforge.core.model.PlatformModelCatalogService models;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.skillforge.core.knowledge.EmbeddingProvider embedding;

    @Test void rejectedUploadCanEndAndNewSourceCanReachRuntime() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_KNOWLEDGE_UPLOAD_REJECTION", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        String initialMarker = "uploadoriginal" + SnowflakeIds.nextId(), newMarker = "uploadrecovered" + SnowflakeIds.nextId();
        Path evidence = Path.of("build/runtime-e2e/knowledge-upload-rejection-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright"); Files.createDirectories(browser);
        System.out.println("KNOWLEDGE_UPLOAD_REJECTION_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Isolated real Spring/PostgreSQL/Redis/Vite/browser/KEYWORD/Runtime and controlled HTTP model. EmbeddingProvider alone is Mockito-controlled for index vectors. No external supplier acceptance. Trace disabled to exclude credentials.");
        var http = new ArrayList<Map<String, Object>>();
        var embeddingCalls = new java.util.concurrent.atomic.AtomicInteger();
        String kb = null;
        try (var model = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            model.start(); TenantContext.setTenantId(tenantId);
            try { configureTenantModel(model.endpoint()); } finally { TenantContext.clear(); }
            var draft = models.create(new com.skillforge.core.model.ModelWrite(null,"上传回执原模型","EMBEDDING",true,"custom","knowledge-embedding-fixture","knowledge-fixture-v1","openai-embeddings","bearer-api-key","https://embedding.example/v1","upload-initial","test-secret",null,null,null,null,null,30,768,null,null,null,null,"CNY"), "upload-rejection");
            var seed = models.publish(draft.id(), draft.revision(), "upload-rejection");
            org.mockito.Mockito.when(embedding.config(org.mockito.ArgumentMatchers.anyString())).thenReturn(models.readRevision(seed.id(), seed.revision()));
            org.mockito.Mockito.when(embedding.embedBatch(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> {
                embeddingCalls.incrementAndGet();
                List<String> input = invocation.getArgument(3); float[] vector = new float[768]; vector[0] = 1;
                return input.stream().map(ignored -> vector).toList();
            });
            var original = new LegacyRebuildKnowledgeFixture(objectMapper, httpClient, baseUrl(), accessToken).publish(runId, initialMarker);
            kb = original.kbId();
            Files.writeString(evidence.resolve("initial-source.json"), objectMapper.writeValueAsString(original));
            var targetDraft = models.create(new com.skillforge.core.model.ModelWrite(null,"上传回执重建模型","EMBEDDING",true,"custom","knowledge-embedding-fixture","knowledge-fixture-v1","openai-embeddings","bearer-api-key","https://embedding.example/v1","upload-target","test-secret",null,null,null,null,null,30,768,null,null,null,null,"CNY"), "upload-rejection");
            var target = models.publish(targetDraft.id(), targetDraft.revision(), "upload-rejection");
            var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
            assertEquals(200, revised.statusCode(), revised.body()); agentId = successData(revised).path("id").asText();
            var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                    "capability_type", "KNOWLEDGE_SOURCE", "capability_definition_id", Long.toString(original.definitionId()),
                    "capability_version_id", Long.toString(original.capabilityVersionId()), "knowledge_config", Map.of("mode", "FOLLOW_ACTIVE"), "priority", 0)), accessToken);
            assertEquals(201, mounted.statusCode(), mounted.body());
            String mountId = successData(mounted).path("mounts").get(0).path("mount_id").asText();
            var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
            assertEquals(200, publication.statusCode(), publication.body());
            exerciseSource(model, kb, original.sourceVersion(), original.definitionId(), original.capabilityVersionId(), mountId, initialMarker, evidence, "original");
            FRONTEND.bindBackend(baseUrl()); Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path cli = admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
            var command = new ProcessBuilder(cli.toString(), "test", "e2e/knowledge-upload-rejection-real.spec.ts");
            command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = command.environment();
            env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString()); env.put("E2E_GATE_DIR", evidence.toString());
            env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_USERNAME", "runtime-e2e-admin"); env.put("E2E_PASSWORD", PASSWORD);
            env.put("E2E_KNOWLEDGE_ID", kb); env.put("E2E_KNOWLEDGE_MARKER", newMarker); env.put("E2E_KNOWLEDGE_UPLOAD_REJECTION", mode);
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = command.start();
            boolean created = false, cancelled = false; JsonNode ready = null;
            int embeddingAtReady = -1;
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (process.isAlive() && System.nanoTime() < deadline) {
                    owned.capture();
                    if (!created && Files.exists(evidence.resolve("page-ready"))) {
                        var current = successData(get("/api/v1/knowledge/bases/rebuilds/detail?kbId=" + kb));
                        var body = Map.<String, Object>of("kb_id", kb, "target_model_id", target.id(), "target_model_revision", target.revision(), "idempotency_key", UUID.randomUUID().toString(), "expected_base_revision", current.path("base_revision").asLong());
                        var response = send("/api/v1/knowledge/bases/rebuilds/create", "POST", body, accessToken);
                        http.add(Map.of("path", "/api/v1/knowledge/bases/rebuilds/create", "request", body, "status", response.statusCode(), "response", response.body()));
                        assertEquals(200, response.statusCode(), response.body()); created = true;
                    }
                    if (created && ready == null) {
                        var response = get("/api/v1/knowledge/bases/rebuilds/detail?kbId=" + kb);
                        var current = successData(response);
                        assertFalse(List.of("FAILED", "CANCELLED").contains(current.path("status").asText()), response.body());
                        if ("READY".equals(current.path("status").asText())) {
                            ready = current; assertTrue(ready.path("write_locked").asBoolean());
                            embeddingAtReady = embeddingCalls.get();
                            Files.writeString(evidence.resolve("rebuild-ready.json"), objectMapper.writeValueAsString(ready));
                        }
                    }
                    if (!cancelled && Files.exists(evidence.resolve("cancel-rebuild"))) {
                        var attempted = objectMapper.readTree(Files.readString(evidence.resolve("upload-response.json")));
                        String request = attempted.path("requestId").asText();
                        var receiptResponse = get("/api/v1/knowledge/bases/documents/upload/result?kb_id=" + kb + "&request_id=" + request);
                        var receipt = successData(receiptResponse);
                        Files.writeString(evidence.resolve("receipt-before-cancel.json"), receiptResponse.body());
                        assertEquals("baseline".equals(mode) ? "PROCESSING" : "REJECTED", receipt.path("status").asText());
                        Files.writeString(evidence.resolve("locked-upload-state.json"), objectMapper.writeValueAsString(Map.of("embeddingAtReady", embeddingAtReady, "embeddingAfterRejectedUpload", embeddingCalls.get(), "receipt", receipt)));
                        assertEquals(embeddingAtReady, embeddingCalls.get(), "写锁拒绝不得调用向量服务");
                        assertEquals(1, countRuntime("SELECT count(*) FROM nw_knowledge_documents WHERE tenant_id=CAST(? AS bigint) AND kb_id=CAST(? AS bigint) AND deleted_at IS NULL", tenantId, kb));
                        var body = Map.<String, Object>of("kb_id", kb, "job_id", ready.path("job_id").asText(), "expected_revision", ready.path("revision").asLong());
                        var response = send("/api/v1/knowledge/bases/rebuilds/cancel", "POST", body, accessToken);
                        http.add(Map.of("path", "/api/v1/knowledge/bases/rebuilds/cancel", "request", body, "status", response.statusCode(), "response", response.body()));
                        var result = successData(response); assertEquals("CANCELLED", result.path("status").asText());
                        Files.writeString(evidence.resolve("rebuild-cancelled.json"), objectMapper.writeValueAsString(result)); cancelled = true;
                    }
                    process.waitFor(200, TimeUnit.MILLISECONDS);
                }
                owned.capture(); assertFalse(process.isAlive(), "知识上传浏览器超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            }
            assertTrue(created); assertTrue(cancelled);
            assertEquals("baseline".equals(mode) ? 1 : 2, countRuntime("SELECT count(*) FROM nw_knowledge_documents WHERE tenant_id=CAST(? AS bigint) AND kb_id=CAST(? AS bigint) AND deleted_at IS NULL", tenantId, kb));
            if ("candidate".equals(mode)) {
                var published = objectMapper.readTree(Files.readString(browser.resolve("publication.json"))).path("receipt").path("revision");
                String source = published.path("source_versions").get(0).asText();
                assertNotEquals(original.sourceVersion(), source);
                runId = "knowledge-upload-after-" + SnowflakeIds.nextId();
                exerciseSource(model, kb, source, original.definitionId(), published.path("capability_version_id").asLong(), mountId, newMarker, evidence, "recovered");
            }
        } finally {
            Files.writeString(evidence.resolve("http-control.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(http));
            if (kb != null) Files.writeString(evidence.resolve("database.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "receipts", queryRows(jdbc, tenantId, "SELECT request_id,status,document_id,source_version,error_message FROM nw_knowledge_upload_receipts WHERE tenant_id=CAST(? AS bigint) AND kb_id=CAST(? AS bigint) LIMIT 10", tenantId, kb),
                    "documents", queryRows(jdbc, tenantId, "SELECT id,current_source_version FROM nw_knowledge_documents WHERE tenant_id=CAST(? AS bigint) AND kb_id=CAST(? AS bigint) AND deleted_at IS NULL LIMIT 10", tenantId, kb),
                    "rebuilds", queryRows(jdbc, tenantId, "SELECT id,status,revision FROM nw_knowledge_rebuild_jobs WHERE tenant_id=CAST(? AS bigint) AND kb_id=CAST(? AS bigint) LIMIT 10", tenantId, kb))));
        }
    }
    private void exerciseSource(RuntimeExecutionWorkloadProvider model,String kb,String source,long definition,long version,String mountId,String sentinel,Path evidence,String stage) throws Exception {
            var script=model.register("knowledge-"+SnowflakeIds.nextId(),RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
            script.requireKnowledge("knowledge:"+mountId,kb,source,sentinel);
            var session=send("/api/v1/chat/sessions","POST",Map.of("agent_id",agentId,"end_user_id",endUserId),accessToken);
            assertEquals(200,session.statusCode(),session.body());sessionId=successData(session).path("session_id").asText();
            String actualRun="run:"+runId;
            try {
                var input = chatPayload(script.prompt());
                Files.writeString(evidence.resolve("runtime-input-" + stage + ".json"), objectMapper.writeValueAsString(input));
                var capture=postSse(input);
                Files.writeString(evidence.resolve("runtime-stream-" + stage + ".txt"), capture.raw());
                assertEquals(200,capture.statusCode());assertTrue(capture.events().contains("execution.completed"),capture.raw());assertFalse(capture.events().contains("error"),capture.raw());
                assertTrue(capture.raw().contains(script.finalAnswer()),capture.raw());
                assertEquals(2,script.snapshot().requestCount());assertFalse(script.snapshot().verifiedKnowledgeItemIds().isEmpty());
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'",tenantId,actualRun,agentId,sessionId));
                var selected=queryRows(jdbc,tenantId,"SELECT request.schema_id AS request_schema,request.canonical_json AS request_json,result.schema_id AS result_schema,result.canonical_json AS result_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt JOIN agent_runtime_execution_materials request ON request.tenant_id=a.tenant_id AND request.reference=a.request_ref JOIN agent_runtime_execution_materials result ON result.tenant_id=t.tenant_id AND result.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='KNOWLEDGE' AND a.status='SUCCEEDED' AND t.adopted AND t.result_kind='SUCCEEDED'",tenantId,actualRun);
                Files.writeString(evidence.resolve("knowledge-materials-" + stage + ".json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(selected));
                assertEquals(1,selected.size());var row=selected.getFirst();var properties=applicationContext.getBean(AgentRuntimeProperties.class);
                var codec=new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(),properties.getMaxPayloadNodes(),properties.getMaxPayloadDepth()));
                var request=codec.decodeKnowledgeRequest(new TypedPayload(row.get("request_schema").toString(),row.get("request_json").toString()));
                assertEquals("knowledge:"+mountId,request.target().handle().value());assertEquals(Long.toString(definition),request.target().capability().capabilityId());
                assertEquals(Long.toString(version),request.target().capability().versionId());
                assertEquals(kb,request.target().descriptorId());
                var retrieved=assertInstanceOf(KnowledgePortal.KnowledgeOutcome.Retrieved.class,codec.decodeKnowledgeOutcome(new TypedPayload(row.get("result_schema").toString(),row.get("result_json").toString())));
                assertFalse(retrieved.bundle().items().isEmpty());
                for(var item:retrieved.bundle().items()) {
                    assertEquals(kb,item.sourceId());assertEquals(source,item.sourceVersion());
                    assertEquals(com.skillforge.runtime.api.KnowledgeOperatorType.KEYWORD, item.operator());
                    assertTrue(objectMapper.readTree(item.content().canonicalJson()).path("text").asText().contains(sentinel));
                }
                Files.writeString(evidence.resolve("knowledge-runtime-"+stage+".json"),objectMapper.writeValueAsString(Map.of("agentId",agentId,"runId",actualRun,"kbId",kb,"sourceVersion",source,"capabilityVersionId",Long.toString(version),"mountId",mountId,"knowledgeActions",1,"modelCalls",script.snapshot().requestCount(),"bindingMode","FOLLOW_ACTIVE")));
            } finally {
                Files.writeString(evidence.resolve("model-http-" + stage + ".json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(script.snapshot()));
                var state=Map.of("runs",queryRows(jdbc,tenantId,"SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?",tenantId,actualRun),
                        "actions",queryRows(jdbc,tenantId,"SELECT action_id,kind,status FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal",tenantId,actualRun),
                        "budgets",queryRows(jdbc,tenantId,"SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE run_id=? LIMIT 20",actualRun),
                        "attempts",queryRows(jdbc,tenantId,"SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",tenantId,actualRun));
                Files.writeString(evidence.resolve("knowledge-runtime-state-"+stage+".json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            }
    }
}

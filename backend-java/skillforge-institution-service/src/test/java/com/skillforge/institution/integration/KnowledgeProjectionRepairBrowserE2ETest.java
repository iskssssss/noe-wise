package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.model.*;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;

import com.skillforge.core.knowledge.search.SearchIndexer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.springframework.web.client.RestClient;
/** 从已发布向量来源修复真实隔离 ES 投影并验证引用。 */
@Import(KnowledgeSemanticThresholdBrowserE2ETest.Catalog.class)
class KnowledgeProjectionRepairBrowserE2ETest extends ChatExecutionChainE2ETest {
    static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("knowledge-projection-repair-");
    static final GenericContainer<?> ES = new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.15.3")
            .withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms384m -Xmx384m").withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200)).withStartupTimeout(java.time.Duration.ofMinutes(3));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        ES.start();
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        registry.add("skillforge.elasticsearch-enabled", () -> true);
        registry.add("skillforge.elasticsearch-uri", KnowledgeProjectionRepairBrowserE2ETest::esUri);
        registry.add("skillforge.elasticsearch-index", () -> "knowledge-repair-browser");
    }
    static String esUri() { return "http://" + ES.getHost() + ":" + ES.getMappedPort(9200); }
    @AfterAll static void cleanup() { try { FRONTEND.close(); } finally { ES.stop(); } }
    @Autowired PlatformModelCatalogService catalog;
    @Autowired SearchIndexer search;
    @Autowired com.skillforge.core.shared.config.SkillForgeProperties properties;

    @Test void publishedSourceProjectionCanBeRepairedFromDocumentPage() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_KNOWLEDGE_PROJECTION_REPAIR", "candidate");
        assertTrue(Set.of("baseline", "candidate").contains(mode));
        login(); String sentinel = "repairneedle" + SnowflakeIds.nextId();
        String text = "Published source contains " + sentinel + " for citation.";
        Path evidence = Path.of("build/runtime-e2e/knowledge-projection-repair-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("KNOWLEDGE_PROJECTION_REPAIR_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "隔离真实 Elasticsearch、Spring、PostgreSQL、浏览器及 Runtime。本地受控 Embedding 和聊天模型；基线 API 修复，候选页面修复。非外部集群验收。", StandardCharsets.UTF_8);
        var embeddingCalls = new AtomicInteger();
        var embedding = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        embedding.createContext("/embeddings", exchange -> { try (exchange) {
            var input = objectMapper.readTree(exchange.getRequestBody()).path("input"); var data = new ArrayList<Map<String,Object>>();
            for (int i=0;i<input.size();i++) { var vector = new ArrayList<Double>(Collections.nCopies(1536, 0.0)); vector.set(0, 1.0); data.add(Map.of("index", i, "embedding", vector)); }
            embeddingCalls.incrementAndGet(); byte[] bytes = objectMapper.writeValueAsBytes(Map.of("data", data, "usage", Map.of("prompt_tokens", input.size(), "total_tokens", input.size())));
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
        }}); embedding.start();
        try (var model = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            model.start(); TenantContext.setTenantId(tenantId);
            try {
                configureTenantModel(model.endpoint());
                var selected = catalog.create(new ModelWrite(null,"Projection embedding","EMBEDDING",true,"custom","custom-embedding","custom-v1","openai-embeddings","bearer-api-key","http://127.0.0.1:"+embedding.getAddress().getPort(),"projection-embedding","fixture-key",null,null,null,null,null,30,1536,null,java.math.BigDecimal.ZERO,null,null,"CNY"),"projection-fixture");
                catalog.publish(selected.id(),selected.revision(),"projection-fixture"); var old=catalog.selections(tenantId); catalog.select(tenantId,new ModelSelections(old.llmModelConfigId(),selected.id(),null));
            } finally { TenantContext.clear(); }
            var create = send("/api/v1/knowledge/bases","POST",Map.of("name","Projection "+sentinel,"indexing_mode","high_quality","retrieval_mode","semantic"),accessToken);
            assertEquals(200,create.statusCode(),create.body()); String kb=successData(create).path("id").asText();
            String boundary="projection-"+UUID.randomUUID(); String multipart="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"projection.txt\"\r\nContent-Type: text/plain\r\n\r\n"+text+"\r\n--"+boundary+"--\r\n";
            assertTrue(properties.isElasticsearchEnabled());
            assertEquals(esUri(), properties.getElasticsearchUri());
            assertEquals("knowledge-repair-browser", properties.getElasticsearchIndex());
            var indexUri = URI.create(esUri()+"/knowledge-repair-browser");
            var existingIndex = httpClient.send(HttpRequest.newBuilder(indexUri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            if (existingIndex.statusCode() == 404) {
                var mappings = new LinkedHashMap<String,Object>();
                mappings.put("content", Map.of("type","text","analyzer","cjk"));
                for (String field : List.of("tenant_id","kb_id","document_id","chunk_id","chunk_index","source_version")) mappings.put(field, Map.of("type","keyword"));
                for (int dimensions : List.of(1536,1024,768)) mappings.put("embedding_"+dimensions, Map.of("type","dense_vector","dims",dimensions,"index",true,"similarity","cosine"));
                RestClient.create().put().uri(indexUri).body(Map.of("mappings",Map.of("properties",mappings))).retrieve().toBodilessEntity();
            } else assertEquals(200, existingIndex.statusCode());
            var actualMapping = RestClient.create().get().uri(esUri()+"/knowledge-repair-browser/_mapping").retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);
            assertEquals("dense_vector",actualMapping.path("knowledge-repair-browser").path("mappings").path("properties").path("embedding_1536").path("type").asText());
            assertEquals(1536,actualMapping.path("knowledge-repair-browser").path("mappings").path("properties").path("embedding_1536").path("dims").asInt());
            Files.writeString(evidence.resolve("es-mapping.json"),objectMapper.writeValueAsString(actualMapping));
            RestClient.create().put().uri(esUri()+"/knowledge-repair-browser/_settings").body(Map.of("index.blocks.write",true)).retrieve().toBodilessEntity();
            try {
                var upload=httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl()+"/api/v1/knowledge/bases/documents/upload?kb_id="+kb+"&request_id="+UUID.randomUUID())).header("Authorization","Bearer "+accessToken).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofString(multipart,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
                Files.writeString(evidence.resolve("upload.json"),upload.body()); assertEquals(200,upload.statusCode(),upload.body()); var uploaded=successData(upload); assertEquals("SUCCEEDED",uploaded.path("status").asText());
                String doc=uploaded.path("document").path("document_id").asText(); var detail=get("/api/v1/knowledge/documents/detail?docId="+doc); assertEquals(200,detail.statusCode()); String source=successData(detail).path("source_version").asText(); String title=successData(detail).path("title").asText();
                assertFalse(source.isBlank()); int embeddingBeforeRepair=embeddingCalls.get(); assertEquals(1,embeddingBeforeRepair);
                assertTrue(search.search(tenantId,kb,sentinel,10,source).isEmpty());
                var before=queryRows(jdbc,tenantId,"SELECT active_revision_id,embedding_model_config_id FROM nw_knowledge_bases WHERE tenant_id=? AND id=?",Long.valueOf(tenantId),Long.valueOf(kb));
                var draft=send("/api/v1/knowledge/bases/revisions","POST",Map.of("kb_id",kb),accessToken); assertEquals(200,draft.statusCode()); String rev=successData(draft).path("revision").asText();
                var ready=send("/api/v1/knowledge/bases/revisions/ready","POST",Map.of("kb_id",kb,"revision",rev,"payload",Map.of("source_versions",List.of(source),"policy",Map.of("model_invocable",true,"user_invocable",false,"allowed_operators",List.of("KEYWORD"),"freshness_policy","PINNED","citation_required",true))),accessToken); assertEquals(200,ready.statusCode(),ready.body());
                var published=send("/api/v1/knowledge/bases/revisions/publish","POST",Map.of("kb_id",kb,"revision",rev),accessToken); assertEquals(200,published.statusCode(),published.body());
                before=queryRows(jdbc,tenantId,"SELECT active_revision_id,embedding_model_config_id FROM nw_knowledge_bases WHERE tenant_id=? AND id=?",Long.valueOf(tenantId),Long.valueOf(kb));
                var chunksBefore=queryRows(jdbc,tenantId,"SELECT document_id,source_version,chunk_index,content,embedding::text AS embedding FROM knowledge_chunk_values WHERE tenant_id=CAST(? AS bigint) AND source_version=? ORDER BY chunk_index",tenantId,source);
                assertFalse(chunksBefore.isEmpty());
                Files.writeString(evidence.resolve("pg-before.json"),objectMapper.writeValueAsString(Map.of("base",before,"chunks",chunksBefore,"embeddingCalls",embeddingBeforeRepair)));
                Files.writeString(evidence.resolve("publication.json"),published.body());
                RestClient.create().put().uri(esUri()+"/knowledge-repair-browser/_settings").body(Map.of("index.blocks.write",false)).retrieve().toBodilessEntity();
                FRONTEND.bindBackend(baseUrl()); Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
                var command=new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),admin.resolve("node_modules/@playwright/test/cli.js").toString(),"test","e2e/knowledge-projection-repair-real.spec.ts","--output",evidence.resolve("playwright-output").toString());
                command.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("browser.log").toFile());
                var env=command.environment(); env.put("E2E_BASE_URL",FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR",browser.toString()); env.put("E2E_TENANT_SLUG",fixtureTenantSlug()); env.put("E2E_PASSWORD",PASSWORD); env.put("E2E_KB_ID",kb); env.put("E2E_DOCUMENT_ID",doc); env.put("E2E_SOURCE_VERSION",source); env.put("E2E_DOCUMENT_TITLE",title); env.put("E2E_KNOWLEDGE_PROJECTION_REPAIR",mode); env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
                Process process=command.start(); try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) { long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3); while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);} owned.capture(); assertFalse(process.isAlive()); assertEquals(0,process.exitValue(),evidence+"\n"+Files.readString(evidence.resolve("browser.log"))); }
                if("baseline".equals(mode)) { var repair=send("/api/v1/knowledge/bases/projections/repair","POST",Map.of("kb_id",kb,"source_version",source),accessToken); Files.writeString(evidence.resolve("api-control-repair.json"),repair.body()); assertEquals(200,repair.statusCode(),repair.body()); }
                RestClient.create().post().uri(esUri()+"/knowledge-repair-browser/_refresh").retrieve().toBodilessEntity();
                var hits=search.search(tenantId,kb,sentinel,10,source); Files.writeString(evidence.resolve("es-hits.json"),objectMapper.writeValueAsString(hits)); assertFalse(hits.isEmpty());
                var vector = new ArrayList<Double>(Collections.nCopies(1536, 0.0)); vector.set(0, 1.0);
                var vectorHits = search.searchKnn(tenantId,kb,objectMapper.writeValueAsString(vector),10,source);
                Files.writeString(evidence.resolve("es-vector-hits.json"),objectMapper.writeValueAsString(vectorHits));
                assertFalse(vectorHits.isEmpty(), "修复后真实 ES 向量检索须命中原来源");
                assertEquals(before,queryRows(jdbc,tenantId,"SELECT active_revision_id,embedding_model_config_id FROM nw_knowledge_bases WHERE tenant_id=? AND id=?",Long.valueOf(tenantId),Long.valueOf(kb))); assertEquals(embeddingBeforeRepair,embeddingCalls.get());
                var chunksAfter=queryRows(jdbc,tenantId,"SELECT document_id,source_version,chunk_index,content,embedding::text AS embedding FROM knowledge_chunk_values WHERE tenant_id=CAST(? AS bigint) AND source_version=? ORDER BY chunk_index",tenantId,source);
                assertEquals(chunksBefore,chunksAfter);
                Files.writeString(evidence.resolve("pg-after.json"),objectMapper.writeValueAsString(Map.of("chunks",chunksAfter,"embeddingCalls",embeddingCalls.get())));
            long version=successData(published).path("capability_version_id").asLong();
            long definition=successData(get("/api/v1/capabilities/versions/detail?versionId="+version)).path("definition_id").asLong();
            var revised=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);assertEquals(200,revised.statusCode());agentId=successData(revised).path("id").asText();
            var mount=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of("capability_type","KNOWLEDGE_SOURCE","capability_definition_id",Long.toString(definition),"capability_version_id",Long.toString(version),"knowledge_config",Map.of("mode","FOLLOW_ACTIVE"))),accessToken);assertEquals(201,mount.statusCode(),mount.body());String mountId=successData(mount).path("mounts").get(0).path("mount_id").asText();
            var script=model.register("projection-repair-"+SnowflakeIds.nextId(),RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);script.requireKnowledge("knowledge:"+mountId,kb,source,sentinel,sentinel);
            assertEquals(200,send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken).statusCode());sessionId=successData(send("/api/v1/chat/sessions","POST",Map.of("agent_id",agentId,"end_user_id",endUserId),accessToken)).path("session_id").asText();
            var capture=postSse(chatPayload(script.prompt()));Files.writeString(evidence.resolve("runtime.sse.txt"),capture.raw());assertTrue(capture.events().contains("execution.completed"),capture.raw());assertTrue(capture.raw().contains(script.finalAnswer()));assertEquals(2,script.snapshot().requestCount());assertFalse(script.snapshot().verifiedKnowledgeItemIds().isEmpty());
            com.fasterxml.jackson.databind.JsonNode finalMessage=null;for(String line:capture.raw().split("\\R")){if(line.startsWith("data:")){var event=objectMapper.readTree(line.substring(5));if(event.path("citations").isArray()&&!event.path("citations").isEmpty())finalMessage=event;}}
            assertNotNull(finalMessage);var citation=finalMessage.path("citations").get(0);assertEquals(source,citation.path("source_version").asText());var cited=get("/api/v1/chat/citations/content?session_id="+sessionId+"&message_id="+URLEncoder.encode(finalMessage.path("message_id").asText(),StandardCharsets.UTF_8)+"&citation_id="+citation.path("citation_id").asText());assertEquals(200,cited.statusCode());assertTrue(successData(cited).path("text").asText().contains(sentinel));Files.writeString(evidence.resolve("citation.json"),cited.body());
            assertEquals(embeddingBeforeRepair,embeddingCalls.get(),"KEYWORD Runtime不请求Embedding");
            var runs=queryRows(jdbc,tenantId,"SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? LIMIT 10",tenantId,sessionId);assertEquals(1,runs.size());assertEquals("COMPLETED",runs.getFirst().get("status"));assertEquals("FINISHED",runs.getFirst().get("phase"));Files.writeString(evidence.resolve("runtime-facts.json"),objectMapper.writeValueAsString(Map.of("runs",runs,"embeddingCalls",embeddingCalls.get(),"modelCalls",script.snapshot().requestCount(),"runtimeScope","KEYWORD operator regression; semantic management threshold is not a Runtime threshold contract")));
            } finally {
                RestClient.create().put().uri(esUri()+"/knowledge-repair-browser/_settings").body(Map.of("index.blocks.write",false)).retrieve().toBodilessEntity();
            }
        } finally { embedding.stop(0); }
    }
}

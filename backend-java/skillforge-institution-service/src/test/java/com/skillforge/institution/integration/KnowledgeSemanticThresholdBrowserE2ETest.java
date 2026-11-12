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

/** 真实PG语义阈值与页面检索；受控HTTP仅提供确定性向量与模型响应。 */
@Import(KnowledgeSemanticThresholdBrowserE2ETest.Catalog.class)
class KnowledgeSemanticThresholdBrowserE2ETest extends ChatExecutionChainE2ETest {
    static final KnowledgeBrowserServer FRONTEND=KnowledgeBrowserServer.startIsolated("semantic-threshold-");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("skillforge.web.allowed-origins",FRONTEND::baseUrl);
        r.add("skillforge.elasticsearch-enabled",()->false);
    }
    @AfterAll static void closeFrontend(){FRONTEND.close();}
    @Autowired PlatformModelCatalogService catalog;
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class Catalog {
        @Bean @Primary ModelProviderCatalog thresholdCatalog(){
            var no=SupportLevel.UNSUPPORTED;
            var capabilities=new CapabilitySupport(SupportLevel.SUPPORTED,no,SupportLevel.SUPPORTED,SupportLevel.SUPPORTED,no);
            var chat=new ProtocolProfile(ModelProtocol.OPENAI_CHAT_COMPLETIONS,"openai-compatible-chat-v1",Set.of(AuthenticationScheme.BEARER_API_KEY),capabilities,VerificationStatus.VERIFIED);
            var embed=new ProtocolProfile(ModelProtocol.OPENAI_EMBEDDINGS,"openai-compatible-vector-v1",Set.of(AuthenticationScheme.BEARER_API_KEY),new CapabilitySupport(no,no,no,no,no),VerificationStatus.VERIFIED);
            return new ModelProviderCatalog(List.of(()->new ProviderPreset("custom","本地确定性测试",ProviderCategory.CUSTOM_OR_SELF_HOSTED,List.of(
                    new ServiceProfile("custom-openai-compatible","本地模型","custom-v1",Availability.SUPPORTED,null,EndpointMode.CUSTOM,null,List.of(chat),ServiceRegion.CHINA_MAINLAND,Set.of(ModelKind.LLM)),
                    new ServiceProfile("custom-embedding","本地向量","custom-v1",Availability.SUPPORTED,null,EndpointMode.CUSTOM,null,List.of(embed),ServiceRegion.CHINA_MAINLAND,Set.of(ModelKind.EMBEDDING))))));
        }
    }

    @Test void healthyLowSimilarityCannotBypassSemanticThreshold() throws Exception {
        login();String sentinel="thresholdsentinel"+SnowflakeIds.nextId();
        String text="Indexed source contains "+sentinel+" and requires an exact citation.";
        Path evidence=Path.of("build","runtime-e2e","semantic-threshold-"+SnowflakeIds.nextId()).toAbsolutePath();Files.createDirectories(evidence);
        var embeddingCalls=new AtomicInteger();
        var embeddingObservations=new java.util.concurrent.CopyOnWriteArrayList<Map<String,Object>>();
        var embedding=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        embedding.createContext("/embeddings",exchange->{try(exchange){
            var input=objectMapper.readTree(exchange.getRequestBody()).path("input");var data=new ArrayList<Map<String,Object>>();
            for(int i=0;i<input.size();i++){
                boolean query=input.get(i).asText().equals(sentinel);var vector=new ArrayList<Double>(Collections.nCopies(1536,0.0));
                vector.set(0,query?.2:1.0);if(query)vector.set(1,Math.sqrt(.96));data.add(Map.of("index",i,"embedding",vector));
                embeddingObservations.add(Map.of("input",input.get(i).asText(),"kind",query?"query":"document","vector",vector));
            }
            embeddingCalls.incrementAndGet();byte[] bytes=objectMapper.writeValueAsBytes(Map.of("data",data,"usage",Map.of("prompt_tokens",input.size(),"total_tokens",input.size())));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }});embedding.start();
        try(var model=new RuntimeExecutionWorkloadProvider(objectMapper)){
            model.start();TenantContext.setTenantId(tenantId);
            try{
                configureTenantModel(model.endpoint());
                var selected=catalog.create(new ModelWrite(null,"Threshold embedding","EMBEDDING",true,"custom","custom-embedding","custom-v1","openai-embeddings","bearer-api-key","http://127.0.0.1:"+embedding.getAddress().getPort(),"threshold-embedding","fixture-key",null,null,null,null,null,30,1536,null,java.math.BigDecimal.ZERO,null,null,"CNY"),"threshold-fixture");
                catalog.publish(selected.id(),selected.revision(),"threshold-fixture");var old=catalog.selections(tenantId);catalog.select(tenantId,new ModelSelections(old.llmModelConfigId(),selected.id(),null));
            }finally{TenantContext.clear();}
            var create=send("/api/v1/knowledge/bases","POST",Map.of("name","Semantic threshold "+sentinel,"indexing_mode","high_quality","retrieval_mode","semantic"),accessToken);
            assertEquals(200,create.statusCode(),create.body());String kb=successData(create).path("id").asText();
            String boundary="threshold-"+UUID.randomUUID();String multipart="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"threshold.txt\"\r\nContent-Type: text/plain\r\n\r\n"+text+"\r\n--"+boundary+"--\r\n";
            var upload=httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl()+"/api/v1/knowledge/bases/documents/upload?kb_id="+kb+"&request_id="+UUID.randomUUID())).header("Authorization","Bearer "+accessToken).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofString(multipart,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
            Files.writeString(evidence.resolve("upload.json"),upload.body());assertEquals(200,upload.statusCode(),upload.body());var uploaded=successData(upload);assertEquals("SUCCEEDED",uploaded.path("status").asText());
            String doc=uploaded.path("document").path("document_id").asText();var detail=get("/api/v1/knowledge/documents/detail?docId="+doc);assertEquals(200,detail.statusCode());String source=successData(detail).path("source_version").asText();assertFalse(source.isBlank());
            Files.writeString(evidence.resolve("base-created.json"),create.body());Files.writeString(evidence.resolve("document-detail.json"),detail.body());
            assertEquals(1,embeddingCalls.get(),"真实上传应派发一次文档向量请求");
            var vectors=queryRows(jdbc,tenantId,"SELECT source_version,vector_dims(embedding) AS dimensions,1-(embedding <=> CAST(? AS vector)) AS similarity FROM knowledge_chunk_values WHERE tenant_id=CAST(? AS bigint) AND source_version=? LIMIT 10",vectorLiteral(),tenantId,source);
            assertEquals(1,vectors.size());assertEquals(1536,((Number)vectors.getFirst().get("dimensions")).intValue());assertEquals(.2,((Number)vectors.getFirst().get("similarity")).doubleValue(),.00001);Files.writeString(evidence.resolve("pg-vector-facts.json"),objectMapper.writeValueAsString(vectors));
            Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");FRONTEND.bindBackend(baseUrl());
            var command=new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),admin.resolve("node_modules/@playwright/test/cli.js").toString(),"test","e2e/knowledge-semantic-threshold-real.spec.ts","--output",evidence.resolve("playwright-output").toString());command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("browser.log").toFile());
            var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",evidence.toString());env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_USERNAME","runtime-e2e-admin");env.put("E2E_PASSWORD",PASSWORD);env.put("E2E_KB_ID",kb);env.put("E2E_SOURCE_VERSION",source);env.put("E2E_QUERY",sentinel);
            var process=command.start();try(var owned=new KnowledgeBrowserProcesses(process.toHandle())){long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}assertFalse(process.isAlive());assertEquals(0,process.exitValue(),Files.readString(evidence.resolve("browser.log")));}
            assertEquals(5,embeddingCalls.get(),"文档一次与真实管理检索四次；KEYWORD Runtime不应派发向量");
            var draft=send("/api/v1/knowledge/bases/revisions","POST",Map.of("kb_id",kb),accessToken);assertEquals(200,draft.statusCode(),draft.body());var revision=successData(draft).path("revision").asText();
            var ready=send("/api/v1/knowledge/bases/revisions/ready","POST",Map.of("kb_id",kb,"revision",revision,"payload",Map.of("source_versions",List.of(source),"policy",Map.of("model_invocable",true,"user_invocable",false,"allowed_operators",List.of("KEYWORD"),"freshness_policy","PINNED","citation_required",true))),accessToken);assertEquals(200,ready.statusCode(),ready.body());
            var publication=send("/api/v1/knowledge/bases/revisions/publish","POST",Map.of("kb_id",kb,"revision",revision),accessToken);assertEquals(200,publication.statusCode(),publication.body());Files.writeString(evidence.resolve("publication.json"),publication.body());long version=successData(publication).path("capability_version_id").asLong();
            long definition=successData(get("/api/v1/capabilities/versions/detail?versionId="+version)).path("definition_id").asLong();
            var revised=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);assertEquals(200,revised.statusCode());agentId=successData(revised).path("id").asText();
            var mount=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of("capability_type","KNOWLEDGE_SOURCE","capability_definition_id",Long.toString(definition),"capability_version_id",Long.toString(version),"knowledge_config",Map.of("mode","FOLLOW_ACTIVE"))),accessToken);assertEquals(201,mount.statusCode(),mount.body());String mountId=successData(mount).path("mounts").get(0).path("mount_id").asText();
            var script=model.register("threshold-"+SnowflakeIds.nextId(),RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);script.requireKnowledge("knowledge:"+mountId,kb,source,sentinel,sentinel);
            assertEquals(200,send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken).statusCode());sessionId=successData(send("/api/v1/chat/sessions","POST",Map.of("agent_id",agentId,"end_user_id",endUserId),accessToken)).path("session_id").asText();
            var capture=postSse(chatPayload(script.prompt()));Files.writeString(evidence.resolve("runtime.sse.txt"),capture.raw());assertTrue(capture.events().contains("execution.completed"),capture.raw());assertTrue(capture.raw().contains(script.finalAnswer()));assertEquals(2,script.snapshot().requestCount());assertFalse(script.snapshot().verifiedKnowledgeItemIds().isEmpty());
            com.fasterxml.jackson.databind.JsonNode finalMessage=null;for(String line:capture.raw().split("\\R")){if(line.startsWith("data:")){var event=objectMapper.readTree(line.substring(5));if(event.path("citations").isArray()&&!event.path("citations").isEmpty())finalMessage=event;}}
            assertNotNull(finalMessage);var citation=finalMessage.path("citations").get(0);assertEquals(source,citation.path("source_version").asText());var cited=get("/api/v1/chat/citations/content?session_id="+sessionId+"&message_id="+URLEncoder.encode(finalMessage.path("message_id").asText(),StandardCharsets.UTF_8)+"&citation_id="+citation.path("citation_id").asText());assertEquals(200,cited.statusCode());assertTrue(successData(cited).path("text").asText().contains(sentinel));Files.writeString(evidence.resolve("citation.json"),cited.body());
            assertEquals(5,embeddingCalls.get(),"KEYWORD Runtime只读取已发布来源，不请求Embedding");
            var runs=queryRows(jdbc,tenantId,"SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? LIMIT 10",tenantId,sessionId);assertEquals(1,runs.size());assertEquals("COMPLETED",runs.getFirst().get("status"));assertEquals("FINISHED",runs.getFirst().get("phase"));Files.writeString(evidence.resolve("runtime-facts.json"),objectMapper.writeValueAsString(Map.of("runs",runs,"embeddingCalls",embeddingCalls.get(),"modelCalls",script.snapshot().requestCount(),"runtimeScope","KEYWORD operator regression; semantic management threshold is not a Runtime threshold contract")));
        }finally{Files.writeString(evidence.resolve("embedding-http-observations.json"),objectMapper.writeValueAsString(embeddingObservations));embedding.stop(0);}
    }
    private static String vectorLiteral(){var values=new ArrayList<String>(Collections.nCopies(1536,"0"));values.set(0,"0.2");values.set(1,Double.toString(Math.sqrt(.96)));return "["+String.join(",",values)+"]";}
}

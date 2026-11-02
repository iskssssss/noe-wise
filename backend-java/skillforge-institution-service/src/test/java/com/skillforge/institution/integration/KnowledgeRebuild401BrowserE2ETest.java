package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.KnowledgePortal;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 发布响应被替换为401时记录真实重建与 Runtime 行为。 */
@org.springframework.context.annotation.Import(KnowledgeRebuild401BrowserE2ETest.CombinedCatalog.class)
class KnowledgeRebuild401BrowserE2ETest extends ChatExecutionChainE2ETest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class CombinedCatalog {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog combinedCatalog() {
            var chat = new DeterministicModelCatalogConfiguration().deterministicModelCatalog().presets().getFirst();
            var embed = new KnowledgeServiceIntegrationTest.LocalEmbeddingFixtureConfiguration().knowledgeEmbeddingFixtureCatalog().presets().getFirst();
            var services = new java.util.ArrayList<>(chat.services());
            services.addAll(embed.services());
            com.skillforge.runtime.adapter.model.provider.ModelProvider provider = () ->
                    new com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderPreset(chat.providerId(), chat.displayName(), chat.category(), services);
            return new com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog(java.util.List.of(provider, new com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider()));
        }
    }
    private static final KnowledgeBrowserServer FRONTEND=KnowledgeBrowserServer.startIsolated("rebuild-401-browser-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {registry.add("skillforge.web.allowed-origins",FRONTEND::baseUrl);}
    @AfterAll static void closeFrontend(){FRONTEND.close();}

    @org.springframework.beans.factory.annotation.Autowired com.skillforge.core.model.PlatformModelCatalogService models;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.skillforge.core.knowledge.EmbeddingProvider embedding;
    @org.springframework.beans.factory.annotation.Autowired com.skillforge.core.knowledge.KnowledgeRebuildService rebuilds;
    @Test void lostUnauthorizedPublicationResponseUsesSameRuntimeVersion() throws Exception {
        login();String sentinel="singleknowledge"+SnowflakeIds.nextId();
        Path evidence=Path.of("build","runtime-e2e","rebuild-401-browser-"+SnowflakeIds.nextId()).toAbsolutePath();
        Path browser=evidence.resolve("playwright");Files.createDirectories(browser);
        try(var model=new RuntimeExecutionWorkloadProvider(objectMapper)) {
            model.start();TenantContext.setTenantId(tenantId);
            try {configureTenantModel(model.endpoint());} finally {TenantContext.clear();}
            var initialModel=models.create(new com.skillforge.core.model.ModelWrite(null,"原向量模型","EMBEDDING",true,"custom","knowledge-embedding-fixture","knowledge-fixture-v1","openai-embeddings","bearer-api-key","https://embedding.example/v1","initial-embedding","test-secret",null,null,null,null,null,30,768,null,null,null,null,"CNY"),"legacy-rebuild");
            var seed=models.publish(initialModel.id(),initialModel.revision(),"legacy-rebuild");
            org.mockito.Mockito.when(embedding.config(org.mockito.ArgumentMatchers.anyString())).thenReturn(models.readRevision(seed.id(),seed.revision()));
            org.mockito.Mockito.when(embedding.embedBatch(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(seed.id()),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation->{java.util.List<String> input=invocation.getArgument(3);float[] vector=new float[768];vector[0]=1;return input.stream().map(ignored->vector).toList();});
            var original=new LegacyRebuildKnowledgeFixture(objectMapper,httpClient,baseUrl(),accessToken).publish(runId,sentinel);
            var targetDraft=models.create(new com.skillforge.core.model.ModelWrite(null,"重建验收模型","EMBEDDING",true,"custom","knowledge-embedding-fixture","knowledge-fixture-v1","openai-embeddings","bearer-api-key","https://embedding.example/v1","target-embedding","test-secret",null,null,null,null,null,30,768,null,null,null,null,"CNY"),"legacy-rebuild");
            var target=models.publish(targetDraft.id(),targetDraft.revision(),"legacy-rebuild");
            var gate=new java.util.concurrent.CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();
            org.mockito.Mockito.when(embedding.embedBatch(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(target.id()),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation->{
                calls.incrementAndGet();Files.writeString(evidence.resolve("embedding-started"),"started");
                if(!gate.await(120,TimeUnit.SECONDS))throw new IllegalStateException("embedding gate timeout");
                java.util.List<String> input=invocation.getArgument(3);float[] vector=new float[768];vector[0]=1;return input.stream().map(ignored->vector).toList();});
            var revised=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);
            assertEquals(200,revised.statusCode(),revised.body());agentId=successData(revised).path("id").asText();
            var mounted=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of(
                    "capability_type","KNOWLEDGE_SOURCE","capability_definition_id",Long.toString(original.definitionId()),
                    "capability_version_id",Long.toString(original.capabilityVersionId()),"knowledge_config",Map.of("mode","FOLLOW_ACTIVE"),"priority",0)),accessToken);
            assertEquals(201,mounted.statusCode(),mounted.body());
            String mountId=successData(mounted).path("mounts").get(0).path("mount_id").asText();assertFalse(mountId.isBlank());
            var agentPublication=send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken);assertEquals(200,agentPublication.statusCode(),agentPublication.body());
            exerciseSource(model,original.kbId(),original.sourceVersion(),original.definitionId(),original.capabilityVersionId(),mountId,sentinel,evidence,"before");
            Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path playwright=admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name","").toLowerCase().contains("win")?"playwright.cmd":"playwright");
            assertTrue(Files.isRegularFile(playwright));FRONTEND.bindBackend(baseUrl());
            var command=new ProcessBuilder(playwright.toString(),"test","e2e/knowledge-rebuild-401-real.spec.ts");
            command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",browser.toString());
            env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_USERNAME","runtime-e2e-admin");env.put("E2E_PASSWORD",PASSWORD);
            env.put("E2E_KNOWLEDGE_ID",original.kbId());env.put("E2E_MODEL_NAME","重建验收模型");env.put("E2E_GATE_DIR",evidence.toString());
            env.remove("SDK4_LIVE_MODEL_KEY");env.remove("E2E_LIVE_MODEL");
            Process process=command.start();boolean released=false,readySaved=false;
            try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4);
                try {
                    while(process.isAlive()&&System.nanoTime()<deadline){
                        owned.capture();
                        if(!released&&Files.exists(evidence.resolve("release-embedding"))){gate.countDown();released=true;}
                        if(released&&!readySaved){TenantContext.setTenantId(tenantId);try{var state=rebuilds.latest(tenantId,Long.parseLong(original.kbId()));
                            if("READY".equals(state.status())){Files.writeString(evidence.resolve("server-ready.json"),objectMapper.writeValueAsString(Map.of("status",state.status(),"jobId",state.jobId().toString())));readySaved=true;}
                        }finally{TenantContext.clear();}}
                        process.waitFor(200,TimeUnit.MILLISECONDS);
                    }
                } finally {gate.countDown();}
                owned.capture();assertFalse(process.isAlive(),"浏览器验收超时："+evidence);
                if(process.exitValue()!=0){Files.writeString(evidence.resolve("failure-state.json"),objectMapper.writeValueAsString(Map.of(
                        "jobs",queryRows(jdbc,tenantId,"SELECT id,status,idempotency_key FROM nw_knowledge_rebuild_jobs WHERE tenant_id=CAST(? AS bigint) AND kb_id=CAST(? AS bigint)",tenantId,original.kbId()),
                        "runs",queryRows(jdbc,tenantId,"SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?",tenantId,agentId))));}
                assertEquals(0,process.exitValue(),"浏览器验收失败："+evidence+"\n"+Files.readString(evidence.resolve("playwright.log")));
            }
            assertTrue(released);assertTrue(readySaved);assertEquals(1,calls.get());
            var facts=objectMapper.readTree(Files.readString(browser.resolve("rebuild-401.json")));
            String mode=System.getenv().getOrDefault("E2E_REBUILD_401_MODE","baseline");
            assertEquals(mode,facts.path("mode").asText());
            assertEquals(1,facts.path("creates").asInt());assertEquals(200,facts.path("upstreamStatus").asInt());
            assertEquals(1,facts.path("upstreamWrites").asInt());
            assertTrue(facts.path("publishPosts").asInt()>=1);
            if("candidate".equals(mode)){assertEquals(1,facts.path("publishPosts").asInt());assertEquals("SUCCEEDED",facts.path("resolutionReceipt").path("status").asText());}
            long revisionNumber=facts.path("publishedRevision").asLong();assertTrue(revisionNumber>0);
            assertEquals(1,countRuntime("SELECT count(*) FROM nw_knowledge_rebuild_jobs WHERE tenant_id=CAST(? AS bigint) AND kb_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND status='PUBLISHED' AND published_revision=?",tenantId,original.kbId(),facts.path("job").path("job_id").asText(),revisionNumber));
            var revisions=successData(get("/api/v1/knowledge/bases/revisions?kbId="+original.kbId())).path("items");
            com.fasterxml.jackson.databind.JsonNode revision=null;for(var item:revisions)if(item.path("revision").asLong()==revisionNumber)revision=item;
            assertNotNull(revision);assertEquals("published",revision.path("status").asText());
            String source=revision.path("source_versions").get(0).asText();assertNotEquals(original.sourceVersion(),source);
            long revisionId=revision.path("id").asLong(),version=revision.path("capability_version_id").asLong();
            var current=successData(get("/api/v1/knowledge/bases/detail?kbId="+original.kbId()));
            Files.writeString(evidence.resolve("published-facts.json"),objectMapper.writeValueAsString(Map.of("rawIndexingMode",current.path("indexing_mode"),"effectiveIndexingMode",current.path("effective_indexing_mode"),"revisionId",revisionId,"versionId",version,"sourceVersion",source)));
            runId="rebuild401-after-"+SnowflakeIds.nextId();
            exerciseSource(model,original.kbId(),source,original.definitionId(),version,mountId,sentinel,evidence,"after");
        }
    }
    private void exerciseSource(RuntimeExecutionWorkloadProvider model,String kb,String source,long definition,long version,String mountId,String sentinel,Path evidence,String stage) throws Exception {
            var script=model.register("knowledge-"+SnowflakeIds.nextId(),RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
            script.requireKnowledge("knowledge:"+mountId,kb,source,sentinel);
            var session=send("/api/v1/chat/sessions","POST",Map.of("agent_id",agentId,"end_user_id",endUserId),accessToken);
            assertEquals(200,session.statusCode(),session.body());sessionId=successData(session).path("session_id").asText();
            String actualRun="run:"+runId;
            try {
                var capture=postSse(chatPayload(script.prompt()));
                assertEquals(200,capture.statusCode());assertTrue(capture.events().contains("execution.completed"),capture.raw());assertFalse(capture.events().contains("error"),capture.raw());
                assertTrue(capture.raw().contains(script.finalAnswer()),capture.raw());
                assertEquals(2,script.snapshot().requestCount());assertFalse(script.snapshot().verifiedKnowledgeItemIds().isEmpty());
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'",tenantId,actualRun,agentId,sessionId));
                var selected=queryRows(jdbc,tenantId,"SELECT request.schema_id AS request_schema,request.canonical_json AS request_json,result.schema_id AS result_schema,result.canonical_json AS result_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt JOIN agent_runtime_execution_materials request ON request.tenant_id=a.tenant_id AND request.reference=a.request_ref JOIN agent_runtime_execution_materials result ON result.tenant_id=t.tenant_id AND result.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='KNOWLEDGE' AND a.status='SUCCEEDED' AND t.adopted AND t.result_kind='SUCCEEDED'",tenantId,actualRun);
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
                    assertTrue(objectMapper.readTree(item.content().canonicalJson()).path("text").asText().contains(sentinel));
                }
                Files.writeString(evidence.resolve("knowledge-runtime-"+stage+".json"),objectMapper.writeValueAsString(Map.of("agentId",agentId,"runId",actualRun,"kbId",kb,"sourceVersion",source,"capabilityVersionId",Long.toString(version),"mountId",mountId,"knowledgeActions",1,"modelCalls",script.snapshot().requestCount(),"bindingMode","FOLLOW_ACTIVE")));
            } finally {
                var state=Map.of("runs",queryRows(jdbc,tenantId,"SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?",tenantId,actualRun),
                        "actions",queryRows(jdbc,tenantId,"SELECT action_id,kind,status FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal",tenantId,actualRun),
                        "attempts",queryRows(jdbc,tenantId,"SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",tenantId,actualRun));
                Files.writeString(evidence.resolve("knowledge-runtime-state-"+stage+".json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            }
    }
}

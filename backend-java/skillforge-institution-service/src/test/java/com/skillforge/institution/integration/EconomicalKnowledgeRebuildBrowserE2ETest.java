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

/** 经济知识库拒绝向量重建并保留原发布来源的真实浏览器与 Runtime 验收。 */
@org.springframework.context.annotation.Import(EconomicalKnowledgeRebuildBrowserE2ETest.CombinedCatalog.class)
class EconomicalKnowledgeRebuildBrowserE2ETest extends ChatExecutionChainE2ETest {
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
    private static final KnowledgeBrowserServer FRONTEND=KnowledgeBrowserServer.startIsolated("economical-rebuild-browser-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {registry.add("skillforge.web.allowed-origins",FRONTEND::baseUrl);}
    @AfterAll static void closeFrontend(){FRONTEND.close();}

    @org.springframework.beans.factory.annotation.Autowired com.skillforge.core.model.PlatformModelCatalogService models;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.skillforge.core.knowledge.EmbeddingProvider embedding;
    @Test void economicalRebuildRejectedAndOriginalSourceFeedsRuntime() throws Exception {
        login();String sentinel="singleknowledge"+SnowflakeIds.nextId();
        Path evidence=Path.of("build","runtime-e2e","economical-rebuild-browser-"+SnowflakeIds.nextId()).toAbsolutePath();
        Path browser=evidence.resolve("playwright");Files.createDirectories(browser);
        try(var model=new RuntimeExecutionWorkloadProvider(objectMapper)) {
            model.start();TenantContext.setTenantId(tenantId);
            try {configureTenantModel(model.endpoint());} finally {TenantContext.clear();}
            var original=new RuntimeExecutionKnowledgeFixture(objectMapper,httpClient,baseUrl(),accessToken).publish(runId,sentinel);
            var modelDraft=models.create(new com.skillforge.core.model.ModelWrite(null,"重建验收模型","EMBEDDING",true,"custom","knowledge-embedding-fixture","knowledge-fixture-v1","openai-embeddings","bearer-api-key","https://embedding.example/v1","target-embedding","test-secret",null,null,null,null,null,30,768,null,null,null,null,"CNY"),"rebuild-browser");
            var target=models.publish(modelDraft.id(),modelDraft.revision(),"rebuild-browser");
            var revised=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);
            assertEquals(200,revised.statusCode(),revised.body());agentId=successData(revised).path("id").asText();
            Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path playwright=admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name","").toLowerCase().contains("win")?"playwright.cmd":"playwright");
            assertTrue(Files.isRegularFile(playwright));FRONTEND.bindBackend(baseUrl());
            var command=new ProcessBuilder(playwright.toString(),"test","e2e/economical-rebuild-real.spec.ts");
            command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",browser.toString());
            env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_USERNAME","runtime-e2e-admin");env.put("E2E_PASSWORD",PASSWORD);
            env.put("E2E_KNOWLEDGE_ID",original.kbId());env.put("E2E_MODEL_NAME","重建验收模型");
                        env.remove("SDK4_LIVE_MODEL_KEY");env.remove("E2E_LIVE_MODEL");
            Process process=command.start();
            try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4);
                while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}
                owned.capture();assertFalse(process.isAlive(),"浏览器验收超时："+evidence);
                String output=Files.readString(evidence.resolve("playwright.log"));
                if(process.exitValue()!=0) {
                    var state=Map.of("mounts",queryRows(jdbc,tenantId,"SELECT id AS mount_id,capability_definition_id,capability_version_id FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint)",tenantId,agentId),
                            "runs",queryRows(jdbc,tenantId,"SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?",tenantId,agentId),
                            "jobs",queryRows(jdbc,tenantId,"SELECT id,status,idempotency_key FROM nw_knowledge_rebuild_jobs WHERE tenant_id=CAST(? AS bigint) AND kb_id=CAST(? AS bigint)",tenantId,original.kbId()));
                    Files.writeString(evidence.resolve("knowledge-browser-failure-state.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
                }
                assertEquals(0,process.exitValue(),"浏览器验收失败："+evidence+"\n"+output);
            }
            var facts=objectMapper.readTree(Files.readString(browser.resolve("economical-rebuild.json")));
            assertEquals(0,facts.path("createPosts").asInt());
            var before=successData(get("/api/v1/knowledge/bases/rebuilds/detail?kbId="+original.kbId()));
            assertEquals("IDLE",before.path("status").asText());
            var attempted=send("/api/v1/knowledge/bases/rebuilds/create","POST",Map.of(
                    "kb_id",original.kbId(),"target_model_id",target.id(),"target_model_revision",target.revision(),
                    "expected_base_revision",before.path("base_revision").asLong(),"idempotency_key",java.util.UUID.randomUUID().toString()),accessToken);
            assertEquals(422,attempted.statusCode(),attempted.body());
            assertEquals("KNOWLEDGE_REBUILD_ECONOMICAL_UNSUPPORTED",objectMapper.readTree(attempted.body()).path("code").asText());
            var after=successData(get("/api/v1/knowledge/bases/rebuilds/detail?kbId="+original.kbId()));
            assertEquals("IDLE",after.path("status").asText());
            assertEquals(0,countRuntime("SELECT count(*) FROM nw_knowledge_rebuild_jobs WHERE tenant_id=CAST(? AS bigint) AND kb_id=CAST(? AS bigint)",tenantId,original.kbId()));
            org.mockito.Mockito.verifyNoInteractions(embedding);
            var option=successData(get("/api/v1/knowledge/bases/mount-option?kbId="+original.kbId()));
            assertEquals(original.revisionId(),option.path("active_revision_id").asLong());
            assertEquals(original.capabilityVersionId(),option.path("version_id").asLong());
            var revisions=successData(get("/api/v1/knowledge/bases/revisions?kbId="+original.kbId())).path("items");
            assertEquals(1,revisions.size());
            assertEquals(original.revisionId(),revisions.get(0).path("id").asLong());
            assertEquals(original.sourceVersion(),revisions.get(0).path("source_versions").get(0).asText());
            Files.writeString(evidence.resolve("economical-rebuild-api.json"),objectMapper.writeValueAsString(Map.of(
                    "httpStatus",attempted.statusCode(),"response",objectMapper.readTree(attempted.body()),"jobs",0,"embeddingCalls",0,
                    "activeRevisionId",original.revisionId(),"sourceVersion",original.sourceVersion(),"capabilityVersionId",original.capabilityVersionId())));
            var published=original;
            long definition=published.definitionId(),version=published.capabilityVersionId();
            var mounted=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of(
                    "capability_type","KNOWLEDGE_SOURCE","capability_definition_id",Long.toString(definition),
                    "capability_version_id",Long.toString(version),"knowledge_config",Map.of("mode","FOLLOW_ACTIVE"),"priority",0)),accessToken);
            assertEquals(201,mounted.statusCode(),mounted.body());
            var mounts=successData(mounted).path("mounts");String mountId=mounts.get(0).path("mount_id").asText();assertFalse(mountId.isBlank());
            var script=model.register("knowledge-"+SnowflakeIds.nextId(),RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
            script.requireKnowledge("knowledge:"+mountId,published.kbId(),published.sourceVersion(),sentinel);
            var agentPublication=send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken);assertEquals(200,agentPublication.statusCode(),agentPublication.body());
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
                assertEquals("knowledge:"+mountId,request.target().handle().value());assertEquals(Long.toString(published.definitionId()),request.target().capability().capabilityId());
                assertEquals(Long.toString(published.capabilityVersionId()),request.target().capability().versionId());
                assertEquals(published.kbId(),request.target().descriptorId());
                var retrieved=assertInstanceOf(KnowledgePortal.KnowledgeOutcome.Retrieved.class,codec.decodeKnowledgeOutcome(new TypedPayload(row.get("result_schema").toString(),row.get("result_json").toString())));
                assertFalse(retrieved.bundle().items().isEmpty());
                for(var item:retrieved.bundle().items()) {
                    assertEquals(published.kbId(),item.sourceId());assertEquals(published.sourceVersion(),item.sourceVersion());
                    assertTrue(objectMapper.readTree(item.content().canonicalJson()).path("text").asText().contains(sentinel));
                }
                Files.writeString(evidence.resolve("knowledge-runtime.json"),objectMapper.writeValueAsString(Map.of("agentId",agentId,"runId",actualRun,"kbId",published.kbId(),"sourceVersion",published.sourceVersion(),"capabilityVersionId",Long.toString(published.capabilityVersionId()),"mountId",mountId,"knowledgeActions",1,"modelCalls",script.snapshot().requestCount(),"bindingMode","FOLLOW_ACTIVE")));
            } finally {
                var state=Map.of("runs",queryRows(jdbc,tenantId,"SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?",tenantId,actualRun),
                        "actions",queryRows(jdbc,tenantId,"SELECT action_id,kind,status FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal",tenantId,actualRun),
                        "attempts",queryRows(jdbc,tenantId,"SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",tenantId,actualRun));
                Files.writeString(evidence.resolve("knowledge-runtime-state.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            }
        }
    }
}

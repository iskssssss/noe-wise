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

/** 唯一知识来源的真实页面挂载与同一 Agent 检索验收。 */
class AgentKnowledgeSingleBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND=KnowledgeBrowserServer.startIsolated("agent-knowledge-single-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        if (System.getenv("E2E_KNOWLEDGE_CASE") != null) {
            registry.add("skillforge.elasticsearch-enabled", () -> true);
            registry.add("skillforge.elasticsearch-uri", () -> "http://127.0.0.1:1");
        }
    }
    @AfterAll static void closeFrontend(){FRONTEND.close();}

    @Test void singlePublishedKnowledgeMountFeedsSameAgentRuntime() throws Exception {
        login();
        boolean caseSearch = System.getenv("E2E_KNOWLEDGE_CASE") != null;
        String sentinel = (caseSearch ? "SingleKnowledgeMixedCase" : "singleknowledge") + SnowflakeIds.nextId();
        String query = caseSearch ? sentinel.toLowerCase(java.util.Locale.ROOT) : sentinel;
        Path evidence=Path.of("build","runtime-e2e","agent-knowledge-single-"+SnowflakeIds.nextId()).toAbsolutePath();
        Path browser=evidence.resolve("playwright");Files.createDirectories(browser);
        try(var model=new RuntimeExecutionWorkloadProvider(objectMapper)) {
            model.start();TenantContext.setTenantId(tenantId);
            try {configureTenantModel(model.endpoint());} finally {TenantContext.clear();}
            var published=new RuntimeExecutionKnowledgeFixture(objectMapper,httpClient,baseUrl(),accessToken).publish(runId,sentinel);
            var revised=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);
            assertEquals(200,revised.statusCode(),revised.body());agentId=successData(revised).path("id").asText();
            Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path playwright=admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name","").toLowerCase().contains("win")?"playwright.cmd":"playwright");
            assertTrue(Files.isRegularFile(playwright));FRONTEND.bindBackend(baseUrl());
            var command=new ProcessBuilder(playwright.toString(),"test","e2e/agent-knowledge-single-real.spec.ts");
            command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",browser.toString());
            env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_USERNAME","runtime-e2e-admin");env.put("E2E_PASSWORD",PASSWORD);
            env.put("E2E_AGENT_ID",agentId);env.put("E2E_KNOWLEDGE_ID",published.kbId());env.put("E2E_KNOWLEDGE_NAME","execution-knowledge-"+runId);
            env.put("E2E_KNOWLEDGE_REVISION",Long.toString(published.revisionId()));env.put("E2E_KNOWLEDGE_DEFINITION",Long.toString(published.definitionId()));env.put("E2E_KNOWLEDGE_VERSION",Long.toString(published.capabilityVersionId()));
            env.put("E2E_KNOWLEDGE_QUERY", query); env.put("E2E_KNOWLEDGE_SENTINEL", sentinel); env.put("E2E_KNOWLEDGE_SOURCE_VERSION", published.sourceVersion());
            env.remove("SDK4_LIVE_MODEL_KEY");env.remove("E2E_LIVE_MODEL");
            Process process=command.start();
            try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4);
                while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}
                owned.capture();assertFalse(process.isAlive(),"浏览器验收超时："+evidence);
                String output=Files.readString(evidence.resolve("playwright.log"));
                if(process.exitValue()!=0) {
                    var state=Map.of("mounts",queryRows(jdbc,tenantId,"SELECT id AS mount_id,capability_definition_id,capability_version_id FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint)",tenantId,agentId),
                            "runs",queryRows(jdbc,tenantId,"SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?",tenantId,agentId));
                    Files.writeString(evidence.resolve("knowledge-browser-failure-state.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
                }
                assertEquals(0,process.exitValue(),"浏览器验收失败："+evidence+"\n"+output);
            }
            var facts=objectMapper.readTree(Files.readString(browser.resolve("agent-knowledge-single.json")));
            var mount=facts.path("mount");String mountId=mount.path("mount_id").asText();assertFalse(mountId.isBlank());
            assertEquals(agentId,facts.path("agentId").asText());assertEquals(published.kbId(),facts.path("kbId").asText());
            assertEquals(1,facts.path("mountPosts").asInt());assertEquals(0,facts.path("publishPosts").asInt());assertEquals(0,facts.path("chatPosts").asInt());
            assertEquals("baseline".equals(System.getenv("E2E_AGENT_KNOWLEDGE_MODE"))?2:0,facts.path("manualSelectionClicks").asInt());
            assertEquals(1,countRuntime("SELECT count(*) FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND capability_definition_id=? AND capability_version_id=?",tenantId,agentId,mountId,published.definitionId(),published.capabilityVersionId()));
            var script=model.register("knowledge-"+SnowflakeIds.nextId(),RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
            script.requireKnowledge("knowledge:"+mountId,published.kbId(),published.sourceVersion(),query,sentinel);
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
                assertEquals(query, assertInstanceOf(com.skillforge.runtime.api.KnowledgeOperation.Search.class, request.request().operation()).query());
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

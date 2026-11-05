package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort;
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

/** 真正发布编辑配置，确认后试用同一 Agent 与不可变发布快照。 */
class AgentSavePublishTrialBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND=KnowledgeBrowserServer.startIsolated("agent-save-publish-trial-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {registry.add("skillforge.web.allowed-origins",FRONTEND::baseUrl);}
    @AfterAll static void closeFrontend(){FRONTEND.close();}

    @Test void crossTabDelegationCatalogReachesRuntime() throws Exception {
        login();String marker="DELEGATION_DIRECTORY_"+SnowflakeIds.nextId(), childPrompt="CHILD_"+marker;
        Path evidence=Path.of("build/runtime-e2e/agent-delegation-directory-"+SnowflakeIds.nextId()).toAbsolutePath(), browser=evidence.resolve("playwright");Files.createDirectories(browser);
        try(var model=new DelegationDirectoryModel(childPrompt,marker)) {
            model.server.start();com.skillforge.common.context.TenantContext.setTenantId(tenantId);
            try{configureTenantModel("http://127.0.0.1:"+model.server.getAddress().getPort());}finally{com.skillforge.common.context.TenantContext.clear();}
            assertEquals(200,send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken).statusCode());
            var child=send("/api/v1/agents","POST",Map.of("name","Directory child "+marker,"system_prompt",childPrompt),accessToken);assertEquals(200,child.statusCode(),child.body());String childId=successData(child).path("id").asText();
            model.expectedChild=childId;
            String existingId="";
            if("candidate".equals(System.getenv("E2E_DELEGATION_DIRECTORY"))) {
                var existing=send("/api/v1/agents","POST",Map.of("name","Directory existing "+marker,"system_prompt","Uncalled existing member"),accessToken);assertEquals(200,existing.statusCode(),existing.body());existingId=successData(existing).path("id").asText();
                assertEquals(200,send("/api/v1/agents/publish","POST",Map.of("agent_id",existingId),accessToken).statusCode());
                assertEquals(200,send("/api/v1/agents/update","PATCH",Map.of("agent_id",agentId,"payload",Map.of("delegation_config",Map.of("enabled",true,"target_agent_ids",java.util.List.of(existingId)))),accessToken).statusCode());
            }
            FRONTEND.bindBackend(baseUrl());Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            var command=new ProcessBuilder(admin.resolve("node_modules/.bin/playwright.cmd").toString(),"test","e2e/agent-delegation-directory-real.spec.ts");command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",browser.toString());env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_USERNAME","runtime-e2e-admin");env.put("E2E_PASSWORD",PASSWORD);env.put("E2E_AGENT_ID",agentId);env.put("E2E_CHILD_AGENT",childId);env.put("E2E_DIRECTORY_MARKER",marker);
            if(!existingId.isEmpty())env.put("E2E_EXISTING_TARGET",existingId);
            Process process=command.start();try(var owned=new KnowledgeBrowserProcesses(process.toHandle())){long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4);while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}assertFalse(process.isAlive());assertEquals(0,process.exitValue(),evidence+"\n"+Files.readString(evidence.resolve("playwright.log")));}
            var facts=objectMapper.readTree(Files.readString(browser.resolve("delegation-directory.json")));String run=facts.path("run").path("execution_id").asText();
            assertEquals(2,model.parentCalls.get());assertEquals(1,model.childCalls.get());
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED'",tenantId,run));
            assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='DELEGATION' AND status='SUCCEEDED'",tenantId,run));
            var runs=queryRows(jdbc,tenantId,"SELECT run_id,agent_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND (run_id=? OR agent_id=?)",tenantId,run,childId);
            assertEquals(2,runs.size());assertTrue(runs.stream().allMatch(row->"COMPLETED".equals(row.get("status"))));
            Files.writeString(evidence.resolve("delegation-runtime.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runs",runs,"requests",model.requests,"parentCalls",2,"childCalls",1)));
        }
    }
    private final class DelegationDirectoryModel implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.atomic.AtomicInteger parentCalls=new java.util.concurrent.atomic.AtomicInteger(),childCalls=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<com.fasterxml.jackson.databind.JsonNode> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile String expectedChild;
        DelegationDirectoryModel(String childPrompt,String marker)throws Exception {
            server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",exchange->{
                var request=objectMapper.readTree(exchange.getRequestBody());requests.add(request.deepCopy());boolean child=request.path("messages").toString().contains(childPrompt);int ordinal=(child?childCalls:parentCalls).incrementAndGet();
                Object delta;String finish;
                if(!child&&ordinal==1){String handle="";for(var tool:request.path("tools"))if("delegate".equals(tool.path("function").path("name").asText()))for(var candidate:tool.path("function").path("parameters").path("properties").path("target_handle").path("enum"))if(candidate.asText().equals("member:"+expectedChild))handle=candidate.asText();if(handle.isBlank())throw new java.io.IOException("delegate_directory_missing");
                    delta=Map.of("role","assistant","tool_calls",java.util.List.of(Map.of("index",0,"id","directory-delegate-1","type","function","function",Map.of("name","delegate","arguments",objectMapper.writeValueAsString(Map.of("target_handle",handle,"instruction","完成独立任务并返回标记","background",false))))));finish="tool_calls";
                }else{if(!child&&!request.path("messages").toString().contains("CHILD_ANSWER_"+marker))throw new java.io.IOException("child_result_missing");delta=Map.of("role","assistant","content",(child?"CHILD_ANSWER_":"PARENT_ANSWER_")+marker);finish="stop";}
                var chunk=Map.of("id","directory-model","object","chat.completion.chunk","model",request.path("model").asText(),"choices",java.util.List.of(Map.of("index",0,"delta",delta,"finish_reason",finish)),"usage",Map.of("prompt_tokens",10,"completion_tokens",5,"total_tokens",15));
                byte[] body=("data: "+objectMapper.writeValueAsString(chunk)+"\n\ndata: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}exchange.close();
            });
        }
        public void close(){server.stop(0);}
    }

    @Test void editedAgentPublicationIsUsedByRealTrial() throws Exception {
        login();
        String decorationVersion = null;
        if (System.getenv("E2E_AGENT_DECORATION") != null) {
            var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
            assertEquals(200, revised.statusCode(), revised.body()); agentId = successData(revised).path("id").asText();
            var definitions = successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"));
            String definition = "";
            for (var item : definitions) if ("tool:get_current_time".equals(item.path("capability_key").asText())) {
                definition = item.path("id").asText(); decorationVersion = item.path("default_version_id").asText();
            }
            assertFalse(definition.isBlank()); assertNotNull(decorationVersion);
            String key = "decoration-clock-" + SnowflakeIds.nextId();
            var deployment = send("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key,
                    "tool_version_id", decorationVersion, "provider_type", "PLATFORM", "provider_ref", "get_current_time",
                    "environment", "DEVELOPMENT", "status", "ACTIVE"), accessToken);
            assertEquals(201, deployment.statusCode(), deployment.body());
            var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                    "capability_type", "TOOL", "capability_definition_id", definition, "capability_version_id", decorationVersion,
                    "priority", 0, "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", successData(deployment).path("revision").asLong()))), accessToken);
            assertEquals(201, mounted.statusCode(), mounted.body());
        }
        String marker="TRIAL_CONFIG_"+SnowflakeIds.nextId();
        String roleMarker = "TRIAL_ROLE_" + SnowflakeIds.nextId();
        String descriptionMarker = "TRIAL_UNUSED_DESCRIPTION_" + SnowflakeIds.nextId();
        Path evidence=Path.of("build","runtime-e2e","agent-save-publish-trial-"+SnowflakeIds.nextId()).toAbsolutePath();
        Path browser=evidence.resolve("playwright");Files.createDirectories(browser);
        Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        Path playwright=admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name","").toLowerCase().contains("win")?"playwright.cmd":"playwright");
        assertTrue(Files.isRegularFile(playwright));FRONTEND.bindBackend(baseUrl());
        boolean unauthorized="401".equals(System.getenv("E2E_AGENT_TRIAL_SCENARIO"));
        var command=new ProcessBuilder(playwright.toString(),"test","e2e/agent-save-publish-trial-real.spec.ts",
                "--grep",unauthorized?"发布响应 401 后核对原发布且不重发":"编辑后发布并试用同一智能体的新配置");
        if (decorationVersion != null) command.command().set(command.command().size() - 1, "版本装饰读取失败不阻断原发布恢复");
        command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",browser.toString());
        env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_USERNAME","runtime-e2e-admin");env.put("E2E_PASSWORD",PASSWORD);
        env.put("E2E_AGENT_ID",agentId);env.put("E2E_TRIAL_PROMPT_MARKER",marker);env.put("E2E_EXPECTED_REPLY","fixture-answer-"+runId);
        env.put("E2E_TRIAL_ROLE_MARKER", roleMarker); env.put("E2E_TRIAL_DESCRIPTION_MARKER", descriptionMarker);
        if (decorationVersion != null) env.put("E2E_DECORATION_VERSION", decorationVersion);
        env.remove("SDK4_LIVE_MODEL_KEY");env.remove("E2E_LIVE_MODEL");
        Process process=command.start();
        try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4);
            while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}
            owned.capture();assertFalse(process.isAlive(),"浏览器验收超时："+evidence);
            String output=Files.readString(evidence.resolve("playwright.log"));
            if(process.exitValue()!=0) {
                var state=Map.of("publications",queryRows(jdbc,tenantId,"SELECT id,agent_id,revision,request_id FROM nw_agent_publications WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) ORDER BY revision",tenantId,agentId),
                        "runs",queryRows(jdbc,tenantId,"SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? ORDER BY created_second",tenantId,agentId),
                        "attempts",queryRows(jdbc,tenantId,"SELECT a.run_id,a.action_id,a.kind,t.result_kind,t.adopted FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id WHERE a.tenant_id=? AND a.run_id IN (SELECT run_id FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?)",tenantId,tenantId,agentId));
                Files.writeString(evidence.resolve("trial-failure-state.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            }
            assertEquals(0,process.exitValue(),"浏览器验收失败："+evidence+"\n"+output);
        }
        if(unauthorized) {
            var facts=objectMapper.readTree(Files.readString(browser.resolve("agent-trial-401.json")));
            var receipt=facts.path("receipt");String requestId=facts.path("requestId").asText();
            String publication=receipt.path("publication_id").asText();long revision=receipt.path("revision").asLong();
            assertEquals(agentId,facts.path("agentId").asText());assertEquals(agentId,receipt.path("agent_id").asText());
            assertFalse(requestId.isBlank());assertEquals(requestId,receipt.path("request_id").asText());
            assertFalse(publication.isBlank());assertTrue(revision>0);
            assertEquals(1,facts.path("upstream").asInt());
            assertEquals("baseline".equals(System.getenv("E2E_AGENT_TRIAL_MODE"))?2:1,facts.path("posts").asInt());
            assertEquals("baseline".equals(System.getenv("E2E_AGENT_TRIAL_MODE"))?1:0,facts.path("refreshes").asInt());
            assertEquals(0,facts.path("sessions").asInt());assertEquals(0,facts.path("chats").asInt());
            assertEquals(1,countRuntime("SELECT count(*) FROM nw_agent_publications WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) AND request_id=CAST(? AS uuid) AND id=CAST(? AS bigint) AND revision=? AND snapshot::text LIKE ?",tenantId,agentId,requestId,publication,revision,"%"+marker+"%"));
            assertEquals(0,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?",tenantId,agentId));
            Files.writeString(evidence.resolve("trial-401-state.json"),objectMapper.writeValueAsString(Map.of("agentId",agentId,"publicationId",publication,"revision",revision,"requestId",requestId,"posts",facts.path("posts").asInt(),"refreshes",facts.path("refreshes").asInt(),"upstream",1,"runCount",0)));
            return;
        }
        var facts=objectMapper.readTree(Files.readString(browser.resolve("agent-trial.json")));
        var receipt=facts.path("receipt");String publishedId=receipt.path("publication_id").asText(), run=facts.path("runId").asText();
        long revision=receipt.path("revision").asLong();assertFalse(publishedId.isBlank());assertFalse(run.isBlank());assertTrue(revision>0);
        assertEquals(agentId,facts.path("agentId").asText());assertEquals(1,facts.path("publications").asInt());
        assertEquals(1,facts.path("sessionWrites").asInt());assertEquals(1,facts.path("chatWrites").asInt());
        assertEquals(System.getenv("E2E_AGENT_DECORATION") != null || "baseline".equals(System.getenv("E2E_AGENT_TRIAL_MODE"))?2:1,facts.path("primaryActions").asInt());
        assertEquals(1,countRuntime("SELECT count(*) FROM nw_agent_publications p JOIN nw_agents a ON a.tenant_id=p.tenant_id AND a.id=p.agent_id AND a.published_revision_id=p.id WHERE p.tenant_id=CAST(? AS bigint) AND p.agent_id=CAST(? AS bigint) AND p.id=CAST(? AS bigint) AND p.revision=? AND p.snapshot::text LIKE ?",tenantId,agentId,publishedId,revision,"%"+marker+"%"));
        assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'",tenantId,run,agentId,facts.path("sessionId").asText()));
        assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'",tenantId,run));
        var modelRows=queryRows(jdbc,tenantId,"SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL'",tenantId,run);
        assertEquals(1,modelRows.size());var material=modelRows.getFirst();
        var properties=applicationContext.getBean(AgentRuntimeProperties.class);
        var codec=new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(),properties.getMaxPayloadNodes(),properties.getMaxPayloadDepth()));
        var actual=ModelRequestTestMaterials.decode(codec,new TypedPayload(material.get("schema_id").toString(),material.get("canonical_json").toString()),jdbc,tenantId);
        assertTrue(actual.request().messages().stream().anyMatch(message->message.role()==ModelPort.ModelRole.SYSTEM && message.textContent().contains(marker)),"真实模型请求必须包含本次发布后的提示词");
        if (System.getenv("E2E_AGENT_DESCRIPTION") != null) {
            String mode = System.getenv("E2E_AGENT_DESCRIPTION");
            assertEquals(mode, facts.path("descriptionMode").asText());
            assertEquals("baseline".equals(mode) ? 1 : 0, facts.path("descriptionFields").asInt());
            assertTrue(actual.request().messages().stream().anyMatch(message -> message.role() == ModelPort.ModelRole.SYSTEM && message.textContent().contains(roleMarker)), "角色描述须进入本次真实模型系统指令");
            assertTrue(actual.request().messages().stream().noneMatch(message -> message.textContent().contains(descriptionMarker)), "未保存的说明不能冒充已生效配置");
            assertEquals(1, countRuntime("SELECT count(*) FROM nw_agent_publications WHERE tenant_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND snapshot::text LIKE ? AND snapshot::text NOT LIKE ?", tenantId, publishedId, "%" + roleMarker + "%", "%" + descriptionMarker + "%"));
            Files.writeString(evidence.resolve("description-runtime.json"), objectMapper.writeValueAsString(Map.of("mode", mode, "agentId", agentId, "publicationId", publishedId, "runId", run, "descriptionFields", facts.path("descriptionFields").asInt(), "roleInModel", true, "unusedDescriptionInModel", false)));
        }
        assertEquals(1,provider.requestCount());
        assertTrue(countRuntime("SELECT count(*) FROM nw_usage_events WHERE tenant_id=CAST(? AS bigint) AND run_id=? AND agent_revision_id=CAST(? AS bigint) AND agent_revision=?",tenantId,run,publishedId,revision)>0,"用量事实须归属同一发布修订");
        Files.writeString(evidence.resolve("trial-runtime.json"),objectMapper.writeValueAsString(Map.of("agentId",agentId,"publicationId",publishedId,"revision",revision,"runId",run,"modelActions",1,"matchingPrompt",true,"matchingUsageRevision",true)));
    }
}

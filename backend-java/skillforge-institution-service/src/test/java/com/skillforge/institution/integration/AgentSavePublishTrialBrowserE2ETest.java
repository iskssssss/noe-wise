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

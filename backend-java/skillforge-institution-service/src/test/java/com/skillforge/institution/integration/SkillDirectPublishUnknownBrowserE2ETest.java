package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 技能候选通过单次摘要读取后挂载并执行。 */
class SkillDirectPublishUnknownBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("skill-direct-publish-unknown-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) { registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl); }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void directPublishLostResponsePreservesObservableFacts() throws Exception {
        String mode=System.getenv().getOrDefault("E2E_SKILL_DIRECT_PUBLISH","baseline");
        assertTrue(List.of("baseline","candidate").contains(mode));
        login();
        String marker = "SKILL_DIRECT_PUBLISH_" + SnowflakeIds.nextId();
        Path evidence = Path.of("build/runtime-e2e/skill-direct-publish-unknown-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser"); Files.createDirectories(browser);
        System.out.println("SKILL_DIRECT_PUBLISH_EVIDENCE=" + evidence);
        String drafts="/api/v1/capabilities/skill-drafts";
        var definitionResponse=send("/api/v1/capabilities/definitions","POST",Map.of("capability_type","SKILL","capability_key","skill:direct-"+SnowflakeIds.nextId(),"name","直接发布验收技能","description","真实发布回执观察"),accessToken);
        assertEquals(201,definitionResponse.statusCode(),definitionResponse.body());String definition=successData(definitionResponse).path("id").asText();
        var draftResponse=send(drafts,"POST",Map.of("definition_id",definition),accessToken);assertEquals(201,draftResponse.statusCode(),draftResponse.body());
        var draft=successData(draftResponse);String draftId=draft.path("id").asText();
        String text="---\nname: direct-publish-skill\ndescription: Isolated publication fixture\n---\n"+marker+"\n";
        String encoded=java.util.Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        var payload=Map.of("draft_id",draftId,"version_key","1.0.0","files",Map.of("SKILL.md",encoded),"model_invocable",true,"user_invocable",false,"activation_mode","ALWAYS","required_capabilities",List.of());
        var save=httpClient.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl()+drafts))
                .header("Authorization","Bearer "+accessToken).header("Content-Type","application/json").header("If-Match",draft.path("revision").asText())
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,save.statusCode(),save.body());var saved=successData(save);
        var definitionBefore=successData(get("/api/v1/capabilities/definitions/detail?definitionId="+definition));
        Files.writeString(evidence.resolve("before.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("definition",definitionBefore,"draft",saved)));
        FRONTEND.bindBackend(baseUrl());Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command=new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),admin.resolve("node_modules/@playwright/test/cli.js").toString(),"test","e2e/skill-direct-publish-unknown-real.spec.ts","--output",evidence.resolve("playwright-output").toString());
        command.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
        var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",browser.toString());
        env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_PASSWORD",PASSWORD);env.put("E2E_SKILL_DEFINITION",definition);
        env.put("E2E_SKILL_DIRECT_PUBLISH",mode);
        env.put("E2E_SKILL_DRAFT",draftId);env.put("E2E_SKILL_REVISION",saved.path("revision").asText());env.put("E2E_SKILL_DEFINITION_REVISION",definitionBefore.path("revision").asText());
        env.remove("SDK4_LIVE_MODEL_KEY");env.remove("E2E_LIVE_MODEL");
        Process process=command.start();try(var owned=new KnowledgeBrowserProcesses(process.toHandle()) ) {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4);
            while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}
            owned.capture();assertFalse(process.isAlive(),"技能发布浏览器超时："+evidence);
            assertEquals(0,process.exitValue(),evidence+"\n"+Files.readString(evidence.resolve("playwright.log")));
        }
        var fact=objectMapper.readTree(Files.readString(browser.resolve("upstream.json")));
        String version=fact.path("response").path("data").path("version_id").asText();assertFalse(version.isBlank());
        if ("candidate".equals(mode)) {
            var continuation=objectMapper.readTree(Files.readString(browser.resolve("continued.json")));
            assertEquals(200,continuation.path("status").asInt());
            assertEquals(fact.path("command"),continuation.path("command"));
            assertEquals(version,continuation.path("data").path("version_id").asText());
            assertEquals(fact.path("response").path("data").path("definition_revision"),continuation.path("data").path("definition_revision"));
        }
        assertEquals(1,countRuntime("SELECT count(*) FROM nw_capability_versions WHERE tenant_id=CAST(? AS bigint) AND definition_id=CAST(? AS bigint) AND status='PUBLISHED'",tenantId,definition));
        var after=get("/api/v1/capabilities/definitions/detail?definitionId="+definition);
        Files.writeString(evidence.resolve("definition-after.json"),after.body());assertEquals(200,after.statusCode());
        assertEquals(version,successData(after).path("default_version_id").asText());
        var active=get(drafts+"?definitionId="+definition);Files.writeString(evidence.resolve("draft-after.json"),active.body());
        var versionResponse=get(drafts+"/version?versionId="+version);Files.writeString(evidence.resolve("version-after.json"),versionResponse.body());assertEquals(200,versionResponse.statusCode());
        assertEquals(1,countRuntime("SELECT count(*) FROM nw_capability_versions WHERE tenant_id=CAST(? AS bigint) AND definition_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND status='PUBLISHED'",tenantId,definition,version));
        var revised=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);assertEquals(200,revised.statusCode(),revised.body());agentId=successData(revised).path("id").asText();
        var mounted=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of("capability_type","SKILL","capability_definition_id",definition,"capability_version_id",version,"skill_config",Map.of("token_budget",8000),"priority",0)),accessToken);
        Files.writeString(evidence.resolve("mount.json"),mounted.body());assertEquals(201,mounted.statusCode(),mounted.body());
        String mount=successData(mounted).path("mounts").get(0).path("mount_id").asText();
        assertFalse(mount.isBlank());
        assertEquals(1, countRuntime("SELECT count(*) FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND capability_definition_id=CAST(? AS bigint) AND capability_version_id=CAST(? AS bigint) AND activation_mode='ALWAYS'", tenantId, agentId, mount, definition, version));
        var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
        Files.writeString(evidence.resolve("publication.json"), publication.body()); assertEquals(200, publication.statusCode(), publication.body());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body()); sessionId = successData(session).path("session_id").asText();
        String run = "run:" + runId;
        try {
            var capture = postSse(chatPayload("请简短说明当前系统能力。"));
            Files.writeString(evidence.resolve("runtime-sse.txt"), capture.raw());
            assertEquals(200, capture.statusCode()); assertTrue(capture.events().contains("execution.completed"), capture.raw());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, run, agentId, sessionId));
            var rows = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' AND a.status='SUCCEEDED' ORDER BY a.action_ordinal", tenantId, run);
            assertEquals(1, rows.size()); var row = rows.getFirst();
            var properties = applicationContext.getBean(AgentRuntimeProperties.class);
            var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
            var request = ModelRequestTestMaterials.decode(codec, new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
            assertTrue(request.request().messages().stream().anyMatch(message -> message.textContent().contains(marker) && message.textContent().contains("Frozen capability: " + definition + " @ " + version) && message.textContent().contains("skill:" + mount)));
            assertEquals(1, provider.requestCount());
            Files.writeString(evidence.resolve("model-messages.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request.request().messages()));
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("run", run, "session", sessionId, "mount", mount, "definition", definition, "version", version,
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, run),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.action_id,a.kind,a.status,t.result_kind,t.adopted FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal", tenantId, run))));
        }
    }
}

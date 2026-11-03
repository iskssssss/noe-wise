package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.ModelSelections;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 隔离浏览器中断原保存响应，并以确认的原修订执行正式部署。 */
@Tag("institution-definition-recovery-browser")
class InstitutionDefinitionRecoveryBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND=KnowledgeBrowserServer.startIsolated("institution-definition-recovery-");
    @DynamicPropertySource static void origins(DynamicPropertyRegistry registry) {registry.add("skillforge.web.allowed-origins",FRONTEND::baseUrl);}
    @AfterAll static void closeFrontend() {FRONTEND.close();}
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired PlatformModelCatalogService models;
    @Autowired com.skillforge.institution.runtime.AgentRuntimeHostProvisioner provisioner;
    @Autowired AgentRuntimeProperties properties;

    @Test void originalSaveReceiptSurvivesLostBrowserResponseAndDrivesDeployedRuntime() throws Exception {
        verifyScenario(System.getenv("E2E_DEFINITION_PREVIEW") == null ? "refresh" : "save-and-preview");
    }

    @Test void originalSaveCanBeCheckedInOpenDrawerWithoutResending() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse("baseline".equals(System.getenv("E2E_DEFINITION_RECOVERY_MODE")));
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("E2E_DEFINITION_PREVIEW") == null);
        verifyScenario("in-page");
    }

    @Test void rejectedOriginalSaveCanBeFencedBeforeLateOriginalRequestArrives() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse("baseline".equals(System.getenv("E2E_DEFINITION_RECOVERY_MODE")));
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("E2E_DEFINITION_PREVIEW") == null);
        verifyScenario("fence");
    }

    @Test void budgetInputAccessibilityTracksRealSaveAndOriginalRevisionRuns() throws Exception {
        String mode=System.getenv("E2E_DEFINITION_BUDGET_A11Y_MODE");
        org.junit.jupiter.api.Assumptions.assumeTrue(mode!=null&&Set.of("baseline","candidate").contains(mode));
        verifyScenario("budget-a11y");
    }

    private void verifyScenario(String scenario) throws Exception {
        login();
        String sourceTenant=tenantId, sourceToken=accessToken;
        String marker="SHARED_DEFINITION_"+SnowflakeIds.nextId();
        String model;
        TenantContext.setTenantId(sourceTenant);
        try {model=PlatformModelFixtures.selectLlm(models,sourceTenant,provider.endpoint(),"runtime-fixture","runtime-fixture-key","custom","custom-openai-compatible","custom-v1");}
        finally {TenantContext.clear();}
        var definition=Map.of("system_prompt","初始隔离共享定义", "role_description","隔离角色", "model_config_id",model,
                "execution_config",Map.of(),"capabilities",List.of(),"resource_slots",List.of(),"delegation_slots",List.of());
        var created=keyed("POST","/api/v1/tenant/agent-definitions",Map.of("name","隔离共享定义恢复", "price_type","free", "price_cents",0,
                "distribution_policy","AUTHORIZED_DEPLOY", "definition",definition),sourceToken,UUID.randomUUID().toString());
        String definitionId=successData(created).path("definition_id").asText();assertFalse(definitionId.isBlank());
        Path evidence=Path.of("build","runtime-e2e","institution-definition-recovery-"+SnowflakeIds.nextId()).toAbsolutePath();
        Files.createDirectories(evidence);Path browser=evidence.resolve("browser");Files.createDirectories(browser);
        Path admin=KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");FRONTEND.bindBackend(baseUrl());
        var selector=switch(scenario){case "save-and-preview"->"保存校验直接读取同修订预览";case "refresh"->"刷新后核对原操作";case "in-page"->"抽屉内核对原保存";case "fence"->"原保存被服务端拒绝后封存原键";case "budget-a11y"->"预算控件的可访问性状态随保存同步";default->throw new IllegalArgumentException("unknown_browser_scenario");};
        var command=new ProcessBuilder("node",admin.resolve("node_modules/@playwright/test/cli.js").toString(),"test","--config=playwright.institution-definition-recovery.config.ts","--grep",selector);
        command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env=command.environment();env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_ARTIFACT_DIR",browser.toString());
        env.put("E2E_TENANT_SLUG",fixtureTenantSlug());env.put("E2E_USERNAME","runtime-e2e-admin");env.put("E2E_PASSWORD",PASSWORD);
        env.put("E2E_DEFINITION_ID",definitionId);env.put("E2E_DEFINITION_PROMPT",marker);
        Process process=command.start();JsonNode original;
        try(var owned=new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
            Path upstream=browser.resolve("upstream.json");
            while(process.isAlive()&&!Files.exists(upstream)&&System.nanoTime()<deadline){owned.capture();process.waitFor(100,TimeUnit.MILLISECONDS);}
            assertTrue(Files.isRegularFile(upstream),"未捕获原上游成功响应："+evidence);
            original=objectMapper.readTree(Files.readString(upstream,StandardCharsets.UTF_8));
            String key=original.path("key").asText();long revision=original.path("receipt").path("draft_revision").asLong();
            assertFalse(key.isBlank());assertEquals(definitionId,original.path("command").path("definition_id").asText());
            var rows=queryRows(jdbc,sourceTenant,"SELECT actor_ref,request_key,definition_id,result_snapshot->>'draft_revision' AS revision FROM nw_agent_definition_operations WHERE operation='update' AND object_scope=? AND request_key=?",definitionId,key);
            if("fence".equals(scenario)) {
                assertEquals(422,original.path("upstreamStatus").asInt());assertTrue(rows.isEmpty());
                assertEquals(1,queryCount(jdbc,sourceTenant,"SELECT count(*) FROM nw_agent_templates WHERE id=CAST(? AS bigint) AND draft_revision=1 AND definition::text NOT LIKE ?",definitionId,"%"+marker+"%"));
            } else {
                assertEquals(2,revision);assertEquals(1,rows.size());assertTrue(rows.getFirst().get("actor_ref").toString().startsWith("tenant:"+sourceTenant+":member:"));
                assertEquals(definitionId,rows.getFirst().get("definition_id").toString());assertEquals(Long.toString(revision),rows.getFirst().get("revision"));
                assertEquals(1,queryCount(jdbc,sourceTenant,"SELECT count(*) FROM nw_agent_templates WHERE id=CAST(? AS bigint) AND draft_revision=? AND definition::text LIKE ?",definitionId,revision,"%"+marker+"%"));
            }
            if("budget-a11y".equals(scenario)) {
                assertEquals(300,original.path("command").path("payload").path("definition").path("execution_config").path("max_wall_time_seconds").asLong());
                Files.writeString(browser.resolve("database-confirmed"),"confirmed",StandardCharsets.UTF_8);
                Path busy=browser.resolve("busy-observed");
                while(process.isAlive()&&!Files.exists(busy)&&System.nanoTime()<deadline){owned.capture();process.waitFor(100,TimeUnit.MILLISECONDS);}
                assertTrue(Files.isRegularFile(busy),"页面未确认保存中控件状态："+evidence);
            }
            Files.writeString(browser.resolve("original-confirmed"),"confirmed",StandardCharsets.UTF_8);
            while(process.isAlive()&&System.nanoTime()<deadline){owned.capture();process.waitFor(200,TimeUnit.MILLISECONDS);}
            owned.capture();assertFalse(process.isAlive(),"浏览器验收超时："+evidence);
            assertEquals(0,process.exitValue(),()->"浏览器验收失败："+evidence+"\n"+readLog(evidence));
        }
        if ("save-and-preview".equals(scenario)) {
            var facts = objectMapper.readTree(Files.readString(browser.resolve("save-preview.json")));
            String mode = System.getenv("E2E_DEFINITION_PREVIEW");
            assertEquals(mode, facts.path("mode").asText());
            assertEquals(1, facts.path("updates").asInt());
            assertEquals("baseline".equals(mode) ? 1 : 0, facts.path("initialDetailReads").asInt());
            assertEquals("baseline".equals(mode) ? 0 : 1, facts.path("initialPreviews").asInt());
            assertEquals("candidate".equals(mode), facts.path("initialPublishEnabled").asBoolean());
            assertEquals(original.path("key").asText(), facts.path("originalKey").asText());
            long revision = original.path("receipt").path("draft_revision").asLong();
            assertEquals(revision, facts.path("revision").asLong());
            assertTrue(facts.path("preview").path("validation").path("valid").asBoolean());
            assertEquals(revision, facts.path("preview").path("impact").path("draft_revision").asLong());
            if ("candidate".equals(mode)) {
                assertEquals(definitionId, facts.path("preview").path("draft").path("definition_id").asText());
                assertEquals(revision, facts.path("preview").path("draft").path("draft_revision").asLong());
                assertEquals(marker, facts.path("preview").path("draft").path("definition").path("system_prompt").asText());
            }
            exerciseOriginalRevision(sourceTenant, sourceToken, definitionId, revision, model, marker, evidence);
            return;
        }
        if("budget-a11y".equals(scenario)) {
            var facts=objectMapper.readTree(Files.readString(browser.resolve("budget-a11y.json")));
            String mode=System.getenv("E2E_DEFINITION_BUDGET_A11Y_MODE");assertEquals(mode,facts.path("mode").asText());
            assertEquals(1,facts.path("writes").asInt());
            assertFalse(facts.path("initial").path("disabled").asBoolean());assertTrue(facts.path("busy").path("disabled").asBoolean());assertFalse(facts.path("after").path("disabled").asBoolean());
            assertEquals("true",facts.path("busy").path("ariaDisabled").asText());
            assertEquals("baseline".equals(mode)?"true":"false",facts.path("initial").path("ariaDisabled").asText());
            assertEquals("baseline".equals(mode)?"true":"false",facts.path("after").path("ariaDisabled").asText());
            exerciseOriginalRevision(sourceTenant,sourceToken,definitionId,original.path("receipt").path("draft_revision").asLong(),model,marker,evidence,300L);
            return;
        }
        if("fence".equals(scenario)) {
            var facts=objectMapper.readTree(Files.readString(browser.resolve("fence.json")));
            String key=original.path("key").asText();assertEquals(key,facts.path("originalKey").asText());
            assertEquals(1,facts.path("updates").asInt());assertEquals(1,facts.path("fences").asInt());
            assertEquals("FENCED",facts.path("resolution").path("state").asText());
            assertEquals(1,queryCount(jdbc,sourceTenant,"SELECT count(*) FROM nw_agent_definition_save_fences WHERE tenant_id=CAST(? AS bigint) AND operation='update' AND object_scope=? AND request_key=?",sourceTenant,definitionId,key));
            // 封存结果已确认，此后模拟原请求迟到，验证同键不能产生写入。
            var lateRequest=HttpRequest.newBuilder(URI.create(baseUrl()+"/api/v1/tenant/agent-definitions/update"))
                    .header("Content-Type","application/json").header("Authorization","Bearer "+sourceToken).header("Idempotency-Key",key)
                    .PUT(HttpRequest.BodyPublishers.ofString(original.path("command").toString())).build();
            var late=httpClient.send(lateRequest,HttpResponse.BodyHandlers.ofString());assertEquals(409,late.statusCode(),late.body());
            assertEquals("AGENT_DEFINITION_SAVE_FENCED",objectMapper.readTree(late.body()).path("code").asText());
            assertEquals(0,queryCount(jdbc,sourceTenant,"SELECT count(*) FROM nw_agent_definition_operations WHERE operation='update' AND object_scope=? AND request_key=?",definitionId,key));
            assertEquals(1,queryCount(jdbc,sourceTenant,"SELECT count(*) FROM nw_agent_templates WHERE id=CAST(? AS bigint) AND draft_revision=1 AND definition::text NOT LIKE ?",definitionId,"%"+marker+"%"));
            Files.writeString(evidence.resolve("late-request.json"),objectMapper.writeValueAsString(Map.of("key",key,"status",late.statusCode(),"code","AGENT_DEFINITION_SAVE_FENCED","revision",1)));
            return;
        }
        var browserFacts=objectMapper.readTree(Files.readString(browser.resolve("recovery.json")));
        boolean baseline="baseline".equals(System.getenv("E2E_DEFINITION_RECOVERY_MODE"));
        assertEquals(baseline?"baseline":"candidate",browserFacts.path("mode").asText());
        assertEquals(scenario,browserFacts.path("scenario").asText());
        assertEquals(1,browserFacts.path("updates").asInt());assertEquals(0,browserFacts.path("previews").asInt());
        assertEquals(baseline?0:1,browserFacts.path("lookups").asInt());
        assertEquals("in-page".equals(scenario)?1:0,browserFacts.path("manualChecks").asInt());
        if("in-page".equals(scenario))assertTrue(browserFacts.path("documentPreserved").asBoolean());
        assertEquals(!baseline,browserFacts.path("persistedBefore").asBoolean());
        assertEquals(original.path("key").asText(),browserFacts.path("originalKey").asText());
        assertEquals(original.path("receipt").path("draft_revision").asLong(),browserFacts.path("originalRevision").asLong());
        if(!baseline) {
            assertEquals(definitionId,browserFacts.path("recoveredReceipt").path("definition_id").asText());
            assertEquals(original.path("receipt").path("draft_revision").asLong(),browserFacts.path("recoveredReceipt").path("draft_revision").asLong());
        }
        assertTrue(browserFacts.path("pendingBefore").asBoolean());assertFalse(browserFacts.path("pendingAfter").asBoolean());
        exerciseOriginalRevision(sourceTenant,sourceToken,definitionId,original.path("receipt").path("draft_revision").asLong(),model,marker,evidence);
    }

    /** 关系是隔离库前置条件；授权、部署和对话均使用正式 HTTP。 */
    private void exerciseOriginalRevision(String sourceTenant,String sourceToken,String definitionId,long revision,String model,String marker,Path evidence) throws Exception {
        exerciseOriginalRevision(sourceTenant,sourceToken,definitionId,revision,model,marker,evidence,null);
    }

    private void exerciseOriginalRevision(String sourceTenant,String sourceToken,String definitionId,long revision,String model,String marker,Path evidence,Long wallTime) throws Exception {
        String childSlug="shared-definition-child-"+SnowflakeIds.nextId();
        String child=createTenant(tenants,plans,childSlug);
        TenantContext.setTenantId(child);
        try {createTestTenantAdministrator(child,"shared-child-admin",PASSWORD);models.select(child,new ModelSelections(model,null,null));provisioner.provision(child);}
        finally {TenantContext.clear();}
        long relation=SnowflakeIds.nextId();
        jdbc.update("UPDATE nw_tenants SET parent_tenant_id=?,relation_revision=2 WHERE id=?",Long.parseLong(sourceTenant),Long.parseLong(child));
        jdbc.update("INSERT INTO nw_institution_relation_revisions(id,child_tenant_id,parent_tenant_id,revision,tree_revision,actor_ref,command_ref) VALUES(?,?,?,2,1,'fixture','fixture')",relation,Long.parseLong(child),Long.parseLong(sourceTenant));
        var published=successData(keyed("POST","/api/v1/tenant/agent-definitions/publish",Map.of("definition_id",definitionId,"expected_revision",revision),sourceToken,UUID.randomUUID().toString()));
        assertEquals(revision,published.path("draft_revision").asLong());
        var grantBody=new LinkedHashMap<String,Object>();grantBody.put("definition_id",definitionId);grantBody.put("target_tenant_id",child);
        grantBody.put("actions",List.of("deploy","execute"));grantBody.put("expires_at",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        grantBody.put("allow_regrant",false);grantBody.put("remaining_depth",0);grantBody.put("parent_grant_id",null);
        var grant=successData(keyed("POST","/api/v1/tenant/agent-grants",grantBody,sourceToken,UUID.randomUUID().toString()));
        var login=send("/api/v1/auth/login","POST",Map.of("tenant_slug",childSlug,"username","shared-child-admin","password",PASSWORD),null);
        String childToken=successData(login).path("access_token").asText();assertFalse(childToken.isBlank());
        var deploymentBody=new LinkedHashMap<String,Object>();deploymentBody.put("definition_id",definitionId);deploymentBody.put("grant_id",grant.path("grant_id").asText());
        deploymentBody.put("integration_id",null);deploymentBody.put("external_agent_ref","definition-recovery-"+SnowflakeIds.nextId());
        deploymentBody.put("resource_bindings",List.of());deploymentBody.put("delegation_bindings",List.of());deploymentBody.put("parameters",Map.of());
        var deployment=successData(keyed("POST","/api/v1/tenant/agent-deployments",deploymentBody,childToken,UUID.randomUUID().toString()));
        String deployedAgent=deployment.path("agent_id").asText();assertFalse(deployedAgent.isBlank());
        String user=successData(send("/api/v1/chat/end-users","POST",Map.of("display_name","隔离共享定义用户"),childToken)).path("id").asText();
        String session=successData(send("/api/v1/chat/sessions","POST",Map.of("agent_id",deployedAgent,"end_user_id",user),childToken)).path("session_id").asText();
        String input="shared-definition-"+SnowflakeIds.nextId();
        var accepted=send("/api/v1/chat","POST",Map.of("session_id",session,"agent_id",deployedAgent,"client_message_id",input,
                "content",List.of(Map.of("type","text","text","请直接回答隔离验收消息")),"context",Map.of("end_user_id",user),"references",List.of()),childToken);
        assertEquals(202,accepted.statusCode(),accepted.body());String run=successData(accepted).path("execution_id").asText();
        String previousTenant=tenantId,previousToken=accessToken;tenantId=child;accessToken=childToken;
        try {
            var events=observeSseUntil(session,null,run,Set.of("execution.completed","error"));
            assertTrue(events.events().contains("execution.completed"),events.raw());assertFalse(events.events().contains("error"),events.raw());
            var rows=queryRows(jdbc,child,"SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL'",child,run);
            assertEquals(1,rows.size());var row=rows.getFirst();
            var codec=new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(),properties.getMaxPayloadNodes(),properties.getMaxPayloadDepth()));
            var request=ModelRequestTestMaterials.decode(codec,new TypedPayload(row.get("schema_id").toString(),row.get("canonical_json").toString()),jdbc,child);
            assertTrue(request.request().messages().stream().anyMatch(message->message.role()==ModelPort.ModelRole.SYSTEM&&message.textContent().contains(marker)));
            assertEquals(1,provider.requestCount());
            var binding=applicationContext.getBean(com.skillforge.institution.runtime.AgentRunBindings.class).find(new com.skillforge.runtime.api.RunRef(new com.skillforge.runtime.api.TenantId(child),run)).orElseThrow();
            assertEquals(Long.parseLong(deployment.path("deployment_id").asText()),binding.scope().authorizations().get(deployedAgent).deploymentId());
            assertEquals(published.path("version").asText(),binding.member().agentDefinitionVersion());
            if(wallTime!=null)assertEquals(wallTime,binding.member().executionConfig().maxWallTimeSeconds());
            Files.writeString(evidence.resolve("runtime.json"),objectMapper.writeValueAsString(Map.of("definitionId",definitionId,"draftRevision",revision,"publishedVersion",published.path("version").asText(),"deploymentId",deployment.path("deployment_id").asText(),"agentId",deployedAgent,"runId",run,"matchedPrompt",true)));
        } finally {tenantId=previousTenant;accessToken=previousToken;}
    }

    private HttpResponse<String> keyed(String method,String path,Map<String,Object> body,String token,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(baseUrl()+path)).header("Content-Type","application/json").header("Authorization","Bearer "+token)
                .header("Idempotency-Key",key).method(method,HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
        var response=httpClient.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());return response;
    }
    private static String readLog(Path evidence) {try{return Files.readString(evidence.resolve("playwright.log"));}catch(Exception ignored){return "<日志不可读>";}}
}

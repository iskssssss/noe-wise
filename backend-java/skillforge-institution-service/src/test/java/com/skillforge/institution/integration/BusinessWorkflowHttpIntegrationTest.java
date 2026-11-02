package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.institution.security.HmacSignatureVerifier;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 独立业务服务、平台宿主与真实机构 Runtime 的私聊及群业务组合；外部模型与业务供应商明确模拟。 */
@Import(DeterministicModelCatalogConfiguration.class)
class BusinessWorkflowHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PLATFORM_USER = "gi08-platform-admin";
    private static final String PLATFORM_PASSWORD = "SyntheticGi08Platform1234";
    private static final String TENANT_USER = "gi08-admin";
    private static final String TENANT_PASSWORD = "SyntheticGi08Tenant1234";
    private static final String PROTOCOL = "2026-07-28";
    private static final Pattern LISTENING = Pattern.compile("Tomcat started on port (\\d+)");
    private static final List<String> TOOL_NAMES = List.of("appointment_query", "appointment_evaluate", "appointment_create",
            "followup_create", "business_receipt", "media_transcribe", "media_synthesize", "video_analyze");
    private static final List<String> MEDIA_TOOLS = List.of("media_transcribe", "media_synthesize", "video_analyze");
    @Autowired private ObjectMapper json;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private PlanMapper plans;
    @Autowired private SkillForgeProperties properties;
    @Autowired private Environment environment;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate businessDatabase;
    @Autowired private com.skillforge.institution.memory.MemoryExtractionWorker memoryWorker;
    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final List<Process> owned = new ArrayList<>();
    private String platformOrigin;
    private String platformToken;
    private String tenantToken;
    private String businessOrigin;
    private String businessToken;
    private Path evidence;
    private ControlledBusinessMcpProxy businessProxy;

    /** 测试 Spring 宿主是唯一执行 Worker；平台子进程只管理发布和授权。 */
    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
    }

    /** 从目录发现到公开 HITL 原等待续答，再核对实际业务回执和重复请求无重复预约。 */
    @Test
    void confirmsPrivateBusinessActionsThroughActualConnectorAndRuntime() throws Exception {
        String marker = "business-http-" + SnowflakeIds.nextId();
        Path root = repositoryRoot();
        evidence = root.resolve("backend-java/skillforge-institution-service/build/reports/business-workflow-http/" + marker);
        Files.createDirectories(evidence);
        var report = new LinkedHashMap<String, Object>();
        report.put("state", "FAILED");
        report.put("business_services_simulated", true);
        report.put("model_provider", "deterministic loopback OpenAI HTTP fixture");
        try (var provider = new BusinessWorkflowModelProvider(json, evidence)) {
            ensureFreePlan(plans);
            provider.start();
            startPlatform(root);
            JsonNode tenant = platform("POST", "/api/v1/admin/tenants", Map.of("tenant_name", marker,
                    "tenant_slug", marker, "plan_code", "free", "admin_username", TENANT_USER,
                    "admin_password", TENANT_PASSWORD), null, 201);
            String tenantId = tenant.path("id").asText();
            tenantToken = institution(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", marker,
                    "username", TENANT_USER, "password", TENANT_PASSWORD), null, null, 200).path("access_token").asText();
            assertThat(tenantToken).isNotBlank();
            String model;
            TenantContext.setTenantId(tenantId);
            try {
                model = PlatformModelFixtures.selectLlm(models, tenantId, provider.endpoint(), marker + "-model",
                        "synthetic-business-model-key", "custom", "custom-openai-compatible", "custom-v1");
            } finally { TenantContext.clear(); }
            startBusiness(root, tenantId);
            businessProxy=new ControlledBusinessMcpProxy(json,businessOrigin);
            var mounts = publishBusinessTools(marker);
            JsonNode association = platform("POST", "/api/v1/platform/institutions/associate", Map.of("tenant_id", tenantId,
                    "namespace", marker, "external_institution_ref", "business-demo"), marker + ":associate", 200);
            JsonNode credential = platform("POST", "/api/v1/platform/institution-credentials/issue", Map.of("tenant_id", tenantId,
                    "integration_id", association.path("integration_id").asText(), "scopes", List.of("sessions.write", "sessions.read",
                            "sessions.events.read", "end_users.write", "business.scopes.write", "artifacts.read", "usage.read",
                            "groups.write","groups.read","groups.members.write","groups.agents.write","groups.messages.write",
                            "groups.triggers.write","groups.triggers.read","groups.runs.read","groups.runs.respond",
                            "groups.controls.write","groups.controls.read","groups.memory.read","groups.memory.write")), marker + ":credential", 200);
            String agent = deployBusinessAgent(marker, tenantId, association.path("integration_id").asText(), model, mounts);
            JsonNode user = machine(credential, "POST", "/api/v1/open-platform/end-users/ensure",
                    Map.of("external_user_ref", "patient-A", "display_name", "模拟患者"), marker + ":user", 200);
            JsonNode store=machine(credential, "POST", "/api/v1/open-platform/business-scopes/ensure", Map.of("scope_type", "store",
                    "external_ref", "store-A", "display_name", "模拟门店"), marker + ":scope", 200);
            String session = session(credential, agent, user.path("end_user_id").asText(), "appointment");
            String time = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withNano(0).toString();
            String requestId = marker + "-appointment";
            Map<String, Object> create = Map.of("request_id", requestId, "patient_ref", "patient-A", "patient_time", time,
                    "appointment_time", time);
            var script = provider.register("appointment", List.of(Map.of("patient_ref", "patient-A"),
                    Map.of("patient_ref", "patient-A", "patient_time", time, "appointment_time", time), create,
                    Map.of("request_id", requestId)), List.of("queried", "evaluated", "created", requestId), "模拟预约已确认并创建");
            Map<String, Object> message = message(session, script.prompt());
            JsonNode accepted = machine(credential, "POST", "/api/v1/open-platform/sessions/messages", message, marker + ":message", 200);
            String run = accepted.path("run_id").asText();
            report.put("tenant_id", tenantId); report.put("session_id", session); report.put("run_id", run);
            JsonNode waiting = waitForInput(credential, session, run);
            assertThat(waiting.path("card").toString()).contains("confirm_business", "confirmed", requestId);
            assertThat(waiting.toString()).doesNotContain("requestState");
            assertThat(script.calls()).as("待确认时不得提前调用后续模型或回执工具").isEqualTo(3);
            JsonNode before = business("business_receipt", Map.of("request_id", requestId), null, null, 200).path("result").path("structuredContent");
            assertThat(before.path("status").asText()).isEqualTo("pending_confirmation");
            assertThat(before.path("business_id").isNull()).isTrue();
            assertThat(before.path("submitted").asBoolean()).isFalse();
            assertThat(machine(credential, "POST", "/api/v1/open-platform/sessions/messages", message, marker + ":message", 200)
                    .path("run_id").asText()).isEqualTo(run);
            Map<String, Object> action = confirmation(session, waiting, true);
            var staleWait = waiting.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) staleWait).put("run_revision",
                    Long.toString(Long.parseLong(waiting.path("run_revision").asText()) + 1));
            machine(credential, "POST", "/api/v1/open-platform/sessions/actions", confirmation(session, staleWait, true), marker + ":wrong-wait", 409);
            assertThat(business("business_receipt", Map.of("request_id", requestId), null, null, 200)
                    .path("result").path("structuredContent").path("submitted").asBoolean()).isFalse();
            JsonNode resumed = machine(credential, "POST", "/api/v1/open-platform/sessions/actions", action, marker + ":confirm", 200);
            assertThat(resumed.path("run_id").asText()).isEqualTo(run);
            awaitCompleted(credential, session, run, "模拟预约已确认并创建", provider);
            JsonNode receipt = business("business_receipt", Map.of("request_id", requestId), null, null, 200).path("result").path("structuredContent");
            assertThat(receipt.path("status").asText()).isEqualTo("created");
            assertThat(receipt.path("business_id").asText()).isNotBlank();
            assertThat(receipt.path("tenant_ref").asText()).isEqualTo(tenantId);
            assertThat(receipt.path("store_ref").asText()).isEqualTo("store-A");
            assertThat(receipt.path("operator_ref").asText()).isEqualTo("operator-A");
            assertThat(receipt.path("submitted").asBoolean()).isTrue();
            assertThat(business("appointment_create", create, null, null, 200).path("result").path("structuredContent")).isEqualTo(receipt);
            assertThat(business("appointment_query", Map.of("patient_ref", "patient-A"), null, null, 200)
                    .path("result").path("structuredContent").path("appointments")).hasSize(1);
            assertThat(machine(credential, "POST", "/api/v1/open-platform/sessions/actions", action, marker + ":confirm", 200)
                    .path("run_id").asText()).isEqualTo(run);
            assertThat(script.calls()).isEqualTo(5);
            assertThat(provider.failures()).isEmpty();
            rejectsForgedBusinessIdentityAndConfirmation(create);
            confirmsFollowup(credential, agent, user.path("end_user_id").asText(), marker, time, provider);
            report.put("appointment_receipt", receipt);
            report.put("appointment_model_calls", script.calls());
            var mediaRuns=new ArrayList<Map<String,Object>>();report.put("media_connector_runs",mediaRuns);
            confirmsMediaConnectors(root,credential,agent,user.path("end_user_id").asText(),marker,provider,mediaRuns);
            var memoryUsage=new GroupMemoryUsageScenario(json,this::machine,this::platform,
                    (method,path,body,key,status)->institution(tenantToken,method,path,body,key,null,status),businessDatabase,memoryWorker)
                    .verify(credential,tenantId,association.path("integration_id").asText(),model,store.path("business_scope_id").asText(),
                            marker+"-memory-usage",provider);
            var headquartersUsage=verifiesUsageAndHeadquarters(credential,tenantId,agent,run,marker,mediaRuns,report,memoryUsage.groupBindingId());
            report.put("group_memory_usage",memoryUsage.withHeadquarters(headquartersUsage));
            report.put("group_business",new GroupBusinessWorkflowScenario(json,this::machine,this::business,businessDatabase,businessProxy)
                    .verify(credential,tenantId,association.path("integration_id").asText(),agent,user.path("end_user_id").asText(),store.path("business_scope_id").asText(),
                            marker+"-group",time,provider));
            report.put("state", "PASS");
        } catch (Exception | AssertionError failure) {
            report.put("failure_type", failure.getClass().getName());
            throw failure;
        } finally {
            TenantContext.clear();
            try { stopOwned(); }
            finally {
                Files.writeString(evidence.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                System.out.println("BUSINESS_WORKFLOW_HTTP_REPORT " + evidence.resolve("report.json").toAbsolutePath());
            }
        }
    }

    /** 用真实业务Run产生的原子事实验证同一事件跨执行机构、平台、总部的读取及当前撤权。 */
    private com.skillforge.core.billing.export.PlatformUsageContracts.Page verifiesUsageAndHeadquarters(JsonNode credential,String tenant,String agent,String run,String marker,
            List<Map<String,Object>> mediaRuns,Map<String,Object> report,String memoryGroup)throws Exception {
        String filter="run_id="+run+"&limit=500";
        JsonNode own=machine(credential,"GET","/api/v1/open-platform/usage/events?"+filter,null,null,200);
        assertThat(own.path("has_more").asBoolean()).isFalse();
        List<JsonNode> toolFacts=new ArrayList<>(),modelFacts=new ArrayList<>(),auxiliaryFacts=new ArrayList<>();
        for(JsonNode item:own.path("items")) {
            assertThat(item.path("tenant_id").asText()).isEqualTo(tenant);
            assertThat(item.path("agent_id").asText()).isEqualTo(agent);
            assertThat(item.path("source_owner_kind").asText()).isEqualTo("platform");
            assertThat(item.path("source_owner_tenant_id").isNull()).isTrue();
            assertThat(item.path("agent_definition_id").asText()).isNotBlank();
            assertThat(item.path("agent_definition_version").asText()).isNotBlank();
            assertThat(item.path("deployment_revision").asLong()).isPositive();
            assertThat(item.path("grant_chain")).hasSize(1);
            assertThat(item.path("grant_chain").get(0).path("target_tenant_id").asText()).isEqualTo(tenant);
            if("tool.call".equals(item.path("event_type").asText())&&!item.path("quantity").isNull())toolFacts.add(item);
            if("llm.chat".equals(item.path("event_type").asText())&&"measured".equals(item.path("kind").asText())) {
                if(item.path("system_agent_run_id").isNull())modelFacts.add(item);else auxiliaryFacts.add(item);
            }
        }
        assertThat(toolFacts).as("查询、评估、首轮确认、原续答和原回执各一次实际MCP调用").hasSize(5);
        assertThat(modelFacts).hasSize(5);
        assertThat(auxiliaryFacts).as("本场景不需要独立辅助模型前置调用").isEmpty();
        assertThat(toolFacts).allSatisfy(item->{
            assertThat(item.path("quantity").asInt()).isEqualTo(1);
            assertThat(item.path("measurement_unit").asText()).isEqualTo("call");
            assertThat(item.path("kind").asText()).isEqualTo("measured");
            assertThat(item.path("amount_cents").isNull()).isTrue();
            assertThat(item.path("attempt_id").asText()).isNotBlank();
        });
        assertThat(toolFacts.stream().map(item->item.path("attempt_id").asText()).distinct().count()).isEqualTo(5);
        for(Map<String,Object> media:mediaRuns) {
            JsonNode usage=machine(credential,"GET","/api/v1/open-platform/usage/events?run_id="+media.get("run_id")+"&limit=100",null,null,200);
            long calls=0;
            for(JsonNode item:usage.path("items"))if("tool.call".equals(item.path("event_type").asText())&&!item.path("quantity").isNull()) {
                assertThat(item.path("quantity").asInt()).isEqualTo(1);calls++;
            }
            assertThat(calls).isEqualTo(1);media.put("usage",usage);
        }
        JsonNode platformEvents=platform("GET","/api/v1/platform/usage/events?tenant_id="+tenant+"&"+filter,null,null,200);
        assertThat(usageIds(platformEvents)).isEqualTo(usageIds(own));
        JsonNode summary=machine(credential,"GET","/api/v1/open-platform/usage/summary?run_id="+run,null,null,200);
        assertThat(summary.path("invocation_count").asLong()).isEqualTo(11);
        assertThat(summary.path("prompt_tokens").asLong()).isEqualTo(66);
        assertThat(summary.path("completion_tokens").asLong()).isEqualTo(42);
        report.put("usage",own);report.put("usage_summary",summary);

        JsonNode headquarters=platform("POST","/api/v1/admin/tenants",Map.of("tenant_name",marker+"总部","tenant_slug",marker+"-hq",
                "plan_code","free","admin_username",TENANT_USER,"admin_password",TENANT_PASSWORD),null,201);
        String hq=headquarters.path("id").asText();
        JsonNode relation=platform("POST","/api/v1/platform/institution-relations/attach",Map.of("child_tenant_id",tenant,
                "parent_tenant_id",hq,"expected_revision",1),marker+":attach-hq",200);
        JsonNode association=platform("POST","/api/v1/platform/institutions/associate",Map.of("tenant_id",hq,"namespace",marker,
                "external_institution_ref","headquarters"),marker+":associate-hq",200);
        JsonNode hqCredential=platform("POST","/api/v1/platform/institution-credentials/issue",Map.of("tenant_id",hq,
                "integration_id",association.path("integration_id").asText(),"scopes",List.of("tenant.sub_institutions.usage_summary.read",
                        "tenant.sub_institutions.usage_details.read","tenant.sub_institutions.usage_events.read")),marker+":hq-key",200);
        String hqQuery="tenant_id="+tenant+"&run_id="+run;
        machine(hqCredential,"GET","/api/v1/tenant/usage/summary?"+hqQuery,null,null,403);
        usageGrant(hq,tenant,"summary",relation.path("revision").asLong(),marker);
        assertThat(machine(hqCredential,"GET","/api/v1/tenant/usage/summary?"+hqQuery,null,null,200)).isEqualTo(summary);
        machine(hqCredential,"GET","/api/v1/tenant/usage/details?"+hqQuery,null,null,403);
        machine(hqCredential,"GET","/api/v1/tenant/usage/events?"+hqQuery,null,null,403);
        usageGrant(hq,tenant,"details",relation.path("revision").asLong(),marker);
        JsonNode eventGrant=usageGrant(hq,tenant,"events",relation.path("revision").asLong(),marker);
        JsonNode hqEvents=machine(hqCredential,"GET","/api/v1/tenant/usage/events?"+hqQuery+"&limit=500",null,null,200);
        assertThat(usageIds(hqEvents)).isEqualTo(usageIds(own));
        JsonNode groupEvents=machine(hqCredential,"GET","/api/v1/tenant/usage/events?tenant_id="+tenant+"&group_binding_id="+memoryGroup+"&limit=100",null,null,200);
        JsonNode ownGroupEvents=machine(credential,"GET","/api/v1/open-platform/usage/events?group_binding_id="+memoryGroup+"&limit=100",null,null,200);
        assertThat(usageIds(groupEvents)).as("总部读取原群后台提取用量，不产生另一份账单事实").isEqualTo(usageIds(ownGroupEvents));
        assertThat(machine(hqCredential,"GET","/api/v1/tenant/usage/details?"+hqQuery+"&limit=500",null,null,200).path("items"))
                .as("总部明细使用原事实，游标按读取动作独立绑定").hasSameSizeAs(own.path("items"));
        report.put("headquarters_usage",hqEvents);
        String cursor=machine(hqCredential,"GET","/api/v1/tenant/usage/events?run_id="+run+"&limit=500",null,null,200).path("next_cursor").asText();
        platform("POST","/api/v1/platform/institution-management-grants/revoke",Map.of("grant_id",eventGrant.path("grant_id").asText(),
                "expected_revision",eventGrant.path("revision").asLong()),marker+":revoke-hq-events",200);
        machine(hqCredential,"GET","/api/v1/tenant/usage/events?run_id="+run+"&limit=500&cursor="+java.net.URLEncoder.encode(cursor,StandardCharsets.UTF_8),null,null,409);
        machine(hqCredential,"GET","/api/v1/tenant/usage/events?"+hqQuery,null,null,403);
        assertThat(machine(hqCredential,"GET","/api/v1/tenant/usage/summary?"+hqQuery,null,null,200)).isEqualTo(summary);
        platform("POST","/api/v1/platform/institution-relations/detach",Map.of("child_tenant_id",tenant,
                "expected_revision",relation.path("revision").asLong()),marker+":detach-hq",200);
        machine(hqCredential,"GET","/api/v1/tenant/usage/summary?"+hqQuery,null,null,403);
        assertThat(usageIds(machine(credential,"GET","/api/v1/open-platform/usage/events?"+filter,null,null,200))).isEqualTo(usageIds(own));
        return json.treeToValue(groupEvents,com.skillforge.core.billing.export.PlatformUsageContracts.Page.class);
    }

    /** 平台精确授予一个当前关系目标上的单项用量动作。 */
    private JsonNode usageGrant(String headquarters,String tenant,String operation,long relationRevision,String marker)throws Exception {
        return platform("POST","/api/v1/platform/institution-management-grants",Map.of("actor_tenant_id",headquarters,
                "target_tenant_id",tenant,"action","tenant.sub_institutions.usage_"+operation+".read",
                "expected_relation_revision",relationRevision),marker+":usage-grant-"+operation,200);
    }

    /** 跨视图去重使用稳定event_id，不比较按当前授权重新签发的cursor。 */
    private static Set<String> usageIds(JsonNode page) {
        var ids=new java.util.HashSet<String>();
        for(JsonNode item:page.path("items"))assertThat(ids.add(item.path("event_id").asText())).isTrue();
        return Set.copyOf(ids);
    }

    /** 三个模拟媒体工具各走独立正式Session/Run；结果与服务已落盘原回执对照，不靠新调用补造。 */
    private void confirmsMediaConnectors(Path root,JsonNode credential,String agent,String user,String marker,
            BusinessWorkflowModelProvider provider,List<Map<String,Object>> runs)throws Exception {
        var scripts=new LinkedHashMap<String,BusinessWorkflowModelProvider.Script>();
        var arguments=new LinkedHashMap<String,Map<String,Object>>();
        for(String name:MEDIA_TOOLS) {
            String request=marker+"-"+name;
            Map<String,Object> input=name.equals("media_synthesize")
                    ? Map.of("request_id",request,"text","模拟语音合成：预约已经确认。")
                    : Map.of("request_id",request,"media_ref",name.equals("media_transcribe")?"fixture:audio:stt-source":"fixture:video:analysis-source");
            String type=switch(name){case "media_transcribe"->"transcription";case "media_synthesize"->"media_reference";default->"video_analysis";};
            String finalText=name+"模拟媒体处理完成";
            var script=provider.registerMedia(name,name,input,type,finalText);
            String session=session(credential,agent,user,name);
            String run=machine(credential,"POST","/api/v1/open-platform/sessions/messages",message(session,script.prompt()),marker+":"+name,200).path("run_id").asText();
            var runEvidence=new LinkedHashMap<String,Object>(Map.of("tool",name,"session_id",session,"run_id",run,"business_services_simulated",true));
            runs.add(runEvidence);
            awaitCompleted(credential,session,run,finalText,provider);
            JsonNode history=machine(credential,"GET","/api/v1/open-platform/sessions/history?sessionId="+session,null,null,200);
            var processes=new ArrayList<JsonNode>();
            for(JsonNode item:history.path("processes"))
                if(run.equals(item.path("run_id").asText()) && "tool".equals(item.path("type").asText()))processes.add(item);
            assertThat(processes).as("每个媒体Run必须公开一个实际成功工具过程").hasSize(1);
            assertThat(processes.getFirst().path("status").asText()).isEqualTo("succeeded");
            assertThat(script.calls()).as("一个真实工具加最终回答只需要两次模型请求").isEqualTo(2);
            assertThat(script.toolResults()).hasSize(1);
            arguments.put(name,input);scripts.put(name,script);
            runEvidence.put("model_calls",script.calls());runEvidence.put("process",processes.getFirst());runEvidence.put("final_text",finalText);
        }
        JsonNode receipts=readOriginalMediaReceipts(root,arguments);
        for(Map<String,Object> item:runs) {
            String name=item.get("tool").toString();
            JsonNode receipt=receipts.path("tool:"+name+":"+arguments.get(name).get("request_id"));
            assertThat(receipt.path("request")).isEqualTo(json.valueToTree(arguments.get(name)));
            JsonNode original=receipt.path("result");assertThat(original.path("simulated").asBoolean()).isTrue();
            var rendered=new ArrayList<JsonNode>();
            try(com.fasterxml.jackson.databind.MappingIterator<JsonNode> values=json.readerFor(JsonNode.class)
                    .readValues(scripts.get(name).toolResults().getFirst())) {
                while(values.hasNextValue())rendered.add(values.nextValue());
            }
            assertThat(rendered).as("模型必须消费原媒体工具的实际结构化结果").isNotEmpty().allSatisfy(value->assertThat(value).isEqualTo(original));
            assertThat(business(name,arguments.get(name),null,null,200).path("result").path("structuredContent"))
                    .as("同键HTTP重读仍返回已存在的原模拟回执").isEqualTo(original);
            item.put("original_receipt",original);
        }
    }

    /** 只读本次业务SQLite原记录；不存在即失败，不通过再次调用工具制造验收回执。 */
    private JsonNode readOriginalMediaReceipts(Path root,Map<String,Map<String,Object>> arguments)throws Exception {
        var keys=arguments.entrySet().stream().map(entry->"tool:"+entry.getKey()+":"+entry.getValue().get("request_id")).toList();
        String script="""
                import json,pathlib,sqlite3,sys
                from contextlib import closing
                with closing(sqlite3.connect(pathlib.Path(sys.argv[1]).as_uri()+'?mode=ro',uri=True)) as db:
                    result={key:json.loads(db.execute('SELECT value FROM kv WHERE key=?',(key,)).fetchone()[0]) for key in json.loads(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'))}
                print(json.dumps(result,ensure_ascii=False))
                """;
        Path report=evidence.resolve("media-original-receipts.json");
        Path keysFile=evidence.resolve("media-receipt-keys.json");
        Files.writeString(keysFile,json.writeValueAsString(keys),StandardCharsets.UTF_8);
        var builder=new ProcessBuilder(System.getenv().getOrDefault("SKILLFORGE_SCENARIO_PYTHON","python"),"-X","utf8","-B","-c",script,
                evidence.resolve("business.sqlite3").toString(),keysFile.toString());
        builder.directory(root.toFile()).redirectOutput(report.toFile()).redirectError(evidence.resolve("media-receipt-read.log").toFile());
        Process process=builder.start();owned.add(process);
        assertThat(process.waitFor(15,TimeUnit.SECONDS)).as("原模拟回执读取应有界结束").isTrue();
        assertThat(process.exitValue()).as("原模拟回执必须已经存在，不可补造").isZero();
        return json.readTree(Files.readString(report,StandardCharsets.UTF_8));
    }

    /** 同一正式链路执行独立随访，确认前不提交，确认后只产生原身份回执。 */
    private void confirmsFollowup(JsonNode credential, String agent, String user, String marker, String time,
            BusinessWorkflowModelProvider provider) throws Exception {
        String request = marker + "-followup";
        var arguments = Map.<String, Object>of("request_id", request, "patient_ref", "patient-A", "scheduled_time", time,
                "content", "模拟随访：确认恢复情况");
        var script = provider.register("followup", List.of(arguments, Map.of("request_id", request)),
                List.of("created", request), "模拟随访任务已确认并创建");
        String session = session(credential, agent, user, "followup");
        String run = machine(credential, "POST", "/api/v1/open-platform/sessions/messages", message(session, script.prompt()),
                marker + ":followup", 200).path("run_id").asText();
        JsonNode waiting = waitForInput(credential, session, run);
        assertThat(business("business_receipt", Map.of("request_id", request), null, null, 200)
                .path("result").path("structuredContent").path("submitted").asBoolean()).isFalse();
        machine(credential, "POST", "/api/v1/open-platform/sessions/actions", confirmation(session, waiting, true), marker + ":followup-confirm", 200);
        awaitCompleted(credential, session, run, "模拟随访任务已确认并创建", provider);
        JsonNode receipt = business("business_receipt", Map.of("request_id", request), null, null, 200).path("result").path("structuredContent");
        assertThat(receipt.path("card_type").asText()).isEqualTo("followup");
        assertThat(receipt.path("status").asText()).isEqualTo("created");
        assertThat(business("followup_create", arguments, null, null, 200).path("result").path("structuredContent")).isEqualTo(receipt);
    }

    /** 拒绝模型参数伪造身份、越权患者、同键异参及确认票据篡改，原待确认操作保持无副作用。 */
    private void rejectsForgedBusinessIdentityAndConfirmation(Map<String, Object> original) throws Exception {
        assertThat(business("appointment_query", Map.of("patient_ref", "patient-B"), null, null, 403).path("error").asText())
                .isEqualTo("BUSINESS_PATIENT_FORBIDDEN");
        var forged = new LinkedHashMap<>(original); forged.put("tenant_ref", "forged-tenant");
        assertThat(business("appointment_create", forged, null, null, 422).path("error").asText()).isEqualTo("DECLARED_BUSINESS_ARGUMENTS_REQUIRED");
        var changed = new LinkedHashMap<>(original); changed.put("patient_time", "2099-01-01T00:00:00Z");
        assertThat(business("appointment_create", changed, null, null, 409).path("error").asText()).isEqualTo("BUSINESS_IDEMPOTENCY_CONFLICT");
        var pending = new LinkedHashMap<>(original); pending.put("request_id", original.get("request_id") + "-tamper");
        JsonNode wait = business("appointment_create", pending, null, null, 200).path("result");
        assertThat(wait.path("resultType").asText()).isEqualTo("input_required");
        assertThat(business("appointment_create", pending, "forged-state", confirmationResponse(), 409).path("error").asText())
                .isEqualTo("BUSINESS_CONFIRMATION_STATE_MISMATCH");
        changed = new LinkedHashMap<>(pending); changed.put("appointment_time", "2099-01-01T00:00:00Z");
        assertThat(business("appointment_create", changed, wait.path("requestState").asText(), confirmationResponse(), 409)
                .path("error").asText()).isEqualTo("BUSINESS_IDEMPOTENCY_CONFLICT");
        JsonNode receipt = business("business_receipt", Map.of("request_id", pending.get("request_id")), null, null, 200)
                .path("result").path("structuredContent");
        assertThat(receipt.path("status").asText()).isEqualTo("pending_confirmation");
        assertThat(receipt.path("business_id").isNull()).isTrue();
        assertThat(receipt.path("submitted").asBoolean()).isFalse();
    }

    /** 发布实际发现的机构工具与精确 Deployment，不直接写 capability 或 Agent 表。 */
    private List<Map<String, Object>> publishBusinessTools(String marker) throws Exception {
        JsonNode connector = institution(tenantToken, "POST", "/api/v1/connectors/instances", Map.of("executor_type", "mcp",
                "name", "模拟门店A业务服务", "base_url", (businessProxy==null?businessOrigin:businessProxy.origin()) + "/mcp", "auth_type", "bearer",
                "auth_credential", businessToken, "headers", Map.of("MCP-Protocol-Version", PROTOCOL)), null, null, 200);
        String instance = connector.path("instance_id").asText();
        assertThat(instance).isNotBlank();
        JsonNode catalog = institution(tenantToken, "GET", "/api/v1/connectors/instances/mcp-tools?instanceId=" + instance,
                null, null, null, 200);
        var mounts = new ArrayList<Map<String, Object>>();
        for (String name : TOOL_NAMES) {
            JsonNode tool = null;
            for (JsonNode item : catalog.path("items")) if (name.equals(item.path("name").asText())) tool = item;
            assertThat(tool).as("真实MCP目录必须发现%s", name).isNotNull();
            var descriptor = new LinkedHashMap<String, Object>();
            descriptor.put("input_schema", tool.path("input_schema")); descriptor.put("output_schema", Map.of());
            boolean writes=name.endsWith("_create") || name.equals("media_synthesize");
            descriptor.put("effect", writes ? "NON_IDEMPOTENT_WRITE" : "READ_ONLY");
            descriptor.put("idempotent", !writes); descriptor.put("timeout_ms", 30000);
            descriptor.put("tool_name", name); descriptor.put("model_invocable", true); descriptor.put("user_invocable", false);
            descriptor.put("confirmation_policy", "NEVER"); descriptor.put("parallel_safe", false);
            String key = "business-" + SnowflakeIds.nextId();
            JsonNode definition = institution(tenantToken, "POST", "/api/v1/capabilities/definitions", Map.of("capability_type", "TOOL",
                    "capability_key", key, "name", name, "description", tool.path("description").asText()), null, null, 201);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(descriptor)));
            JsonNode draft = institution(tenantToken, "POST", "/api/v1/capabilities/definitions/versions", Map.of("definition_id", definition.path("id").asText(),
                    "payload", Map.of("version_key", "v1", "name", name, "description", "模拟门店业务", "content_hash", hash, "descriptor", descriptor)), null, null, 201);
            var version = Map.of("definition_id", definition.path("id").asText(), "version_id", draft.path("id").asText());
            JsonNode validation = institution(tenantToken, "POST", "/api/v1/capabilities/definitions/versions/validate", version, null, null, 200);
            assertThat(validation.path("valid").asBoolean()).isTrue();
            institution(tenantToken, "POST", "/api/v1/capabilities/definitions/versions/publish", version, null,
                    validation.path("definition_revision").asText(), 200);
            JsonNode deployment = institution(tenantToken, "POST", "/api/v1/capabilities/deployments", Map.of("deployment_key", key,
                    "tool_version_id", draft.path("id").asText(), "provider_type", "MCP", "provider_ref", instance,
                    "environment", "TEST", "status", "ACTIVE"), null, null, 201);
            mounts.add(Map.of("slot_key", name, "mount", Map.of("capability_type", "TOOL", "capability_definition_id", definition.path("id").asText(),
                    "capability_version_id", draft.path("id").asText(), "tool_config", Map.of("tool_deployment_id", key,
                            "tool_deployment_revision", deployment.path("revision").asLong()), "priority", 0)));
        }
        return mounts;
    }

    /** 平台只发布通用槽位，机构资源与固定Bearer保留在本地Connector，真实代部署检查资源归属。 */
    private String deployBusinessAgent(String marker, String tenant, String integration, String model, List<Map<String, Object>> mounts) throws Exception {
        var slots = TOOL_NAMES.stream().map(name -> Map.of("key", name, "label", name, "description", "机构业务工具", "capability_type", "TOOL")).toList();
        JsonNode definition = platform("POST", "/api/v1/platform/agent-definitions", Map.of("name", marker + "-assistant",
                "price_type", "free", "price_cents", 0, "distribution_policy", "AUTHORIZED_DEPLOY", "definition", Map.of("system_prompt", "调用机构授权的业务工具，按业务确认表单提交。",
                        "role_description", "模拟业务助手", "model_config_id", model, "execution_config", Map.of("max_business_model_calls", 10, "max_tool_calls", 8),
                        "capabilities", List.of(), "resource_slots", slots, "delegation_slots", List.of())), marker + ":definition", 200);
        String id = definition.path("definition_id").asText();
        platform("POST", "/api/v1/platform/agent-definitions/publish", Map.of("definition_id", id,
                "expected_revision", definition.path("draft_revision").asLong()), marker + ":publish", 200);
        JsonNode grant = platform("POST", "/api/v1/platform/agent-grants", Map.of("definition_id", id, "target_tenant_id", tenant,
                "actions", List.of("deploy", "execute"), "allow_regrant", false, "remaining_depth", 0), marker + ":grant", 200);
        JsonNode deployed = institutionPlatform("POST", "/api/v1/platform/agent-deployments", Map.of("tenant_id", tenant, "payload", Map.of(
                "definition_id", id, "grant_id", grant.path("grant_id").asText(), "integration_id", integration, "external_agent_ref", "business-assistant",
                "name", "模拟门店业务助手", "resource_bindings", mounts, "delegation_bindings", List.of(), "parameters", Map.of())), marker + ":deploy", 200);
        assertThat(deployed.path("ready").asBoolean()).as("真实机构资源绑定后应可执行").isTrue();
        return deployed.path("agent_id").asText();
    }

    private String session(JsonNode credential, String agent, String user, String room) throws Exception {
        return machine(credential, "POST", "/api/v1/open-platform/sessions", Map.of("external_user_ref", "patient-A", "external_conversation_ref", room,
                "agent_id", agent, "end_user_id", user, "business_scope_type", "store", "business_scope_ref", "store-A"), null, 200).path("session_id").asText();
    }

    private Map<String, Object> message(String session, String text) {
        return Map.of("session_id", session, "payload", Map.of("content", List.of(Map.of("type", "text", "text", text)), "context", Map.of(), "references", List.of()));
    }

    /** 只读取原Run公开状态，失败终态立即暴露；不重新派发业务消息。 */
    private JsonNode waitForInput(JsonNode credential, String session, String run) {
        var waiting = new AtomicReference<JsonNode>();
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            JsonNode status = machine(credential, "GET", "/api/v1/open-platform/sessions/status?sessionId=" + session + "&runId=" + run, null, null, 200);
            rejectFailedRun(status);
            assertThat(status.path("pending_interactions")).hasSize(1);
            waiting.set(status.path("pending_interactions").get(0));
        });
        return waiting.get();
    }

    private void awaitCompleted(JsonNode credential, String session, String run, String finalText, BusinessWorkflowModelProvider provider) {
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            if (!provider.failures().isEmpty()) throw new IllegalStateException("business_provider_failed:" + provider.failures());
            JsonNode status = machine(credential, "GET", "/api/v1/open-platform/sessions/status?sessionId=" + session + "&runId=" + run, null, null, 200);
            rejectFailedRun(status);
            assertThat(status.path("status").asText()).isEqualTo("completed");
            JsonNode history = machine(credential, "GET", "/api/v1/open-platform/sessions/history?sessionId=" + session, null, null, 200);
            assertThat(history.path("pending_interactions")).isEmpty();
            assertThat(history.path("messages").toString()).contains(finalText);
        });
    }

    private static void rejectFailedRun(JsonNode status) {
        String phase = status.path("status").asText();
        if (Set.of("failed", "cancelled", "uncertain").contains(phase)) throw new IllegalStateException("business_run_terminal:" + phase);
    }

    /** 公共确认只回传原动作等待与版本；私有MCP票据由Runtime保存，不交给调用方。 */
    private Map<String, Object> confirmation(String session, JsonNode wait, boolean confirmed) {
        // canonical_json是线协议正文，不能依赖Map.of的未定义遍历顺序生成。
        String response = "{\"action\":\"accept\",\"content\":{\"confirmed\":" + confirmed + "}}";
        var context = Map.of("execution_id", wait.path("execution_id").asText(), "run_revision", wait.path("run_revision").asText(),
                "tool_input", Map.of("wait", Map.of("action_id", wait.path("tool_wait").path("action_id").asText(), "generation", wait.path("tool_wait").path("generation").asText()),
                        "responses", Map.of("confirm_business", Map.of("method", "elicitation/create", "result", Map.of("schema_id", "application/json",
                                "canonical_json", response)))));
        return Map.of("session_id", session, "payload", Map.of("execution_id", wait.path("execution_id").asText(),
                "action_key", wait.path("tool_wait").path("action_id").asText(), "context", context));
    }

    private Map<String, Object> confirmationResponse() { return Map.of("confirm_business", Map.of("action", "accept", "content", Map.of("confirmed", true))); }

    /** 本地业务HTTP负控使用同一固定Bearer；对象参数不能提供机构、门店或操作者身份。 */
    private JsonNode business(String tool, Map<String, Object> arguments, String state, Map<String, Object> responses, int expected) throws Exception {
        var params = new LinkedHashMap<String, Object>(); params.put("name", tool); params.put("arguments", arguments);
        if (state != null) params.put("requestState", state); if (responses != null) params.put("inputResponses", responses);
        String body = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "gi08-probe", "method", "tools/call", "params", params));
        var response = http.send(HttpRequest.newBuilder(URI.create(businessOrigin + "/mcp")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("MCP-Protocol-Version", PROTOCOL).header("Authorization", "Bearer " + businessToken)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("本地业务 %s HTTP状态", tool).isEqualTo(expected);
        return json.readTree(response.body());
    }

    /** 签名覆盖实际UTF-8正文与完整target，密钥不进入诊断输出。 */
    private JsonNode machine(JsonNode credential, String method, String path, Object value, String key, int expected) throws Exception {
        String body = value == null ? "" : json.writeValueAsString(value);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        var builder = HttpRequest.newBuilder(URI.create(institutionOrigin() + path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("X-Api-Key", credential.path("api_key").asText()).header("X-Timestamp", timestamp).header("X-Signature",
                        HmacSignatureVerifier.sign(credential.path("signing_secret").asText(), HmacSignatureVerifier.canonical(timestamp, method, path, body)));
        if (key != null) builder.header("Idempotency-Key", key);
        return send(builder, method, path, body, expected);
    }

    private JsonNode platform(String method, String path, Object body, String key, int expected) throws Exception {
        return exchange(platformOrigin, "X-Platform-Token", platformToken, method, path, body, key, null, expected);
    }
    private JsonNode institutionPlatform(String method, String path, Object body, String key, int expected) throws Exception {
        return exchange(institutionOrigin(), "X-Platform-Token", platformToken, method, path, body, key, null, expected);
    }
    private JsonNode institution(String token, String method, String path, Object body, String key, String match, int expected) throws Exception {
        return exchange(institutionOrigin(), "Authorization", token, method, path, body, key, match, expected);
    }
    private String institutionOrigin() { return "http://127.0.0.1:" + port; }

    private JsonNode exchange(String origin, String header, String token, String method, String path, Object value, String key, String match, int expected) throws Exception {
        String body = value == null ? "" : json.writeValueAsString(value);
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("Accept", "application/json");
        if (token != null) builder.header(header, "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (match != null) builder.header("If-Match", match);
        return send(builder, method, path, body, expected);
    }

    private JsonNode send(HttpRequest.Builder builder, String method, String path, String body, int expected) throws Exception {
        var response = http.send(builder.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode envelope = json.readTree(response.body());
        assertThat(response.statusCode()).as("%s %s code=%s", method, path, envelope.path("code").asText()).isEqualTo(expected);
        return envelope.path("data");
    }

    /** 独立平台JVM绑定实际测试数据源，端口0启动后读取监听事实。 */
    private void startPlatform(Path root) throws Exception {
        Path jar = root.resolve("backend-java/skillforge-platform-service/build/libs/skillforge-platform-service.jar");
        assertThat(jar).as("先构建当前平台bootJar").exists();
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path log = evidence.resolve("platform.log");
        var builder = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-jar", jar.toString(), "--server.port=0", "--server.address=127.0.0.1",
                "--agent-runtime.workers.auto-startup=false", "--spring.flyway.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.nacos.config.enabled=false");
        builder.directory(root.resolve("backend-java/skillforge-platform-service").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env = builder.environment();
        env.put("SPRING_DATASOURCE_URL", environment.getRequiredProperty("spring.datasource.url"));
        env.put("SPRING_DATASOURCE_USERNAME", environment.getRequiredProperty("spring.datasource.username"));
        env.put("SPRING_DATASOURCE_PASSWORD", environment.getRequiredProperty("spring.datasource.password"));
        env.put("PLATFORM_DATASOURCE_USERNAME", environment.getRequiredProperty("spring.datasource.username"));
        env.put("PLATFORM_DATASOURCE_PASSWORD", environment.getRequiredProperty("spring.datasource.password"));
        env.put("REDIS_HOST", environment.getRequiredProperty("spring.data.redis.host"));
        env.put("REDIS_PORT", environment.getRequiredProperty("spring.data.redis.port"));
        env.put("REDIS_DATABASE", environment.getProperty("spring.data.redis.database", "0"));
        env.put("SECRET_KEY", properties.getSecretKey()); env.put("PLATFORM_SECRET_KEY", TEST_PLATFORM_JWT_SECRET);
        env.put("PLATFORM_ADMIN_USERNAME", PLATFORM_USER); env.put("PLATFORM_ADMIN_PASSWORD", PLATFORM_PASSWORD);
        Process process = builder.start(); owned.add(process);
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(() -> {
            assertThat(process.isAlive()).as("平台进程应存活，见platform.log").isTrue();
            var listening = LISTENING.matcher(new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
            if (!listening.find()) return false;
            platformOrigin = "http://127.0.0.1:" + listening.group(1);
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create(platformOrigin + "/actuator/health")).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                return response.statusCode() == 200;
            } catch (IOException unavailable) { return false; }
        });
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> platformToken = platform("POST", "/api/v1/platform/auth/login",
                Map.of("username", PLATFORM_USER, "password", PLATFORM_PASSWORD), null, 200).path("access_token").asText());
        assertThat(platformToken).isNotBlank();
    }

    /** 配置只保存环境变量引用；一次性本地Bearer不写入配置和报告。 */
    private void startBusiness(Path root, String tenant) throws Exception {
        businessToken = "synthetic-" + UUID.randomUUID();
        Path config = evidence.resolve("business-config.json");
        Files.writeString(config, json.writeValueAsString(Map.of("confirmation_ttl_seconds", 300, "authorizations", List.of(Map.of(
                "token_env", "GI08_BUSINESS_TOKEN", "tenant_ref", tenant, "store_ref", "store-A", "operator_ref", "operator-A", "patient_refs", List.of("patient-A"))))));
        Path log = evidence.resolve("business.log");
        var builder = new ProcessBuilder(System.getenv().getOrDefault("SKILLFORGE_SCENARIO_PYTHON", "python"), "-X", "utf8", "-B",
                root.resolve("tools/integration-scenario/business_service.py").toString(), "--port", "0", "--state", evidence.resolve("business.sqlite3").toString(),
                "--business-config", config.toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("GI08_BUSINESS_TOKEN", businessToken);
        Process process = builder.start(); owned.add(process);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(() -> {
            assertThat(process.isAlive()).as("业务进程应存活，见business.log").isTrue();
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (!line.startsWith("{")) continue;
                JsonNode ready = json.readTree(line);
                if ("business_workflow_ready".equals(ready.path("event").asText())) {
                    businessOrigin = "http://127.0.0.1:" + ready.path("port").asInt();
                    return true;
                }
            }
            return false;
        });
    }

    /** 一个清理失败也继续停止其他owned进程，之后才报告错误。 */
    private void stopOwned() throws Exception {
        Throwable failure = null;
        if(businessProxy!=null)try { businessProxy.close(); }catch(Throwable stopped) { failure=stopped; }
        for (int i = owned.size() - 1; i >= 0; i--) {
            Process process = owned.get(i);
            try {
                if (process.isAlive()) process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue(); }
            } catch (Throwable stopped) { if (failure == null) failure = stopped; else failure.addSuppressed(stopped); }
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("tools/integration-scenario/business_service.py"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("repository_root_missing");
        return path;
    }
}

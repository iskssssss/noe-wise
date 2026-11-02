package com.skillforge.institution.integration;

import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillDraftContent;
import com.skillforge.core.capability.SkillDraftService;
import com.skillforge.core.model.ModelSelections;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真实视觉模型与产品UI的隔离驻留环境；只建资源和观测，不替UI提交、配对或补发continuation。
 * PostgreSQL/Redis归本类Testcontainers，视觉及浏览器服务凭据仅从私有文件读取。
 */
@EnabledIfEnvironmentVariable(named="SKILLFORGE_BROWSER_VISION_LIVE",matches="true")
@Import(BrowserVisionProductHarnessTest.ObservationConfiguration.class)
class BrowserVisionProductHarnessTest extends AbstractIntegrationTest {
    /** 总夹具窗口从类初始化开始，不能在ready或首个UI输入时重置。 */
    private static final Instant FIXTURE_DEADLINE=Instant.now().plus(Duration.ofMinutes(20));
    private static final String ADMIN_USER="vision-browser-admin";
    private static final List<String> TOOLS=List.of("browser_start","browser_call","browser_stop","browser_tasks","read_image","read_context_reference");
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private CapabilityCatalogService catalog;
    @Autowired private SkillDraftService drafts;
    @Autowired private AgentRuntimeHostProvisioner provisioner;
    @Autowired private AgentRuntimeProperties runtime;
    @Autowired private ApplicationContext application;
    @Autowired private BrowserVisionAuditGuard audit;
    @Autowired private ExecutionBudgetPort budgets;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper controls=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 浏览器连接和UI允许源只绑定本批显式服务，不读取共享开发库。 */
    @DynamicPropertySource
    static void configureProductHarness(DynamicPropertyRegistry registry) {
        ServiceConfiguration service=privateService();
        registry.add("BROWSER_SERVICE_URL",service::endpoint);registry.add("BROWSER_SERVICE_TOKEN",service::token);
        registry.add("agent-runtime.workers.auto-startup",()->"true");
        registry.add("skillforge.web.allowed-origins",()->"http://localhost:18720,http://127.0.0.1:18720,http://localhost:18700,http://127.0.0.1:18700");
    }

    @Test
    @Timeout(value=22,unit=TimeUnit.MINUTES)
    void realProductBrowserVisionEnvironmentWaitsForAtMostTwoUiSamples() throws Exception {
        Path evidence=Path.of(required("SKILLFORGE_BROWSER_VISION_EVIDENCE")).toAbsolutePath();Files.createDirectories(evidence);
        List<SampleEvidence> samples=new ArrayList<>();Fixture fixture=null;String result="FAILED";String reason="fixture_not_ready";
        try {
            fixture=createFixture();
            audit.configure(fixture.tenantId(),fixture.agentId(),evidence,FIXTURE_DEADLINE);
            var publicReady=new Ready(endpoint(),fixture.tenantId(),fixture.tenantSlug(),fixture.agentId(),fixture.agentName(),fixture.channelId(),
                    fixture.modelId(),fixture.modelRevision(),fixture.skill(),Instant.now(),FIXTURE_DEADLINE,2,12);
            write(evidence.resolve("ready.private.json"),new PrivateReady(publicReady,ADMIN_USER,fixture.adminPassword(),fixture.adminToken(),fixture.channelToken()));
            write(evidence.resolve("ready.json"),publicReady);
            reason="awaiting_ui";
            Set<String> seen=new HashSet<>();Map<String,Submitted> observed=new LinkedHashMap<>();
            List<ObservedRun> lastRuns=List.of();
            while(Instant.now().isBefore(FIXTURE_DEADLINE)) {
                if(audit.snapshot().stopReason()!=null){reason=audit.snapshot().stopReason();break;}
                var currentRuns=observedRuns(fixture);
                if(!currentRuns.equals(lastRuns)){write(evidence.resolve("observed-runs.json"),currentRuns);lastRuns=currentRuns;}
                for(String file:List.of("sample-submitted.json","sample-submitted-2.json")) {
                    Path path=evidence.resolve(file);if(!Files.isRegularFile(path))continue;
                    Submitted submitted=controls.readValue(Files.readString(path),Submitted.class);
                    if(submitted.sessionId()==null||submitted.sessionId().isBlank()||submitted.clientMessageId()==null||submitted.clientMessageId().isBlank())
                        throw new IllegalArgumentException("ui_sample_identity_missing");
                    String run="run:"+submitted.clientMessageId();if(seen.contains(run))continue;
                    if(seen.size()>=2)throw new IllegalStateException("ui_sample_limit_exceeded");
                    seen.add(run);observed.put(run,submitted);
                    write(evidence.resolve("samples-observed.json"),List.copyOf(observed.values()));
                }
                for(var entry:observed.entrySet()) {
                    if(samples.stream().anyMatch(value->value.runId().equals(entry.getKey())))continue;
                    var row=run(fixture,entry.getKey());if(row==null)continue;
                    assertEquals(entry.getValue().sessionId(),row.get("session_id"));assertEquals(fixture.agentId(),Objects.toString(row.get("agent_id")));
                    String status=Objects.toString(row.get("status"));
                    if(Set.of("FAILED","CANCELLED","TERMINATED").contains(status)) {
                        reason="ui_original_run_"+status.toLowerCase(Locale.ROOT);
                        throw new IllegalStateException(reason);
                    }
                    if("COMPLETED".equals(status)) {
                        // Run先终结，完成事件由独立publication物化；这里只观测原事件，不补发业务。
                        if(count(fixture,"SELECT count(*) FROM agent_runtime_session_events WHERE tenant_id=? AND run_id=? AND event_type='run.completed'",entry.getKey())!=1)continue;
                        var collected=sampleEvidence(fixture,entry.getKey(),entry.getValue());samples.add(collected);
                        write(evidence.resolve("sample-"+samples.size()+".json"),collected);
                    }
                }
                Path done=evidence.resolve("done.json");
                if(Files.isRegularFile(done)) {
                    Done signal=controls.readValue(Files.readString(done),Done.class);
                    if(!"PASS".equals(signal.status())){reason="ui_actor_failed";break;}
                    if(observed.isEmpty()||samples.size()!=observed.size()){reason="ui_done_before_original_runs_completed";break;}
                    result="PASS";reason="original_runs_and_image_usage_verified_ui_pixel_oracle_is_external";break;
                }
                Thread.sleep(250);
            }
            if(Instant.now().compareTo(FIXTURE_DEADLINE)>=0)reason="fixture_20min_deadline";
            assertEquals("PASS",result,"视觉产品组合未通过，原因="+reason);
        } catch (Exception | AssertionError failure) {
            if(!reason.startsWith("ui_original_run_"))reason="fixture_failure_"+failure.getClass().getSimpleName();
            throw failure;
        } finally {
            audit.stop("HARNESS_FINISHED");
            var finalReport=new HarnessResult(result,reason,Instant.now(),List.copyOf(samples),audit.snapshot());
            write(evidence.resolve("result.json"),finalReport);
            if(fixture!=null)write(evidence.resolve("database-evidence.json"),databaseEvidence(fixture));
            if(POSTGRES.isRunning()) {
                var dump=POSTGRES.execInContainer("pg_dump","-U",POSTGRES.getUsername(),"-d",POSTGRES.getDatabaseName(),"--no-owner","--no-privileges");
                Files.writeString(evidence.resolve("database.private.sql"),dump.getStdout());
                assertEquals(0,dump.getExitCode(),"隔离原库留证失败");
            }
        }
    }

    /** 真实建制、模型正式发布、能力发布和公开Agent/渠道API；不建立用户profile或Session。 */
    private Fixture createFixture() throws Exception {
        checkFixtureTime();String slug="vision-browser-"+SnowflakeIds.nextId();String tenant=createTenant(tenants,plans,slug);
        String password="VisionBrowser-"+UUID.randomUUID();String publishedModel;long modelRevision;
        TenantContext.setTenantId(tenant);
        try {
            createTestTenantAdministrator(tenant,ADMIN_USER,password);
            ModelWrite model=json.readValue(Files.readAllBytes(Path.of(required("SKILLFORGE_BROWSER_VISION_MODEL_PRIVATE"))),ModelWrite.class);
            if(!"LLM".equals(model.kind())||!model.enabled()||model.imagePolicy()==null||!model.imagePolicy().enabled()||!model.imagePolicy().toolImages())
                throw new IllegalArgumentException("explicit_vision_tool_image_model_required");
            var created=models.create(model,"vision-browser-harness");var published=models.publish(created.id(),created.revision(),"vision-browser-harness");
            publishedModel=published.id();modelRevision=Objects.requireNonNull(published.publishedRevision());
            models.select(tenant,new ModelSelections(publishedModel,null,null));provisioner.provision(tenant);
            requireRunningRuntimeWorkers(application,runtime);
        }finally{TenantContext.clear();}
        String token=api(null,"POST","/api/v1/auth/login",new Login(slug,ADMIN_USER,password),200).path("access_token").asText();
        String name="真实视觉浏览器产品验收";
        String agent=api(token,"POST","/api/v1/agents",Map.of("agent_key",slug,"name",name,"system_prompt",
                "你是浏览器操作助手。遵循用户真实任务，使用已挂载BrowserSkill及个人浏览器工具。需要图片证据时先截图并消费真实图片；只依据实际工具/图片回答，不猜测图中像素或动作结果。任务结束后调用browser_stop收尾。"),200).path("id").asText();
        for(String tool:TOOLS)mountTool(token,agent,tool);
        SkillIdentity skill=publishAndMountSkill(tenant,token,agent);
        api(token,"POST","/api/v1/agents/publish",Map.of("agent_id",agent),200);
        var channel=api(token,"POST","/api/v1/channels",Map.of("name","真实视觉产品聊天","channel_type","openapi","config",Map.of(),"ui_config",Map.of()),200);
        String channelId=channel.path("id").asText(),channelToken=channel.path("channel_token").asText();assertFalse(channelToken.isBlank());
        api(token,"PUT","/api/v1/channels/agents",Map.of("channel_id",channelId,"payload",List.of(Map.of("agent_id",agent,"priority",0,"is_default",true,"traffic_percentage",100))),200);
        checkFixtureTime();return new Fixture(tenant,slug,password,token,agent,name,channelId,channelToken,publishedModel,modelRevision,skill);
    }

    /** 按当前平台目录的精确已发布版本创建本批Deployment与挂载。 */
    private void mountTool(String token,String agent,String tool)throws Exception {
        var definition=element(api(token,"GET","/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED",null,200),"capability_key","tool:"+tool);
        long id=definition.path("id").asLong();String key=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(entry->entry.name().equals(tool)).findFirst().orElseThrow().publishedVersionKey();
        long version=element(api(token,"GET","/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId="+id,null,200),"version_key",key).path("id").asLong();
        String deployment="vision-"+tool.replace('_','-')+"-"+SnowflakeIds.nextId();
        var created=api(token,"POST","/api/v1/capabilities/deployments",Map.of("deployment_key",deployment,"tool_version_id",version,"provider_type","PLATFORM","provider_ref",tool,"environment","TEST","status","ACTIVE"),201);
        api(token,"POST","/api/v1/agents/capability-mounts",Map.of("agent_id",agent,"payload",Map.of("capability_type","TOOL","capability_definition_id",Long.toString(id),"capability_version_id",Long.toString(version),
                "tool_config",Map.of("tool_deployment_id",deployment,"tool_deployment_revision",created.path("revision").asLong()),"priority",0)),201);
    }

    /** 导入本批精确ZIP为新不可变Skill版本，读回每个文件，准确挂载ON_DEMAND版本。 */
    private SkillIdentity publishAndMountSkill(String tenant,String token,String agent)throws Exception {
        byte[] archive=Files.readAllBytes(Path.of(required("SKILLFORGE_BROWSER_VISION_SKILL_ZIP")));
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
        var files=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new java.io.ByteArrayInputStream(archive))){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(!entry.isDirectory())files.put(entry.getName(),zip.readAllBytes());}
        if(!files.containsKey("SKILL.md"))throw new IllegalArgumentException("browser_skill_root_missing");
        var scope=CapabilityCatalogScope.tenant(Long.parseLong(tenant));long definition,version;String versionKey;
        TenantContext.setTenantId(tenant);
        try {
            var created=catalog.createDefinition(scope,CapabilityType.SKILL,"browser-vision-"+UUID.randomUUID(),"BrowserSkill 真实视觉验收","");definition=created.id();
            var draft=drafts.create(scope,definition,null);var imported=drafts.importZip(scope,draft.id(),draft.revision(),archive);
            imported=drafts.save(scope,imported.id(),imported.revision(),new SkillDraftContent("browser-connection-v3",
                    imported.files(),imported.modelInvocable(),imported.userInvocable(),imported.activationMode(),imported.conflictGroup(),imported.requiredCapabilities()));
            var validation=drafts.validate(scope,imported.id(),imported.revision());assertTrue(validation.valid(),validation.toString());
            version=drafts.publish(scope,imported.id(),imported.revision(),created.revision()).versionId();versionKey=catalog.getVersion(scope,version).versionKey();
            var persisted=drafts.version(scope,version).files();assertEquals(files.keySet(),persisted.keySet());
            for(var file:files.entrySet())assertArrayEquals(file.getValue(),Base64.getDecoder().decode(persisted.get(file.getKey())),file.getKey());
        }finally{TenantContext.clear();}
        var mounted=api(token,"POST","/api/v1/agents/capability-mounts",Map.of("agent_id",agent,"payload",Map.of("capability_type","SKILL","capability_definition_id",Long.toString(definition),"capability_version_id",Long.toString(version),
                "skill_config",Map.of("activation_mode","ON_DEMAND","token_budget",12000),"priority",0)),201);
        var exact=element(mounted.path("mounts"),"capability_definition_id",Long.toString(definition));assertEquals(Long.toString(version),exact.path("capability_version_id").asText());
        assertEquals(mounted,api(token,"GET","/api/v1/agents/capability-mounts?agentId="+agent,null,200));
        return new SkillIdentity(definition,version,versionKey,exact.path("mount_id").asText(),sha,files.size());
    }

    /** 仅消费UI实际提交产生的原Run证据，成功要求Tool图片进入真实成功Provider调用。 */
    private SampleEvidence sampleEvidence(Fixture fixture,String run,Submitted submitted)throws Exception {
        var attempts=audit.snapshot().attempts().stream().filter(value->run.equals(value.runId())).toList();
        assertTrue(!attempts.isEmpty()&&attempts.size()<=12);assertTrue(attempts.stream().allMatch(value->"SUCCEEDED".equals(value.state())));
        var withImages=attempts.stream().filter(value->!value.images().isEmpty()).toList();assertFalse(withImages.isEmpty(),"不能以截图存在冒充模型收到图片");
        assertTrue(withImages.stream().anyMatch(value->value.images().stream().anyMatch(image->"TOOL".equals(image.role()))),"至少一次真实Tool Image输入");
        for(var attempt:withImages) {
            assertNotNull(attempt.usage());assertNotNull(attempt.usage().inputTokens());assertNotNull(attempt.usage().outputTokens());
            for(var image:attempt.images())assertTrue(audit.snapshot().reads().stream().anyMatch(read->read.media().equals(image.media())&&read.actualBytes()==image.media().byteLength()),"图片规范输入必须对应实际受管字节读取");
        }
        var starts=rows(fixture,"SELECT invocation_id,status,public_receipt FROM nw_browser_call_receipts WHERE tenant_id=?::bigint AND run_id=? AND operation='start'",run);
        assertEquals(1,starts.size());assertEquals("active",starts.getFirst().get("status"));
        var connections=rows(fixture,"SELECT request_id,invocation_id,status,device_id,browser_id,wait_snapshot FROM nw_browser_connection_requests WHERE tenant_id=?::bigint AND run_id=?",run);
        assertEquals(submitted.connectionExpected()?1:0,connections.size());
        if(submitted.connectionExpected()) {
            assertEquals(starts.getFirst().get("invocation_id"),connections.getFirst().get("invocation_id"));
            assertNotNull(connections.getFirst().get("wait_snapshot"));
        }
        assertEquals(submitted.connectionExpected()?1:0,count(fixture,"SELECT count(*) FROM nw_runtime_session_tool_input_responses WHERE tenant_id=? AND run_id=?",run));
        var actions=rows(fixture,"SELECT action_id,state,reason_code,execute_call_kind FROM agent_runtime_actions WHERE tenant_id=? AND run_id=?",run);
        var media=rows(fixture,"SELECT id::text,sha256,size,pixel_width,pixel_height,source_media_id::text,media_transform,media_run_id FROM nw_chat_session_attachments WHERE tenant_id=?::bigint AND media_run_id=?",run);
        assertFalse(media.isEmpty());
        var usage=rows(fixture,"SELECT event_id,attempt_id,usage_event_kind,report_revision,provider,model,usage_source,prompt_tokens,completion_tokens FROM nw_usage_events WHERE tenant_id=?::bigint AND run_id=? AND event_type='llm.chat'",run);
        assertFalse(usage.isEmpty());
        var history=api(fixture.adminToken(),"GET","/api/v1/chat/history?session_id="+submitted.sessionId(),null,200);var answers=new ArrayList<String>();
        for(var message:history.path("messages"))if("assistant".equals(message.path("role").asText())&&"final_answer".equals(message.path("phase").asText()))answers.add(message.path("content").asText());
        assertFalse(answers.isEmpty(),"原Run完成还必须有公开最终回答");
        var expected=controls.readValue(Files.readString(Path.of(required("SKILLFORGE_BROWSER_VISION_EXPECTED_PRIVATE"))),ExpectedSamples.class);
        var oracle=expected.samples().stream().filter(value->value.targetSampleKey().equals(submitted.targetSampleKey())).findFirst().orElseThrow();
        assertTrue(answers.stream().anyMatch(answer->answer.contains(oracle.expectedCode())),"模型最终回答必须匹配仅存在于图片像素的随机码");
        var budget=budgets.snapshot(new RunRef(new TenantId(fixture.tenantId()),run));
        return new SampleEvidence(submitted.sampleId(),submitted.sessionId(),run,"COMPLETED",List.copyOf(answers),starts,connections,actions,media,usage,attempts,budget);
    }

    /** 保留数据库权威计量及公开失败码，不输出供应商请求、base64或凭据。 */
    private DatabaseEvidence databaseEvidence(Fixture fixture){
        return new DatabaseEvidence(queryRows(jdbc,fixture.tenantId(),"SELECT run_id,status,run_revision FROM agent_runtime_runs WHERE tenant_id=?",fixture.tenantId()),
                queryRows(jdbc,fixture.tenantId(),"SELECT attempt_id,invocation_id,status,root_input_id FROM nw_external_invocations WHERE tenant_id=?::bigint AND operation_type='model.chat'",fixture.tenantId()),
                queryRows(jdbc,fixture.tenantId(),"SELECT event_id,attempt_id,run_id,usage_event_kind,report_revision,usage_source,prompt_tokens,completion_tokens FROM nw_usage_events WHERE tenant_id=?::bigint AND event_type='llm.chat'",fixture.tenantId()),
                queryRows(jdbc,fixture.tenantId(),"SELECT run_id,action_id,invocation_id,state,reason_code,execute_call_kind,execute_target_handle FROM agent_runtime_actions WHERE tenant_id=?",fixture.tenantId()),
                toolFeedback(fixture),
                queryRows(jdbc,fixture.tenantId(),"SELECT run_id,request_id,invocation_id,status,reason,error FROM nw_browser_connection_requests WHERE tenant_id=?::bigint",fixture.tenantId()));
    }
    /** 工具失败留存公开错误码，原始正文、网页内容和图片字节不写诊断报告。 */
    private List<ToolFeedback> toolFeedback(Fixture fixture) {
        var results=new ArrayList<ToolFeedback>();
        for(var row:queryRows(jdbc,fixture.tenantId(),"SELECT event_id,run_id,payload_canonical_json FROM agent_runtime_session_events WHERE tenant_id=? AND event_type='agent.tool-result' ORDER BY session_sequence",fixture.tenantId())) {
            try {
                var root=controls.readTree(row.get("payload_canonical_json").toString());
                var output=root.path("output").path("canonicalJson");
                var structured=output.isTextual()?controls.readTree(output.asText()).path("structuredContent"):controls.getNodeFactory().missingNode();
                results.add(new ToolFeedback(row.get("event_id").toString(),row.get("run_id").toString(),
                        root.path("status").asText(),root.path("reasonCode").asText(null),
                        structured.path("status").asText(null),structured.path("error_code").asText(null)));
            }catch(java.io.IOException invalid){throw new IllegalStateException("tool_feedback_evidence_invalid",invalid);}
        }
        return List.copyOf(results);
    }
    /** 准确原Run持久投影；尚未受理返回null。 */
    private Map<String,Object> run(Fixture fixture,String run){var rows=rows(fixture,"SELECT run_id,session_id,agent_id,status FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?",run);return rows.isEmpty()?null:rows.getFirst();}
    /** 原Run安全数据库行，保留SQL实际返回的未知值。 */
    private List<Map<String,Object>> rows(Fixture fixture,String sql,String run){return queryRows(jdbc,fixture.tenantId(),sql,fixture.tenantId(),run);}
    /** 原Run准确数量，不把HTTP成功当作持久恢复成功。 */
    private long count(Fixture fixture,String sql,String run){return queryCount(jdbc,fixture.tenantId(),sql,fixture.tenantId(),run);}
    /** 只读报告UI已受理的真实身份，供可见浏览器操作关联；不从网页隐藏状态提取或推测输入。 */
    private List<ObservedRun> observedRuns(Fixture fixture) {
        return queryRows(jdbc,fixture.tenantId(),"SELECT run_id,session_id,causation_input_id,status FROM agent_runtime_runs WHERE tenant_id=? ORDER BY created_epoch_second,created_nano",fixture.tenantId()).stream()
                .map(row->new ObservedRun(row.get("run_id").toString(),row.get("session_id").toString(),row.get("causation_input_id").toString(),row.get("status").toString())).toList();
    }
    /** 仅资源API；此类不提供业务Chat或pair调用helper。 */
    private JsonNode api(String token,String method,String path,Object body,int expected)throws Exception{
        checkFixtureTime();if(path.equals("/api/v1/chat")||path.endsWith("/pair"))throw new IllegalArgumentException("harness_must_not_submit_or_pair");
        var request=HttpRequest.newBuilder(URI.create(endpoint()+path)).timeout(Duration.ofSeconds(30));if(token!=null)request.header("Authorization","Bearer "+token);
        if(body==null)request.method(method,HttpRequest.BodyPublishers.noBody());else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=expected)throw new IllegalStateException("fixture_api_failed "+method+" "+path+" status="+response.statusCode());
        return json.readTree(response.body()).path("data");
    }
    /** 精确能力目录项缺失立即失败，不根据列表位置猜测。 */
    private static JsonNode element(JsonNode rows,String field,String expected){for(var row:rows)if(expected.equals(row.path(field).asText()))return row;throw new IllegalStateException("fixture_catalog_entry_missing");}
    /** 当前隔离实例地址。 */
    private String endpoint(){return "http://127.0.0.1:"+port;}
    /** 同一总截止贯穿资源准备与UI观察，不人为延长生产Run窗口。 */
    private static void checkFixtureTime(){if(!Instant.now().isBefore(FIXTURE_DEADLINE))throw new IllegalStateException("fixture_deadline_reached");}
    /** 安全/私有证据都采用原子替换，actor不会读到半份ready。 */
    private void write(Path path,Object value)throws Exception{var temporary=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temporary,controls.writerWithDefaultPrettyPrinter().writeValueAsString(value));Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    /** 根提供的私有内部服务配置不进入普通输出。 */
    private static ServiceConfiguration privateService(){try{return new ObjectMapper().readValue(Files.readAllBytes(Path.of(required("SKILLFORGE_BROWSER_VISION_SERVICE_PRIVATE"))),ServiceConfiguration.class);}catch(Exception failure){throw new IllegalStateException("browser_private_service_configuration_unavailable");}}
    /** 显式启用缺失参数即拒绝，不回落共享开发服务。 */
    private static String required(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("missing_harness_environment "+name);return value;}

    /** 本类先关发送门禁及worker；上下文由Spring按正式顺序关闭。 */
    @AfterAll static void stopOwnedWorkers(@Autowired ApplicationContext context){context.getBean(BrowserVisionAuditGuard.class).stop("HARNESS_CLOSED");context.getBean(AgentRuntimeProperties.class).getWorkers().setAutoStartup(false);context.getBeansOfType(SmartLifecycle.class).values().stream().filter(value->value.getClass().getName().contains("AgentRuntimeWorkers")).forEach(SmartLifecycle::stop);}

    /** 只装配观测包装，不替换Provider目录、模型返回或媒体正文。 */
    @TestConfiguration(proxyBeanMethods=false)
    static class ObservationConfiguration {
        /** 正式审计外围增加显式本批发送上限。 */
        @Bean BrowserVisionAuditGuard browserVisionAuditGuard(){return new BrowserVisionAuditGuard();}
        /** 只包装生产为本次Registered返回的DirectAudit，发送上限与真实审计均保留。 */
        @Bean @Primary AgentRuntimeModelInvocationAudit browserVisionScopedAudit(
                @org.springframework.beans.factory.annotation.Qualifier("agentRuntimeModelInvocationAudit") AgentRuntimeModelInvocationAudit original,
                BrowserVisionAuditGuard guard, com.skillforge.core.billing.invocation.ExternalInvocationLedger ledger,
                com.skillforge.core.billing.metering.CostRecorder recorder, org.springframework.transaction.PlatformTransactionManager transactions,
                org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeWorkflowNodeExecutions> workflows) {
            return new AgentRuntimeModelInvocationAudit(ledger,recorder,transactions,workflows) {
                @Override public ModelInvocationAudit dispatched(com.skillforge.runtime.session.spi.ExecutionStore.DispatchResult.Registered registered,
                        com.skillforge.runtime.session.spi.ExecutionStore.Prepared prepared,com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings.Binding binding) {
                    return guard.wrap(original.dispatched(registered,prepared,binding));
                }
            };
        }
        /** 正式媒体服务外围采实际准备/编码读取的安全元数据。 */
        @Bean @Primary MediaPort browserVisionMedia(SessionMediaService original,BrowserVisionAuditGuard guard){return guard.media(original);}
    }

    /** @param endpoint 受控浏览器服务地址 @param token 内部令牌，仅私有输入 */
    private record ServiceConfiguration(String endpoint,String token){}
    /** @param tenantSlug 新机构登录名 @param username 测试管理员 @param password 本次随机密码 */
    private record Login(String tenantSlug,String username,String password){}
    /** @param definitionId 新Skill定义 @param versionId 不可变版本 @param versionKey 版本键 @param mountId 准确挂载 @param archiveSha256 实际ZIP摘要 @param fileCount 发布读回文件数 */
    private record SkillIdentity(long definitionId,long versionId,String versionKey,String mountId,String archiveSha256,int fileCount){}
    /** @param tenantId 机构 @param tenantSlug 登录名 @param adminPassword 私有随机密码 @param adminToken 私有成员令牌 @param agentId 已发布Agent @param agentName 展示名 @param channelId 已绑定渠道 @param channelToken 私有渠道令牌 @param modelId 正式模型ID @param modelRevision 正式发布修订 @param skill 精确Skill挂载 */
    private record Fixture(String tenantId,String tenantSlug,String adminPassword,String adminToken,String agentId,String agentName,String channelId,String channelToken,String modelId,long modelRevision,SkillIdentity skill){}
    /** @param backendEndpoint 本次后端 @param tenantId 机构 @param tenantSlug 登录slug @param agentId Agent @param agentName Agent展示名 @param channelId 渠道 @param modelId 正式模型 @param modelRevision 模型修订 @param skill 精确Skill @param readyAt 设施就绪时间 @param fixtureDeadline 总夹具截止 @param maximumUiSamples 允许UI新样本数 @param maximumModelSendsPerRun 每Run发送前上限 */
    private record Ready(String backendEndpoint,String tenantId,String tenantSlug,String agentId,String agentName,String channelId,String modelId,long modelRevision,SkillIdentity skill,Instant readyAt,Instant fixtureDeadline,int maximumUiSamples,int maximumModelSendsPerRun){}
    /** @param environment 不含密钥的环境身份 @param adminUsername 管理员登录名 @param adminPassword 私有密码 @param adminAccessToken 私有成员令牌 @param channelToken 私有渠道令牌 */
    private record PrivateReady(Ready environment,String adminUsername,String adminPassword,String adminAccessToken,String channelToken){}
    /** @param sampleId UI场景标识，可空 @param sessionId UI真正创建的会话 @param clientMessageId UI首次提交的稳定输入 */
    private record Submitted(@JsonAlias("sample_id")String sampleId,@JsonAlias("session_id")String sessionId,
            @JsonAlias("client_message_id")String clientMessageId,String targetSampleKey,boolean connectionExpected){}
    private record ExpectedSamples(List<ExpectedSample> samples){}
    private record ExpectedSample(String targetSampleKey,String expectedCode){}
    private record ObservedRun(String runId,String sessionId,String clientMessageId,String status){}
    /** @param status UI独立验收PASS或FAILED @param message UI安全说明，可空 */
    private record Done(String status,String message){}
    /** @param sampleId UI场景 @param sessionId 原会话 @param runId 原Run @param runStatus 原Run状态 @param finalAnswers 公开模型最终回答 @param starts 原start回执 @param connections 原连接等待 @param actions 原Action状态 @param media 原媒体与变体元数据 @param providerUsage Provider实际计量 @param modelAttempts 真实调用图片与用量元数据 @param productionBudget 原Run冻结生产预算 */
    private record SampleEvidence(String sampleId,String sessionId,String runId,String runStatus,List<String> finalAnswers,List<Map<String,Object>> starts,List<Map<String,Object>> connections,List<Map<String,Object>> actions,List<Map<String,Object>> media,List<Map<String,Object>> providerUsage,List<BrowserVisionAuditGuard.Attempt> modelAttempts,ExecutionBudgetPort.Snapshot productionBudget){}
    /** @param runs 数据库原Run @param invocations 原供应商尝试 @param usage 实际用量事件 */
    private record DatabaseEvidence(List<Map<String,Object>> runs,List<Map<String,Object>> invocations,List<Map<String,Object>> usage,
            List<Map<String,Object>> actions,List<ToolFeedback> toolFeedback,List<Map<String,Object>> connections){}
    private record ToolFeedback(String eventId,String runId,String status,String reasonCode,String browserStatus,String browserErrorCode){}
    /** @param status 本技术组合结果 @param reason 安全关闭原因 @param finishedAt 结束时间 @param samples 实际UI样本技术证据 @param modelAudit 安全发送账本，像素答案真值由独立UI验收提供 */
    private record HarnessResult(String status,String reason,Instant finishedAt,List<SampleEvidence> samples,BrowserVisionAuditGuard.Snapshot modelAudit){}
}

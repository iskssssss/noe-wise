package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.foundation.application.ServicePrincipalService.IssuedPrincipal;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.security.HmacSignatureVerifier;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真签名、正式发布安装挂载及唯一Worker验证明确Workflow入口；Provider只记录意外模型请求。 */
@Import(OpenPlatformWorkflowInvocationHttpIntegrationTest.OwnedLoopbackModelConfiguration.class)
class OpenPlatformWorkflowInvocationHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PATH="/api/v1/open-platform/sessions/workflow-invocations";
    private static final String PASSWORD="WorkflowInvocationHttp123";
    @Autowired ObjectMapper json;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired InstitutionIntegrationMapper integrations;
    @Autowired ServicePrincipalService principals;
    @Autowired PlatformModelCatalogService models;
    @Autowired AgentRuntimeHostProvisioner provisioner;
    @Autowired ExecutionStore store;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired ApplicationContext application;
    @Autowired AgentRuntimeProperties properties;
    @LocalServerPort int port;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String token;

    @DynamicPropertySource static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup",()->"true");
    }

    @Test @Timeout(180)
    void signedExplicitInvocationValidatesFormalMountAndSchemaThenCompletesWithoutModelRequest() throws Exception {
        var modelCalls=new AtomicInteger();
        var provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/",exchange->{modelCalls.incrementAndGet();try(exchange){exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(500,-1);}});
        provider.start();
        try {
            String marker="workflow-invoke-"+SnowflakeIds.nextId();
            String tenant=createTenant(tenants,plans,marker);
            TenantContext.runWithTenant(tenant,()->{
                createTestTenantAdministrator(tenant,"workflow-http-admin",PASSWORD);
                PlatformModelFixtures.selectLlm(models,tenant,"http://127.0.0.1:"+provider.getAddress().getPort(),
                        marker+"-model","synthetic-workflow-key","custom","custom-openai-compatible","custom-v1");
                provisioner.provision(tenant);
            });
            requireRunningRuntimeWorkers(application,properties);
            var owner=credential(tenant,marker,Set.of("sessions.write","sessions.read","end_users.write"));
            var readonly=credential(tenant,marker,Set.of("sessions.read"));
            token=admin("POST","/api/v1/auth/login",Map.of("tenant_slug",marker,"username","workflow-http-admin","password",PASSWORD),null,200)
                    .path("access_token").asText();
            assertFalse(token.isBlank());
            String workflow=marker+"-formal";
            var draft=admin("POST","/api/v1/builder/workflows",Map.of("workflow_id",workflow,"definition",definition()),null,200);
            var published=admin("POST","/api/v1/builder/workflows/publish",Map.of("workflow_id",workflow),draft.path("revision").asText(),200);
            var installed=admin("POST","/api/v1/builder/workflows/install",Map.of("workflow_id",workflow,"payload",Map.of("config",Map.of())),null,200);
            var instances=admin("GET","/api/v1/builder/workflows/instances",null,null,200);
            var matches=new ArrayList<JsonNode>();
            for(var item:instances.path("items"))if(installed.path("instance_id").asText().equals(item.path("instance_id").asText()))matches.add(item);
            assertEquals(1,matches.size());
            var version=admin("GET","/api/v1/capabilities/versions/detail?versionId="+published.path("capability_version_id").asText(),null,null,200);
            String agent=admin("POST","/api/v1/agents",Map.of("agent_key",marker,"name","明确WorkflowHTTP",
                    "system_prompt","执行当前正式配置。","config",Map.of()),null,200).path("id").asText();
            var mounted=admin("POST","/api/v1/agents/capability-mounts",Map.of("agent_id",agent,"payload",Map.of(
                    "capability_type","WORKFLOW","capability_definition_id",version.path("definition_id").asText(),
                    "capability_version_id",published.path("capability_version_id").asText(),"priority",100,
                    "workflow_config",Map.of("workflow_instance_id",matches.getFirst().path("id").asText(),
                            "workflow_instance_revision",published.path("definition_version_id").asText()))),null,201);
            assertEquals(1,mounted.path("mounts").size());
            String mount=mounted.path("mounts").get(0).path("mount_id").asText();
            assertFalse(mount.isBlank(),mounted.toString());
            admin("POST","/api/v1/agents/publish",Map.of("agent_id",agent),null,200);
            String user=machine(owner,"/api/v1/open-platform/end-users/ensure",Map.of("external_user_ref",marker,"display_name","HTTP用户"),null,false,200).path("end_user_id").asText();
            String session=machine(owner,"/api/v1/open-platform/sessions",Map.of("external_user_ref",marker,
                    "external_conversation_ref",marker,"agent_id",agent,"end_user_id",user),null,false,200).path("session_id").asText();
            // 同一正式Session验证context-only的真实签名入口；不以service mock替代权限链。
            String contextPath="/api/v1/open-platform/sessions/context-messages";
            var contextBody=Map.<String,Object>of("session_id",session,"source_message_id","business-result-1",
                    "question","","answer","业务系统已经记录的预约结果，仅为背景");
            var writer=credential(tenant,marker,Set.of("sessions.write"));
            var otherIntegration=credential(tenant,marker+"-other",Set.of("sessions.write"));
            long outboxBefore=queryCount(jdbc,tenant,"select count(*) from nw_chat_session_outbox where tenant_id=? and session_id=?",Long.parseLong(tenant),session);
            machine(writer,contextPath,contextBody,"context-replay",true,401);
            machine(readonly,contextPath,contextBody,"context-replay",false,403);
            machine(otherIntegration,contextPath,contextBody,"context-replay",false,403);
            var recorded=machine(writer,contextPath,contextBody,"context-replay",false,200);
            assertTrue(recorded.path("recorded").asBoolean());assertFalse(recorded.path("replayed").asBoolean());
            assertFalse(recorded.has("run_id"));
            var contextReplay=machine(writer,contextPath,contextBody,"context-replay",false,200);
            assertTrue(contextReplay.path("replayed").asBoolean());assertEquals(recorded.path("message_id"),contextReplay.path("message_id"));
            machine(writer,contextPath,Map.of("session_id",session,"source_message_id","business-result-1","answer","替换原背景"),"context-replay",false,409);
            assertEquals(1L,queryCount(jdbc,tenant,"select count(*) from nw_chat_messages where tenant_id=? and session_id=? and message_id=?",Long.parseLong(tenant),session,recorded.path("message_id").asText()));
            assertEquals(0L,runCount(tenant,session));
            assertEquals(outboxBefore,queryCount(jdbc,tenant,"select count(*) from nw_chat_session_outbox where tenant_id=? and session_id=?",Long.parseLong(tenant),session));
            TenantContext.runWithTenant(tenant,()->assertTrue(principals.revoke(tenant,writer.principal().getId())));
            machine(writer,contextPath,contextBody,"context-replay",false,401);
            var body=new LinkedHashMap<String,Object>();body.put("session_id",session);body.put("mount_id",mount);
            body.put("capability_id",version.path("definition_id").asText());body.put("version_id",published.path("capability_version_id").asText());
            body.put("parameters",Map.of("count",0));
            assertEquals(0L,runCount(tenant,session));
            machine(owner,PATH,body,"bad-signature",true,401);
            machine(readonly,PATH,body,"no-write-scope",false,403);
            for(var invalid:List.of(Map.<String,Object>of(),Map.<String,Object>of("count","0"),Map.<String,Object>of("count",4))) {
                body.put("parameters",invalid);
                var failure=machineEnvelope(owner,PATH,body,"schema-"+SnowflakeIds.nextId(),false,422);
                assertEquals("workflow_invocation_input_invalid",failure.path("code").asText());
                assertEquals(0L,runCount(tenant,session));
            }
            body.put("parameters",Map.of("count",0));body.put("mount_id","missing-mount");
            machine(owner,PATH,body,"missing-mount",false,403);assertEquals(0L,runCount(tenant,session));
            body.put("mount_id",mount);body.put("version_id",Long.toString(SnowflakeIds.nextId()));
            machine(owner,PATH,body,"wrong-version",false,403);assertEquals(0L,runCount(tenant,session));
            body.put("version_id",published.path("capability_version_id").asText());
            var receipt=machine(owner,PATH,body,"success",false,200);
            var ref=new RunRef(new TenantId(tenant),receipt.path("run_id").asText());
            assertFalse(receipt.path("replayed").asBoolean());
            await().atMost(Duration.ofSeconds(45)).until(()->TenantContext.callWithTenant(tenant,()->store.load(ref)
                    .filter(run->Set.of(RunStatus.COMPLETED,RunStatus.FAILED,RunStatus.CANCELLED,RunStatus.TERMINATED).contains(run.status())).isPresent()));
            TenantContext.runWithTenant(tenant,()->{
                assertEquals(RunStatus.COMPLETED,store.load(ref).orElseThrow().status());
                var actions=store.actions(ref,0,100);assertFalse(actions.isEmpty());
                assertTrue(actions.stream().anyMatch(action->action.kind()==ExecutionStore.Kind.WORKFLOW));
                var workflowAction=actions.stream().filter(action->action.kind()==ExecutionStore.Kind.WORKFLOW).findFirst().orElseThrow();
                var protocol=new com.skillforge.runtime.session.SessionExecutionWorkflows(new com.skillforge.runtime.json.CodecLimits(
                        properties.getMaxPayloadCodeUnits(),properties.getMaxPayloadNodes(),properties.getMaxPayloadDepth()));
                var dispatch=assertInstanceOf(com.skillforge.runtime.session.SessionExecutionWorkflows.Dispatch.class,
                        protocol.decode(store.material(ref.tenantId(),workflowAction.request()).orElseThrow().content()));
                assertEquals("{\"count\":0}",dispatch.parameters().canonicalJson());
                assertEquals(published.path("capability_version_id").asText(),dispatch.target().capability().versionId());
                assertTrue(store.events(new com.skillforge.runtime.api.SessionRef(ref.tenantId(),session),0,100).stream()
                        .filter(event->event.run().equals(ref)).anyMatch(event->event.event().data().canonicalJson().contains("formal-workflow-http-completed")));
                assertTrue(actions.stream().noneMatch(action->action.kind()==ExecutionStore.Kind.MODEL));
                assertTrue(actions.stream().noneMatch(action->AgentExecutionCodec.MODEL_REQUEST_SCHEMA.equals(store.material(ref.tenantId(),action.request()).orElseThrow().content().schemaId())));
            });
            var replay=machine(owner,PATH,body,"success",false,200);
            assertTrue(replay.path("replayed").asBoolean());assertEquals(receipt.path("run_id"),replay.path("run_id"));
            body.put("parameters",Map.of("count",1));machine(owner,PATH,body,"success",false,409);
            assertEquals(1L,runCount(tenant,session));assertEquals(0,modelCalls.get());
        } finally {provider.stop(0);TenantContext.clear();}
    }

    @Test @Timeout(180)
    void workflowLlmRejectsTruncatedOutputAndPreservesModelFacts() throws Exception {
        String marker="workflow-llm-"+SnowflakeIds.nextId();String model=marker+"-model";
        var finish=new AtomicReference<>("length");var content=new AtomicReference<>("fixture-result");var calls=new AtomicInteger();
        var provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/",exchange->{
            calls.incrementAndGet();
            try(exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] response=json.writeValueAsBytes(Map.of("id","workflow-llm-fixture","object","chat.completion","model",model,
                        "choices",List.of(Map.of("index",0,"finish_reason",finish.get(),"message",Map.of("role","assistant","content",content.get()))),
                        "usage",Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18)));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
                exchange.getResponseBody().write(response);
            }
        });
        provider.start();
        try {
            String tenant=createTenant(tenants,plans,marker);
            TenantContext.runWithTenant(tenant,()->{
                createTestTenantAdministrator(tenant,"workflow-llm-admin",PASSWORD);
                PlatformModelFixtures.selectLlm(models,tenant,"http://127.0.0.1:"+provider.getAddress().getPort(),
                        model,"synthetic-workflow-key","custom","custom-openai-compatible","custom-v1");
                provisioner.provision(tenant);
            });
            requireRunningRuntimeWorkers(application,properties);
            var owner=credential(tenant,marker,Set.of("sessions.write","sessions.read","end_users.write"));
            token=admin("POST","/api/v1/auth/login",Map.of("tenant_slug",marker,"username","workflow-llm-admin","password",PASSWORD),null,200).path("access_token").asText();
            String workflow=marker+"-formal";
            var draft=admin("POST","/api/v1/builder/workflows",Map.of("workflow_id",workflow,"definition",llmDefinition()),null,200);
            var published=admin("POST","/api/v1/builder/workflows/publish",Map.of("workflow_id",workflow),draft.path("revision").asText(),200);
            var installed=admin("POST","/api/v1/builder/workflows/install",Map.of("workflow_id",workflow,"payload",Map.of("config",Map.of())),null,200);
            var instances=admin("GET","/api/v1/builder/workflows/instances",null,null,200);
            JsonNode instance=null;for(var item:instances.path("items"))if(installed.path("instance_id").asText().equals(item.path("instance_id").asText()))instance=item;
            assertNotNull(instance);
            var version=admin("GET","/api/v1/capabilities/versions/detail?versionId="+published.path("capability_version_id").asText(),null,null,200);
            String agent=admin("POST","/api/v1/agents",Map.of("agent_key",marker,"name","Workflow LLM",
                    "system_prompt","执行当前正式配置。","config",Map.of()),null,200).path("id").asText();
            var mounted=admin("POST","/api/v1/agents/capability-mounts",Map.of("agent_id",agent,"payload",Map.of(
                    "capability_type","WORKFLOW","capability_definition_id",version.path("definition_id").asText(),
                    "capability_version_id",published.path("capability_version_id").asText(),"priority",100,
                    "workflow_config",Map.of("workflow_instance_id",instance.path("id").asText(),
                            "workflow_instance_revision",published.path("definition_version_id").asText()))),null,201);
            String mount=mounted.path("mounts").get(0).path("mount_id").asText();
            admin("POST","/api/v1/agents/publish",Map.of("agent_id",agent),null,200);
            String user=machine(owner,"/api/v1/open-platform/end-users/ensure",Map.of("external_user_ref",marker,"display_name","HTTP用户"),null,false,200).path("end_user_id").asText();
            for(var testCase:List.of(new String[]{"length","fixture-result"},new String[]{"content_filter","fixture-result"},
                    new String[]{"stop","fixture-result"},new String[]{"length",""},new String[]{"content_filter",""})) {
                String reason=testCase[0],expectedContent=testCase[1];
                String caseId=reason+(expectedContent.isEmpty()?"-empty":"");
                finish.set(reason);content.set(expectedContent);
                String session=machine(owner,"/api/v1/open-platform/sessions",Map.of("external_user_ref",marker,
                        "external_conversation_ref",marker+caseId,"agent_id",agent,"end_user_id",user),null,false,200).path("session_id").asText();
                var body=Map.of("session_id",session,"mount_id",mount,"capability_id",version.path("definition_id").asText(),
                        "version_id",published.path("capability_version_id").asText(),"parameters",Map.of());
                var receipt=machine(owner,PATH,body,"llm-"+caseId,false,200);
                var ref=new RunRef(new TenantId(tenant),receipt.path("run_id").asText());
                await().atMost(Duration.ofSeconds(45)).until(()->TenantContext.callWithTenant(tenant,()->store.load(ref)
                        .filter(run->Set.of(RunStatus.COMPLETED,RunStatus.FAILED,RunStatus.CANCELLED,RunStatus.TERMINATED).contains(run.status())).isPresent()));
                TenantContext.runWithTenant(tenant,()->{
                    var events=store.events(new com.skillforge.runtime.api.SessionRef(ref.tenantId(),session),0,100);
                    boolean reached=events.stream().filter(event->event.run().equals(ref))
                            .anyMatch(event->event.event().data().canonicalJson().contains("llm-next-node-reached"));
                    assertEquals(reason.equals("stop"),reached);
                    assertEquals(reason.equals("stop")?RunStatus.COMPLETED:RunStatus.FAILED,store.load(ref).orElseThrow().status());
                });
                var executions=queryRows(jdbc,tenant,"select state, reason_code from agent_runtime_executions where tenant_id=? and run_id=?",tenant,ref.runId());
                assertEquals(1,executions.size(),executions.toString());
                assertEquals(reason.equals("stop")?"COMPLETED":"FAILED",executions.getFirst().get("state"));
                if(!reason.equals("stop"))assertEquals(reason.equals("length")?"model_output_length":"model_content_filtered",executions.getFirst().get("reason_code"));
                var rows=queryRows(jdbc,tenant,"select a.run_id, a.selected_attempt from agent_runtime_execution_actions a join agent_runtime_execution_runs r on r.tenant_id=a.tenant_id and r.run_id=a.run_id where a.tenant_id=? and r.session_id=? and a.kind='MODEL'",tenant,session);
                assertEquals(1,rows.size(),rows.toString());
                var nodeRun=new RunRef(new TenantId(tenant),rows.getFirst().get("run_id").toString());
                TenantContext.runWithTenant(tenant,()->{
                    var attempt=store.attempt(nodeRun,rows.getFirst().get("selected_attempt").toString()).orElseThrow();
                    var material=store.material(nodeRun.tenantId(),attempt.result().orElseThrow()).orElseThrow();
                    var codec=new AgentExecutionCodec(new com.skillforge.runtime.json.CodecLimits(properties.getMaxPayloadCodeUnits(),properties.getMaxPayloadNodes(),properties.getMaxPayloadDepth()));
                    var actual=assertInstanceOf(com.skillforge.runtime.api.port.ModelPort.ModelResult.Completed.class,codec.decodeModelOutcome(material.content()));
                    assertEquals(reason,actual.finishReasonCode());assertEquals(expectedContent.isEmpty()?"\"\"":"\"fixture-result\"",actual.output().canonicalJson());
                    assertEquals(11,actual.usage().inputTokens().orElseThrow());assertEquals(7,actual.usage().outputTokens().orElseThrow());
                });
            }
            assertEquals(5,calls.get());
        } finally {provider.stop(0);TenantContext.clear();}
    }

    private long runCount(String tenant,String session) {
        return queryCount(jdbc,tenant,"select count(*) from agent_runtime_execution_runs where tenant_id=? and session_id=?",tenant,session);
    }

    private IssuedPrincipal credential(String tenant,String integration,Set<String> scopes) {
        return TenantContext.callWithTenant(tenant,()->{integrations.registerIntegration(Long.parseLong(tenant),integration);return principals.issue(tenant,integration,scopes);});
    }

    private JsonNode machine(IssuedPrincipal key,String path,Object value,String idempotency,boolean corrupt,int expected) throws Exception {
        return machineEnvelope(key,path,value,idempotency,corrupt,expected).path("data");
    }

    private JsonNode machineEnvelope(IssuedPrincipal key,String path,Object value,String idempotency,boolean corrupt,int expected) throws Exception {
        String body=json.writeValueAsString(value),timestamp=Long.toString(Instant.now().getEpochSecond());
        String signature=HmacSignatureVerifier.sign(key.signingSecret(),HmacSignatureVerifier.canonical(timestamp,"POST",path,body));
        if(corrupt)signature=(signature.charAt(0)=='0'?"1":"0")+signature.substring(1);
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/json").header("X-Api-Key",key.apiKey()).header("X-Timestamp",timestamp).header("X-Signature",signature);
        if(idempotency!=null)builder.header("Idempotency-Key",idempotency);
        var response=http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),response.body());return json.readTree(response.body());
    }

    private JsonNode admin(String method,String path,Object value,String revision,int expected) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);if(revision!=null)builder.header("If-Match",revision);
        var response=http.send(builder.method(method,value==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value),StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),response.body());return json.readTree(response.body()).path("data");
    }

    /** 测试专属境内loopback服务，显式声明正式模型目录要求的地域及LLM类型，不导入真实供应商。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class OwnedLoopbackModelConfiguration {
        @org.springframework.context.annotation.Bean
        ModelProviderCatalog workflowInvocationModelCatalog() {
            var capabilities=new CapabilitySupport(
                    SupportLevel.SUPPORTED,
                    SupportLevel.UNSUPPORTED,
                    SupportLevel.SUPPORTED,
                    SupportLevel.SUPPORTED,
                    SupportLevel.UNSUPPORTED);
            var protocol=new ProtocolProfile(
                    ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                    "openai-compatible-chat-v1",Set.of(AuthenticationScheme.BEARER_API_KEY),
                    capabilities,VerificationStatus.VERIFIED);
            var service=new ServiceProfile(
                    "custom-openai-compatible","本测试拥有的境内loopback服务","custom-v1",
                    Availability.SUPPORTED,null,
                    EndpointMode.CUSTOM,null,List.of(protocol),
                    ServiceRegion.CHINA_MAINLAND,
                    Set.of(ModelKind.LLM));
            ModelProvider provider=()->new ProviderPreset(
                    "custom","本测试拥有的loopback Provider",ProviderCategory.CUSTOM_OR_SELF_HOSTED,List.of(service));
            return new ModelProviderCatalog(List.of(provider));
        }
    }

    private static Map<String,Object> llmDefinition() {
        return Map.of("version","1.0","entry","start","meta",Map.of("display_name","Workflow LLM 完成原因",
                "capability",Map.of("model_invocable",false,"user_invocable",true,"input_schema",Map.of(),
                        "output_schema",Map.of(),"supports_hitl",false,"export_fields",List.of("messages"),"timeout_ms",300000,"concurrency_safe",true)),
                "nodes",List.of(Map.of("id","start","type","start","name","开始"),
                        Map.of("id","llm","type","llm","name","模型","prompt","返回结果。"),
                        Map.of("id","done","type","output","name","后继","message","llm-next-node-reached"),
                        Map.of("id","end","type","end","name","结束")),
                "edges",List.of(Map.of("from","start","to","llm"),Map.of("from","llm","to","done"),Map.of("from","done","to","end")));
    }

    private static Map<String,Object> definition() {
        return Map.of("version","1.0","entry","start","meta",Map.of("display_name","明确调用HTTP",
                "capability",Map.of("model_invocable",false,"user_invocable",true,"input_schema",Map.of("type","object",
                        "properties",Map.of("count",Map.of("type","integer","minimum",0,"maximum",3)),"required",List.of("count"),"additionalProperties",false),
                        "output_schema",Map.of(),"supports_hitl",false,"export_fields",List.of("messages"),"timeout_ms",300000,"concurrency_safe",true)),
                "nodes",List.of(Map.of("id","start","type","start","name","开始"),Map.of("id","done","type","output","name","完成","message","formal-workflow-http-completed"),Map.of("id","end","type","end","name","结束")),
                "edges",List.of(Map.of("from","start","to","done"),Map.of("from","done","to","end")));
    }
}

package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.OpenPlatformRequestMetering;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.foundation.application.ServicePrincipalService.IssuedPrincipal;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.runtime.AgentRuntimeExecutionWorker;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.security.HmacSignatureVerifier;
import com.skillforge.runtime.api.port.RunIdentity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 真签名HTTP/受限PG验证MESSAGE请求根与受理事务；Worker显式静止，模型调用由组合宿主另验。 */
@Tag("runtime-latency")
@EnabledIfEnvironmentVariable(named="SKILLFORGE_S03_EXTERNAL_JDBC_URL",matches=".+")
@Import(DeterministicModelCatalogConfiguration.class)
class OpenPlatformRequestMeteringHttpIntegrationTest extends AbstractIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired InstitutionIntegrationMapper integrations;
    @Autowired ServicePrincipalService principals;
    @Autowired PlatformModelCatalogService models;
    @Autowired AgentRuntimeHostProvisioner provisioner;
    @Autowired AgentRuntimeExecutionWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.skillforge.core.chat.message.MemoryService memory;
    @Autowired com.skillforge.runtime.session.spi.ExecutionStore store;
    @Autowired com.skillforge.runtime.session.AgentExecutionCodec codec;
    @MockitoSpyBean OpenPlatformRequestMetering metering;
    @LocalServerPort int port;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String token;

    @DynamicPropertySource static void noPolling(DynamicPropertyRegistry properties) {
        properties.add("agent-runtime.workers.auto-startup",()->false);
    }

    @Test @Timeout(120)
    void messageRootSharesAdmissionRollbackAndReplaysWithoutCountingOtherInputs() throws Exception {
        // autoStartup只关闭轮询，direct wake仍会派发；close正式唯一Worker后本类只验证受理事务。
        worker.close();assertFalse(worker.isRunning());
        String marker="message-root-"+SnowflakeIds.nextId();
        String tenant=createTenant(tenants,plans,marker);
        TenantContext.runWithTenant(tenant,()->{
            createTestTenantAdministrator(tenant,"request-admin","SyntheticRequest123");
            PlatformModelFixtures.selectLlm(models,tenant,"http://127.0.0.1:9",marker+"-model","synthetic-request-key","custom","custom-openai-compatible","custom-v1");
            provisioner.provision(tenant);
        });
        var owner=credential(tenant,marker,Set.of("sessions.write","sessions.read","end_users.write","usage.read"));
        var readOnly=credential(tenant,marker,Set.of("sessions.read","usage.read"));
        var otherIntegration=credential(tenant,marker+"-other",Set.of("sessions.write","usage.read"));
        String foreignTenant=createTenant(tenants,plans,marker+"-foreign");
        var foreign=credential(foreignTenant,marker,Set.of("sessions.write","usage.read"));
        token=data(admin("/api/v1/auth/login",Map.of("tenant_slug",marker,"username","request-admin","password","SyntheticRequest123"))).path("access_token").asText();
        String agent=data(admin("/api/v1/agents",Map.of("agent_key",marker,"name","合成请求根验收","system_prompt","仅验证合成文本，不执行外部业务操作。","config",Map.of()))).path("id").asText();
        data(admin("/api/v1/agents/publish",Map.of("agent_id",agent)));
        String endUser=data(machine(owner,"POST","/api/v1/open-platform/end-users/ensure",Map.of("external_user_ref",marker),null)).path("end_user_id").asText();
        String session=data(machine(owner,"POST","/api/v1/open-platform/sessions",Map.of("external_user_ref",marker,"external_conversation_ref",marker,"agent_id",agent,"end_user_id",endUser),null)).path("session_id").asText();
        var baseline=counts(tenant);
        var failOnce=new AtomicBoolean(true);var realWrites=new AtomicInteger();
        doAnswer(call->{call.callRealMethod();realWrites.incrementAndGet();if(failOnce.compareAndSet(true,false))throw new IllegalStateException("synthetic_request_metering_after_write_failure");return null;})
                .when(metering).record(any(SqlSession.class),any(RunIdentity.class),anyString());
        String path="/api/v1/open-platform/sessions/messages";
        var body=message(session,"synthetic accepted text");
        var failed=machine(owner,"POST",path,body,"rollback");assertEquals(500,failed.statusCode());
        assertEquals(1,realWrites.get(),"failure must follow the actual same-SqlSession request write");
        assertEquals(baseline,counts(tenant),"request fact/publication reference/run/events/budget must all roll back");
        var denied=machine(readOnly,"POST",path,body,"denied");assertEquals(403,denied.statusCode());
        assertEquals(403,machine(otherIntegration,"POST",path,body,"other-integration").statusCode());
        int foreignStatus=machine(foreign,"POST",path,body,"foreign").statusCode();assertTrue(foreignStatus==403||foreignStatus==404);
        assertEquals(baseline,counts(tenant));assertEquals(1,realWrites.get(),"authorization rejects before metering");
        var accepted=data(machine(owner,"POST",path,body,"accepted"));assertFalse(accepted.path("replayed").asBoolean());
        String run=accepted.path("run_id").asText(),input=accepted.path("input_id").asText();
        assertFalse(run.isBlank());assertFalse(input.isBlank());assertEquals(2,realWrites.get());
        var after=counts(tenant);assertEquals(1L,after.get("agent_runtime_execution_runs"));assertEquals(1L,after.get("nw_usage_events"));
        assertEquals(1L,after.get("nw_chat_messages"),"accepted external USER must be archived with the Run");
        var archived=TenantContext.callWithTenant(tenant,()->memory.listArchived(tenant,session,100)).getFirst();
        assertEquals("user",archived.role());assertEquals("synthetic accepted text",archived.content());
        assertEquals(64,archived.messageId().length());assertEquals(input,archived.metadata().get("input_id"));
        assertNotNull(archived.metadata().get("content_parts"));assertNotNull(archived.metadata().get("content_fingerprint"));
        assertTrue(after.get("agent_runtime_execution_events")>0);assertTrue(after.get("agent_runtime_budget_roots")>0);
        var replay=data(machine(owner,"POST",path,body,"accepted"));assertTrue(replay.path("replayed").asBoolean());
        for(String field:List.of("session_id","input_id","root_input_id","event_id","run_id","status"))assertEquals(accepted.path(field),replay.path(field));
        assertEquals(409,machine(owner,"POST",path,message(session,"different synthetic text"),"accepted").statusCode());
        assertEquals(after,counts(tenant));assertEquals(2,realWrites.get(),"same-key replay and conflict must not call metering");
        var usage=data(machine(owner,"GET","/api/v1/open-platform/usage/events?session_id="+session+"&run_id="+run,null,null)).path("items");
        assertEquals(1,usage.size());var root=usage.get(0);
        assertEquals("request",root.path("event_type").asText());assertEquals(1,root.path("quantity").asInt());
        assertEquals("request",root.path("measurement_unit").asText());assertEquals("not_applicable",root.path("amount_status").asText());
        assertEquals(tenant,root.path("tenant_id").asText());assertEquals(marker,root.path("integration_id").asText());
        assertEquals(session,root.path("session_id").asText());assertEquals(run,root.path("run_id").asText());
        assertEquals(agent,root.path("agent_id").asText());assertEquals(endUser,root.path("end_user_id").asText());
        assertEquals(accepted.path("root_input_id").asText(),root.path("root_input_id").asText());
        assertEquals(input+":request",root.path("source_event_id").asText());assertEquals(input,root.path("request_id").asText());
        for(String field:List.of("tokens","prompt_tokens","completion_tokens","amount_cents"))assertTrue(root.path(field).isNull());
        var summary=data(machine(owner,"GET","/api/v1/open-platform/usage/summary?session_id="+session+"&run_id="+run,null,null));
        assertEquals(1,summary.path("request_count").asInt());assertEquals(0,summary.path("invocation_count").asInt());
        data(machine(owner,"POST","/api/v1/open-platform/sessions/context-messages",Map.of("session_id",session,"source_message_id","context-1","answer","synthetic context only"),"context"));
        assertEquals(422,machine(owner,"POST","/api/v1/open-platform/sessions/actions",Map.of("session_id",session,"payload",Map.of("execution_id",run,"action_key","missing-wait","context",Map.of())),"action").statusCode());
        data(machine(owner,"POST","/api/v1/open-platform/sessions/commands",Map.of("session_id",session,"payload",Map.of("command","cancel_execution","execution_id",run)),"cancel"));
        var callback=data(machine(owner,"POST","/api/v1/open-platform/sessions/callbacks",Map.of("session_id",session,"payload",Map.of("callback_id","business-1","event_type","business_event","payload",Map.of("order_status","synthetic"))),"callback"));
        assertNotEquals(run,callback.path("run_id").asText());
        assertEquals(2L,count(tenant,"agent_runtime_execution_runs"));
        assertEquals(1L,count(tenant,"nw_usage_events"));assertEquals(2,realWrites.get(),"context/action/cancel/callback cannot record MESSAGE roots");
        var stable=data(machine(owner,"GET","/api/v1/open-platform/usage/events?session_id="+session+"&run_id="+run,null,null)).path("items");
        assertEquals(1,stable.size());assertEquals(root.path("event_id"),stable.get(0).path("event_id"));
        // 后续组合宿主复用本隔离库；先取消本例遗留callback Run，避免其被另一个Worker领取。
        data(machine(owner,"POST","/api/v1/open-platform/sessions/commands",Map.of("session_id",session,"payload",
                Map.of("command","cancel_execution","execution_id",callback.path("run_id").asText())),"cleanup-callback"));
        assertEquals(1L,count(tenant,"nw_usage_events"));
        // 通过真实受理入口核对下一轮冻结历史，不靠模型答复推测消息是否保存。
        TenantContext.runWithTenant(tenant,()->memory.append(tenant,session,Long.valueOf(endUser),"assistant","synthetic prior answer",Map.of(),null));
        var next=data(machine(owner,"POST",path,message(session,"synthetic follow-up"),"follow-up"));
        var nextRun=store.load(new com.skillforge.runtime.api.RunRef(new com.skillforge.runtime.api.TenantId(tenant),next.path("run_id").asText())).orElseThrow();
        var frozen=store.material(new com.skillforge.runtime.api.TenantId(tenant),new com.skillforge.runtime.api.ResultRef("execution-input:"+nextRun.identity().runRef().runId())).orElseThrow();
        var history=codec.decodeInput(frozen.content());
        assertEquals(1,history.stream().filter(value->value.role()==com.skillforge.runtime.api.port.ModelPort.ModelRole.USER
                && value.textContent().equals("synthetic accepted text")).count());
        assertEquals(1,history.stream().filter(value->value.role()==com.skillforge.runtime.api.port.ModelPort.ModelRole.ASSISTANT
                && value.textContent().equals("synthetic prior answer")).count());
        assertEquals(1,history.stream().filter(value->value.role()==com.skillforge.runtime.api.port.ModelPort.ModelRole.USER
                && value.textContent().contains("synthetic follow-up")).count(),"current input appears once");
        data(machine(owner,"POST","/api/v1/open-platform/sessions/commands",Map.of("session_id",session,"payload",
                Map.of("command","cancel_execution","execution_id",next.path("run_id").asText())),"cleanup-follow-up"));
    }

    private IssuedPrincipal credential(String tenant,String integration,Set<String> scopes) {
        return TenantContext.callWithTenant(tenant,()->{integrations.registerIntegration(Long.parseLong(tenant),integration);return principals.issue(tenant,integration,scopes);});
    }
    private Map<String,Long> counts(String tenant) {
        var result=new LinkedHashMap<String,Long>();
        for(String table:List.of("nw_chat_messages","nw_usage_events","nw_usage_publication_pending","agent_runtime_execution_runs","agent_runtime_execution_events",
                "agent_runtime_budget_roots","agent_runtime_budget_runs","agent_runtime_budget_balances"))result.put(table,count(tenant,table));
        return result;
    }
    private long count(String tenant,String table) {
        return queryCount(jdbc,tenant,"SELECT count(*) FROM "+table+" WHERE tenant_id=?"+(table.startsWith("nw_")?"::bigint":""),tenant);
    }
    private Map<String,Object> message(String session,String text) {return Map.of("session_id",session,"payload",Map.of("content",List.of(Map.of("type","text","text",text)),"context",Map.of()));}
    private HttpResponse<String> admin(String path,Object value) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value),StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> machine(IssuedPrincipal principal,String method,String path,Object value,String key) throws Exception {
        String body=value==null?"":json.writeValueAsString(value),timestamp=Long.toString(Instant.now().getEpochSecond());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
                .header("X-Api-Key",principal.apiKey()).header("X-Timestamp",timestamp)
                .header("X-Signature",HmacSignatureVerifier.sign(principal.signingSecret(),HmacSignatureVerifier.canonical(timestamp,method,path,body)));
        if(key!=null)request.header("Idempotency-Key",key);
        return http.send(request.method(method,value==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode()>=200&&response.statusCode()<300,"synthetic_http_operation_failed:"+response.statusCode());
        var result=json.readTree(response.body());assertEquals("OK",result.path("code").asText());return result.path("data");
    }
}

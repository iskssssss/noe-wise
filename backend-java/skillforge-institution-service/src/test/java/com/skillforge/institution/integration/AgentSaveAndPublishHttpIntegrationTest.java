package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantChain;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantHop;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.LocalBindings;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 完整迁移与真实HTTP/IAM事务验证；模型只登记配置。独立受限连接另证新列RLS，不将owner HTTP池冒称受限池。 */
@Import(DeterministicModelCatalogConfiguration.class)
class AgentSaveAndPublishHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD="SyntheticSavePublish123";
    private static final String AGENTS="/api/v1/agents";
    private static final String SAVE=AGENTS+"/save-and-publish";
    private static final String ACCESS="/api/v1/tenant/foundation/access";
    private static final List<String> BOTH=List.of("foundation.agents.read","foundation.agents.update","foundation.agents.publish",
            "foundation.resource.agent.read","foundation.resource.agent.manage");
    private final HttpClient http=HttpClient.newHttpClient();
    @LocalServerPort private int port;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private Environment environment;
    @AfterEach void clear(){TenantContext.clear();}

    @Test void publishOnlyReceiptPreservesSavedDraftAndOriginalResultAcrossLaterPublication() throws Exception {
        var f = fixture();
        var permissions = List.of("foundation.agents.read", "foundation.agents.publish",
                "foundation.resource.agent.read", "foundation.resource.agent.manage");
        String actor = member(f.admin(), f.slug(), "publisher-" + f.suffix(), permissions);
        var key = UUID.randomUUID();
        var body = Map.of("agent_id", f.agent(), "request_id", key);
        String endpoint = AGENTS + "/publish-with-receipt";
        var before = facts(f.tenant(), f.agent());
        var receipt = call(actor, "POST", endpoint, body, 200);
        assertReceipt(receipt, f.agent(), key, 1);
        var published = facts(f.tenant(), f.agent());
        assertEquals(before.get("name"), published.get("name"));
        assertEquals(receipt, call(actor, "POST", endpoint, body, 200));
        assertEquals(receipt, call(actor, "GET", result(f.agent(), key), null, 200));
        assertEquals(published, facts(f.tenant(), f.agent()));
        call(f.admin(), "GET", result(f.agent(), key), null, 404);
        call(f.admin(), "POST", endpoint, body, 409);
        call(f.admin(), "POST", SAVE, command(f.agent(), UUID.randomUUID(), Map.of("name", "后续保存发布")), 200);
        var latest = facts(f.tenant(), f.agent());
        assertEquals(receipt, call(actor, "POST", endpoint, body, 200));
        assertEquals(latest, facts(f.tenant(), f.agent()));
        call(actor, "POST", SAVE, command(f.agent(), UUID.randomUUID(), Map.of("name", "无更新权限")), 403);
    }

    @Test void publishReceiptRejectsCrossOperationKeysAndRequiresPublishAuthority() throws Exception {
        var f = fixture();
        String endpoint = AGENTS + "/publish-with-receipt";
        var key = UUID.randomUUID();
        var saved = call(f.admin(), "POST", SAVE, command(f.agent(), key, Map.of("name", "已保存发布")), 200);
        call(f.admin(), "POST", endpoint, Map.of("agent_id", f.agent(), "request_id", key), 409);
        var publishKey = UUID.randomUUID();
        call(f.admin(), "POST", endpoint, Map.of("agent_id", f.agent(), "request_id", publishKey), 200);
        call(f.admin(), "POST", SAVE, command(f.agent(), publishKey, Map.of("name", "跨操作复用")), 409);
        assertEquals(saved, call(f.admin(), "GET", result(f.agent(), key), null, 200));
        var before = facts(f.tenant(), f.agent());
        String reader = member(f.admin(), f.slug(), "reader-" + f.suffix(), List.of(
                "foundation.agents.read", "foundation.resource.agent.read", "foundation.resource.agent.manage"));
        call(reader, "POST", endpoint, Map.of("agent_id", f.agent(), "request_id", UUID.randomUUID()), 403);
        assertEquals(before, facts(f.tenant(), f.agent()));
    }

    @Test void bothActionsAreRequiredAndValidationRollsBackDraftAndPublication() throws Exception {
        var f=fixture();var before=facts(f.tenant(),f.agent());
        for(var permissions:List.of(
                List.of("foundation.agents.read","foundation.agents.update","foundation.resource.agent.read","foundation.resource.agent.manage"),
                List.of("foundation.agents.read","foundation.agents.publish","foundation.resource.agent.read","foundation.resource.agent.manage"),
                List.of("foundation.agents.read","foundation.agents.update","foundation.agents.publish","foundation.resource.agent.read"),
                List.of("foundation.agents.read","foundation.agents.update","foundation.agents.publish","foundation.resource.agent.manage"))) {
            String actor=member(f.admin(),f.slug(),"partial-"+SnowflakeIds.nextId(),permissions);
            call(actor,"POST",SAVE,command(f.agent(),UUID.randomUUID(),Map.of("name","不得部分保存")),403);
            assertEquals(before,facts(f.tenant(),f.agent()));
        }
        var request=UUID.randomUUID();
        call(f.admin(),"POST",SAVE,command(f.agent(),request,Map.of("name","应回滚名称","system_prompt","")),422);
        assertEquals(before,facts(f.tenant(),f.agent()));
        call(f.admin(),"GET",result(f.agent(),request),null,204);
        call(f.admin(),"POST",SAVE,command(f.agent(),UUID.randomUUID(),Map.of("status","draft","name","不接受状态")),422);
        assertEquals(before,facts(f.tenant(),f.agent()));
        deployment(f.tenant(),f.agent());var managed=facts(f.tenant(),f.agent());
        call(f.admin(),"POST",SAVE,command(f.agent(),UUID.randomUUID(),Map.of("name","公共部署不可覆盖")),409);
        assertEquals(managed,facts(f.tenant(),f.agent()));
    }

    @Test void sameCommandReplaysOriginalReceiptAndReadRequiresOriginalActorCurrentPermission() throws Exception {
        var f=fixture();var actor=memberActor(f,"writer-"+f.suffix(),BOTH);
        var key=UUID.randomUUID();var command=command(f.agent(),key,Map.of("name","已原子保存发布","system_prompt","原始发布提示词"));
        call(actor.token(),"GET",result(f.agent(),key),null,204);var before=facts(f.tenant(),f.agent());
        var receipt=call(actor.token(),"POST",SAVE,command,200);
        assertReceipt(receipt,f.agent(),key,1);assertEquals(receipt,call(actor.token(),"POST",SAVE,command,200));
        var first=facts(f.tenant(),f.agent());assertEquals("已原子保存发布",first.get("name"));
        assertEquals("published",first.get("status"));assertEquals(1L,((Number)first.get("publications")).longValue());
        assertFalse(before.equals(first));
        assertEquals(receipt,call(actor.token(),"GET",result(f.agent(),key),null,200));assertEquals(first,facts(f.tenant(),f.agent()));
        var conflict=call(actor.token(),"POST",SAVE,command(f.agent(),key,Map.of("name","同键异参数")),409);
        assertEquals("AGENT_SAVE_PUBLISH_REQUEST_CONFLICT",conflict.path("code").asText());
        call(f.admin(),"POST",SAVE,command,409);call(f.admin(),"GET",result(f.agent(),key),null,404);
        assertEquals(first,facts(f.tenant(),f.agent()));
        var next=call(actor.token(),"POST",SAVE,command(f.agent(),UUID.randomUUID(),Map.of("name","后续发布")),200);
        assertReceipt(next,f.agent(),null,2);var latest=facts(f.tenant(),f.agent());
        assertEquals(receipt,call(actor.token(),"POST",SAVE,command,200));
        assertEquals(receipt,call(actor.token(),"GET",result(f.agent(),key),null,200));assertEquals(latest,facts(f.tenant(),f.agent()));
        var withoutRead=List.of("foundation.agents.update","foundation.agents.publish","foundation.resource.agent.read","foundation.resource.agent.manage");
        call(f.admin(),"PUT",ACCESS+"/role",Map.of("id",actor.role().path("id").asText(),"name","撤销读取",
                "data_scope","all","permissions",withoutRead,"department_ids",List.of(),"enabled",true,
                "lock_version",actor.role().path("lock_version").asLong()),200);
        call(actor.token(),"GET",result(f.agent(),key),null,401);
        String currentToken=login(f.slug(),"writer-"+f.suffix());
        call(currentToken,"GET",result(f.agent(),key),null,403);assertEquals(latest,facts(f.tenant(),f.agent()));
    }

    @Test void twoConcurrentHttpConnectionsCommitOnePublicationAndOneReceipt() throws Exception {
        var f=fixture();var key=UUID.randomUUID();var body=command(f.agent(),key,Map.of("name","并发单次发布"));
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var one=workers.submit(()->{start.await();return call(f.admin(),"POST",SAVE,body,200);});
            var two=workers.submit(()->{start.await();return call(f.admin(),"POST",SAVE,body,200);});
            start.countDown();var receipt=one.get(45,TimeUnit.SECONDS);assertEquals(receipt,two.get(45,TimeUnit.SECONDS));
            assertReceipt(receipt,f.agent(),key,1);
        }
        assertEquals(1L,((Number)facts(f.tenant(),f.agent()).get("publications")).longValue());
        assertEquals(1L,inTenant(f.tenant(),()->jdbc.queryForObject("SELECT count(*) FROM nw_agent_publications WHERE tenant_id=? AND agent_id=? AND request_id=?",Long.class,Long.parseLong(f.tenant()),Long.parseLong(f.agent()),key)).longValue());
    }

    @Test void publicationInsertFailureRollsBackPreviouslySavedFields() throws Exception {
        var f=fixture();var before=facts(f.tenant(),f.agent());var key=UUID.randomUUID();
        String trigger="test_save_publish_fail_"+f.suffix();var owner=owner();
        owner.execute("CREATE FUNCTION public."+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $body$ BEGIN IF NEW.agent_id="+f.agent()+" THEN RAISE EXCEPTION 'synthetic save publish insertion failure'; END IF; RETURN NEW; END; $body$");
        try {
            owner.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON public.nw_agent_publications FOR EACH ROW EXECUTE FUNCTION public."+trigger+"()");
            call(f.admin(),"POST",SAVE,command(f.agent(),key,Map.of("name","插入失败必须回滚","system_prompt","不应保留")),500);
            assertEquals(before,facts(f.tenant(),f.agent()));call(f.admin(),"GET",result(f.agent(),key),null,204);
        } finally {
            owner.execute("DROP TRIGGER IF EXISTS "+trigger+" ON public.nw_agent_publications");owner.execute("DROP FUNCTION public."+trigger+"()");
        }
        var receipt=call(f.admin(),"POST",SAVE,command(f.agent(),key,Map.of("name","失败原键可重新受理")),200);
        assertReceipt(receipt,f.agent(),key,1);
    }

    @Test void committedResponseLossRecoversByReadOrOriginalCommandWithoutRepublishing() throws Exception {
        var f=fixture();var key=UUID.randomUUID();var body=command(f.agent(),key,Map.of("name","响应丢失后恢复"));
        var backendResult=new AtomicReference<JsonNode>();var failure=new AtomicReference<Throwable>();var completed=new CountDownLatch(1);
        var proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        proxy.createContext(SAVE,exchange->{
            try {
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+SAVE)).timeout(Duration.ofSeconds(30))
                        .header("Content-Type","application/json").header("Authorization","Bearer "+f.admin())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build();
                var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200,response.statusCode());backendResult.set(json.readTree(response.body()).path("data"));
                exchange.sendResponseHeaders(200,response.body().length+1L);exchange.getResponseBody().write(response.body());
                // 已得到提交成功响应，再故意少发一个已声明字节，模拟客户端响应截断。
            } catch(Throwable problem){failure.set(problem);} finally {try{exchange.close();}finally{completed.countDown();}}
        });
        proxy.start();
        try {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()+SAVE))
                    .timeout(Duration.ofSeconds(35)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            assertThrows(IOException.class,()->http.send(request,HttpResponse.BodyHandlers.ofString()));
            assertTrue(completed.await(40,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());
        } finally {proxy.stop(0);}
        assertNotNull(backendResult.get());var committed=facts(f.tenant(),f.agent());
        assertEquals(backendResult.get(),call(f.admin(),"GET",result(f.agent(),key),null,200));
        assertEquals(backendResult.get(),call(f.admin(),"POST",SAVE,body,200));assertEquals(committed,facts(f.tenant(),f.agent()));
        assertEquals(1L,((Number)committed.get("publications")).longValue());
    }

    @Test void tenantBoundaryAndIndependentRestrictedConnectionPreservePublicationRls() throws Exception {
        var f=fixture();var key=UUID.randomUUID();call(f.admin(),"POST",SAVE,command(f.agent(),key,Map.of("name","隔离发布")),200);
        var other=fixture();var before=facts(f.tenant(),f.agent());
        call(other.admin(),"POST",SAVE,command(f.agent(),key,Map.of("name","跨机构覆盖")),403);
        call(other.admin(),"GET",result(f.agent(),key),null,403);assertEquals(before,facts(f.tenant(),f.agent()));
        String role="save_publish_reader_"+f.suffix(),password=UUID.randomUUID().toString();var owner=owner();
        owner.execute("CREATE ROLE "+role+" LOGIN PASSWORD '"+password+"' NOSUPERUSER NOBYPASSRLS");
        try {
            owner.execute("GRANT USAGE ON SCHEMA public TO "+role);owner.execute("GRANT SELECT ON public.nw_agent_publications TO "+role);
            try(var connection=DriverManager.getConnection(environment.getRequiredProperty("spring.datasource.url"),role,password)) {
                connection.setAutoCommit(false);
                try(var sql=connection.createStatement();var flags=sql.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {assertTrue(flags.next());assertFalse(flags.getBoolean(1));}
                try(var bind=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {bind.setString(1,f.tenant());bind.execute();}
                try(var select=connection.prepareStatement("SELECT request_id,request_fingerprint,request_actor_ref FROM nw_agent_publications WHERE tenant_id=? AND agent_id=?")) {
                    select.setLong(1,Long.parseLong(f.tenant()));select.setLong(2,Long.parseLong(f.agent()));
                    try(var row=select.executeQuery()){assertTrue(row.next());assertEquals(key,row.getObject(1,UUID.class));assertTrue(row.getString(2).matches("[0-9a-f]{64}"));assertTrue(row.getString(3).startsWith("member:"));assertFalse(row.next());}
                    try(var bind=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")){bind.setString(1,other.tenant());bind.execute();}
                    try(var row=select.executeQuery()){assertFalse(row.next(),"other tenant cannot read new command identity columns");}
                }
                connection.rollback();
            }
        } finally {owner.execute("DROP OWNED BY "+role);owner.execute("DROP ROLE "+role);}
    }

    @Test void migrationRetainsStandalonePublicationsAndRejectsPartialHashAndDuplicateIdentity() throws Exception {
        var f=fixture();call(f.admin(),"POST",AGENTS+"/publish",Map.of("agent_id",f.agent()),200);
        assertEquals(1L,inTenant(f.tenant(),()->jdbc.queryForObject("SELECT count(*) FROM nw_agent_publications WHERE tenant_id=? AND agent_id=? AND request_id IS NULL AND request_fingerprint IS NULL AND request_actor_ref IS NULL",Long.class,Long.parseLong(f.tenant()),Long.parseLong(f.agent()))).longValue());
        var key=UUID.randomUUID();call(f.admin(),"POST",SAVE,command(f.agent(),key,Map.of("name","命令身份约束")),200);
        var before=facts(f.tenant(),f.agent());
        rejectedIdentity(f,UUID.randomUUID(),null,null);
        rejectedIdentity(f,UUID.randomUUID(),"A".repeat(64),"member:test");
        rejectedIdentity(f,key,"a".repeat(64),"member:test");
        assertEquals(before,facts(f.tenant(),f.agent()));
    }
    private void rejectedIdentity(Fixture f,UUID request,String fingerprint,String actor) {
        assertThrows(org.springframework.dao.DataAccessException.class,()->inTenant(f.tenant(),()->jdbc.update(
                "INSERT INTO nw_agent_publications(id,tenant_id,agent_id,revision,snapshot,published_at,request_id,request_fingerprint,request_actor_ref) "
                +"SELECT ?,tenant_id,agent_id,revision+1000,snapshot,published_at,?::uuid,?,? FROM nw_agent_publications WHERE tenant_id=? AND agent_id=? AND request_id IS NOT NULL",
                SnowflakeIds.nextId(),request,fingerprint,actor,Long.parseLong(f.tenant()),Long.parseLong(f.agent()))));
    }

    private record Fixture(String tenant,String slug,String admin,String agent,String suffix) { }
    private Fixture fixture() throws Exception {
        String suffix=Long.toString(SnowflakeIds.nextId()),slug="save-publish-"+suffix;
        String tenant=createTenant(tenants,plans,slug);TenantContext.setTenantId(tenant);
        String username="admin-"+suffix;createTestTenantAdministrator(tenant,username,PASSWORD);String admin=login(slug,username);
        PlatformModelFixtures.selectLlm(models,tenant,"http://127.0.0.1:1/v1","save-publication-fixture","synthetic-unused-key","custom","custom-openai-compatible","custom-v1");
        String agent=call(admin,"POST",AGENTS,Map.of("name","原始草稿","system_prompt","未执行的测试定义"),200).path("id").asText();
        return new Fixture(tenant,slug,admin,agent,suffix);
    }
    private record Member(String token,JsonNode role) { }
    private Member memberActor(Fixture f,String username,List<String> permissions) throws Exception {
        var role=call(f.admin(),"POST",ACCESS+"/role",Map.of("code",username.replace('-','_'),"name",username,"data_scope","all","permissions",permissions,"department_ids",List.of()),200);
        call(f.admin(),"POST",ACCESS+"/member",Map.of("username",username,"display_name",username,"password",PASSWORD,"role_ids",List.of(role.path("id").asText())),200);
        return new Member(login(f.slug(),username),role);
    }
    private Map<String,Object> command(String agent,UUID request,Map<String,Object> payload){return Map.of("agent_id",agent,"request_id",request.toString(),"payload",payload);}
    private String result(String agent,UUID request){return SAVE+"/result?agentId="+agent+"&requestId="+request;}
    private void assertReceipt(JsonNode receipt,String agent,UUID request,int revision) {
        if(request!=null)assertEquals(request.toString(),receipt.path("request_id").asText());
        assertEquals(agent,receipt.path("agent_id").asText());assertFalse(receipt.path("publication_id").asText().isBlank());
        assertEquals(revision,receipt.path("revision").asInt());assertFalse(receipt.path("published_at").asText().isBlank());
    }
    private JdbcTemplate owner() {
        String user=System.getenv("SKILLFORGE_S03_EXTERNAL_ADMIN_USER"),password=System.getenv("SKILLFORGE_S03_EXTERNAL_ADMIN_PASSWORD");
        if(user==null){user=environment.getRequiredProperty("spring.datasource.username");password=environment.getRequiredProperty("spring.datasource.password");}
        return new JdbcTemplate(new DriverManagerDataSource(environment.getRequiredProperty("spring.datasource.url"),user,password));
    }

    private String member(String admin, String slug, String username, List<String> permissions) throws Exception {
        String access = "/api/v1/tenant/foundation/access";
        var role = call(admin, "POST", access + "/role", Map.of("code", username.replace('-', '_'), "name", username,
                "data_scope", "all", "permissions", permissions, "department_ids", List.of()), 200);
        assertFalse(role.path("administrator").asBoolean());
        var actualPermissions = new java.util.TreeSet<String>();
        role.path("permissions").forEach(value -> actualPermissions.add(value.asText()));
        assertEquals(new java.util.TreeSet<>(permissions), actualPermissions);
        call(admin, "POST", access + "/member", Map.of("username", username, "display_name", username,
                "password", PASSWORD, "role_ids", List.of(role.path("id").asText())), 200);
        return login(slug, username);
    }

    private String login(String slug, String username) throws Exception {
        return call(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug, "username", username,
                "password", PASSWORD), 200).path("access_token").asText();
    }

    private Map<String, Object> facts(String tenant, String agent) {
        return inTenant(tenant, () -> jdbc.queryForMap("SELECT a.name,a.system_prompt,a.status,a.revision,a.published_revision_id,a.published_at,a.has_draft_changes,"
                + "(SELECT count(*) FROM nw_agent_publications p WHERE p.tenant_id=a.tenant_id AND p.agent_id=a.id) AS publications "
                + "FROM nw_agents a WHERE a.tenant_id=? AND a.id=?", Long.parseLong(tenant), Long.parseLong(agent)));
    }

    /** 复用既有部署/RLS夹具形态；拒绝测试只要求真实部署身份，不触发定义解析或执行。 */
    private void deployment(String tenant, String agent) {
        inTenant(tenant, () -> {
            long definition = SnowflakeIds.nextId(), grant = SnowflakeIds.nextId(), deployment = SnowflakeIds.nextId();
            jdbc.update("INSERT INTO nw_agent_templates(id,template_key,name,version,definition,status,published_at,distribution_policy,owner_kind) VALUES(?,?,?,'1.0.0','{}','published',now(),'AUTHORIZED_DEPLOY','platform')",
                    definition, "publication-source-" + definition, "公共源");
            jdbc.update("INSERT INTO nw_agent_definition_grants(grant_id,definition_id,target_tenant_id,actions,status,revision,actor_ref,request_key,fingerprint,created_at) VALUES(?,?,?,'deploy,execute','active',1,?,?,?,now())",
                    grant, definition, Long.parseLong(tenant), "fixture", "publication-" + grant, "a".repeat(64));
            jdbc.update("INSERT INTO nw_agent_definition_grant_revisions SELECT g.*,'fixture','create',request_key FROM nw_agent_definition_grants g WHERE grant_id=?", grant);
            String chain = json.valueToTree(new GrantChain(definition, "platform", null,
                    List.of(new GrantHop(grant, 1, "platform", null, Long.parseLong(tenant), null, null, null, null)))).toString();
            String bindings = json.valueToTree(new LocalBindings(List.of(), List.of(), Map.of())).toString();
            jdbc.update("INSERT INTO nw_agent_deployments(deployment_id,tenant_id,agent_id,definition_id,grant_id,grant_revision,external_agent_ref,fingerprint,bindings,revision,created_at,grant_chain) VALUES(?,?,?,?,?,1,?,?,?::jsonb,1,now(),?::jsonb)",
                    deployment, Long.parseLong(tenant), Long.parseLong(agent), definition, grant, "publication-" + agent,
                    "b".repeat(64), bindings, chain);
            return null;
        });
    }

    private <T> T inTenant(String tenant, Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, tenant);
            return action.get();
        });
    }

    private JsonNode call(String token, String method, String path, Object body, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), method + " " + path);
        if (expected == 204) return json.nullNode();
        var envelope = json.readTree(response.body());
        return expected >= 400 ? envelope : envelope.path("data");
    }
}

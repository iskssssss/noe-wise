package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.model.PlatformModelCatalogService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP login/Permission/tenant/object boundary with parent schema03 PG/Redis; no ES or provider calls. */
@org.springframework.context.annotation.Import(KnowledgeEmbeddingFixture.Configuration.class)
class KnowledgeStorageHttpBoundaryIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private TenantMemberMapper members;
    @Autowired private PasswordEncoder passwords;
    @Autowired private KnowledgeService knowledge;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @LocalServerPort private int port;
    private static final String FIXTURE_PASSWORD="ControlledLocal123";
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void anonymousAndMissingPermissionCannotChangeBudgetAndAdministratorCan() throws Exception {
        ensureFreePlan(plans);
        String slug="k5-http-"+SnowflakeIds.nextId();
        String tenant=createTenant(tenants,plans,slug);
        TenantContext.setTenantId(tenant);
        createTestTenantAdministrator(tenant,"governance-admin",FIXTURE_PASSWORD);
        createUnprivilegedMember(tenant,"governance-no-permission");
        TenantContext.clear();
        String admin=login(slug,"governance-admin");
        String denied=login(slug,"governance-no-permission");
        Map<String,Object> budget=Map.of("observation_only",false,"max_content_bytes",10000L,"max_chunk_bodies",20L);
        var before=budgets(tenant);
        assertEquals(401,post("/api/v1/knowledge/storage/budget",null,budget).statusCode());
        assertEquals(403,post("/api/v1/knowledge/storage/budget",denied,budget).statusCode());
        assertEquals(before,budgets(tenant));
        assertEquals(200,post("/api/v1/knowledge/storage/budget",admin,budget).statusCode());
        var stored=budgets(tenant);
        assertEquals(1,stored.size());
        assertEquals(10000L,((Number)stored.getFirst().get("max_content_bytes")).longValue());
        assertEquals(20L,((Number)stored.getFirst().get("max_chunk_bodies")).longValue());
        assertEquals(200,get("/api/v1/knowledge/storage/capacity",admin).statusCode());
        assertEquals(401,get("/api/v1/knowledge/storage/capacity",null).statusCode());
        assertEquals(400,post("/api/v1/knowledge/storage/budget",admin,
                Map.of("observation_only",true,"tenant_id","999999")).statusCode());
        assertEquals(stored,budgets(tenant));
    }

    @Test void recycleRequiresActualObjectManageAndRejectsForeignBaseWithoutMutation() throws Exception {
        ensureFreePlan(plans);
        String slug="k5-recycle-http-"+SnowflakeIds.nextId();
        String tenant=createTenant(tenants,plans,slug);
        TenantContext.setTenantId(tenant);
        createTestTenantAdministrator(tenant,"recycle-admin",FIXTURE_PASSWORD);
        createUnprivilegedMember(tenant,"recycle-no-permission");
        KnowledgeEmbeddingFixture.select(models,tenant,"http://127.0.0.1:9");
        var own=knowledge.createBase(tenant,"Controlled HTTP governance",null);
        String foreign=createTenant(tenants,plans,"k5-http-foreign-"+SnowflakeIds.nextId());
        TenantContext.setTenantId(foreign);
        KnowledgeEmbeddingFixture.select(models,foreign,"http://127.0.0.1:9");
        var foreignBase=knowledge.createBase(foreign,"Foreign controlled fixture",null);
        TenantContext.clear();
        String admin=login(slug,"recycle-admin"),denied=login(slug,"recycle-no-permission");
        var ownBefore=versions(tenant);
        var foreignBefore=versions(foreign);
        var command=Map.of("kb_id",own.id(),"before",Instant.now().minusSeconds(1).toString(),"limit",10,"dry_run",true);
        assertEquals(401,post("/api/v1/knowledge/storage/recycle",null,command).statusCode());
        assertEquals(403,post("/api/v1/knowledge/storage/recycle",denied,command).statusCode());
        assertEquals(200,post("/api/v1/knowledge/storage/recycle",admin,command).statusCode());
        assertEquals(403,post("/api/v1/knowledge/storage/recycle",admin,
                Map.of("kb_id",foreignBase.id(),"before",Instant.now().minusSeconds(1).toString(),"limit",10,"dry_run",false)).statusCode());
        assertEquals(ownBefore,versions(tenant));assertEquals(foreignBefore,versions(foreign));
    }
    private void createUnprivilegedMember(String tenant,String username) {
        var member=new TenantMemberDO();member.setId(SnowflakeIds.nextId());member.setTenantId(Long.parseLong(tenant));
        member.setUsername(username);member.setRole("developer");member.setPermissions(List.of());member.setStatus("active");
        member.setPasswordHash(passwords.encode(FIXTURE_PASSWORD));member.setCreatedAt(Instant.now());members.insert(member);
        // Legacy role text is not permission evidence; only actual HTTP403 is asserted.
    }
    private String login(String slug,String username) throws Exception {
        var login=post("/api/v1/auth/login",null,Map.of("tenant_slug",slug,"username",username,"password",FIXTURE_PASSWORD));
        assertEquals(200,login.statusCode());String token=json.readTree(login.body()).at("/data/access_token").asText();
        assertFalse(token.isBlank());return token;
    }
    private List<Map<String,Object>> budgets(String tenant) {
        return new TransactionTemplate(transactions).execute(s->queryRows(jdbc,tenant,
                "SELECT tenant_id,max_content_bytes,max_chunk_bodies FROM nw_knowledge_storage_budgets WHERE tenant_id=?",Long.parseLong(tenant)));
    }
    private List<Map<String,Object>> versions(String tenant) {
        return new TransactionTemplate(transactions).execute(s->queryRows(jdbc,tenant,
                "SELECT id,source_version,content_id,chunk_set_id FROM nw_knowledge_document_versions WHERE tenant_id=? ORDER BY id",Long.parseLong(tenant)));
    }
    private HttpResponse<String> post(String path,String token,Map<String,?> body) throws Exception {
        var request=request(path,token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path,String token) throws Exception {
        return HttpClient.newHttpClient().send(request(path,token).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder request(String path,String token) {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)builder.header("Authorization","Bearer "+token);return builder;
    }
}

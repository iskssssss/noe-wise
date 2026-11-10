package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantChain;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantHop;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.LocalBindings;
import com.skillforge.core.agent.model.AgentDistributionPolicy;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实登录、Foundation授权与PostgreSQL发布事实；模型仅登记元数据，不执行。 */
@Import(DeterministicModelCatalogConfiguration.class)
class AgentPublicationHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD = "SyntheticAgentPublication123";
    private static final String AGENTS = "/api/v1/agents";
    private final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort private int port;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private AgentTemplateService templates;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void patchCannotPublishAndDedicatedPublicationRetainsCurrentPermissionAndDefinitionBoundary() throws Exception {
        String suffix = Long.toString(SnowflakeIds.nextId());
        String slug = "agent-publication-" + suffix;
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String username = "admin-" + suffix;
        createTestTenantAdministrator(tenant, username, PASSWORD);
        String administrator = login(slug, username);
        String modelId = PlatformModelFixtures.selectLlm(models, tenant, "http://127.0.0.1:1/v1", "publication-fixture",
                "synthetic-unused-key", "custom", "custom-openai-compatible", "custom-v1");
        String editor = member(administrator, slug, "editor-" + suffix,
                List.of("foundation.agents.read", "foundation.agents.update",
                        "foundation.resource.agent.read", "foundation.resource.agent.manage"));
        String publisher = member(administrator, slug, "publisher-" + suffix,
                List.of("foundation.agents.read", "foundation.agents.publish",
                        "foundation.resource.agent.read", "foundation.resource.agent.manage"));
        var created = call(administrator, "POST", AGENTS, Map.of("name", "原始草稿", "system_prompt", "发布边界验证"), 200);
        String agent = created.path("id").asText();
        assertFalse(created.get("definition_managed").booleanValue());
        var original = facts(tenant, agent);
        assertFalse(call(editor, "PATCH", AGENTS + "/update", Map.of("agent_id", agent, "payload", Map.of("status", "draft")), 200)
                .get("definition_managed").booleanValue());
        assertEquals(original, facts(tenant, agent));

        call(editor, "PATCH", AGENTS + "/update", Map.of("agent_id", agent,
                "payload", Map.of("status", "published", "name", "禁止写入的名称", "system_prompt", "禁止写入的提示词")), 422);
        assertEquals(original, facts(tenant, agent), "拒绝发布不能写入同请求的配置、快照或发布指针");
        call(editor, "POST", AGENTS + "/publish", Map.of("agent_id", agent), 403);
        assertEquals(original, facts(tenant, agent));

        call(editor, "PATCH", AGENTS + "/update", Map.of("agent_id", agent, "payload", Map.of("name", "已保存草稿")), 200);
        var saved = facts(tenant, agent);
        assertEquals("已保存草稿", saved.get("name"));
        assertEquals(original.get("published_revision_id"), saved.get("published_revision_id"));
        assertEquals(original.get("publications"), saved.get("publications"));
        call(publisher, "POST", AGENTS + "/publish", Map.of("agent_id", agent), 200);
        var published = facts(tenant, agent);
        assertEquals("published", published.get("status"));
        assertNotNull(published.get("published_revision_id"));
        assertEquals(1L, ((Number) published.get("publications")).longValue());

        call(editor, "PATCH", AGENTS + "/update", Map.of("agent_id", agent, "payload", Map.of("status", "disabled")), 200);
        assertEquals("disabled", facts(tenant, agent).get("status"));
        call(editor, "PATCH", AGENTS + "/update", Map.of("agent_id", agent, "payload", Map.of("status", "published")), 422);
        call(publisher, "POST", AGENTS + "/publish", Map.of("agent_id", agent), 200);
        assertEquals("published", facts(tenant, agent).get("status"));
        call(editor, "PATCH", AGENTS + "/update", Map.of("agent_id", agent, "payload", Map.of("status", "archived")), 200);
        assertEquals("archived", facts(tenant, agent).get("status"));

        String managed = call(administrator, "POST", AGENTS, Map.of("name", "公共部署实例", "system_prompt", "源定义"), 200)
                .path("id").asText();
        deployment(tenant, managed);
        assertTrue(call(publisher, "GET", AGENTS + "/detail?agentId=" + managed, Map.of(), 200)
                .get("definition_managed").booleanValue());
        assertTrue(call(editor, "PATCH", AGENTS + "/update", Map.of("agent_id", managed, "payload", Map.of("status", "draft")), 200)
                .get("definition_managed").booleanValue());
        var managedBefore = facts(tenant, managed);
        assertEquals("AGENT_DEFINITION_MANAGED", call(publisher, "POST", AGENTS + "/publish",
                Map.of("agent_id", managed), 409).path("code").asText());
        assertEquals(managedBefore, facts(tenant, managed), "公共部署不能生成普通Agent发布快照");
        call(editor, "PATCH", AGENTS + "/update", Map.of("agent_id", managed,
                "payload", Map.of("status", "published")), 422);
        assertEquals(managedBefore, facts(tenant, managed));

        AgentTemplateDO template = new AgentTemplateDO();
        template.setId(SnowflakeIds.nextId()); template.setTemplateKey("publication-library-" + template.getId());
        template.setName("独立安装来源"); template.setVersion("1.0.0"); template.setCategory("test");
        template.setTags(List.of()); template.setDescription("definition ownership");
        template.setAuthor("test"); template.setAuthorType("platform"); template.setOwnerKind("platform");
        template.setDistributionPolicy(AgentDistributionPolicy.OPEN_INSTALL);
        template.setDefinition(new AgentTemplateDefinition("独立安装提示词", "独立角色", modelId, null, List.of(), List.of(), List.of()));
        template.setPriceType("free"); template.setPriceCents(0); template.setInstallCount(0);
        template.setStatus("published"); template.setPublishedAt(Instant.now());
        template.setCreatedAt(Instant.now()); template.setUpdatedAt(Instant.now());
        templates.save(template); templates.appendPublication(template);
        var installed = call(administrator, "POST", AGENTS + "/install-template", Map.of(
                "template_id", template.getId(), "request_id", "library-" + suffix, "name", "独立安装实例"), 200).path("agent");
        String independent = installed.path("id").asText();
        assertEquals(template.getId().toString(), installed.path("template_id").asText());
        assertFalse(installed.get("definition_managed").booleanValue());
        assertFalse(call(publisher, "GET", AGENTS + "/detail?agentId=" + independent, Map.of(), 200)
                .get("definition_managed").booleanValue());
        var definitionOwners = new HashMap<String, Boolean>();
        for (var item : call(publisher, "GET", AGENTS, Map.of(), 200).path("items"))
            definitionOwners.put(item.path("id").asText(), item.get("definition_managed").booleanValue());
        assertEquals(Boolean.FALSE, definitionOwners.get(agent));
        assertEquals(Boolean.FALSE, definitionOwners.get(independent));
        assertEquals(Boolean.TRUE, definitionOwners.get(managed));
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
        var response = http.send(request.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), method + " " + path);
        var envelope = json.readTree(response.body());
        return expected >= 400 ? envelope : envelope.path("data");
    }
}

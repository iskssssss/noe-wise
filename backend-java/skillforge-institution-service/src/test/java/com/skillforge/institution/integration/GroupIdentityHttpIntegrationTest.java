package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.institution.security.HmacSignatureVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** GI-02正式HTTP组合：群身份、对象修订、固定执行域、关闭重建及进程重启原回执。 */
@Import(DeterministicModelCatalogConfiguration.class)
class GroupIdentityHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String GROUPS = "/api/v1/open-platform/groups";
    private static final String USERNAME = "group-admin", PASSWORD = "GroupFixture1234";
    private static final List<String> SCOPES = List.of("groups.write", "groups.read", "groups.members.write",
            "groups.agents.write", "business.scopes.write", "end_users.write", "sessions.write", "sessions.read");
    private static final Pattern LISTENING = Pattern.compile("Tomcat started on port (\\d+)");
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private SkillForgeProperties properties;
    @Autowired private org.springframework.core.env.Environment environment;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Host> ownedHosts = new ArrayList<>();
    private Host platformHost, institutionHost;
    private String platformToken;
    private Path evidence;
    private String tenantDatabaseUser, platformDatabaseUser, hostDatabasePassword;

    /** 只登记不可连接loopback模型目录；没有Run、模型调用或外部业务副作用。 */
    @DynamicPropertySource static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "false");
    }

    /** 同一私有PG贯穿真实API与重启，失败时仍停止并等待全部owned宿主。 */
    @Test @Timeout(480)
    void groupsKeepIdentityAndCurrentQualificationsAcrossVersionChangesRotationAndRestart() throws Exception {
        String marker = "groups-" + SnowflakeIds.nextId();
        evidence = repositoryRoot().resolve("backend-java/skillforge-institution-service/build/reports/group-identity/" + marker);
        Files.createDirectories(evidence);
        Throwable failure = null;
        try {
            prepareDatabaseRoles(); startHosts("first");
            Account owner = account(marker, "owner"), foreign = account(marker, "foreign");
            String integration = association(owner, marker, "first"), otherIntegration = association(owner, marker, "second");
            JsonNode credential = credential(owner, integration, SCOPES, marker + ":credential");
            JsonNode other = credential(owner, otherIntegration, SCOPES, marker + ":other-credential");
            String foreignIntegration = association(foreign, marker, "foreign");
            JsonNode foreignCredential = credential(foreign, foreignIntegration, SCOPES, marker + ":foreign-credential");
            JsonNode readOnly = credential(owner, integration, List.of("groups.read"), marker + ":read-only");
            JsonNode writeOnly = credential(owner, integration, List.of("groups.write"), marker + ":write-only");
            JsonNode scope = scope(credential, "clinic", marker + ":scope");
            JsonNode otherScope = scope(other, "other-clinic", marker + ":other-scope");
            JsonNode user = user(credential, "member-1"), secondUser = user(credential, "member-2");
            JsonNode otherUser = user(other, "member-1");
            String model = model(owner, marker);
            String nativeAgent = nativeAgent(owner, model, marker + ":native");
            String foreignAgent = nativeAgent(foreign, model, marker + ":foreign-agent");
            JsonNode source = sharedSource(model, marker);
            JsonNode grant = platform("POST", "/api/v1/platform/agent-grants", Map.of(
                    "definition_id", source.path("definition_id").asText(), "target_tenant_id", owner.id(),
                    "actions", List.of("deploy", "execute"), "allow_regrant", false, "remaining_depth", 0), marker + ":grant", 200);
            JsonNode managed = managedAgent(owner, source, grant, null, marker + ":managed");
            JsonNode fixed = managedAgent(owner, source, grant, otherIntegration, marker + ":fixed");
            Map<String, Object> ensure = ensure(marker, "lifecycle-1", scope.path("business_scope_id").asText());
            JsonNode group = machine(credential, "POST", GROUPS + "/ensure", ensure, marker + ":ensure", 200);
            String groupId = group.path("group_binding_id").asText(), session = group.path("session_id").asText();
            assertThat(groupId).matches("[1-9][0-9]*"); assertThat(session).isNotBlank();
            assertThat(group.path("subject").path("kind").asText()).isEqualTo("group");
            assertThat(group.path("subject").path("group_binding_id").asText()).isEqualTo(groupId);
            assertThat(group.has("end_user_id")).isFalse(); assertThat(group.has("agent_id")).isFalse();
            assertThat(machine(credential, "POST", GROUPS + "/ensure", ensure, marker + ":ensure", 200)).isEqualTo(group);
            var different = new LinkedHashMap<>(ensure); different.put("external_group_ref", "different");
            rejects(credential, "POST", GROUPS + "/ensure", different, marker + ":ensure", 409);
            rejects(readOnly, "POST", GROUPS + "/ensure", ensure, marker + ":read-cannot-create", 403);
            rejects(writeOnly, "GET", detailPath(group), null, null, 403);
            rejects(other, "GET", detailPath(group), null, null, 403);
            rejects(foreignCredential, "GET", detailPath(group), null, null, 403);
            rejects(credential, "POST", GROUPS + "/ensure", ensure(marker, "competing-lifecycle", scope.path("business_scope_id").asText()), marker + ":active-lifecycle-conflict", 409);
            rejects(credential, "POST", GROUPS + "/ensure", ensure(marker + "-wrong", "lifecycle-1", otherScope.path("business_scope_id").asText()), marker + ":wrong-scope", 403);

            Map<String, Object> join = member(groupId, user, "manager", "active", 0, "join", 10);
            JsonNode joined = machine(credential, "POST", GROUPS + "/members/update", join, marker + ":join", 200);
            assertThat(joined.path("revision").asLong()).isEqualTo(1);
            rejects(writeOnly, "POST", GROUPS + "/members/update", join, marker + ":wrong-member-scope", 403);
            rejects(credential, "POST", GROUPS + "/members/update", member(groupId, otherUser, "member", "active", 0, "foreign-user", 1), marker + ":wrong-user", 404);
            Map<String, Object> leave = member(groupId, user, "member", "left", 1, "leave", 20);
            JsonNode left = machine(credential, "POST", GROUPS + "/members/update", leave, marker + ":leave", 200);
            assertThat(left.path("revision").asLong()).isEqualTo(2);
            assertThat(machine(credential, "POST", GROUPS + "/members/update", join, marker + ":join", 200)).isEqualTo(joined);
            var replaySource = new LinkedHashMap<>(join); replaySource.put("expected_revision", 2);
            assertThat(machine(credential, "POST", GROUPS + "/members/update", replaySource, marker + ":join-another-key", 200)).isEqualTo(joined);
            var changedSource = new LinkedHashMap<>(replaySource); changedSource.put("role", "member");
            rejects(credential, "POST", GROUPS + "/members/update", changedSource, marker + ":source-body-conflict", 409);
            rejects(credential, "POST", GROUPS + "/members/update", member(groupId, user, "member", "active", 2, "delayed-join", 15), marker + ":stale-source", 409);
            rejects(credential, "POST", GROUPS + "/members/update", member(groupId, user, "member", "active", 1, "wrong-cas", 30), marker + ":stale-cas", 409);
            Map<String, Object> rejoin = member(groupId, user, "member", "active", 2, "rejoin", 30);
            JsonNode rejoined = machine(credential, "POST", GROUPS + "/members/update", rejoin, marker + ":rejoin", 200);
            machine(credential, "POST", GROUPS + "/members/update", member(groupId, secondUser, "member", "active", 0, "second-join", 1), marker + ":second-join", 200);

            Map<String, Object> nativeService = agent(groupId, nativeAgent, "active", 0, "native-service", 1);
            JsonNode nativeJoined = machine(credential, "POST", GROUPS + "/agents/update", nativeService, marker + ":native-service", 200);
            String managedId = managed.path("agent_id").asText();
            machine(credential, "POST", GROUPS + "/agents/update", agent(groupId, managedId, "active", 0, "managed-service", 1), marker + ":managed-service", 200);
            rejects(writeOnly, "POST", GROUPS + "/agents/update", nativeService, marker + ":wrong-agent-scope", 403);
            rejects(credential, "POST", GROUPS + "/agents/update", agent(groupId, fixed.path("agent_id").asText(), "active", 0, "fixed-service", 1), marker + ":wrong-agent-integration", 403);
            rejects(credential, "POST", GROUPS + "/agents/update", agent(groupId, foreignAgent, "active", 0, "foreign-service", 1), marker + ":wrong-agent-tenant", 404);
            tenant(owner, "PATCH", "/api/v1/agents/update", Map.of("agent_id", nativeAgent, "payload", Map.of("status", "disabled")), null, 200);
            JsonNode removed = machine(credential, "POST", GROUPS + "/agents/update", agent(groupId, nativeAgent, "removed", 1, "remove-native", 2), marker + ":remove-native", 200);
            assertThat(machine(credential, "POST", GROUPS + "/agents/update", nativeService, marker + ":native-service", 200)).isEqualTo(nativeJoined);
            rejects(credential, "POST", GROUPS + "/agents/update", agent(groupId, nativeAgent, "active", 2, "stale-native", 1), marker + ":stale-native", 409);
            assertThat(removed.path("status").asText()).isEqualTo("removed");
            assertThat(machine(credential, "GET", detailPath(group), null, null, 200).path("session_id").asText()).isEqualTo(session);
            // 正式目录按可信集成与稳定身份分页，读取权不附赠成员或服务Agent写权。
            for(String kind:List.of("members","agents")) {
                String path=GROUPS+"/"+kind+"?group_binding_id="+groupId+"&limit=1";
                JsonNode first=machine(readOnly,"GET",path,null,null,200);
                assertThat(first.path("items")).hasSize(1);assertThat(first.path("has_more").asBoolean()).isTrue();
                JsonNode last=machine(readOnly,"GET",path+"&after_id="+first.path("next_after").asText(),null,null,200);
                assertThat(last.path("items")).hasSize(1);assertThat(last.path("has_more").asBoolean()).isFalse();
                assertThat(last.path("next_after").isNull()).isTrue();
                rejects(writeOnly,"GET",path,null,null,403);rejects(other,"GET",path,null,null,403);
                rejects(foreignCredential,"GET",path,null,null,403);
                rejects(credential,"GET",path+"&after_id=1e3",null,null,400);
            }
            platform("POST", "/api/v1/platform/agent-grants/revoke", Map.of("grant_id", grant.path("grant_id").asText(), "expected_revision", 1), marker + ":revoke-source", 200);
            JsonNode managedRemoved = machine(credential, "POST", GROUPS + "/agents/update", agent(groupId, managedId, "removed", 1, "remove-revoked", 2), marker + ":remove-revoked", 200);
            assertThat(managedRemoved.path("status").asText()).isEqualTo("removed");
            rejects(credential, "POST", GROUPS + "/agents/update", agent(groupId, managedId, "active", 2, "restore-revoked", 3), marker + ":restore-revoked", 403);
            rejectsPersonalSessionUse(credential, group, user, nativeAgent, marker);

            JsonNode rotated = platform("POST", "/api/v1/platform/institution-credentials/rotate", Map.of(
                    "tenant_id", owner.id(), "integration_id", integration, "principal_id", credential.path("principal").path("id").asText(),
                    "expected_revision", credential.path("principal").path("credential_version").asLong()), marker + ":rotate", 200);
            JsonNode effective = rotated;
            rejects(credential, "GET", detailPath(group), null, null, 401);
            assertThat(machine(effective, "POST", GROUPS + "/ensure", ensure, marker + ":ensure", 200)).isEqualTo(group);
            assertThat(machine(effective, "POST", GROUPS + "/members/update", rejoin, marker + ":rejoin", 200)).isEqualTo(rejoined);
            Map<String, Object> close = Map.of("group_binding_id", groupId, "expected_revision", 1);
            JsonNode closed = machine(effective, "POST", GROUPS + "/close", close, marker + ":close", 200);
            assertThat(closed.path("status").asText()).isEqualTo("closed");
            var closedFacts = groupFacts();
            JsonNode sameClosed = machine(effective, "POST", GROUPS + "/ensure", ensure, marker + ":no-resurrection", 200);
            assertThat(sameClosed.path("status").asText()).isEqualTo("closed");
            assertThat(sameClosed.path("session_id").asText()).isEqualTo(session);
            assertThat(groupFacts()).isEqualTo(closedFacts);
            rejects(effective, "POST", GROUPS + "/members/update", member(groupId, user, "member", "active", 3, "closed-join", 40), marker + ":closed-join", 409);
            Map<String, Object> nextLifecycle = ensure(marker, "lifecycle-2", scope.path("business_scope_id").asText());
            JsonNode replacement = machine(effective, "POST", GROUPS + "/ensure", nextLifecycle, marker + ":replacement", 200);
            assertThat(replacement.path("group_binding_id").asText()).isNotEqualTo(groupId);
            assertThat(replacement.path("session_id").asText()).isNotEqualTo(session);
            long replacementId = replacement.path("group_binding_id").asLong();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_group_memberships WHERE group_binding_id=?", Long.class, replacementId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM nw_group_service_agents WHERE group_binding_id=?", Long.class, replacementId)).isZero();

            stopHosts(); startHosts("restart");
            assertThat(machine(effective, "POST", GROUPS + "/close", close, marker + ":close", 200)).isEqualTo(closed);
            assertThat(machine(effective, "GET", detailPath(group), null, null, 200).path("status").asText()).isEqualTo("closed");
            assertThat(machine(effective, "POST", GROUPS + "/ensure", nextLifecycle, marker + ":replacement", 200)).isEqualTo(replacement);
            assertThat(machine(effective, "POST", GROUPS + "/members/update", rejoin, marker + ":rejoin", 200)).isEqualTo(rejoined);
            assertRls(owner.id(), foreign.id(), groupId);
        } catch (Exception | Error problem) { failure = problem; throw problem; }
        finally {
            RequestContext.clear(); TenantContext.clear();
            try { stopHosts(); } catch (Exception | Error cleanup) { if (failure != null) failure.addSuppressed(cleanup); else throw cleanup; }
        }
    }

    /** 群写入拒绝前后核对业务分母，鉴权和失败审计不算非法业务事实。 */
    private void rejects(JsonNode credential, String method, String path, Object command, String key, int expected) throws Exception {
        var before = groupFacts(); machine(credential, method, path, command, key, expected);
        assertThat(groupFacts()).isEqualTo(before);
    }

    /** 群维护本批不创建Run；个人输入路径不能接管群Session。 */
    private void rejectsPersonalSessionUse(JsonNode credential, JsonNode group, JsonNode user, String agentId, String marker) throws Exception {
        var before = groupFacts();
        Reply reply = signed(credential, "POST", "/api/v1/open-platform/sessions/messages", Map.of(
                "session_id", group.path("session_id").asText(), "payload", Map.of("content", List.of(Map.of("type", "text", "text", "群不走个人入口")))), marker + ":personal-input");
        assertThat(reply.status()).isEqualTo(403);
        machine(credential, "POST", "/api/v1/open-platform/sessions", Map.of("session_id", group.path("session_id").asText(),
                "external_user_ref", user.path("external_user_ref").asText(), "external_conversation_ref", marker + ":personal",
                "end_user_id", user.path("end_user_id").asText(), "agent_id", agentId), null, 403);
        machine(credential, "GET", "/api/v1/open-platform/sessions/history?sessionId=" + group.path("session_id").asText(), null, null, 403);
        assertThat(groupFacts()).isEqualTo(before);
    }

    /** 只读真实持久事实，不把HTTP拒绝或未启动worker单独当作无副作用证据。 */
    private Map<String, Object> groupFacts() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM nw_group_bindings) AS groups,
                  (SELECT coalesce(sum(revision),0) FROM nw_group_bindings) AS group_revisions,
                  (SELECT count(*) FROM nw_group_memberships) AS members,
                  (SELECT coalesce(sum(revision),0) FROM nw_group_memberships) AS member_revisions,
                  (SELECT count(*) FROM nw_group_service_agents) AS agents,
                  (SELECT coalesce(sum(revision),0) FROM nw_group_service_agents) AS agent_revisions,
                  (SELECT count(*) FROM nw_chat_sessions) AS sessions,
                  (SELECT count(*) FROM agent_runtime_execution_runs) AS runs,
                  (SELECT count(*) FROM agent_runtime_execution_attempts) AS attempts
                """);
    }

    /** 原表真实启用行安全且受限角色不能通过另一Tenant读取已知群。 */
    private void assertRls(String tenant, String foreignTenant, String groupId) {
        var restricted = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"), tenantDatabaseUser, hostDatabasePassword));
        restricted.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement(); var result = statement.executeQuery(
                        "SELECT row_security_active('group_bindings'),NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                    assertThat(result.next()).isTrue(); assertThat(result.getBoolean(1)).isTrue(); assertThat(result.getBoolean(2)).isTrue();
                }
                try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) { bind.setString(1, tenant); bind.execute(); }
                try (var query = connection.prepareStatement("SELECT count(*) FROM nw_group_bindings WHERE id=?")) {
                    query.setLong(1, Long.parseLong(groupId)); try (var result = query.executeQuery()) { result.next(); assertThat(result.getLong(1)).isEqualTo(1); }
                }
                try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) { bind.setString(1, foreignTenant); bind.execute(); }
                try (var query = connection.prepareStatement("SELECT count(*) FROM nw_group_bindings WHERE id=?")) {
                    query.setLong(1, Long.parseLong(groupId)); try (var result = query.executeQuery()) { result.next(); assertThat(result.getLong(1)).isZero(); }
                }
            } finally { connection.rollback(); connection.setAutoCommit(true); }
            return null;
        });
    }

    /** 首次稳定键明确包含来源生命周期与已登记业务范围。 */
    private Map<String, Object> ensure(String external, String lifecycle, String scope) {
        return Map.of("namespace", "group-fixture", "external_group_ref", external, "lifecycle_ref", lifecycle, "business_scope_id", scope);
    }
    /** CAS与来源顺序独立；退出不改变EndUser或共享Session身份。 */
    private Map<String, Object> member(String group, JsonNode user, String role, String status, long expected, String event, long source) {
        return Map.of("group_binding_id", group, "end_user_id", user.path("end_user_id").asText(), "role", role,
                "status", status, "expected_revision", expected, "source_event_id", event, "source_revision", source);
    }
    /** 服务名单只表达当前群资格，不冻结新Run或准备公共工具。 */
    private Map<String, Object> agent(String group, String agent, String status, long expected, String event, long source) {
        return Map.of("group_binding_id", group, "agent_id", agent, "status", status,
                "expected_revision", expected, "source_event_id", event, "source_revision", source);
    }
    private String detailPath(JsonNode group) { return GROUPS + "/detail?group_binding_id=" + group.path("group_binding_id").asText(); }
    private JsonNode scope(JsonNode credential, String external, String key) throws Exception {
        return machine(credential, "POST", "/api/v1/open-platform/business-scopes/ensure", Map.of(
                "scope_type", "store", "external_ref", external, "display_name", external), key, 200);
    }
    private JsonNode user(JsonNode credential, String external) throws Exception {
        return machine(credential, "POST", "/api/v1/open-platform/end-users/ensure", Map.of("external_user_ref", external, "display_name", external), null, 200);
    }
    /** 外部integration由正式平台关联建立，不在群请求中创建或切换。 */
    private String association(Account tenant, String namespace, String external) throws Exception {
        return platform("POST", "/api/v1/platform/institutions/associate", Map.of("tenant_id", tenant.id(),
                "namespace", namespace, "external_institution_ref", external), namespace + ":associate:" + external, 200).path("integration_id").asText();
    }
    private JsonNode credential(Account tenant, String integration, List<String> scopes, String key) throws Exception {
        return platform("POST", "/api/v1/platform/institution-credentials/issue", Map.of("tenant_id", tenant.id(), "integration_id", integration, "scopes", scopes), key, 200);
    }
    /** 原生机构Agent经真实创建和发布，不借群服务名单产生源grant。 */
    private String nativeAgent(Account tenant, String model, String key) throws Exception {
        JsonNode created = tenant(tenant, "POST", "/api/v1/agents", Map.of("agent_key", key, "name", key,
                "system_prompt", "本地机构助手", "model_config_id", model, "config", Map.of()), null, 200);
        String id = created.path("id").asText();
        tenant(tenant, "POST", "/api/v1/agents/publish", Map.of("agent_id", id), null, 200); return id;
    }
    /** 公共源只登记可运行发布元数据；没有模型或业务工具调用。 */
    private JsonNode sharedSource(String model, String marker) throws Exception {
        JsonNode created = platform("POST", "/api/v1/platform/agent-definitions", Map.of("name", marker + ":source", "price_type", "free",
                "price_cents", 0, "distribution_policy", "AUTHORIZED_DEPLOY", "definition", Map.of("system_prompt", "公共机构助手",
                        "role_description", "群服务角色", "model_config_id", model, "execution_config", Map.of(), "capabilities", List.of(),
                        "resource_slots", List.of(), "delegation_slots", List.of())), marker + ":source", 200);
        return platform("POST", "/api/v1/platform/agent-definitions/publish", Map.of("definition_id", created.path("definition_id").asText(),
                "expected_revision", created.path("draft_revision").asLong()), marker + ":publish", 200);
    }
    private JsonNode managedAgent(Account tenant, JsonNode source, JsonNode grant, String integration, String key) throws Exception {
        var command = new LinkedHashMap<String, Object>(); command.put("definition_id", source.path("definition_id").asText());
        command.put("grant_id", grant.path("grant_id").asText()); command.put("integration_id", integration); command.put("external_agent_ref", key);
        command.put("resource_bindings", List.of()); command.put("delegation_bindings", List.of()); command.put("parameters", Map.of());
        return tenant(tenant, "POST", "/api/v1/tenant/agent-deployments", command, key, 200);
    }
    /** 每次重试使用当前秘密签实际方法、完整请求目标和相同业务正文。 */
    private JsonNode machine(JsonNode credential, String method, String path, Object value, String key, int expected) throws Exception {
        return success(signed(credential, method, path, value, key), method, path, expected);
    }
    private Reply signed(JsonNode credential, String method, String path, Object value, String key) throws Exception {
        String body = value == null ? "" : json.writeValueAsString(value), timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = HmacSignatureVerifier.sign(credential.path("signing_secret").asText(), HmacSignatureVerifier.canonical(timestamp, method, path, body));
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + institutionHost.port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-Api-Key", credential.path("api_key").asText()).header("X-Timestamp", timestamp).header("X-Signature", signature);
        if (key != null) builder.header("Idempotency-Key", key);
        var response = http.send(builder.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }

    /** 创建free机构与本地管理员，完整八项quota由正式当前覆盖读取后只改本fixture所需数量。 */
    private Account account(String marker,String name)throws Exception {
        String slug=marker+"-"+name;
        JsonNode created=platform("POST","/api/v1/admin/tenants",Map.of("tenant_name",name,"tenant_slug",slug,"plan_code","free",
                "admin_username",USERNAME,"admin_password",PASSWORD,"admin_email",name+"@example.test"),null,201);
        Account account=login(new Account(created.path("id").asText(),slug,null));
        JsonNode quota=platform("GET","/api/v1/admin/foundation/catalog/tenant-entitlements?tenant_id="+account.id(),null,null,200);
        ObjectNode overrides=((ObjectNode)quota.path("overrides")).deepCopy();
        assertThat(overrides.size()).isEqualTo(8);overrides.put("agents",8);overrides.put("sub_institutions",8);
        platform("PUT","/api/v1/admin/foundation/catalog/tenant-quota",Map.of("tenant_id",account.id(),"limits",overrides,
                "expected_revision",quota.path("revision").asLong()),null,200);
        return account;
    }

    /** 原生登录重新取得安全会话，不将平台JWT或其他Tenant身份传入机构realm。 */
    private Account login(Account account)throws Exception {
        JsonNode result=success(exchange(institutionHost,null,"POST","/api/v1/auth/login",Map.of("tenant_slug",account.slug(),"username",USERNAME,"password",PASSWORD),null),"POST","tenant login",200);
        assertThat(result.path("tenant_id").asText()).isEqualTo(account.id());
        assertThat(result.path("access_token").asText()).isNotBlank();
        return new Account(account.id(),account.slug(),result.path("access_token").asText());
    }

    /** 只用不可连接的loopback端点登记已发布模型元数据；本套件不调用模型。 */
    private String model(Account tenant,String marker) {
        TenantContext.setTenantId(tenant.id());
        try{return PlatformModelFixtures.selectLlm(models,tenant.id(),"http://127.0.0.1:9/v1",marker+"-metadata-model","synthetic-unused-key","custom","custom-openai-compatible","custom-v1");}
        finally{TenantContext.clear();}
    }


    /** 机构请求只携带当前账号的JWT，expected在唯一封装中校验。 */
    private JsonNode tenant(Account account,String method,String path,Object body,String key,int expected)throws Exception {
        return success(exchange(institutionHost,"Bearer "+account.token(),method,path,body,key),method,path,expected);
    }

    /** 平台realm使用独立JWT头，不能作为机构请求身份。 */
    private JsonNode platform(String method,String path,Object body,String key,int expected)throws Exception {
        return success(exchange(platformHost,"Bearer "+platformToken,method,path,body,key),method,path,expected);
    }

    /** 原始HTTP保留JSON线格式；异常和断言不打印请求体、JWT或秘密。 */
    private Reply exchange(Host host,String token,String method,String path,Object value,String key)throws Exception {
        String body=value==null?"":json.writeValueAsString(value);
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+host.port+path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json").header("Accept","application/json");
        if(token!=null)builder.header(host.platform?"X-Platform-Token":"Authorization",token);if(key!=null)builder.header("Idempotency-Key",key);
        var response=http.send(builder.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),json.readTree(response.body()));
    }

    /** 失败仅报告端点、状态和安全错误码，秘密响应不会出现在断言描述。 */
    private JsonNode success(Reply response,String method,String path,int expected) {
        assertThat(response.status()).as("%s %s code=%s",method,path,response.body().path("code").asText()).isEqualTo(expected);
        return response.body().path("data");
    }

    /** 两个正式bootJar共用当前私有PG/Redis，端口由OS原子分配，重启不重建业务库。 */
    private void startHosts(String label)throws Exception {
        platformHost=startHost(true,label);institutionHost=startHost(false,label);
        platformToken=success(exchange(platformHost,null,"POST","/api/v1/platform/auth/login",Map.of(
                "username","group-platform-admin","password","SyntheticPlatform1234"),null),"POST","platform login",200).path("access_token").asText();
        assertThat(platformToken).isNotBlank();
    }

    /** 迁移owner只准备独立测试角色；两个实际宿主分别使用机构RLS及平台BYPASSRLS角色。 */
    private void prepareDatabaseRoles() {
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user",Boolean.class))
                .as("本套件由私有Testcontainers迁移owner创建宿主角色").isTrue();
        String suffix=Long.toString(SnowflakeIds.nextId());
        tenantDatabaseUser="gi02_tenant_"+suffix;platformDatabaseUser="gi02_platform_"+suffix;
        hostDatabasePassword=java.util.UUID.randomUUID().toString();
        jdbc.execute("CREATE ROLE "+tenantDatabaseUser+" LOGIN PASSWORD '"+hostDatabasePassword+"' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("CREATE ROLE "+platformDatabaseUser+" LOGIN PASSWORD '"+hostDatabasePassword+"' NOSUPERUSER BYPASSRLS");
        for(String role:List.of(tenantDatabaseUser,platformDatabaseUser)) {
            jdbc.execute("GRANT USAGE ON SCHEMA public TO "+role);
            jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO "+role);
            jdbc.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO "+role);
        }
        // V69认证解析器收回PUBLIC执行权；自有机构角色仅获这条正式认证函数，不提升普通数据访问。
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.resolve_service_principal_by_api_key_hash(text) TO "+tenantDatabaseUser);
        assertThat(jdbc.queryForObject("SELECT has_function_privilege(CAST(? AS name), 'public.resolve_service_principal_by_api_key_hash(text)', 'EXECUTE')",
                Boolean.class,tenantDatabaseUser)).as("机构角色必须能调用唯一SP认证解析器").isTrue();
    }

    /** 获取进程后立即登记清理；只在监听日志与实际HTTP共同ready后返回。 */
    private Host startHost(boolean platform,String label)throws Exception {
        Path root=repositoryRoot();String module=platform?"skillforge-platform-service":"skillforge-institution-service";
        Path jar=root.resolve("backend-java/"+module+"/build/libs/"+module+".jar");assertThat(jar).exists();
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        Path log=evidence.resolve((platform?"platform-":"institution-")+label+".log");
        var builder=new ProcessBuilder(java.toString(),"-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8",
                "-jar",jar.toString(),"--server.port=0","--server.address=127.0.0.1",
                "--agent-runtime.workers.auto-startup=false","--spring.flyway.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.nacos.config.enabled=false");
        builder.directory(root.resolve("backend-java/"+module).toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();env.put("SPRING_DATASOURCE_URL",environment.getRequiredProperty("spring.datasource.url"));
        env.put("SPRING_DATASOURCE_USERNAME",tenantDatabaseUser);env.put("SPRING_DATASOURCE_PASSWORD",hostDatabasePassword);
        env.put("PLATFORM_DATASOURCE_USERNAME",platformDatabaseUser);env.put("PLATFORM_DATASOURCE_PASSWORD",hostDatabasePassword);
        env.put("REDIS_HOST",environment.getRequiredProperty("spring.data.redis.host"));env.put("REDIS_PORT",environment.getRequiredProperty("spring.data.redis.port"));
        env.put("REDIS_DATABASE",environment.getProperty("spring.data.redis.database","0"));
        env.put("SECRET_KEY",properties.getSecretKey());env.put("PLATFORM_SECRET_KEY",TEST_PLATFORM_JWT_SECRET);
        env.put("PLATFORM_ADMIN_USERNAME","group-platform-admin");env.put("PLATFORM_ADMIN_PASSWORD","SyntheticPlatform1234");
        Host host=new Host(builder.start(),log,platform);ownedHosts.add(host);
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(()->{
            assertThat(host.process.isAlive()).as("宿主应存活，诊断日志 %s",host.log).isTrue();
            // 正在追加的末尾UTF-8字符可能尚未完整写入；只匹配完整ASCII监听事件，不把日志切片当解码失败。
            var match=LISTENING.matcher(new String(Files.readAllBytes(log),StandardCharsets.UTF_8));assertThat(match.find()).as("宿主必须报告实际监听端口").isTrue();
            host.port=Integer.parseInt(match.group(1));
            var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+host.port+"/v3/api-docs")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
        });
        return host;
    }

    /** 即使一个进程清理失败仍关闭其他owned宿主，并等待进程静止。 */
    private void stopHosts()throws Exception {
        Throwable failure=null;
        for(int i=ownedHosts.size()-1;i>=0;i--) {
            Host host=ownedHosts.get(i);
            try {host.process.destroy();if(!host.process.waitFor(10,TimeUnit.SECONDS)){host.process.destroyForcibly();assertThat(host.process.waitFor(10,TimeUnit.SECONDS)).as("宿主须完成退出 %s",host.log).isTrue();}}
            catch(Throwable problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        }
        ownedHosts.clear();if(failure instanceof Error error)throw error;if(failure instanceof Exception problem)throw problem;
    }

    /** 从模块或仓库目录定位已构建的正式产物，不依赖机器绝对工作目录。 */
    private static Path repositoryRoot() {
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.exists(root.resolve("settings.gradle.kts"))&&!Files.isDirectory(root.resolve(".git")))root=root.getParent();
        if(root!=null&&Files.exists(root.resolve("settings.gradle.kts")))root=root.getParent();if(root==null)throw new IllegalStateException("repository_root_missing");return root;
    }

    /** 测试内的真实普通机构和本地安全会话；toString不输出JWT。 */
    private record Account(String id,String slug,String token) { @Override public String toString(){return "Tenant "+id;} }
    /** 测试拥有的OS宿主、日志和原子分配后的监听端口。 */
    private static final class Host {
        private final Process process;private final Path log;private final boolean platform;private int port;
        /** 进程启动即持有其回收责任，port只在监听事件后赋值。 */
        private Host(Process process,Path log,boolean platform){this.process=process;this.log=log;this.platform=platform;}
    }
    /** 原始响应只供expected校验，不输出响应正文或一次秘密。 */
    private record Reply(int status,JsonNode body) { @Override public String toString(){return "HTTP "+status;} }
}

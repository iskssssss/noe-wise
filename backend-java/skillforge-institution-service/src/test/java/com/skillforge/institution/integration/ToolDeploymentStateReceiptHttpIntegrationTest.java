package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.skillforge.core.shared.config.SnowflakeIds;
import org.junit.jupiter.api.Test;

/** 真实HTTP/CAS事务回执验证，不调用工具提供方。 */
class ToolDeploymentStateReceiptHttpIntegrationTest extends ChatExecutionChainE2ETest {
    private static final String PATH = "/api/v1/capabilities/deployments/state-with-receipt";
    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource receiptDataSource;

    @Test void originalStateReceiptSurvivesLaterChangesAndRejectedRevisionIsFinal() throws Exception {
        login();
        String version = "";
        for (var item : successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED")))
            if ("tool:get_current_time".equals(item.path("capability_key").asText())) version = item.path("default_version_id").asText();
        assertFalse(version.isBlank());
        String key = "state-clock-" + SnowflakeIds.nextId();
        var created = send("/api/v1/capabilities/deployments/create-with-receipt", "POST", Map.of(
                "request_id", UUID.randomUUID().toString(), "payload", Map.of("deployment_key", key,
                "tool_version_id", version, "provider_type", "PLATFORM", "provider_ref", "get_current_time",
                "environment", "PRODUCTION")), accessToken);
        assertEquals(200, created.statusCode(), created.body());
        String id = UUID.randomUUID().toString();
        Map<String, Object> stop = Map.of("request_id", id, "deployment_key", key, "expected_revision", 1, "active", false);
        assertEquals(204, get(PATH + "/result?request_id=" + id).statusCode());
        var response = send(PATH, "POST", stop, accessToken);
        assertEquals(200, response.statusCode(), response.body());
        var receipt = successData(response);
        assertEquals("SUCCEEDED", receipt.path("status").asText());
        assertEquals("DISABLED", receipt.path("deployment_status").asText());
        assertEquals(2, receipt.path("revision").asInt());
        assertEquals(receipt, successData(send(PATH, "POST", stop, accessToken)));
        Map<String, Object> start = Map.of("request_id", UUID.randomUUID().toString(), "deployment_key", key, "expected_revision", 2, "active", true);
        assertEquals(200, send(PATH, "POST", start, accessToken).statusCode());
        assertEquals(receipt, successData(get(PATH + "/result?request_id=" + id)));
        assertReceiptRls(id);
        assertEquals(receipt, successData(send(PATH, "POST", stop, accessToken)));
        assertEquals(409, send(PATH, "POST", Map.of("request_id", id, "deployment_key", key,
                "expected_revision", 3, "active", true), accessToken).statusCode());
        String rejectedId = UUID.randomUUID().toString();
        Map<String, Object> stale = Map.of("request_id", rejectedId, "deployment_key", key, "expected_revision", 1, "active", false);
        var refused = send(PATH, "POST", stale, accessToken);
        assertEquals(200, refused.statusCode(), refused.body());
        var rejected = successData(refused);
        assertEquals("REJECTED", rejected.path("status").asText());
        assertEquals(rejected, successData(get(PATH + "/result?request_id=" + rejectedId)));
        assertEquals(rejected, successData(send(PATH, "POST", stale, accessToken)));
        var current = successData(get("/api/v1/capabilities/deployments/detail?deploymentKey=" + key));
        assertEquals("ACTIVE", current.path("status").asText());
        assertEquals(3, current.path("revision").asInt());

        String reader = actor(List.of("foundation.capabilities.read"));
        assertEquals(204, send(PATH + "/result?request_id=" + id, "GET", null, reader).statusCode());
        assertEquals(403, send(PATH, "POST", stop, reader).statusCode());
        String other = actor(List.of("foundation.capabilities.read", "foundation.capabilities.cancel"));
        assertEquals(409, send(PATH, "POST", stop, other).statusCode());
        String stopper = actor(List.of("foundation.capabilities.cancel"));
        Map<String, Object> finalStop = Map.of("request_id", UUID.randomUUID().toString(), "deployment_key", key, "expected_revision", 3, "active", false);
        assertEquals(200, send(PATH, "POST", finalStop, stopper).statusCode());
        assertEquals(403, send(PATH + "/result?request_id=" + finalStop.get("request_id"), "GET", null, stopper).statusCode());
        assertEquals(403, send(PATH, "POST", start, stopper).statusCode());
    }

    private void assertReceiptRls(String requestId) throws Exception {
        String role = "tool_state_reader_" + SnowflakeIds.nextId();
        try (var connection = receiptDataSource.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
                statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
                statement.execute("GRANT SELECT,INSERT ON public.nw_tool_deployment_state_receipts TO " + role);
            }
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL ROLE " + role);
                    try (var flags = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                        assertTrue(flags.next()); assertFalse(flags.getBoolean(1), "RLS probe must use a restricted role");
                    }
                }
                try (var scope = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    scope.setString(1, tenantId); scope.execute();
                }
                try (var query = connection.prepareStatement("SELECT count(*) FROM nw_tool_deployment_state_receipts WHERE request_id=?::uuid")) {
                    query.setString(1, requestId);
                    try (var rows = query.executeQuery()) { rows.next(); assertEquals(1, rows.getInt(1)); }
                    try (var scope = connection.prepareStatement("SELECT set_config('app.tenant_id', '0', true)")) { scope.execute(); }
                    try (var rows = query.executeQuery()) { rows.next(); assertEquals(0, rows.getInt(1)); }
                }
                try (var insert = connection.prepareStatement("INSERT INTO nw_tool_deployment_state_receipts(tenant_id,request_id,actor_ref,request_fingerprint,deployment_key,expected_revision,active) VALUES (?::bigint,?::uuid,'rls',repeat('a',64),'rls',1,false)")) {
                    insert.setString(1, tenantId); insert.setString(2, UUID.randomUUID().toString());
                    assertThrows(java.sql.SQLException.class, insert::executeUpdate);
                }
            } finally {
                connection.rollback(); connection.setAutoCommit(true);
                try (var statement = connection.createStatement()) {
                    statement.execute("DROP OWNED BY " + role);
                    statement.execute("DROP ROLE " + role);
                }
            }
        }
    }

    private String actor(List<String> permissions) throws Exception {
        String prefix = "/api/v1/tenant/foundation/access", name = "state_reader_" + SnowflakeIds.nextId();
        var role = send(prefix + "/role", "POST", Map.of("code", name, "name", name,
                "data_scope", "all", "permissions", permissions, "department_ids", List.of()), accessToken);
        assertEquals(200, role.statusCode(), role.body());
        var member = send(prefix + "/member", "POST", Map.of("username", name, "display_name", name,
                "password", PASSWORD, "role_ids", List.of(successData(role).path("id").asText())), accessToken);
        assertEquals(200, member.statusCode(), member.body());
        var logged = send("/api/v1/auth/login", "POST", Map.of("tenant_slug", fixtureTenantSlug(),
                "username", name, "password", PASSWORD), null);
        assertEquals(200, logged.statusCode(), logged.body());
        return successData(logged).path("access_token").asText();
    }
}

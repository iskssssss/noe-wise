package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.service.TenantToolDeploymentProviderValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 原请求回执与部署创建的事务、归属和并发验证。 */
class ToolDeploymentCreationReceiptHttpIntegrationTest extends ChatExecutionChainE2ETest {
    private static final String PATH = "/api/v1/capabilities/deployments/create-with-receipt";
    @Autowired TenantMapper receiptTenants;
    @Autowired PlanMapper receiptPlans;
    @MockitoSpyBean TenantToolDeploymentProviderValidator receiptValidator;

    @Test void receiptBindsActorAndRequestAndCommitsWithDeployment() throws Exception {
        login();
        String version = "";
        for (var item : successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"))) {
            if ("tool:get_current_time".equals(item.path("capability_key").asText()))
                version = item.path("default_version_id").asText();
        }
        assertFalse(version.isBlank());
        String key = "receipt-clock-" + SnowflakeIds.nextId();
        String id = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("deployment_key", key, "tool_version_id", version,
                "provider_type", "PLATFORM", "provider_ref", "get_current_time", "environment", "PRODUCTION");
        Map<String, Object> command = Map.of("request_id", id, "payload", payload);
        assertEquals(204, get(PATH + "/result?request_id=" + id).statusCode());
        var first = send(PATH, "POST", command, accessToken);
        assertEquals(200, first.statusCode(), first.body());
        var result = successData(first);
        assertEquals("SUCCEEDED", result.path("status").asText());
        assertTrue(result.path("deployment_id").isTextual());
        assertEquals(key, result.path("deployment_key").asText());
        assertEquals(result, successData(get(PATH + "/result?request_id=" + id)));
        var replay = send(PATH, "POST", command, accessToken);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(result, successData(replay));
        var different = Map.of("request_id", id, "payload", Map.of("deployment_key", key + "-different",
                "tool_version_id", version, "provider_type", "PLATFORM",
                "provider_ref", "get_current_time", "environment", "PRODUCTION"));
        assertEquals(409, send(PATH, "POST", different, accessToken).statusCode());
        String duplicateId = UUID.randomUUID().toString();
        var duplicateCommand = Map.of("request_id", duplicateId, "payload", payload);
        var duplicateResponse = send(PATH, "POST", duplicateCommand, accessToken);
        assertEquals(200, duplicateResponse.statusCode(), duplicateResponse.body());
        var duplicateReceipt = successData(duplicateResponse);
        assertEquals("REJECTED", duplicateReceipt.path("status").asText());
        assertEquals(key, duplicateReceipt.path("deployment_key").asText());
        assertTrue(duplicateReceipt.path("message").asText().contains("部署标识"));
        assertEquals(duplicateReceipt, successData(get(PATH + "/result?request_id=" + duplicateId)));

        String prefix = "/api/v1/tenant/foundation/access";
        var role = send(prefix + "/role", "POST", Map.of("code", "receipt_" + SnowflakeIds.nextId(),
                "name", "回执测试", "data_scope", "all", "permissions",
                List.of("foundation.capabilities.read", "foundation.capabilities.create"), "department_ids", List.of()), accessToken);
        assertEquals(200, role.statusCode(), role.body());
        var member = send(prefix + "/member", "POST", Map.of("username", "receipt-other",
                "display_name", "其他成员", "password", PASSWORD, "role_ids", List.of(successData(role).path("id").asText())), accessToken);
        assertEquals(200, member.statusCode(), member.body());
        var logged = send("/api/v1/auth/login", "POST", Map.of("tenant_slug", fixtureTenantSlug(),
                "username", "receipt-other", "password", PASSWORD), null);
        assertEquals(200, logged.statusCode(), logged.body());
        String other = successData(logged).path("access_token").asText();
        assertEquals(204, send(PATH + "/result?request_id=" + id, "GET", null, other).statusCode());
        assertEquals(409, send(PATH, "POST", command, other).statusCode());
        assertEquals(1, countRuntime("SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=CAST(? AS bigint) AND deployment_key=?", tenantId, key));

        var createOnlyRole = send(prefix + "/role", "POST", Map.of("code", "create_" + SnowflakeIds.nextId(),
                "name", "仅创建", "data_scope", "all", "permissions",
                List.of("foundation.capabilities.create"), "department_ids", List.of()), accessToken);
        assertEquals(200, createOnlyRole.statusCode(), createOnlyRole.body());
        var createOnlyMember = send(prefix + "/member", "POST", Map.of("username", "receipt-create-only",
                "display_name", "仅创建", "password", PASSWORD,
                "role_ids", List.of(successData(createOnlyRole).path("id").asText())), accessToken);
        assertEquals(200, createOnlyMember.statusCode(), createOnlyMember.body());
        var createOnlyLogin = send("/api/v1/auth/login", "POST", Map.of("tenant_slug", fixtureTenantSlug(),
                "username", "receipt-create-only", "password", PASSWORD), null);
        assertEquals(200, createOnlyLogin.statusCode(), createOnlyLogin.body());
        assertEquals(403, send(PATH + "/result?request_id=" + id, "GET", null,
                successData(createOnlyLogin).path("access_token").asText()).statusCode());

        String otherSlug = "receipt-isolation-" + SnowflakeIds.nextId();
        String otherTenant = createTenant(receiptTenants, receiptPlans, otherSlug);
        TenantContext.setTenantId(otherTenant);
        try {
            createTestTenantAdministrator(otherTenant, "receipt-tenant-admin", PASSWORD);
        } finally {
            TenantContext.clear();
        }
        var otherTenantLogin = send("/api/v1/auth/login", "POST", Map.of("tenant_slug", otherSlug,
                "username", "receipt-tenant-admin", "password", PASSWORD), null);
        assertEquals(200, otherTenantLogin.statusCode(), otherTenantLogin.body());
        assertEquals(204, send(PATH + "/result?request_id=" + id, "GET", null,
                successData(otherTenantLogin).path("access_token").asText()).statusCode());

        String rejectedId = UUID.randomUUID().toString(), rejectedKey = key + "-rejected";
        var rejectedCommand = Map.of("request_id", rejectedId, "payload", Map.of("deployment_key", rejectedKey,
                "tool_version_id", version, "provider_type", "PLATFORM", "provider_ref", "not-a-tool", "environment", "PRODUCTION"));
        var rejectedResponse = send(PATH, "POST", rejectedCommand, accessToken);
        assertEquals(200, rejectedResponse.statusCode(), rejectedResponse.body());
        var rejected = successData(rejectedResponse);
        assertEquals("REJECTED", rejected.path("status").asText());
        assertEquals(rejectedKey, rejected.path("deployment_key").asText());
        assertEquals("TOOL_DEPLOYMENT_CREATE_REJECTED", rejected.path("error_code").asText());
        assertEquals(rejected, successData(get(PATH + "/result?request_id=" + rejectedId)));
        assertEquals(rejected, successData(send(PATH, "POST", rejectedCommand, accessToken)));
        assertEquals(0, countRuntime("SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=CAST(? AS bigint) AND deployment_key=?", tenantId, rejectedKey));

        String concurrentId = UUID.randomUUID().toString(), concurrentKey = key + "-parallel";
        var concurrent = Map.of("request_id", concurrentId, "payload", Map.of("deployment_key", concurrentKey,
                "tool_version_id", version, "provider_type", "PLATFORM", "provider_ref", "get_current_time", "environment", "PRODUCTION"));
        var a = CompletableFuture.supplyAsync(() -> post(concurrent));
        var b = CompletableFuture.supplyAsync(() -> post(concurrent));
        assertEquals(a.get(), b.get());
        assertEquals(1, countRuntime("SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=CAST(? AS bigint) AND deployment_key=?", tenantId, concurrentKey));

        // Provider 后续不可用不应覆盖已提交原回执。
        doThrow(CapabilityCatalogErrors.exception(HttpStatus.UNPROCESSABLE_ENTITY,
                CapabilityCatalogErrors.DEPLOYMENT_INVALID, "provider unavailable"))
                .when(receiptValidator).validate(anyLong(), any(), anyString(), any());
        var replayAfterProviderChange = send(PATH, "POST", command, accessToken);
        assertEquals(200, replayAfterProviderChange.statusCode(), replayAfterProviderChange.body());
        assertEquals(result, successData(replayAfterProviderChange));

        // 相同原请求在首次明确拒绝与并发成功竞争时，只能有一个不可变终态。
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0)
                throw CapabilityCatalogErrors.exception(HttpStatus.UNPROCESSABLE_ENTITY,
                        CapabilityCatalogErrors.DEPLOYMENT_INVALID, "provider unavailable");
            return invocation.callRealMethod();
        }).when(receiptValidator).validate(anyLong(), any(), anyString(), any());
        String raceId = UUID.randomUUID().toString(), raceKey = key + "-race";
        var race = Map.of("request_id", raceId, "payload", Map.of("deployment_key", raceKey,
                "tool_version_id", version, "provider_type", "PLATFORM", "provider_ref", "get_current_time", "environment", "PRODUCTION"));
        var raceA = CompletableFuture.supplyAsync(() -> post(race));
        var raceB = CompletableFuture.supplyAsync(() -> post(race));
        var raceResult = raceA.get();
        assertEquals(raceResult, raceB.get());
        assertEquals(raceResult, successData(get(PATH + "/result?request_id=" + raceId)));
        long created = countRuntime("SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=CAST(? AS bigint) AND deployment_key=?", tenantId, raceKey);
        assertEquals("SUCCEEDED".equals(raceResult.path("status").asText()) ? 1 : 0, created);
    }

    private com.fasterxml.jackson.databind.JsonNode post(Map<String, Object> command) {
        try {
            var response = send(PATH, "POST", command, accessToken);
            assertEquals(200, response.statusCode(), response.body());
            return successData(response);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}

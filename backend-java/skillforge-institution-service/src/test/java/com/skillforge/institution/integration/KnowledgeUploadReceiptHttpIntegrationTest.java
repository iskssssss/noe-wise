package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.knowledge.EmbeddingProvider;
import com.skillforge.core.knowledge.search.SearchIndexer;
import com.skillforge.core.knowledge.mapper.KnowledgeUploadReceiptMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.service.KnowledgeManagementService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** 隔离 PostgreSQL 与真实 HTTP；经济索引不调用模型。 */
class KnowledgeUploadReceiptHttpIntegrationTest extends AbstractIntegrationTest {
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired KnowledgeManagementService management;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean EmbeddingProvider embedding;
    @MockitoBean SearchIndexer indexer;
    @MockitoSpyBean KnowledgeUploadReceiptMapper receipts;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void uploadReceiptIsStableAndDoesNotRepeatDocumentWrite() throws Exception {
        String slug = "knowledge-upload-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        long member = createTestTenantAdministrator(tenant, "upload-admin", "UploadTestPassword123");
        long kb = withAuthenticatedMember(member,
                () -> management.create(tenant, "知识上传验收", null, "economical", "full_text", false).id());
        TenantContext.clear();
        var login = request("POST", "/api/v1/auth/login", null,
                json.writeValueAsBytes(Map.of("tenant_slug", slug, "username", "upload-admin", "password", "UploadTestPassword123")),
                "application/json");
        assertEquals(200, login.statusCode());
        String token = json.readTree(login.body()).path("data").path("access_token").asText();
        String id = UUID.randomUUID().toString();
        String url = "/api/v1/knowledge/bases/documents/upload?kb_id=" + kb + "&request_id=" + id;
        String result = "/api/v1/knowledge/bases/documents/upload/result?kb_id=" + kb + "&request_id=" + id;
        assertEquals(405, multipart("/api/v1/knowledge/bases/documents", token, "legacy.txt", "legacy").statusCode());
        assertEquals(204, request("GET", result, token, null, null).statusCode());
        var first = multipart(url, token, "fixture.txt", "hello knowledge");
        assertEquals(200, first.statusCode(), first.body());
        var receipt = json.readTree(first.body()).path("data");
        assertEquals("SUCCEEDED", receipt.path("status").asText());
        assertEquals(id, receipt.path("request_id").asText());
        assertTrue(receipt.path("document").path("document_id").isTextual());
        assertFalse(receipt.path("document").path("source_version").asText().isBlank());
        assertEquals(receipt, json.readTree(request("GET", result, token, null, null).body()).path("data"));
        String access = "/api/v1/tenant/foundation/access";
        var role = request("POST", access + "/role", token, json.writeValueAsBytes(Map.of(
                "code", "reader_" + SnowflakeIds.nextId(), "name", "reader", "data_scope", "all",
                "permissions", List.of("foundation.knowledge.read", "foundation.resource.knowledge.read"), "department_ids", List.of())), "application/json");
        assertEquals(200, role.statusCode(), role.body());
        String roleId = json.readTree(role.body()).path("data").path("id").asText();
        var created = request("POST", access + "/member", token, json.writeValueAsBytes(Map.of(
                "username", "other-reader", "display_name", "other-reader", "password", "UploadTestPassword123",
                "role_ids", List.of(roleId))), "application/json");
        assertEquals(200, created.statusCode(), created.body());
        var otherLogin = request("POST", "/api/v1/auth/login", null, json.writeValueAsBytes(Map.of(
                "tenant_slug", slug, "username", "other-reader", "password", "UploadTestPassword123")), "application/json");
        assertEquals(200, otherLogin.statusCode(), otherLogin.body());
        String other = json.readTree(otherLogin.body()).path("data").path("access_token").asText();
        assertEquals(204, request("GET", result, other, null, null).statusCode());
        String deniedId = UUID.randomUUID().toString();
        var denied = multipart("/api/v1/knowledge/bases/documents/upload?kb_id=" + kb + "&request_id=" + deniedId,
                other, "denied.txt", "no permission");
        assertEquals(403, denied.statusCode(), denied.body());
        assertEquals("KNOWLEDGE_UPLOAD_REJECTED", json.readTree(denied.body()).path("code").asText());
        assertEquals(204, request("GET", "/api/v1/knowledge/bases/documents/upload/result?kb_id="
                + kb + "&request_id=" + deniedId, token, null, null).statusCode());
        assertEquals(receipt, json.readTree(multipart(url, token, "fixture.txt", "hello knowledge").body()).path("data"));
        assertEquals(409, multipart(url, token, "fixture.txt", "different").statusCode());
        assertEquals(1, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_knowledge_documents WHERE tenant_id=? AND kb_id=?", Long.parseLong(tenant), kb));
        assertEquals(1, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_knowledge_upload_receipts WHERE tenant_id=? AND kb_id=? AND status='SUCCEEDED'", Long.parseLong(tenant), kb));
        String rejectedId = UUID.randomUUID().toString();
        String rejectedUrl = "/api/v1/knowledge/bases/documents/upload?kb_id=" + kb + "&request_id=" + rejectedId;
        var rejected = multipart(rejectedUrl, token, "fixture.bin", "unsupported");
        assertEquals(200, rejected.statusCode(), rejected.body());
        var rejectedReceipt = json.readTree(rejected.body()).path("data");
        assertEquals("REJECTED", rejectedReceipt.path("status").asText());
        assertFalse(rejectedReceipt.path("error_message").asText().isBlank());
        assertEquals(rejectedReceipt, json.readTree(request("GET", "/api/v1/knowledge/bases/documents/upload/result?kb_id="
                + kb + "&request_id=" + rejectedId, token, null, null).body()).path("data"));
        String failedId = UUID.randomUUID().toString();
        org.mockito.Mockito.doReturn(0).when(receipts).succeed(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(failedId), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
        String failedUrl = "/api/v1/knowledge/bases/documents/upload?kb_id=" + kb + "&request_id=" + failedId;
        assertEquals(500, multipart(failedUrl, token, "rollback.txt", "rollback document").statusCode());
        assertEquals("PROCESSING", json.readTree(request("GET", "/api/v1/knowledge/bases/documents/upload/result?kb_id="
                + kb + "&request_id=" + failedId, token, null, null).body()).path("data").path("status").asText());
        assertEquals(1, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_knowledge_documents WHERE tenant_id=? AND kb_id=?", Long.parseLong(tenant), kb));
        verifyNoInteractions(embedding);
        var published = new RuntimeExecutionKnowledgeFixture(json, http,
                "http://127.0.0.1:" + port, token).publish("receipt-fixture", "sentinel-" + SnowflakeIds.nextId());
        assertNotNull(published.sourceVersion());
        verifyNoInteractions(embedding);
    }

    private HttpResponse<String> multipart(String url, String token, String filename, String text) throws Exception {
        String boundary = "knowledge-upload-fixture";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/plain\r\n\r\n" + text + "\r\n--" + boundary + "--\r\n";
        return request("POST", url, token, body.getBytes(StandardCharsets.UTF_8), "multipart/form-data; boundary=" + boundary);
    }

    private HttpResponse<String> request(String method, String path, String token, byte[] body, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (contentType != null) builder.header("Content-Type", contentType);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}

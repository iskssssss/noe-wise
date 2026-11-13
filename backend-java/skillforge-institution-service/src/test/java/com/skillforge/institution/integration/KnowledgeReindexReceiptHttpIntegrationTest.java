package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.knowledge.EmbeddingProvider;
import com.skillforge.core.knowledge.mapper.KnowledgeReindexReceiptMapper;
import com.skillforge.core.knowledge.search.SearchIndexer;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.service.KnowledgeManagementService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 真实 PostgreSQL 与 HTTP：同一重索引请求只切换一次来源，成功回执可恢复。 */
@org.springframework.context.annotation.Import(KnowledgeServiceIntegrationTest.LocalEmbeddingFixtureConfiguration.class)
class KnowledgeReindexReceiptHttpIntegrationTest extends AbstractIntegrationTest {
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired KnowledgeManagementService management;
    @Autowired PlatformModelCatalogService models;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean EmbeddingProvider embedding;
    @MockitoBean SearchIndexer indexer;
    @MockitoSpyBean KnowledgeReindexReceiptMapper receipts;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void reindexReceiptIsStableAndFailedAtomicWriteKeepsCurrentSource() throws Exception {
        String slug = "knowledge-reindex-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        long member = createTestTenantAdministrator(tenant, "reindex-admin", "ReindexTestPassword123");
        long kb = withAuthenticatedMember(member,
                () -> management.create(tenant, "知识重索引验收", null, "economical", "full_text", false).id());
        TenantContext.clear();

        var login = request("POST", "/api/v1/auth/login", null,
                json.writeValueAsBytes(Map.of(
                        "tenant_slug", slug, "username", "reindex-admin",
                        "password", "ReindexTestPassword123")),
                "application/json");
        assertEquals(200, login.statusCode(), login.body());
        String token = json.readTree(login.body()).path("data").path("access_token").asText();

        String uploadId = UUID.randomUUID().toString();
        var uploaded = multipart("/api/v1/knowledge/bases/documents/upload?kb_id=" + kb
                        + "&request_id=" + uploadId,
                token, "receipt.txt", "reindex receipt fixture");
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        var uploadedDocument = json.readTree(uploaded.body()).path("data").path("document");
        String documentId = uploadedDocument.path("document_id").asText();
        String originalSource = uploadedDocument.path("source_version").asText();
        assertFalse(documentId.isBlank());
        assertFalse(originalSource.isBlank());

        String requestId = UUID.randomUUID().toString();
        String command = json.writeValueAsString(Map.of(
                "doc_id", documentId,
                "request_id", requestId,
                "expected_source_version", originalSource));
        String reindexUrl = "/api/v1/knowledge/documents/reindex";
        var first = request("POST", reindexUrl, token,
                command.getBytes(StandardCharsets.UTF_8), "application/json");
        assertEquals(200, first.statusCode(), first.body());
        var firstReceipt = json.readTree(first.body()).path("data");
        assertEquals("SUCCEEDED", firstReceipt.path("status").asText());
        assertEquals(requestId, firstReceipt.path("request_id").asText());
        assertEquals(documentId, firstReceipt.path("document").path("document_id").asText());
        String reindexedSource = firstReceipt.path("document").path("source_version").asText();
        assertFalse(reindexedSource.isBlank());
        assertTrue(firstReceipt.path("document").path("chunks").asInt() > 0);

        var repeated = request("POST", reindexUrl, token,
                command.getBytes(StandardCharsets.UTF_8), "application/json");
        assertEquals(200, repeated.statusCode(), repeated.body());
        assertEquals(firstReceipt, json.readTree(repeated.body()).path("data"));

        String resultUrl = "/api/v1/knowledge/documents/reindex/result?doc_id=" + documentId
                + "&request_id=" + requestId;
        var readback = request("GET", resultUrl, token, null, null);
        assertEquals(200, readback.statusCode(), readback.body());
        assertEquals(firstReceipt, json.readTree(readback.body()).path("data"));
        assertEquals(204, request("GET",
                "/api/v1/knowledge/documents/reindex/result?doc_id=" + documentId
                        + "&request_id=" + UUID.randomUUID(), token, null, null).statusCode());

        var mismatched = request("POST", reindexUrl, token,
                json.writeValueAsBytes(Map.of(
                        "doc_id", documentId,
                        "request_id", requestId,
                        "expected_source_version", "different-" + originalSource)),
                "application/json");
        assertEquals(409, mismatched.statusCode(), mismatched.body());
        assertEquals(firstReceipt, json.readTree(request("GET", resultUrl, token, null, null).body())
                .path("data"));

        assertEquals(2, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_knowledge_document_versions WHERE tenant_id=? AND document_id=?",
                Long.parseLong(tenant), Long.parseLong(documentId)));
        assertEquals(1, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_knowledge_reindex_receipts WHERE tenant_id=? AND document_id=?",
                Long.parseLong(tenant), Long.parseLong(documentId)));
        assertEquals(reindexedSource, currentSource(tenant, documentId));

        String failedId = UUID.randomUUID().toString();
        doReturn(0).when(receipts).succeed(eq(Long.parseLong(tenant)), eq(failedId), anyString(), anyInt());
        var failed = request("POST", reindexUrl, token,
                json.writeValueAsBytes(Map.of(
                        "doc_id", documentId,
                        "request_id", failedId,
                        "expected_source_version", reindexedSource)),
                "application/json");
        assertEquals(500, failed.statusCode(), failed.body());
        assertEquals(reindexedSource, currentSource(tenant, documentId));
        var failedReadback = request("GET",
                "/api/v1/knowledge/documents/reindex/result?doc_id=" + documentId
                        + "&request_id=" + failedId,
                token, null, null);
        assertEquals(200, failedReadback.statusCode(), failedReadback.body());
        assertEquals("PROCESSING", json.readTree(failedReadback.body()).path("data").path("status").asText());
        assertEquals(2, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_knowledge_document_versions WHERE tenant_id=? AND document_id=?",
                Long.parseLong(tenant), Long.parseLong(documentId)));
        verifyNoInteractions(embedding);
    }

    @Test
    void concurrentSameRequestInvokesEmbeddingOnceAndSwitchesSourceOnce() throws Exception {
        String slug = "knowledge-reindex-concurrent-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        long member = createTestTenantAdministrator(tenant, "reindex-concurrent-admin",
                "ReindexTestPassword123");
        float[] vector = new float[1536];
        vector[0] = 1;
        var model = models.create(new ModelWrite(null, "重索引回执模型", "EMBEDDING", true,
                "custom", "knowledge-embedding-fixture", "knowledge-fixture-v1",
                "openai-embeddings", "bearer-api-key", "https://embedding.example/v1",
                "fixture", "test-secret", null, null, null, null, null, 30, 1536,
                null, null, null, null, "CNY"), "reindex-test");
        var published = models.publish(model.id(), model.revision(), "reindex-test");
        when(embedding.config(anyString())).thenReturn(
                models.readRevision(published.id(), published.revision()));
        when(embedding.embedBatch(anyString(), anyString(), anyLong(), anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(3)).stream()
                        .map(ignored -> vector).toList());
        long kb = withAuthenticatedMember(member, () -> management.create(
                tenant, "知识重索引并发验收", null, "high_quality", "full_text", false).id());
        TenantContext.clear();

        var login = request("POST", "/api/v1/auth/login", null,
                json.writeValueAsBytes(Map.of(
                        "tenant_slug", slug, "username", "reindex-concurrent-admin",
                        "password", "ReindexTestPassword123")),
                "application/json");
        assertEquals(200, login.statusCode(), login.body());
        String token = json.readTree(login.body()).path("data").path("access_token").asText();
        String uploadId = UUID.randomUUID().toString();
        var uploaded = multipart("/api/v1/knowledge/bases/documents/upload?kb_id=" + kb
                + "&request_id=" + uploadId, token, "concurrent.txt",
                "concurrent reindex receipt fixture");
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        var uploadedDocument = json.readTree(uploaded.body()).path("data").path("document");
        String documentId = uploadedDocument.path("document_id").asText();
        String originalSource = uploadedDocument.path("source_version").asText();

        clearInvocations(embedding);
        AtomicInteger embeddingCalls = new AtomicInteger();
        CountDownLatch embeddingEntered = new CountDownLatch(1);
        CountDownLatch releaseEmbedding = new CountDownLatch(1);
        when(embedding.embedBatch(anyString(), anyString(), anyLong(), anyList()))
                .thenAnswer(invocation -> {
                    embeddingCalls.incrementAndGet();
                    embeddingEntered.countDown();
                    if (!releaseEmbedding.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("并发测试未释放 Embedding");
                    return ((List<?>) invocation.getArgument(3)).stream()
                            .map(ignored -> vector).toList();
                });

        String requestId = UUID.randomUUID().toString();
        String command = json.writeValueAsString(Map.of(
                "doc_id", documentId,
                "request_id", requestId,
                "expected_source_version", originalSource));
        String reindexUrl = "/api/v1/knowledge/documents/reindex";
        HttpResponse<String> completed;
        HttpResponse<String> overlap;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> request("POST", reindexUrl, token,
                    command.getBytes(StandardCharsets.UTF_8), "application/json"));
            assertTrue(embeddingEntered.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> request("POST", reindexUrl, token,
                    command.getBytes(StandardCharsets.UTF_8), "application/json"));
            overlap = second.get(10, TimeUnit.SECONDS);
            assertEquals(200, overlap.statusCode(), overlap.body());
            assertEquals("PROCESSING",
                    json.readTree(overlap.body()).path("data").path("status").asText());
            releaseEmbedding.countDown();
            completed = first.get(20, TimeUnit.SECONDS);
        } finally {
            releaseEmbedding.countDown();
        }
        assertEquals(200, completed.statusCode(), completed.body());
        var receipt = json.readTree(completed.body()).path("data");
        assertEquals("SUCCEEDED", receipt.path("status").asText());
        assertEquals(1, embeddingCalls.get());
        var readback = request("GET", "/api/v1/knowledge/documents/reindex/result?doc_id="
                        + documentId + "&request_id=" + requestId,
                token, null, null);
        assertEquals(200, readback.statusCode(), readback.body());
        assertEquals(receipt, json.readTree(readback.body()).path("data"));
        assertEquals(2, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_knowledge_document_versions WHERE tenant_id=? AND document_id=?",
                Long.parseLong(tenant), Long.parseLong(documentId)));
        assertEquals(1, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_knowledge_reindex_receipts WHERE tenant_id=? AND document_id=?",
                Long.parseLong(tenant), Long.parseLong(documentId)));
        assertEquals(receipt.path("document").path("source_version").asText(),
                currentSource(tenant, documentId));
        verify(embedding, times(1)).embedBatch(anyString(), anyString(), anyLong(), anyList());
    }

    private String currentSource(String tenant, String documentId) {
        return String.valueOf(queryRows(jdbc, tenant,
                "SELECT current_source_version FROM nw_knowledge_documents WHERE tenant_id=? AND id=?",
                Long.parseLong(tenant), Long.parseLong(documentId)).getFirst().get("current_source_version"));
    }

    private HttpResponse<String> multipart(String url, String token, String filename, String text)
            throws Exception {
        String boundary = "knowledge-reindex-fixture";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: text/plain\r\n\r\n" + text
                + "\r\n--" + boundary + "--\r\n";
        return request("POST", url, token, body.getBytes(StandardCharsets.UTF_8),
                "multipart/form-data; boundary=" + boundary);
    }

    private HttpResponse<String> request(
            String method, String path, String token, byte[] body, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (contentType != null) builder.header("Content-Type", contentType);
        return http.send(builder.method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}

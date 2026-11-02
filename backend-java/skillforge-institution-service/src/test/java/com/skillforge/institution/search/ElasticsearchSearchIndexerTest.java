package com.skillforge.institution.search;
import com.skillforge.core.knowledge.search.ElasticsearchSearchIndexer;

import com.skillforge.core.shared.resilience.ResilienceFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Elasticsearch 检索索引器单元测试。
 *
 * <p>使用本地 stub HTTP 服务验证 bulk 写入与 _search 检索请求及响应解析。
 */
class ElasticsearchSearchIndexerTest {

    /** Stub HTTP 服务。 */
    private HttpServer server;

    /** 最近请求路径。 */
    private volatile String lastPath;

    /** 最近请求体。 */
    private volatile String lastBody;

    /** 每例可控的写入响应，用于验证 HTTP 成功但 bulk 项失败。 */
    private String bulkResponse;
    private int responseStatus;

    /** 应用配置。 */
    private SkillForgeProperties properties;

    /** 被测索引器。 */
    private ElasticsearchSearchIndexer indexer;

    /**
     * 启动 stub 服务并初始化索引器。
     *
     * @throws Exception 启动失败时抛出
     */
    @BeforeEach
    void setUp() throws Exception {
        bulkResponse = "{\"errors\":false,\"items\":[{\"index\":{\"status\":201}}]}";
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        properties = new SkillForgeProperties();
        properties.setElasticsearchEnabled(true);
        properties.setElasticsearchUri("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setElasticsearchIndex("skillforge-chunks");
        indexer = new ElasticsearchSearchIndexer(new ObjectMapper(), properties, ResilienceFixtures.withDefaultSettings());
    }

    /**
     * 停止 stub 服务。
     */
    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /**
     * bulk 写入应发送 NDJSON 并包含分块内容。
     */
    @Test
    void indexChunksPostsBulk() {
        assertTrue(indexer.indexChunks("tenant-1", List.of(Map.of(
                "chunk_id", "c1",
                "kb_id", "kb1",
                "document_id", "d1",
                "content", "服务申请流程"))));
        assertTrue(lastPath != null && lastPath.endsWith("/_bulk"), "lastPath=" + lastPath);
        assertTrue(lastBody.contains("服务申请流程"));
    }

    /**
     * _search 应返回解析后的检索项。
     */
    @Test
    void searchParsesHits() {
        List<Map<String, Object>> items = indexer.search("tenant-1", "kb1", "申请", 3, null);
        assertTrue(lastPath.endsWith("/_search"));
        assertEquals(1, items.size());
        assertEquals("申请成功", items.get(0).get("content"));
    }

    /**
     * 未启用时不应发起请求并返回空结果。
     */
    @Test
    void disabledReturnsEmpty() {
        properties.setElasticsearchEnabled(false);
        assertFalse(indexer.indexChunks("tenant-1", List.of(Map.of("content", "x"))));
        assertEquals(null, lastPath);
        assertTrue(indexer.search("tenant-1", "kb1", "x", 3, null).isEmpty());
    }

    /** 当前 manifest 的多个文档版本必须与租户、知识库同时过滤。 */
    @Test
    void activeVersionsStayInsideTenantAndBaseForBothSearchLegs() throws Exception {
        indexer.searchVersions("tenant-1", "kb1", "申请", 3, List.of("source-a", "source-b"));
        var query = new ObjectMapper().readTree(lastBody);
        assertEquals("tenant-1", query.at("/query/bool/filter/0/term/tenant_id").asText());
        assertEquals("kb1", query.at("/query/bool/filter/1/term/kb_id").asText());
        assertEquals(List.of("source-a", "source-b"), new ObjectMapper().convertValue(
                query.at("/query/bool/filter/2/terms/source_version"), List.class));
        indexer.searchKnnVersions("tenant-1", "kb1", new ObjectMapper().writeValueAsString(new double[768]),
                3, List.of("source-a", "source-b"));
        var knn = new ObjectMapper().readTree(lastBody);
        assertEquals("tenant-1", knn.at("/knn/filter/0/term/tenant_id").asText());
        assertEquals("kb1", knn.at("/knn/filter/1/term/kb_id").asText());
        assertEquals(2, knn.at("/knn/filter/2/terms/source_version").size());
        lastPath = null;
        assertTrue(indexer.searchVersions("tenant-1", "kb1", "申请", 3, List.of()).isEmpty());
        assertTrue(indexer.searchKnnVersions("tenant-1", "kb1", "[]", 3, List.of()).isEmpty());
        assertEquals(null, lastPath);
    }

    /** 可选索引降级必须识别 HTTP 200 中的失败项，同时不暴露 item.error.reason。 */
    @Test
    void bulkPartialFailureProducesSafeDiagnosticAndDoesNotFailPrimaryWrite() {
        String marker = "synthetic-es-bulk-secret-799a";
        bulkResponse = "{\"errors\":true,\"items\":[{\"index\":{\"status\":201}},"
                + "{\"index\":{\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\","
                + "\"reason\":\"" + marker + "\"}}}]}";
        withSafeFailureLogs(marker, () -> assertFalse(indexer.indexChunks("tenant-1", List.of(
                Map.of("chunk_id", "c1", "content", "first"),
                Map.of("chunk_id", "c2", "content", "second")))));
        assertTrue(lastPath.endsWith("/_bulk"));
    }

    /** HTTP 异常默认携带响应体，不得将原始异常链输出到日志。 */
    @Test
    void httpFailureDoesNotLogSensitiveResponseBody() {
        String marker = "synthetic-es-http-secret-521d";
        responseStatus = 503;
        bulkResponse = marker;
        withSafeFailureLogs(marker, () -> assertFalse(indexer.indexChunks("tenant-1",
                List.of(Map.of("chunk_id", "c1", "content", "first")))));
    }

    private void withSafeFailureLogs(String marker, Runnable action) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(ElasticsearchSearchIndexer.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(action::run);
            assertTrue(logs.list.stream().anyMatch(event -> event.getLevel()
                    .isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)), "索引失败应有安全诊断");
            for (var event : logs.list) {
                assertTrue(!event.getFormattedMessage().contains(marker));
                if (event.getThrowableProxy() != null) {
                    assertTrue(!ch.qos.logback.classic.spi.ThrowableProxyUtil
                            .asString(event.getThrowableProxy()).contains(marker));
                }
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    /**
     * 处理 stub 请求。
     *
     * @param exchange HTTP 交换
     * @throws Exception 处理失败时抛出
     */
    private void handle(HttpExchange exchange) throws IOException {
        lastPath = exchange.getRequestURI().getPath();
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        String response;
        if (lastPath.endsWith("/_search")) {
            response = "{\"hits\":{\"hits\":[{\"_score\":1.2,\"_source\":"
                    + "{\"content\":\"申请成功\",\"chunk_id\":\"c1\",\"document_id\":\"d1\"}}]}}";
        } else {
            response = bulkResponse;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

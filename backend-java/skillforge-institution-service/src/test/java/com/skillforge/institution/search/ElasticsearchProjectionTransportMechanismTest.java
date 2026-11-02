package com.skillforge.institution.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.resilience.ResilienceFixtures;
import com.skillforge.core.knowledge.search.ElasticsearchSearchIndexer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production HTTP transport against a free loopback receipt fixture; never real Elasticsearch acceptance. */
class ElasticsearchProjectionTransportMechanismTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private SkillForgeProperties properties;
    private ElasticsearchSearchIndexer indexer;
    private volatile String response = "{\"timed_out\":false,\"version_conflicts\":0,\"failures\":[]}";
    private volatile int status = 200;
    private volatile String lastPath;
    private volatile String lastQuery;
    private volatile String lastBody;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        properties = new SkillForgeProperties();
        properties.setElasticsearchEnabled(true);
        properties.setElasticsearchUri("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setElasticsearchIndex("k5-controlled-transport");
        indexer = newIndexer();
    }
    @AfterEach void close() { if (server != null) server.stop(0); }
    private ElasticsearchSearchIndexer newIndexer() {
        return new ElasticsearchSearchIndexer(json, properties, ResilienceFixtures.withDefaultSettings());
    }

    @Test void deletePostsOnlyExactTenantBaseVersionAndAcceptsCompleteReceipt() throws Exception {
        assertTrue(indexer.deleteSourceVersion("7", "10", "source-v1"));
        assertEquals("/k5-controlled-transport/_delete_by_query", lastPath);
        assertEquals("conflicts=proceed", lastQuery);
        var body = json.readTree(lastBody);
        assertEquals(3, body.at("/query/bool/filter").size());
        assertEquals("7", body.at("/query/bool/filter/0/term/tenant_id").asText());
        assertEquals("10", body.at("/query/bool/filter/1/term/kb_id").asText());
        assertEquals("source-v1", body.at("/query/bool/filter/2/term/source_version").asText());
        assertFalse(lastBody.contains("chunk_id"));
    }

    @Test void deleteRejectsMissingTimeoutConflictAndFailureReceipts() {
        for (String receipt : List.of("null", "{}",
                "{\"timed_out\":true,\"version_conflicts\":0,\"failures\":[]}",
                "{\"timed_out\":false,\"version_conflicts\":1,\"failures\":[]}",
                "{\"timed_out\":false,\"version_conflicts\":0,\"failures\":[{}]}",
                "{\"timed_out\":false,\"version_conflicts\":0}",
                "{\"timed_out\":false,\"failures\":[]}")) {
            response = receipt;
            int before = requests.get();
            assertFalse(newIndexer().deleteSourceVersion("7", "10", "source-v1"));
            assertTrue(requests.get() > before, "each receipt must reach actual loopback transport");
        }
    }

    @Test void deleteHttpFailureIsUnconfirmedAndDisabledDoesNotSend() {
        status = 503;
        response = "{\"error\":\"controlled_failure\"}";
        assertFalse(indexer.deleteSourceVersion("7", "10", "source-v1"));
        assertTrue(requests.get() > 0);
        int before = requests.get();
        properties.setElasticsearchEnabled(false);
        assertFalse(newIndexer().deleteSourceVersion("7", "10", "source-v1"));
        assertEquals(before, requests.get());
    }

    @Test void bulkPreservesSeparateVersionIdentitiesForTheSameChunk() throws Exception {
        response = "{\"errors\":false,\"items\":[{\"index\":{\"status\":201}},{\"index\":{\"status\":201}}]}";
        assertTrue(indexer.indexChunks("7", List.of(chunk("v1"), chunk("v2"))));
        assertTrue(lastPath.endsWith("/_bulk"));
        String[] lines = lastBody.split("\n");
        assertEquals(4, lines.length);
        String first = json.readTree(lines[0]).at("/index/_id").asText();
        String second = json.readTree(lines[2]).at("/index/_id").asText();
        assertEquals(64, first.length());
        assertNotEquals(first, second);
        assertEquals("v1", json.readTree(lines[1]).path("source_version").asText());
        assertEquals("v2", json.readTree(lines[3]).path("source_version").asText());
        assertEquals("7", json.readTree(lines[1]).path("tenant_id").asText());
        assertEquals("31", json.readTree(lines[1]).path("chunk_id").asText());
        assertEquals("31", json.readTree(lines[3]).path("chunk_id").asText());
    }

    @Test void bulk200WithoutEverySuccessfulItemIsUnconfirmed() {
        for (String receipt : List.of(
                "{\"errors\":false,\"items\":[{\"index\":{\"status\":201}}]}",
                "{\"errors\":false,\"items\":[{\"index\":{\"status\":201}},{\"index\":{\"status\":409,\"error\":{\"type\":\"controlled_conflict\"}}}]}",
                "{\"items\":[{\"index\":{\"status\":201}},{\"index\":{\"status\":201}}]}")) {
            response = receipt;
            int before = requests.get();
            assertFalse(newIndexer().indexChunks("7", List.of(chunk("v1"), chunk("v2"))));
            assertTrue(requests.get() > before);
        }
    }
    private static Map<String,Object> chunk(String version) {
        return Map.of("kb_id","10","source_version",version,"chunk_id","31","document_id","20","content","controlled body");
    }
    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        lastPath=exchange.getRequestURI().getPath();lastQuery=exchange.getRequestURI().getRawQuery();
        lastBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);
        exchange.getResponseBody().write(bytes);exchange.close();
    }
}

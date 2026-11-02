package com.skillforge.runtime.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** MCP tools/list 缓存提示与分页按 2026-07-28 官方契约解析。 */
class McpDiscoveryClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void explicitHandshakeNegotiatesAndCarriesSessionToDiscovery() throws Exception {
        var methods = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/mcp", exchange -> {
            try {
                var request = JSON.readTree(exchange.getRequestBody());
                String method = request.path("method").asText(); methods.add(method);
                if (method.equals("notifications/initialized")) { exchange.sendResponseHeaders(202, -1); return; }
                var packet = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", request.path("id").asText());
                if (method.equals("initialize")) {
                    assertEquals(null, exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                    exchange.getResponseHeaders().set("Mcp-Session-Id", "catalog-session");
                    packet.putObject("result").put("protocolVersion", "2025-11-25").putObject("capabilities").putObject("tools");
                } else {
                    assertEquals("catalog-session", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                    assertEquals("2025-11-25", exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                    assertTrue(!request.path("params").has("_meta"));
                    packet.putObject("result").putArray("tools");
                }
                byte[] bytes = JSON.writeValueAsBytes(packet);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        assertTrue(client().listTools(new McpDiscoveryClient.Request("server", endpoint, null, "2025-11-25")).tools().isEmpty());
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list"), methods);
    }

    @Test
    void unsupportedConfiguredProtocolNeverSendsDiscovery() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpDiscoveryClient.Request("server", endpoint, null, "2025-03-26"));
    }

    @Test
    void acceptsMissingEmptyAndWhitespaceDescriptionsAcrossPages() throws Exception {
        var missing = tool("alpha"); missing.remove("description");
        var empty = tool("beta").put("description", "");
        var whitespace = tool("gamma").put("description", "  ");
        serve((id, cursor) -> cursor == null
                ? catalogResult(id, "private", 0, List.of(missing), "page-2")
                : catalogResult(id, "private", 0, List.of(empty, whitespace), null));
        var catalog = client().listTools(request());
        assertEquals(List.of("alpha", "beta", "gamma"), catalog.tools().stream().map(McpDiscoveryClient.Tool::name).toList());
        assertEquals(List.of("", "", "  "), catalog.tools().stream().map(McpDiscoveryClient.Tool::description).toList());
    }

    @Test
    void rejectsPresentNonStringDescriptions() throws Exception {
        for (var invalid : List.of(JSON.readTree("1"), JSON.readTree("{}"), JSON.nullNode())) {
            var malformed = tool("alpha"); malformed.set("description", invalid);
            serve((id, cursor) -> catalogResult(id, null, null, List.of(malformed), null));
            assertThrows(McpDiscoveryClient.DiscoveryException.class, () -> client().listTools(request()));
            server.removeContext("/mcp");
        }
    }

    @Test
    void acceptsZeroTtlWithPrivateCacheScope() throws Exception {
        serve((id, cursor) -> catalogResult(id, "private", 0, List.of(tool("alpha")), null));

        McpDiscoveryClient.Catalog catalog = client().listTools(request());

        assertEquals("private", catalog.cacheScope().orElseThrow());
        assertEquals(0, catalog.ttlMs());
        assertEquals(List.of("alpha"), catalog.tools().stream().map(McpDiscoveryClient.Tool::name).toList());
    }

    @Test
    void treatsMissingCacheHintsAsImmediatelyStale() throws Exception {
        serve((id, cursor) -> catalogResult(id, null, null, List.of(tool("beta")), null));

        McpDiscoveryClient.Catalog catalog = client().listTools(request());

        assertTrue(catalog.cacheScope().isEmpty());
        assertEquals(0, catalog.ttlMs());
        assertEquals("beta", catalog.tools().getFirst().name());
    }

    @Test
    void treatsNegativeTtlAsImmediatelyStale() throws Exception {
        serve((id, cursor) -> catalogResult(id, "public", -15, List.of(tool("gamma")), null));

        McpDiscoveryClient.Catalog catalog = client().listTools(request());

        assertEquals("public", catalog.cacheScope().orElseThrow());
        assertEquals(0, catalog.ttlMs());
    }

    @Test
    void rejectsNonPublicPrivateCacheScope() {
        serve((id, cursor) -> catalogResult(id, "tenant-7", 1000, List.of(tool("delta")), null));

        var failure = assertThrows(McpDiscoveryClient.DiscoveryException.class,
                () -> client().listTools(request()));
        assertEquals("mcp_tool_catalog_invalid_cache_hints", failure.getMessage());
    }

    @Test
    void usesShortestTtlWhenPagesDifferAndRequiresStableScope() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        serve((id, cursor) -> {
            if (pages.getAndIncrement() == 0) {
                return catalogResult(id, "private", 5000, List.of(tool("zeta")), "page-2");
            }
            return catalogResult(id, "private", 0, List.of(tool("eta")), null);
        });

        McpDiscoveryClient.Catalog catalog = client().listTools(request());

        assertEquals("private", catalog.cacheScope().orElseThrow());
        assertEquals(0, catalog.ttlMs());
        assertEquals(List.of("eta", "zeta"),
                catalog.tools().stream().map(McpDiscoveryClient.Tool::name).toList());
    }

    @Test
    void rejectsCacheScopeChangeAcrossPages() {
        AtomicInteger pages = new AtomicInteger();
        serve((id, cursor) -> {
            if (pages.getAndIncrement() == 0) {
                return catalogResult(id, "public", 1000, List.of(tool("one")), "page-2");
            }
            return catalogResult(id, "private", 1000, List.of(tool("two")), null);
        });

        var failure = assertThrows(McpDiscoveryClient.DiscoveryException.class,
                () -> client().listTools(request()));
        assertEquals("mcp_tool_catalog_cache_hints_changed", failure.getMessage());
    }

    @Test
    void rejectsHttpFailureEvenWithValidCatalog() {
        serveRaw(503, "application/json", id -> catalogResult(id, null, null, List.of(), null));
        var failure = assertThrows(McpDiscoveryClient.DiscoveryException.class,
                () -> client().listTools(request()));
        assertEquals("mcp_tool_catalog_http_rejected", failure.getMessage());
    }

    @Test
    void acceptsSseMetadataCommentsNotificationsAndMultilineData() {
        serveRaw(200, "text/event-stream; charset=utf-8", id ->
                ": heartbeat\r\nevent: message\r\nid: event-1\r\n"
                + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\r\n\r\n"
                + "data: {\r\ndata: \"jsonrpc\":\"2.0\",\"id\":\"" + id
                + "\",\r\ndata: \"result\":{\"tools\":[]}}\r\n\r\n");
        assertTrue(client().listTools(request()).tools().isEmpty());
    }

    @Test
    void rejectsOversizedJsonAndSseResponses() {
        for (String type : List.of("application/json", "text/event-stream")) {
            serveRaw(200, type, id -> " ".repeat(65537));
            var failure = assertThrows(McpDiscoveryClient.DiscoveryException.class,
                    () -> client().listTools(request()));
            assertEquals("mcp_response_limit", failure.getCause().getMessage());
            server.removeContext("/mcp");
        }
    }

    @Test
    void rejectsUnexpectedContentType() {
        serveRaw(200, "text/html", id -> catalogResult(id, null, null, List.of(), null));
        assertThrows(McpDiscoveryClient.DiscoveryException.class, () -> client().listTools(request()));
    }

    @Test
    void rejectsSseResponseWithDifferentRequestId() {
        serveRaw(200, "text/event-stream", id -> "data: "
                + catalogResult("other-request", null, null, List.of(), null) + "\n\n");
        assertThrows(McpDiscoveryClient.DiscoveryException.class, () -> client().listTools(request()));
    }

    private void serveRaw(int status, String contentType, java.util.function.Function<String, String> responder) {
        server.createContext("/mcp", exchange -> {
            try (exchange) {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                byte[] response = responder.apply(request.path("id").textValue()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(status, 0);
                exchange.getResponseBody().write(response);
            }
        });
    }

    private McpDiscoveryClient client() {
        return new McpDiscoveryClient(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                uri -> uri);
    }

    private McpDiscoveryClient.Request request() {
        return new McpDiscoveryClient.Request("server-1", endpoint, null);
    }

    private void serve(Responder responder) {
        server.createContext("/mcp", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            byte[] response = responder.respond(
                    body.path("id").textValue(),
                    body.path("params").path("cursor").isTextual()
                            ? body.path("params").path("cursor").textValue() : null)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
    }

    private static String catalogResult(String id, String cacheScope, Integer ttlMs,
            List<ObjectNode> tools, String nextCursor) {
        try {
            ObjectNode result = JSON.createObjectNode();
            var array = result.putArray("tools");
            tools.forEach(array::add);
            if (cacheScope != null) {
                result.put("cacheScope", cacheScope);
            }
            if (ttlMs != null) {
                result.put("ttlMs", ttlMs);
            }
            if (nextCursor != null) {
                result.put("nextCursor", nextCursor);
            }
            ObjectNode root = JSON.createObjectNode();
            root.put("jsonrpc", "2.0");
            root.put("id", id);
            root.set("result", result);
            return JSON.writeValueAsString(root);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ObjectNode tool(String name) {
        ObjectNode tool = JSON.createObjectNode();
        tool.put("name", name);
        tool.put("description", name + " tool");
        tool.putObject("inputSchema").put("type", "object");
        return tool;
    }

    @FunctionalInterface
    private interface Responder {
        String respond(String id, String cursor);
    }
}

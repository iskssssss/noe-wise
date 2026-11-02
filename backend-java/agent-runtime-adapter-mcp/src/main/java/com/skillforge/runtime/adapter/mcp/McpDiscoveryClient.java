package com.skillforge.runtime.adapter.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** MCP Adapter 统一拥有的 {@code tools/list} 发现入口。 */
public final class McpDiscoveryClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAXIMUM_BYTES = 64 * 1024;
    private static final int MAXIMUM_PAGES = 256;
    private static final int MAXIMUM_TOOLS = 10_000;

    private final HttpClient http;
    private final UnaryOperator<URI> endpointGuard;

    /** endpointGuard 必须在网络发送前完成宿主 SSRF 策略校验。 */
    public McpDiscoveryClient(HttpClient http, UnaryOperator<URI> endpointGuard) {
        this.http = Objects.requireNonNull(http, "http");
        this.endpointGuard = Objects.requireNonNull(endpointGuard, "endpointGuard");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("mcp_transport_configuration_invalid");
        }
    }

    /** 拉取完整、确定排序的工具目录；失败与合法空目录严格区分。 */
    public Catalog listTools(Request request) {
        Objects.requireNonNull(request, "request");
        URI endpoint = endpointGuard.apply(URI.create(request.endpoint()));
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null || endpoint.getRawQuery() != null
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))) {
            throw new DiscoveryException("mcp_tool_catalog_invalid_endpoint");
        }
        try {
            Optional<String> session = Optional.empty();
            if (McpHandshake.VERSION.equals(request.protocolVersion())) {
                String id = "mcp-initialize:" + UUID.randomUUID();
                var initialized = exchange(endpoint, request.bearerToken(), "initialize", null, Optional.empty(),
                        McpHandshake.initialize(id), id);
                McpHandshake.validate(initialized.packet(), id);
                session = McpHandshake.session(initialized.session());
                exchange(endpoint, request.bearerToken(), "notifications/initialized", request.protocolVersion(), session,
                        McpHandshake.initialized(), null);
            }
            List<Tool> tools = new ArrayList<>();
            Set<String> cursors = new HashSet<>();
            Optional<String> cursor = Optional.empty();
            Optional<String> cacheScope = Optional.empty();
            long ttlMs = 0;
            for (int page = 0; page < MAXIMUM_PAGES; page++) {
                Page value = page(endpoint, request.bearerToken(), cursor, request.protocolVersion(), session);
                if (page == 0) {
                    cacheScope = value.cacheScope();
                    ttlMs = value.ttlMs();
                } else if (!cacheScope.equals(value.cacheScope())) {
                    // 官方契约只要求同一次 list 的各页 cacheScope 一致；ttlMs 可以按页不同。
                    throw new DiscoveryException("mcp_tool_catalog_cache_hints_changed");
                } else {
                    // 完整目录快照取各页最短 TTL，避免把立即过期页当成可缓存。
                    ttlMs = Math.min(ttlMs, value.ttlMs());
                }
                tools.addAll(value.tools());
                if (tools.size() > MAXIMUM_TOOLS) {
                    throw new DiscoveryException("mcp_tool_catalog_limit");
                }
                cursor = value.nextCursor();
                if (cursor.isEmpty()) {
                    tools.sort(Comparator.comparing(Tool::name));
                    Set<String> names = new HashSet<>();
                    if (tools.stream().anyMatch(tool -> !names.add(tool.name()))) {
                        throw new DiscoveryException("mcp_tool_catalog_duplicate_name");
                    }
                    return new Catalog(request.serverIdentity(), cacheScope, ttlMs, List.copyOf(tools));
                }
                if (!cursors.add(cursor.orElseThrow())) {
                    throw new DiscoveryException("mcp_tool_catalog_cursor_cycle");
                }
            }
            throw new DiscoveryException("mcp_tool_catalog_page_limit");
        } catch (DiscoveryException failure) {
            throw failure;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new DiscoveryException("mcp_tool_catalog_unavailable", failure);
        }
    }

    private Page page(URI endpoint, String token, Optional<String> cursor, String protocol, Optional<String> session)
            throws IOException, InterruptedException {
        String id = "mcp-tools-list:" + UUID.randomUUID();
        var params = JSON.createObjectNode();
        cursor.ifPresent(value -> params.put("cursor", value));
        var metadata = params.putObject("_meta");
        metadata.put("io.modelcontextprotocol/protocolVersion", McpProtocol.PROTOCOL_VERSION);
        metadata.putObject("io.modelcontextprotocol/clientInfo")
                .put("name", "skillforge-agent-runtime").put("version", "1");
        metadata.putObject("io.modelcontextprotocol/clientCapabilities")
                .putObject("tools").put("listChanged", true);
        var root = JSON.createObjectNode();
        root.put("jsonrpc", "2.0").put("id", id).put("method", "tools/list").set("params", params);
        byte[] payload = JSON.writeValueAsBytes(root);
        if (McpHandshake.VERSION.equals(protocol)) payload = McpHandshake.business(payload);
        JsonNode packet = exchange(endpoint, token, "tools/list", protocol, session, payload, id).packet();
        JsonNode result = McpProtocol.result(packet, id);
        JsonNode rawTools = result.get("tools");
        if (rawTools == null || !rawTools.isArray()) throw new DiscoveryException("mcp_tool_catalog_invalid");
        List<Tool> tools = new ArrayList<>();
        for (JsonNode value : rawTools) tools.add(tool(value));
        Optional<String> next = result.hasNonNull("nextCursor")
                ? Optional.of(text(result.get("nextCursor"))) : Optional.empty();
        return new Page(List.copyOf(tools), next, cacheScope(result), ttlMs(result));
    }

    private Exchange exchange(URI endpoint, String token, String method, String protocol,
            Optional<String> session, byte[] payload, String id) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Method", method);
        if (protocol != null) builder.header("MCP-Protocol-Version", protocol);
        session.ifPresent(value -> builder.header("Mcp-Session-Id", value));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        var response = http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(
                payload)).build(), HttpResponse.BodyHandlers.ofInputStream());
        JsonNode packet;
        try (var body = response.body()) {
            if (!McpHttpResponseReader.successful(response.statusCode())) {
                throw new DiscoveryException("mcp_tool_catalog_http_rejected");
            }
            if (id == null) return new Exchange(null, Optional.empty());
            // InputStream 在响应头之后仍可能阻塞；页级截止负责主动关闭正文。
            Thread watchdog = Thread.startVirtualThread(() -> {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
                    body.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) { /* 主读取路径报告传输失败。 */ }
            });
            try {
                packet = McpHttpResponseReader.packet(response, id, MAXIMUM_BYTES, () -> {
                    if (System.nanoTime() >= deadline) {
                        throw new DiscoveryException("mcp_tool_catalog_timeout");
                    }
                });
            } finally {
                watchdog.interrupt();
                watchdog.join();
            }
        }
        return new Exchange(packet, response.headers().firstValue("Mcp-Session-Id"));
    }
    private record Exchange(JsonNode packet, Optional<String> session) { }

    private Tool tool(JsonNode value) {
        if (!value.isObject() || !value.path("inputSchema").isObject()) {
            throw new DiscoveryException("mcp_tool_catalog_invalid_tool");
        }
        String name = text(value.get("name"));
        JsonNode declaredDescription = value.get("description");
        if (declaredDescription != null && !declaredDescription.isTextual())
            throw new DiscoveryException("mcp_tool_catalog_invalid_text");
        String description = declaredDescription == null ? "" : declaredDescription.textValue();
        List<Icon> icons = new ArrayList<>();
        if (value.has("icons")) {
            if (!value.get("icons").isArray()) throw new DiscoveryException("mcp_tool_catalog_invalid_icon");
            for (JsonNode icon : value.get("icons")) {
                List<String> sizes = new ArrayList<>();
                if (icon.has("sizes")) icon.get("sizes").forEach(size -> sizes.add(text(size)));
                icons.add(new Icon(text(icon.get("src")), optionalText(icon.get("mimeType")),
                        List.copyOf(sizes), optionalText(icon.get("theme"))));
            }
        }
        return new Tool(name, optionalText(value.get("title")), description,
                map(value.get("inputSchema")), value.has("outputSchema")
                        ? Optional.of(map(value.get("outputSchema"))) : Optional.empty(),
                List.copyOf(icons), value.has("annotations")
                        ? Optional.of(map(value.get("annotations"))) : Optional.empty());
    }

    /**
     * 解析 cacheScope：缺省表示未声明；出现则必须是 public/private。
     *
     * @param result tools/list 的 result 对象
     * @return 声明的缓存范围
     */
    private static Optional<String> cacheScope(JsonNode result) {
        JsonNode value = result.get("cacheScope");
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        String scope = text(value);
        if (!"public".equals(scope) && !"private".equals(scope)) {
            throw new DiscoveryException("mcp_tool_catalog_invalid_cache_hints");
        }
        return Optional.of(scope);
    }

    /**
     * 解析 ttlMs：缺省或负数按官方契约视为立即过期（0）；0 是合法值。
     *
     * @param result tools/list 的 result 对象
     * @return 毫秒 TTL，0 表示不得缓存
     */
    private static long ttlMs(JsonNode result) {
        JsonNode value = result.get("ttlMs");
        if (value == null || value.isNull()) {
            return 0;
        }
        if (!value.canConvertToLong()) {
            throw new DiscoveryException("mcp_tool_catalog_invalid_cache_hints");
        }
        long ttl = value.longValue();
        return ttl < 0 ? 0 : ttl;
    }

    private static Map<String, Object> map(JsonNode value) {
        if (value == null || !value.isObject()) throw new DiscoveryException("mcp_tool_catalog_invalid_object");
        return JSON.convertValue(value, new TypeReference<>() { });
    }
    private static String text(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new DiscoveryException("mcp_tool_catalog_invalid_text");
        return value.textValue();
    }
    private static Optional<String> optionalText(JsonNode value) {
        return value == null || value.isNull() ? Optional.empty() : Optional.of(text(value));
    }

    private record Page(List<Tool> tools, Optional<String> nextCursor,
                        Optional<String> cacheScope, long ttlMs) { }

    public record Request(String serverIdentity, String endpoint, String bearerToken, String protocolVersion) {
        public Request(String serverIdentity, String endpoint, String bearerToken) {
            this(serverIdentity, endpoint, bearerToken, McpProtocol.PROTOCOL_VERSION);
        }
        public Request {
            if (!McpHandshake.supported(protocolVersion) || serverIdentity == null || serverIdentity.isBlank() || endpoint == null || endpoint.isBlank()
                    || bearerToken != null && bearerToken.isBlank()) throw new IllegalArgumentException("mcp_discovery_request_invalid");
        }
        @Override public String toString() { return "McpDiscoveryRequest[server=" + serverIdentity + ", credential=REDACTED]"; }
    }
    public record Catalog(String serverIdentity, Optional<String> cacheScope, long ttlMs, List<Tool> tools) {
        public String qualifiedName(Tool tool) { return serverIdentity + "/" + tool.name(); }
    }
    public record Tool(String name, Optional<String> title, String description,
                       Map<String, Object> inputSchema, Optional<Map<String, Object>> outputSchema,
                       List<Icon> icons, Optional<Map<String, Object>> annotations) { }
    public record Icon(String src, Optional<String> mimeType, List<String> sizes, Optional<String> theme) { }

    public static final class DiscoveryException extends RuntimeException {
        public DiscoveryException(String message) { super(message); }
        public DiscoveryException(String message, Throwable cause) { super(message, cause); }
    }
}

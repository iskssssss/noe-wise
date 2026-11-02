package com.skillforge.runtime.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Optional;

/** 显式握手传输的版本协商；不从 HTTP 错误正文猜测版本，也不重试业务调用。 */
final class McpHandshake {
    static final String VERSION = "2025-11-25";
    private static final ObjectMapper JSON = new ObjectMapper();
    private McpHandshake() { }

    static boolean supported(String version) {
        return McpProtocol.PROTOCOL_VERSION.equals(version) || VERSION.equals(version);
    }
    static byte[] initialize(String id) throws IOException {
        var request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", "initialize");
        var params = request.putObject("params").put("protocolVersion", VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "skillforge-agent-runtime").put("version", "1");
        return JSON.writeValueAsBytes(request);
    }
    static void validate(JsonNode packet, String id) throws IOException {
        var result = McpProtocol.result(packet, id);
        if (!VERSION.equals(result.path("protocolVersion").asText())
                || !result.path("capabilities").path("tools").isObject()) {
            throw new IllegalArgumentException("mcp_negotiated_protocol_unsupported");
        }
    }
    static Optional<String> session(Optional<String> value) {
        value.ifPresent(id -> {
            if (id.isEmpty() || id.length() > 1024 || id.chars().anyMatch(c -> c < 0x21 || c > 0x7e))
                throw new IllegalArgumentException("mcp_session_id_invalid");
        });
        return value;
    }
    static byte[] initialized() {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    static byte[] business(byte[] payload) throws IOException {
        var root = (ObjectNode) McpProtocol.parse(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
        var params = (ObjectNode) root.path("params");
        if (params.has("inputResponses") || params.has("requestState"))
            throw new IllegalArgumentException("mcp_handshake_continuation_unsupported");
        if (params.path("_meta") instanceof ObjectNode metadata) {
            metadata.remove(java.util.List.of("io.modelcontextprotocol/protocolVersion", "io.modelcontextprotocol/clientInfo", "io.modelcontextprotocol/clientCapabilities"));
            if (metadata.isEmpty()) params.remove("_meta");
        }
        return JSON.writeValueAsBytes(root);
    }
    static JsonNode result(JsonNode result) {
        if (result.has("resultType")) throw new IllegalArgumentException("mcp_handshake_result_invalid");
        return ((ObjectNode) result.deepCopy()).put("resultType", "complete");
    }
}

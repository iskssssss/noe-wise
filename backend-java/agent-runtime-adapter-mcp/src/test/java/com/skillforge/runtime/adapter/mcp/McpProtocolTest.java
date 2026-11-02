package com.skillforge.runtime.adapter.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** MCP 2026 无状态封套、多轮结果与资源上限，均在无外部效果的协议层验证。 */
class McpProtocolTest {
    @Test void buildsCanonicalToolCallWithPerRequestProtocolMetadata() throws Exception {
        var call = McpProtocol.parse(new String(McpProtocol.call("call-1", "search", new TypedPayload("arguments.v1", "{\"a\":1,\"z\":\"你好😀\"}")), StandardCharsets.UTF_8));
        assertEquals("2.0", call.path("jsonrpc").textValue());
        assertEquals("call-1", call.path("id").textValue());
        assertEquals("tools/call", call.path("method").textValue()); assertEquals("search", call.path("params").path("name").textValue());
        assertEquals("你好😀", call.path("params").path("arguments").path("z").textValue());
        assertModernMetadata(call);
    }

    @Test void carriesTrustedExtraParametersOutsideModelArguments() throws Exception {
        var call = McpProtocol.parse(new String(McpProtocol.call("call-context", "search",
                new TypedPayload("arguments.v1", "{}"), Optional.of(new TypedPayload(
                        "application/json", "{\"buildingParkId\":\"clinic-1\",\"future\":2}"))),
                StandardCharsets.UTF_8));
        assertEquals("clinic-1", call.path("params").path("_meta")
                .path("com.skillforge/extraParameters").path("buildingParkId").textValue());
        assertTrue(call.path("params").path("arguments").isEmpty());
    }

    @Test void rejectsNoncanonicalOrNonobjectArgumentsAndBlankRpcFields() {
        for (String value : List.of("[]", "null", "{\"z\":1,\"a\":2}", "{ \"a\":1}", "{} {}"))
            assertSafe(() -> McpProtocol.call("call", "tool", new TypedPayload("arguments.v1", value)));
        assertSafe(() -> McpProtocol.call("", "tool", new TypedPayload("arguments.v1", "{}")));
        assertSafe(() -> McpProtocol.call("call", " ", new TypedPayload("arguments.v1", "{}")));
    }

    /** Runtime 规范参数的每种 number 形状都必须通过出站字节相等检查；指数与尾随零写法不是同一份字节。 */
    @Test void acceptsRuntimeCanonicalNumbersAndRejectsAliasWritings() {
        for (String canonical : List.of("{\"page\":1}", "{\"pageSize\":10}", "{\"total\":1200}",
                "{\"ratio\":0.25}", "{\"tiny\":0.0000001}", "{\"big\":9007199254740993123456789}",
                "{\"offset\":-10.5}", "{\"zero\":0}"))
            assertDoesNotThrow(() -> McpProtocol.call("call", "tool", new TypedPayload("arguments.v1", canonical)), canonical);
        for (String alias : List.of("{\"page\":1e1}", "{\"page\":1E+2}", "{\"page\":10.0}", "{\"page\":-0}"))
            assertSafe(() -> McpProtocol.call("call", "tool", new TypedPayload("arguments.v1", alias)));
    }

    /** 服务端返回的 number 在入站边界一次性收敛为普通十进制；短 token 不得展开成巨串。 */
    @Test void normalizesServerNumberWritingsIntoPlainDecimal() throws Exception {
        var observation = McpProtocol.observation(McpProtocol.parse("{\"resultType\":\"complete\",\"content\":[],"
                + "\"structuredContent\":{\"a\":1E+2,\"b\":1.50,\"c\":1e1,\"d\":-0,\"e\":1e-7}}"));
        assertTrue(observation.canonicalJson().contains(
                "{\"a\":100,\"b\":1.5,\"c\":10,\"d\":0,\"e\":0.0000001}"), observation.canonicalJson());
        assertSafe(() -> McpProtocol.observation(McpProtocol.parse(
                "{\"resultType\":\"complete\",\"content\":[],\"structuredContent\":{\"x\":1e1000000}}")));
    }

    @Test void acceptsOnlyExactJsonRpcResponseIdAndExclusiveResult() throws Exception {
        var result = McpProtocol.result(McpProtocol.parse("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"content\":[]}}"), "x");
        assertTrue(result.path("content").isArray());
        for (String envelope : List.of(
                "{\"jsonrpc\":\"1.0\",\"id\":\"x\",\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"other\",\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":null}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{},\"error\":null}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":null}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":1.5,\"message\":\"bad\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":1,\"message\":1}}"))
            assertSafe(() -> McpProtocol.result(McpProtocol.parse(envelope), "x"));
        var error = assertThrows(McpProtocol.RpcError.class, () -> McpProtocol.result(McpProtocol.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":-1,\"message\":\"private-provider-secret\"}}"), "x"));
        assertEquals("mcp_rpc_error", error.getMessage()); assertNull(error.getCause());
        assertEquals(McpProtocol.RPC_FAILURE_SCHEMA, error.comparisonFact().orElseThrow().schemaId());
        assertEquals("private-provider-secret", McpProtocol.parse(error.comparisonFact().orElseThrow().canonicalJson()).path("message").asText());
        assertFalse(error.toString().contains("private-provider-secret"));
    }

    /** 小型合法响应的指数展开可能超出规范正文预算，但不改变确定失败。 */
    @Test void preservesDeterminateErrorsWhenCanonicalExpansionExceedsBudget() throws Exception {
        String manyNumbers = "[" + "1e999,".repeat(1100) + "0]";
        var rpc = McpProtocol.parse("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":-1,\"message\":\"bad\",\"data\":" + manyNumbers + "}}");
        var rejected = assertThrows(McpProtocol.RpcError.class, () -> McpProtocol.result(rpc, "x"));
        assertTrue(rejected.comparisonFact().isEmpty());
        var result = McpProtocol.parse("{\"resultType\":\"complete\",\"isError\":true,\"content\":[],\"structuredContent\":" + manyNumbers + "}");
        assertTrue(McpProtocol.failureObservation(result).isEmpty());
        assertSafe(() -> McpProtocol.failureObservation(McpProtocol.parse("{\"resultType\":\"complete\",\"isError\":true,\"content\":[{}]}")));
    }

    @Test void canonicalizesControlCharactersInErrorBodiesAndObjectKeys() throws Exception {
        var error = assertThrows(McpProtocol.RpcError.class, () -> McpProtocol.result(McpProtocol.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"error\":{\"code\":1,\"message\":\"\\u000B\\u001F\",\"data\":{\"\\u001F\":\"x\"}}}"), "x"));
        assertEquals("{\"code\":1,\"data\":{\"\\u001f\":\"x\"},\"message\":\"\\u000b\\u001f\"}", error.comparisonFact().orElseThrow().canonicalJson());
        var result = McpProtocol.parse("{\"content\":[],\"isError\":true,\"resultType\":\"complete\",\"structuredContent\":{\"text\":\"\\u000B\\u001F\"}}");
        assertTrue(McpProtocol.failureObservation(result).orElseThrow().canonicalJson().contains("\\u000b\\u001f"));
    }

    @Test void preservesNontextContentStructuredDataExtensionsAndExactLargeNumbers() throws Exception {
        String source = "{\"resultType\":\"complete\",\"structuredContent\":{\"z\":9007199254740993,\"a\":{\"v\":0.1234567890123456789}},\"isError\":false,\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"AQID\"},{\"type\":\"resource\",\"resource\":{\"uri\":\"test://item\",\"text\":\"body\"}}],\"_meta\":{\"source\":\"server\"}}";
        var parsed = McpProtocol.parse(source); var observation = McpProtocol.observation(parsed);
        assertEquals(McpProtocol.OBSERVATION_SCHEMA, observation.schemaId());
        assertEquals(parsed, McpProtocol.parse(observation.canonicalJson()));
        assertTrue(observation.canonicalJson().startsWith("{\"_meta\":"));
        assertTrue(observation.canonicalJson().contains("9007199254740993"));
        assertTrue(observation.canonicalJson().contains("0.1234567890123456789"));
        assertEquals("image", McpProtocol.parse(observation.canonicalJson()).path("content").get(0).path("type").textValue());
        assertTrue(McpProtocol.parse(McpProtocol.observation(McpProtocol.parse(
                "{\"resultType\":\"complete\",\"content\":[],\"isError\":true}")).canonicalJson()).path("isError").booleanValue());
        McpProtocol.observation(McpProtocol.parse(
                "{\"resultType\":\"complete\",\"content\":[],\"structuredContent\":[1,null]}"));
        McpProtocol.observation(McpProtocol.parse(
                "{\"resultType\":\"complete\",\"content\":[],\"structuredContent\":null}"));
    }

    @Test void mapsOfficialInputRequiredAndBuildsExactContinuationWire() throws Exception {
        String state = "opaque / 不解析 😀";
        var required = McpProtocol.inputRequired(McpProtocol.parse("""
                {"resultType":"input_required","inputRequests":{
                  "github_login":{"method":"elicitation/create","params":{"message":"登录","mode":"form","requestedSchema":{"type":"object","properties":{}}}},
                  "workspace_roots":{"method":"roots/list"}},
                 "requestState":"opaque / 不解析 😀"}
                """));
        assertEquals(Set.of("github_login", "workspace_roots"), required.requests().keySet());
        assertEquals(ToolInputMethod.ELICITATION_CREATE,
                required.requests().get("github_login").method());
        assertEquals("{\"message\":\"登录\",\"mode\":\"form\",\"requestedSchema\":{\"properties\":{},\"type\":\"object\"}}",
                required.requests().get("github_login").params().canonicalJson());
        assertEquals(ToolInputMethod.ROOTS_LIST,
                required.requests().get("workspace_roots").method());
        assertEquals("{}", required.requests().get("workspace_roots").params().canonicalJson());
        assertEquals(Optional.of(state), required.requestState());

        var response = new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE,
                new TypedPayload("mcp.elicitation-result.v1",
                        "{\"action\":\"accept\",\"content\":{\"name\":\"octocat\"}}"));
        var continuation = McpProtocol.parse(new String(McpProtocol.continueCall(
                "call-2", "search", new TypedPayload("arguments.v1", "{\"query\":\"weather\"}"),
                Map.of("github_login", response), Optional.of(state)), StandardCharsets.UTF_8));
        assertEquals("call-2", continuation.path("id").textValue());
        assertEquals("tools/call", continuation.path("method").textValue());
        assertEquals("weather", continuation.path("params").path("arguments").path("query").textValue());
        assertEquals("accept", continuation.path("params").path("inputResponses")
                .path("github_login").path("action").textValue());
        assertFalse(continuation.path("params").path("inputResponses")
                .path("github_login").has("method"));
        assertFalse(continuation.path("params").path("inputResponses")
                .path("github_login").has("result"));
        assertEquals(state, continuation.path("params").path("requestState").textValue());
        assertModernMetadata(continuation);

        var stateOnly = McpProtocol.parse(new String(McpProtocol.continueCall(
                "call-3", "search", new TypedPayload("arguments.v1", "{}"),
                Map.of(), Optional.of(state)), StandardCharsets.UTF_8));
        assertFalse(stateOnly.path("params").has("inputResponses"));
        assertEquals(state, stateOnly.path("params").path("requestState").textValue());
        assertModernMetadata(stateOnly);
    }

    @Test void rejectsMalformedInputRequiredAndContinuationPayloads() {
        for (String result : List.of(
                "{}",
                "{\"resultType\":\"input_required\"}",
                "{\"resultType\":\"input_required\",\"inputRequests\":[]}",
                "{\"resultType\":\"input_required\",\"inputRequests\":{\"x\":{\"method\":\"unsupported\",\"params\":{}}}}",
                "{\"resultType\":\"input_required\",\"inputRequests\":{\"x\":{\"method\":\"elicitation/create\"}}}",
                "{\"resultType\":\"input_required\",\"inputRequests\":{\"x\":{\"method\":\"roots/list\",\"params\":{},\"extra\":true}}}",
                "{\"resultType\":\"input_required\",\"requestState\":1}")) {
            assertSafe(() -> McpProtocol.inputRequired(McpProtocol.parse(result)));
        }
        assertSafe(() -> McpProtocol.continueCall("call", "tool",
                new TypedPayload("arguments.v1", "{}"), Map.of(), Optional.empty()));
        assertSafe(() -> McpProtocol.continueCall("call", "tool",
                new TypedPayload("arguments.v1", "{}"),
                Map.of("x", new ToolInputResponse(ToolInputMethod.ROOTS_LIST,
                        new TypedPayload("response.v1", "{ \"roots\":[]}"))), Optional.empty()));
    }

    @Test void rejectsMalformedMcpResultShapes() {
        for (String result : List.of("{}", "{\"content\":{}}", "{\"content\":[1]}", "{\"content\":[{}]}",
                "{\"content\":[{\"type\":2}]}", "{\"resultType\":\"input_required\",\"content\":[]}",
                "{\"resultType\":\"future\",\"content\":[]}", "{\"resultType\":1,\"content\":[]}",
                "{\"content\":[],\"isError\":\"true\"}"))
            assertSafe(() -> McpProtocol.observation(McpProtocol.parse(result)));
    }

    @Test void rejectsDuplicateTrailingDeepLargeAndInvalidUnicodeInputWithSafeErrors() {
        for (String json : List.of("{\"private\":1,\"private\":2}", "{} {}", "{\"private-provider-secret\":", "[".repeat(65) + "0" + "]".repeat(65),
                "\"" + "x".repeat(1_048_577) + "\"", "\"\\uD800\"")) assertSafe(() -> McpProtocol.parse(json));
    }

    private static void assertSafe(Checked operation) {
        var exception = assertThrows(IOException.class, operation::run);
        assertEquals("mcp_protocol_invalid", exception.getMessage()); assertNull(exception.getCause());
    }
    private static void assertModernMetadata(JsonNode request) {
        var metadata = request.path("params").path("_meta");
        assertEquals(McpProtocol.PROTOCOL_VERSION,
                metadata.path("io.modelcontextprotocol/protocolVersion").textValue());
        assertEquals("skillforge-agent-runtime",
                metadata.path("io.modelcontextprotocol/clientInfo").path("name").textValue());
        var capabilities = metadata.path("io.modelcontextprotocol/clientCapabilities");
        assertTrue(capabilities.path("roots").isObject());
        assertTrue(capabilities.path("sampling").isObject());
        assertTrue(capabilities.path("elicitation").path("form").isObject());
        assertTrue(capabilities.path("elicitation").path("url").isObject());
    }
    @FunctionalInterface private interface Checked { void run() throws IOException; }
}

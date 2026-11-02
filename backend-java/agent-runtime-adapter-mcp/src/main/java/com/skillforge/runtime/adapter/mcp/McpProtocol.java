package com.skillforge.runtime.adapter.mcp;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputRequest;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** 有界 MCP JSON-RPC wire；不负责网络、身份授权或 Tool 业务 schema。 */
final class McpProtocol {
    static final String PROTOCOL_VERSION = "2026-07-28";
    static final String TOOL_CALL_METHOD = "tools/call";
    static final String OBSERVATION_SCHEMA = "skillforge.runtime.tool.result.v1";
    static final String RPC_FAILURE_SCHEMA = "skillforge.runtime.tool.mcp-rpc-failure.v1";
    static final String HTTP_FAILURE_SCHEMA = "skillforge.runtime.tool.mcp-http-failure.v1";
    private static final int MAXIMUM_BYTES = 1_048_576;
    /** 与 RuntimeJson 的规范转义一致，其他 ASCII 控制字符使用小写十六进制。 */
    private static final CharacterEscapes CANONICAL_ESCAPES = new CharacterEscapes() {
        private final int[] codes = standardAsciiEscapesForJSON();
        {
            for (int index = 0; index < 32; index++) {
                if (codes[index] == ESCAPE_STANDARD) codes[index] = ESCAPE_CUSTOM;
            }
        }
        @Override public int[] getEscapeCodesForAscii() { return codes; }
        @Override public SerializableString getEscapeSequence(int character) {
            if (character >= 32 || codes[character] != ESCAPE_CUSTOM) return null;
            return new SerializedString("\\u00" + Character.forDigit(character >>> 4, 16)
                    + Character.forDigit(character & 15, 16));
        }
    };
    /** 与 Runtime 规范形式同值的普通记数法展开上限；两侧不同值会让同一份字节一边接受一边拒绝。 */
    private static final int MAXIMUM_NUMBER_DIGITS = 1000;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                    .maxStringLength(MAXIMUM_BYTES).maxNumberLength(MAXIMUM_NUMBER_DIGITS).build()).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            // 规范 number 不带指数，DecimalNode 必须按 toPlainString 写出。
            .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);

    private McpProtocol() { }

    static byte[] call(String id, String toolName, TypedPayload arguments) throws IOException {
        return call(id, toolName, arguments, Optional.empty());
    }

    /** 参数必须是已规范化对象；业务 schema 仍由传输层强制 registry 验证。 */
    static byte[] call(String id, String toolName, TypedPayload arguments,
            Optional<TypedPayload> extraParameters) throws IOException {
        requireText(toolName);
        if (arguments == null) throw invalid();
        var value = parse(arguments.canonicalJson());
        if (!value.isObject() || !arguments.canonicalJson().equals(canonicalJson(value))) throw invalid();
        var root = request(id, TOOL_CALL_METHOD); var params = root.putObject("params");
        params.put("name", toolName); params.set("arguments", value);
        requestMetadata(params, extraParameters);
        return encode(root);
    }

    /** continuation 保持原 Tool 与参数，只附加本轮精确响应和服务端 opaque 状态。 */
    static byte[] continueCall(String id, String toolName, TypedPayload arguments,
            Map<String, ToolInputResponse> responses, Optional<String> requestState)
            throws IOException {
        return continueCall(id, toolName, arguments, responses, requestState, Optional.empty());
    }

    static byte[] continueCall(String id, String toolName, TypedPayload arguments,
            Map<String, ToolInputResponse> responses, Optional<String> requestState,
            Optional<TypedPayload> extraParameters) throws IOException {
        requireText(toolName);
        if (arguments == null || responses == null || requestState == null
                || responses.isEmpty() && requestState.isEmpty()) throw invalid();
        var argumentValue = parse(arguments.canonicalJson());
        if (!argumentValue.isObject()
                || !arguments.canonicalJson().equals(canonicalJson(argumentValue))) throw invalid();
        var root = request(id, TOOL_CALL_METHOD);
        var params = root.putObject("params");
        params.put("name", toolName);
        params.set("arguments", argumentValue);
        if (!responses.isEmpty()) {
            var responseValues = params.putObject("inputResponses");
            var requestIds = new TreeSet<String>();
            for (var entry : responses.entrySet()) {
                requireText(entry.getKey());
                if (entry.getValue() == null) throw invalid();
                requestIds.add(entry.getKey());
            }
            for (String requestId : requestIds) {
                ToolInputResponse response = responses.get(requestId);
                // method 只绑定 Session 权威请求，MCP wire 的 map value 必须是对应请求的原始 Result。
                requireText(response.method().wireName());
                JsonNode result = parse(response.result().canonicalJson());
                if (!response.result().canonicalJson().equals(canonicalJson(result))) throw invalid();
                responseValues.set(requestId, result);
            }
        }
        if (requestState.isPresent()) {
            requireText(requestState.orElseThrow());
            params.put("requestState", requestState.orElseThrow());
        }
        requestMetadata(params, extraParameters);
        return encode(root);
    }

    /** 重复键、额外文档、过深/过大及非法 Unicode 都以固定异常失败关闭。 */
    static JsonNode parse(String json) throws IOException {
        try {
            if (json == null || json.length() > MAXIMUM_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) throw invalid();
            requireUnicode(json);
            try (var parser = JSON.createParser(json)) {
                JsonNode root = JSON.readTree(parser);
                if (root == null || parser.nextToken() != null) throw invalid();
                requireTree(root, 0);
                return root;
            }
        } catch (IOException | RuntimeException ignored) { throw invalid(); }
    }

    /** 精确 string id 对应唯一响应；供应商 error 正文绝不作为异常 message/cause 返回。 */
    static JsonNode result(JsonNode root, String id) throws IOException {
        requireText(id);
        if (root == null || !root.isObject() || !root.path("jsonrpc").isTextual()
                || !"2.0".equals(root.path("jsonrpc").textValue()) || !root.path("id").isTextual()
                || !id.equals(root.path("id").textValue()) || root.has("result") == root.has("error")) throw invalid();
        if (root.has("error")) {
            var error = root.get("error");
            if (!error.isObject() || !error.path("code").isIntegralNumber() || !error.path("message").isTextual()) throw invalid();
            Optional<TypedPayload> fact;
            try { fact = Optional.of(new TypedPayload(RPC_FAILURE_SCHEMA, canonicalJson(error))); }
            catch (IOException unavailable) { fact = Optional.empty(); }
            throw new RpcError(fact);
        }
        var result = root.get("result");
        if (!result.isObject()) throw invalid();
        return result;
    }

    /** 完整保留 MCP complete 结果（包括非文本 content 和任意 JSON structuredContent）。 */
    static TypedPayload observation(JsonNode result) throws IOException {
        validateObservation(result);
        return new TypedPayload(OBSERVATION_SCHEMA, canonicalJson(result));
    }

    /** 已验证 isError 的确定结果：规范正文超限时仅失去跨来源比较能力。 */
    static Optional<TypedPayload> failureObservation(JsonNode result) throws IOException {
        validateObservation(result);
        try { return Optional.of(new TypedPayload(OBSERVATION_SCHEMA, canonicalJson(result))); }
        catch (IOException unavailable) { return Optional.empty(); }
    }

    private static void validateObservation(JsonNode result) throws IOException {
        if (result == null || !result.isObject() || !result.path("content").isArray()
                || !result.path("resultType").isTextual()
                || !"complete".equals(result.path("resultType").textValue())) throw invalid();
        for (var item : result.path("content")) {
            if (!item.isObject()) throw invalid();
            textField(item, "type");
        }
        if (result.has("isError") && !result.get("isError").isBoolean()) throw invalid();
    }

    /** 解析 MCP 2026 多轮输入结果；未知方法、重复请求或畸形状态一律拒绝。 */
    static InputRequired inputRequired(JsonNode result) throws IOException {
        if (result == null || !result.isObject()
                || !result.path("resultType").isTextual()
                || !"input_required".equals(result.path("resultType").textValue())
                || result.has("inputRequests") && !result.get("inputRequests").isObject()) throw invalid();
        Map<String, ToolInputRequest> requests = new LinkedHashMap<>();
        if (result.has("inputRequests")) {
            var fields = result.get("inputRequests").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                requireText(entry.getKey());
                var request = entry.getValue();
                if (!request.isObject() || !request.path("method").isTextual()) throw invalid();
                ToolInputMethod method;
                try {
                    method = ToolInputMethod.fromWireName(request.path("method").textValue());
                } catch (IllegalArgumentException rejected) {
                    throw invalid();
                }
                if (request.size() != (request.has("params") ? 2 : 1)
                        || request.has("params") && !request.get("params").isObject()
                        || !request.has("params") && method != ToolInputMethod.ROOTS_LIST) throw invalid();
                JsonNode params = request.has("params")
                        ? request.get("params") : JSON.createObjectNode();
                var value = new ToolInputRequest(method, new TypedPayload(
                        "skillforge.runtime.tool.mcp-input-request.v1", canonicalJson(params)));
                if (requests.put(entry.getKey(), value) != null) throw invalid();
            }
        }
        Optional<String> requestState = Optional.empty();
        if (result.has("requestState")) {
            if (!result.get("requestState").isTextual()) throw invalid();
            String value = result.get("requestState").textValue();
            requireText(value);
            requestState = Optional.of(value);
        }
        if (requests.isEmpty() && requestState.isEmpty()) throw invalid();
        return new InputRequired(Map.copyOf(requests), requestState);
    }

    /** 私有解析结果，避免协议层依赖 Action/Session 事实。 */
    record InputRequired(Map<String, ToolInputRequest> requests,
                         Optional<String> requestState) { }

    /** 现代 MCP 每个请求都自描述版本与客户端能力，不依赖连接或历史握手。 */
    private static void requestMetadata(ObjectNode params, Optional<TypedPayload> extraParameters)
            throws IOException {
        var metadata = params.putObject("_meta");
        metadata.put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION);
        metadata.putObject("io.modelcontextprotocol/clientInfo")
                .put("name", "skillforge-agent-runtime")
                .put("version", "1");
        var capabilities = metadata.putObject(
                "io.modelcontextprotocol/clientCapabilities");
        capabilities.putObject("roots");
        capabilities.putObject("sampling");
        var elicitation = capabilities.putObject("elicitation");
        elicitation.putObject("form");
        elicitation.putObject("url");
        if (extraParameters != null && extraParameters.isPresent()) {
            TypedPayload payload = extraParameters.orElseThrow();
            if (!"application/json".equals(payload.schemaId())) throw invalid();
            JsonNode value = parse(payload.canonicalJson());
            if (!value.isObject()) throw invalid();
            metadata.set("com.skillforge/extraParameters", value);
        }
    }

    /** RPC 请求只由显式 method/id 构造；参数不能注入 JSON-RPC envelope。 */
    private static ObjectNode request(String id, String method) throws IOException {
        requireText(id); var root = JSON.createObjectNode(); root.put("jsonrpc", "2.0"); root.put("id", id); root.put("method", method); return root;
    }

    /** 编码同样执行结构和字节上限，避免大参数通过出站路径绕过入站预算。 */
    private static byte[] encode(JsonNode value) throws IOException {
        try {
            requireTree(value, 0); byte[] bytes = JSON.writeValueAsBytes(value);
            if (bytes.length > MAXIMUM_BYTES) throw invalid();
            return bytes;
        } catch (IOException | RuntimeException ignored) { throw invalid(); }
    }

    /** 对象键递归排序，数组次序及非文本内容语义保持不变。 */
    private static String canonicalJson(JsonNode value) throws IOException {
        try {
            requireTree(value, 0);
            // Writer 保留合法补充平面字符；UTF-8 generator 会将 surrogate 转成转义，不能据此拒绝 Core 规范输入。
            String text = JSON.writer().with(CANONICAL_ESCAPES).writeValueAsString(canonical(value));
            if (text.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) throw invalid();
            return text;
        } catch (IOException | RuntimeException ignored) { throw invalid(); }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var result = JSON.createObjectNode(); var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, canonical(value.get(key)))); return result;
        }
        if (value.isArray()) { var result = JSON.createArrayNode(); value.forEach(item -> result.add(canonical(item))); return result; }
        if (value.isNumber()) { return DecimalNode.valueOf(plainDecimal(value.decimalValue())); }
        return value;
    }

    /**
     * number 在入站边界一次性归一化为 Runtime 规范形式：零折叠为 0、去尾随零、普通十进制不带指数。
     * 保留供应商写法会让 10 与 1e1 成为同一数值的两串字节，出站字节相等检查与 Session wire 都会因此拒绝合法参数。
     * 展开位数按精度与标度估算后越界即拒绝，避免 1e1000000 这类短 token 膨胀成巨串。
     */
    private static BigDecimal plainDecimal(BigDecimal value) {
        if (value.signum() == 0) { return BigDecimal.ZERO; }
        BigDecimal stripped = value.stripTrailingZeros();
        long scale = stripped.scale();
        long precision = stripped.precision();
        long digits = scale <= 0 ? precision - scale : scale < precision ? precision + 1 : scale + 2;
        if (digits > MAXIMUM_NUMBER_DIGITS) { throw new IllegalArgumentException("mcp_number_too_long"); }
        return stripped;
    }

    /** 对传入树也验证层级/Unicode，避免直接构造 JsonNode 绕过 parser 的约束。 */
    private static void requireTree(JsonNode value, int depth) throws IOException {
        if (value == null || depth > 64) throw invalid();
        if (value.isTextual()) requireUnicode(value.textValue());
        if (value.isObject()) {
            var names = value.fieldNames(); while (names.hasNext()) requireUnicode(names.next());
        }
        if (value.isContainerNode()) for (var child : value) requireTree(child, depth + 1);
    }

    private static void textField(JsonNode value, String field) throws IOException {
        if (!value.path(field).isTextual()) throw invalid(); requireText(value.path(field).textValue());
    }
    private static void requireText(String value) throws IOException {
        if (value == null || value.isBlank()) throw invalid(); requireUnicode(value);
    }
    private static void requireUnicode(String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(ch)) throw invalid();
        }
    }
    private static IOException invalid() { return new IOException("mcp_protocol_invalid"); }

    /** 包含完整 HTTP 错误正文的私有事实；不采集可能含凭据的响应头。 */
    static TypedPayload httpFailure(int status, String contentType, byte[] body) throws IOException {
        var fact = JSON.createObjectNode();
        fact.put("status", status);
        fact.put("contentType", contentType);
        fact.put("bodyBase64", java.util.Base64.getEncoder().encodeToString(body));
        return new TypedPayload(HTTP_FAILURE_SCHEMA, canonicalJson(fact));
    }

    /** 匹配 id 的合法 RPC error 保存私有事实，异常消息仍是固定字符串。 */
    static final class RpcError extends IOException {
        private static final long serialVersionUID = 1L;
        private final Optional<TypedPayload> comparisonFact;
        private RpcError(Optional<TypedPayload> comparisonFact) { super("mcp_rpc_error"); this.comparisonFact = comparisonFact; }
        Optional<TypedPayload> comparisonFact() { return comparisonFact; }
    }
}

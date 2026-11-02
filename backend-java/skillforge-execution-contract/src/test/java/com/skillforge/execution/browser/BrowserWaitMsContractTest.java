package com.skillforge.execution.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 固定上游 wait-ms 的纯参数契约；不运行 CLI、daemon 或浏览器设备。 */
class BrowserWaitMsContractTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 官方计时命令只有毫秒位置参数；可信宿主会话不能变成 CLI 不支持的 flag。 */
    @Test void pinnedDaemonWaitUsesOnlyTypedDurationWithoutSessionFlag() throws Exception {
        try (var input = BrowserCommandContract.class.getResourceAsStream("/browser-cli-contract.json")) {
            JsonNode contract = json.readTree(input);
            assertEquals("3f10983c09fcd443c7420830ab984c9d409954bf", contract.path("upstream_commit").asText());
            JsonNode wait = contract.path("operations").path("wait-ms");
            assertEquals(json.valueToTree(List.of("wait-ms")), wait.path("command"));
            assertFalse(wait.path("session").asBoolean());
            assertEquals(1, wait.path("fields").size());
            assertEquals("duration", wait.path("fields").get(0).path("name").asText());
        }
        var parsed = BrowserParameters.parse("wait-ms", parameters(Map.of("duration", 120)));
        assertInstanceOf(BrowserParameters.WaitMsParameters.class, parsed);
        var typed = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(parsed);
        typed.remove("_operation");
        assertEquals(List.of("--json", "wait-ms", "120"), BrowserCommandContract.arguments(
                "wait-ms", typed, "bound-session", "bound-browser", Path.of("owned-output")));
        JsonNode schema = StreamSupport.stream(json.valueToTree(BrowserCommandContract.schema()).path("oneOf").spliterator(), false)
                .filter(node -> "wait-ms".equals(node.path("properties").path("operation").path("const").asText()))
                .findFirst().orElseThrow().path("properties").path("parameters");
        assertEquals(json.valueToTree(List.of("duration")), schema.path("required"));
        assertEquals("integer", schema.path("properties").path("duration").path("type").asText());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(1, schema.path("properties").size());
    }

    /** 取消 session 输出不开放身份或 flag 注入，也不放宽原有必填及类型限制。 */
    @Test void daemonWaitStillRejectsIdentityInjectionMissingDurationAndWrongTypes() throws Exception {
        for (String forbidden : List.of("session", "browser", "tenant", "end_user", "task", "out")) {
            var injected = parameters(Map.of("duration", 120, forbidden, "foreign-owner"));
            assertEquals("browser_parameters_rejected", assertThrows(IllegalArgumentException.class,
                    () -> arguments(injected)).getMessage(), forbidden);
            assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                    () -> BrowserParameters.parse("wait-ms", injected), forbidden);
        }
        assertEquals("browser_parameter_required:duration", assertThrows(IllegalArgumentException.class,
                () -> arguments(parameters(Map.of()))).getMessage());
        for (Object wrong : List.of("120", "--session=foreign", 1.5, true, List.of(120))) {
            assertEquals("browser_parameter_type:duration", assertThrows(IllegalArgumentException.class,
                    () -> arguments(parameters(Map.of("duration", wrong)))).getMessage());
        }
    }

    /** 按公开 JSON 结构构造请求，避免直接修改内部类型标记。 */
    private JsonNode parameters(Map<String, Object> values) {
        return json.valueToTree(values);
    }

    /** 只调用真实映射器，不通过 Shell 或 CLI 消费这些参数。 */
    private List<String> arguments(JsonNode values) {
        return BrowserCommandContract.arguments("wait-ms", values, "bound-session", "bound-browser", Path.of("owned-output"));
    }
}

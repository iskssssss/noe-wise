package com.skillforge.execution.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 固定34操作的正负传输序列；协议测试不冒充用户浏览器或人工授权动作。 */
class BrowserCommandComplexContractTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 所有可选字段都带真实类型，避免只覆盖空参数或必填字段。 */
    @Test void allThirtyFourOperationsKeepTypedFieldsAndTrustedBindings() throws Exception {
        assertEquals(34, BrowserCommandContract.operations().size());
        for (String operation : BrowserCommandContract.operations()) {
            ObjectNode values = sample(operation);
            var parsed = BrowserParameters.parse(operation, values);
            assertEquals(operation, parsed.operation());
            ObjectNode serialized = json.valueToTree(parsed);
            serialized.remove("_operation");
            var args = BrowserCommandContract.arguments(operation, serialized, "bound-session", "bound-browser", Path.of("owned-output"));
            assertEquals("--json", args.getFirst(), operation);
            assertFalse(args.isEmpty());
            assertTrue(args.stream().noneMatch(value -> value.contains("foreign-owner")), operation);
            if (operation.equals("evaluate")) assertTrue(args.contains("(()=>({rows:72,text:'中文 `$()'}))()"));
            if (operation.equals("record-start")) assertTrue(args.contains("bound-browser"));
            if (operation.equals("screenshot")) assertTrue(args.contains(Path.of("owned-output", "screenshot.png").toString()));
        }
    }

    /** 已授权字段集合不能被未知字段、错误类型或宿主身份扩展。 */
    @Test void everyOperationRejectsIdentityInjectionAndEachFieldTypeMutation() throws Exception {
        for (String operation : BrowserCommandContract.operations()) {
            ObjectNode legal = sample(operation);
            for (String forbidden : List.of("tenant", "user", "session", "browser", "out", "service_token")) {
                ObjectNode changed = legal.deepCopy().put(forbidden, "foreign-owner");
                assertThrows(IllegalArgumentException.class, () -> arguments(operation, changed), operation + ":" + forbidden);
            }
            for (JsonNode field : fields(operation)) {
                ObjectNode wrong = legal.deepCopy();
                String name = field.path("name").asText();
                if (field.path("type").asText().equals("string")) wrong.put(name, 72);
                else wrong.put(name, "wrong-type");
                assertThrows(IllegalArgumentException.class, () -> arguments(operation, wrong), operation + ":" + name);
            }
        }
    }

    /** 缺必填字段、枚举越界及脚本参数边界分别取自合法基线。 */
    @Test void requiredEnumAndScriptBoundariesCannotBecomeCliFlags() throws Exception {
        for (String operation : BrowserCommandContract.operations()) {
            for (JsonNode field : fields(operation)) {
                String name = field.path("name").asText();
                if (field.path("required").asBoolean()) {
                    ObjectNode missing = sample(operation); missing.remove(name);
                    assertThrows(IllegalArgumentException.class, () -> arguments(operation, missing));
                }
                if (!field.path("enum").isEmpty()) {
                    ObjectNode wrong = sample(operation).put(name, "outside-enum");
                    assertThrows(IllegalArgumentException.class, () -> arguments(operation, wrong));
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> arguments("evaluate", json.createObjectNode().put("expression", "--browser=foreign")));
        assertTrue(Set.of("upload", "download", "shell").stream().noneMatch(BrowserCommandContract.operations()::contains));
    }

    /** 按冻结Schema构造丰富字段；这是协议样本，业务动作另由专用profile验收。 */
    private ObjectNode sample(String operation) throws Exception {
        ObjectNode values = json.createObjectNode();
        for (JsonNode field : fields(operation)) {
            String name = field.path("name").asText(), type = field.path("type").asText();
            if (!field.path("enum").isEmpty()) values.set(name, field.path("enum").get(0));
            else switch (type) {
                case "integer" -> values.put(name, name.equals("tab_id") ? 72 : 12);
                case "number" -> values.put(name, 1.5);
                case "boolean" -> values.put(name, true);
                case "array" -> values.putArray(name).add("@e1").add("@e2");
                default -> values.put(name, switch (name) {
                    case "expression" -> "(()=>({rows:72,text:'中文 `$()'}))()";
                    case "url" -> "http://127.0.0.1:18581/owned?case=72";
                    case "timeout", "settle" -> "1s";
                    case "selector" -> "#records";
                    case "key" -> "Enter";
                    default -> "中文-record-72";
                });
            }
        }
        return values;
    }

    private JsonNode fields(String operation) throws Exception {
        try (var input = BrowserCommandContract.class.getResourceAsStream("/browser-cli-contract.json")) {
            return json.readTree(input).path("operations").path(operation).path("fields");
        }
    }

    private List<String> arguments(String operation, ObjectNode values) {
        return BrowserCommandContract.arguments(operation, values, "bound-session", "bound-browser", Path.of("owned-output"));
    }
}

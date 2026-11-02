package com.skillforge.execution.browser;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** 固定 CLI 参数映射的纯协议回归，不启动或借用真实浏览器设备。 */
class BrowserCommandContractTest {
    /** 模型可见联合节点显式声明对象类型，每个操作仍绑定唯一判别值及封闭参数。 */
    @Test void publishedCommandSchemaDeclaresTypesWithoutOpeningOperationBranches() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var schema = json.valueToTree(BrowserCommandContract.schema());
        assertEquals("object", schema.path("type").asText());
        assertEquals("string", schema.at("/properties/operation/type").asText());
        assertEquals("object", schema.at("/properties/parameters/type").asText());
        assertEquals(json.valueToTree(List.of("operation", "parameters")), schema.path("required"));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        var branches = schema.path("oneOf");
        assertEquals(34, branches.size());
        var operations = new HashSet<String>();
        for (var branch : branches) {
            assertFalse(branch.has("type"));
            assertFalse(branch.has("additionalProperties"));
            assertFalse(branch.has("required"));
            var operation = branch.path("properties").path("operation");
            assertFalse(operation.has("type"));
            assertTrue(operations.add(operation.path("const").asText()));
            var parameters = branch.path("properties").path("parameters");
            assertFalse(parameters.has("type"));
            assertFalse(parameters.path("additionalProperties").asBoolean(true));
        }
        assertEquals(new HashSet<>(BrowserCommandContract.operations()), operations);
    }

    /** 单值格式不变，多值逐项重复 value flag，保留请求顺序和重复项。 */
    @Test void selectRepeatsValueFlagsWithoutChangingSingleValueOrArrayOrder() {
        var single = BrowserCommandContract.arguments("select", parameters(Map.of(
                "target", "#departments", "values", List.of("d2"))), "bound-session", "bound-browser", Path.of("output"));
        assertEquals(List.of("--json", "select", "--session", "bound-session", "#departments", "--value=d2"), single);
        var multiple = BrowserCommandContract.arguments("select", parameters(Map.of(
                "target", "#departments", "values", List.of("d2", "d5"))), "bound-session", "bound-browser", Path.of("output"));
        assertEquals(List.of("--json", "select", "--session", "bound-session", "#departments", "--value=d2", "--value=d5"), multiple);
        var repeated = BrowserCommandContract.arguments("select", parameters(Map.of(
                "target", "#departments", "values", List.of("d5", "d2", "d5"))), "bound-session", "bound-browser", Path.of("output"));
        assertEquals(List.of("--json", "select", "--session", "bound-session", "#departments", "--value=d5", "--value=d2", "--value=d5"), repeated);
    }

    /** 值中的逗号、空格或 flag 文本只是一个选项值，不能拆分为宿主身份或额外参数。 */
    @Test void selectKeepsEachRawOptionValueInsideItsOwnArgument() {
        var argv = BrowserCommandContract.arguments("select", parameters(Map.of("selector", "#departments",
                "values", List.of("d2,d5", "two words", "--session=foreign", "中文 `$()"))),
                "bound-session", "bound-browser", Path.of("output"));
        assertEquals(List.of("--json", "select", "--session", "bound-session", "--value=d2,d5",
                "--value=two words", "--value=--session=foreign", "--value=中文 `$()", "--selector=#departments"), argv);
    }

    /** 多值映射仍拒绝缺失值、非字符串数组项、未知身份字段和冒充 flag 的位置参数。 */
    @Test void selectRejectsMalformedValuesAndParameterBoundaryInjection() {
        assertEquals("browser_parameter_required:values", assertThrows(IllegalArgumentException.class,
                () -> BrowserCommandContract.arguments("select", parameters(Map.of("target", "#departments")),
                        "bound-session", "bound-browser", Path.of("output"))).getMessage());
        for (Object invalid : List.of("d2", List.of("d2", 5), List.of("d2", true))) {
            assertEquals("browser_parameter_type:values", assertThrows(IllegalArgumentException.class,
                    () -> BrowserCommandContract.arguments("select", parameters(Map.of("target", "#departments", "values", invalid)),
                            "bound-session", "bound-browser", Path.of("output"))).getMessage());
        }
        assertEquals("browser_parameters_rejected", assertThrows(IllegalArgumentException.class,
                () -> BrowserCommandContract.arguments("select", parameters(Map.of("target", "#departments",
                        "values", List.of("d2", "d5"), "session", "foreign")),
                        "bound-session", "bound-browser", Path.of("output"))).getMessage());
        assertEquals("browser_positional_flag_rejected", assertThrows(IllegalArgumentException.class,
                () -> BrowserCommandContract.arguments("select", parameters(Map.of("target", "--session=foreign", "values", List.of("d2", "d5"))),
                        "bound-session", "bound-browser", Path.of("output"))).getMessage());
    }

    @Test void helpWithoutOptionalTargetsUsesTheOfficialDefaultActiveTab(){
        var args=BrowserCommandContract.arguments("request-help",parameters(Map.of("prompt","请人工完成验证","title","人工协助")),"bound-session","bound-browser",Path.of("output"));
        assertTrue(args.contains("--prompt=请人工完成验证"));
        assertFalse(args.stream().anyMatch(arg->arg.startsWith("--target")));
        var withTarget=BrowserCommandContract.arguments("request-help",parameters(Map.of("prompt","验证","target",java.util.List.of("@e1"))),"bound-session","bound-browser",Path.of("output"));
        assertTrue(withTarget.contains("--target=@e1"));
        assertThrows(IllegalArgumentException.class,()->BrowserCommandContract.arguments("request-help",parameters(Map.of("title","缺少说明")),"bound-session","bound-browser",Path.of("output")));
    }
    private static com.fasterxml.jackson.databind.JsonNode parameters(Map<String,Object> values){return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(values);}
    @Test void officialScriptIsOneArgumentAndIdentityCannotBeOverridden(){
        var args=BrowserCommandContract.arguments("evaluate",parameters(Map.of("expression","(()=>{ return '`$()'; })()")),"bound-session","bound-browser",Path.of("output"));
        assertTrue(args.contains("(()=>{ return '`$()'; })()"));assertTrue(args.contains("bound-session"));
        assertThrows(IllegalArgumentException.class,()->BrowserCommandContract.arguments("evaluate",parameters(Map.of("expression","1","session","foreign")),"bound-session","bound-browser",Path.of("output")));
        assertThrows(IllegalArgumentException.class,()->BrowserCommandContract.arguments("screenshot",parameters(Map.of("out","outside")),"bound-session","bound-browser",Path.of("output")));
        assertFalse(BrowserCommandContract.operations().contains("upload"));assertFalse(BrowserCommandContract.operations().contains("download"));
    }
    @Test void navigationPreservesOfficialOptionalTabSemantics()throws Exception{
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var parsed=BrowserParameters.parse("navigate",parameters(Map.of("url","http://127.0.0.1:18481/browser-fixture.html")));
        var fields=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(parsed);fields.remove("_operation");
        var argv=BrowserCommandContract.arguments("navigate",fields,"bound-session","bound-browser",Path.of("output"));
        assertFalse(argv.stream().anyMatch(arg->arg.startsWith("--tab-id")));
        var schemas=json.valueToTree(BrowserCommandContract.schema()).path("oneOf");
        var navigate=java.util.stream.StreamSupport.stream(schemas.spliterator(),false)
                .filter(node->node.path("properties").path("operation").path("const").asText().equals("navigate")).findFirst().orElseThrow();
        String description=navigate.path("properties").path("parameters").path("properties").path("tab_id").path("description").asText();
        assertTrue(description.contains("actual Chrome tab ID"));
        assertTrue(description.contains("active tab"));
        assertTrue(description.contains("tab-list"));
    }

}

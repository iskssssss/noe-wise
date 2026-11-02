package com.skillforge.institution.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.execution.browser.BrowserCommandContract;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.SessionSchemaValidation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Runtime validator 对提取共同约束前后的接受集合保持一致；不连接浏览器或模型。 */
class BrowserCommandSchemaEquivalenceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void commonConstraintsPreserveAllOperationAndParameterBoundaries() {
        ObjectNode current = json.valueToTree(BrowserCommandContract.schema());
        var compact = new TypedPayload("json-schema", current.toString());
        var expanded = new TypedPayload("json-schema", expanded(current).toString());
        int samples = 0;
        for (var branch : current.path("oneOf")) {
            ObjectNode command = json.createObjectNode();
            command.put("operation", branch.at("/properties/operation/const").asText());
            JsonNode parametersSchema = branch.at("/properties/parameters");
            command.set("parameters", sample(parametersSchema));
            equivalent(compact, expanded, command, true); samples++;
            for (String missing : List.of("operation", "parameters")) {
                var changed = command.deepCopy(); changed.remove(missing);
                equivalent(compact, expanded, changed, false); samples++;
            }
            for (String field : List.of("operation", "parameters")) {
                for (JsonNode wrong : List.of(json.getNodeFactory().nullNode(), json.getNodeFactory().numberNode(1), json.createArrayNode(), json.getNodeFactory().textNode("invalid"))) {
                    var changed = command.deepCopy(); changed.set(field, wrong);
                    equivalent(compact, expanded, changed, false); samples++;
                }
            }
            var outerExtra = command.deepCopy().put("session", "foreign");
            equivalent(compact, expanded, outerExtra, false); samples++;
            var innerExtra = command.deepCopy(); innerExtra.with("parameters").put("session", "foreign");
            equivalent(compact, expanded, innerExtra, false); samples++;
            var fields = parametersSchema.path("properties").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                var missing = command.deepCopy(); missing.with("parameters").remove(field.getKey());
                boolean required = java.util.stream.StreamSupport.stream(parametersSchema.path("required").spliterator(), false)
                        .anyMatch(value -> value.asText().equals(field.getKey()));
                equivalent(compact, expanded, missing, !required); samples++;
                for (JsonNode wrong : List.of(json.getNodeFactory().nullNode(), json.createObjectNode())) {
                    var changed = command.deepCopy(); changed.with("parameters").set(field.getKey(), wrong);
                    equivalent(compact, expanded, changed, false); samples++;
                }
                if (field.getValue().has("enum")) {
                    var changed = command.deepCopy(); changed.with("parameters").put(field.getKey(), "not-in-enum");
                    equivalent(compact, expanded, changed, false); samples++;
                }
                if ("array".equals(field.getValue().path("type").asText())) {
                    var changed = command.deepCopy(); changed.with("parameters").set(field.getKey(), json.createArrayNode().add(1));
                    equivalent(compact, expanded, changed, false); samples++;
                }
            }
        }
        for (JsonNode nonObject : List.of(json.getNodeFactory().nullNode(), json.getNodeFactory().numberNode(1), json.createArrayNode(), json.getNodeFactory().textNode("{}"))) {
            equivalent(compact, expanded, nonObject, false); samples++;
        }
        assertEquals(34, current.path("oneOf").size());
        System.out.println("browser schema equivalent validation samples=" + samples);
    }

    /** 展开优化前每分支重复的约束，参数字段正文保持原样，避免维护第二份CLI契约。 */
    private ObjectNode expanded(ObjectNode current) {
        var old = current.deepCopy();
        old.remove(List.of("properties", "required", "additionalProperties"));
        for (var item : old.withArray("oneOf")) {
            var branch = (ObjectNode) item;
            branch.put("type", "object").put("additionalProperties", false);
            branch.set("required", current.path("required").deepCopy());
            ((ObjectNode) branch.at("/properties/operation")).put("type", "string");
            ((ObjectNode) branch.at("/properties/parameters")).put("type", "object");
        }
        return old;
    }

    private JsonNode sample(JsonNode schema) {
        if (schema.has("enum")) return schema.path("enum").get(0).deepCopy();
        String type = schema.path("type").asText();
        if ("object".equals(type) || schema.has("properties")) {
            var value = json.createObjectNode();
            schema.path("properties").fields().forEachRemaining(field -> value.set(field.getKey(), sample(field.getValue())));
            return value;
        }
        return switch (type) {
            case "array" -> json.createArrayNode().add("value");
            case "integer" -> json.getNodeFactory().numberNode(1);
            case "number" -> json.getNodeFactory().numberNode(1.5);
            case "boolean" -> json.getNodeFactory().booleanNode(true);
            default -> json.getNodeFactory().textNode("value");
        };
    }

    private void equivalent(TypedPayload compact, TypedPayload expanded, JsonNode value, boolean expected) {
        var payload = new TypedPayload("application/json", value.toString());
        assertEquals(expected, accepts(expanded, payload), "original schema boundary");
        assertEquals(expected, accepts(compact, payload), "compact schema boundary");
    }

    private boolean accepts(TypedPayload schema, TypedPayload value) {
        try { SessionSchemaValidation.validate(schema, value); return true; }
        catch (IllegalArgumentException rejected) { return false; }
    }
}

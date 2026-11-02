package com.skillforge.institution.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** 冻结槽的确定性编码：只规范实际 Set 字段，其他数组和字符串均保留原值与顺序。 */
final class WorkflowResolvedBindingsJson {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Set<String> SET_PATHS = Set.of(
            "/knowledge/*/target/sourceVersions", "/knowledge/*/target/source_versions",
            "/knowledge/*/target/allowedOperators", "/knowledge/*/target/allowed_operators",
            "/tools/*/target/sensitiveFields", "/tools/*/target/sensitive_fields",
            "/tools/*/target/providerRequirements", "/tools/*/target/provider_requirements");

    private WorkflowResolvedBindingsJson() { }

    public static String canonical(String value) {
        try {
            JsonNode root = JSON.readTree(value);
            if (root == null || !root.isObject()) throw invalid();
            return JSON.writeValueAsString(normalize(root, ""));
        } catch (java.io.IOException failure) { throw invalid(); }
    }

    private static JsonNode normalize(JsonNode value, String path) {
        if (SET_PATHS.contains(path) && !value.isArray()) throw invalid();
        if (value.isObject()) {
            var result = JSON.createObjectNode();
            var names = new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, normalize(value.get(name), path + "/"
                    + name.replace("~", "~0").replace("/", "~1").replace("*", "~2"))));
            return result;
        }
        if (!value.isArray()) return value;
        var items = new ArrayList<JsonNode>();
        value.forEach(item -> items.add(normalize(item, path + "/*")));
        if (SET_PATHS.contains(path)) {
            var unique = new HashSet<String>();
            for (var item : items) {
                if (path.endsWith("sourceVersions") || path.endsWith("source_versions")) {
                    String source = path.endsWith("sourceVersions") ? "sourceId" : "source_id";
                    String version = path.endsWith("sourceVersions") ? "sourceVersion" : "source_version";
                    if (!item.isObject() || item.size() != 2 || !text(item.get(source)) || !text(item.get(version))) throw invalid();
                } else if (!text(item)) throw invalid();
                if (!unique.add(item.toString())) throw invalid();
            }
            items.sort(Comparator.comparing(JsonNode::toString));
        }
        var result = JSON.createArrayNode(); items.forEach(result::add); return result;
    }

    private static boolean text(JsonNode value) { return value != null && value.isTextual() && !value.textValue().isBlank(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("workflow_resolved_bindings_invalid"); }
}

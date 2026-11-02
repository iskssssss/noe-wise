package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 宿主公开呈现和交互卡的可见文字选择器；结构化恢复与引用选择器不是正文。 */
public final class PublicPresentationTexts {
    private static final Set<String> TEXT_FIELDS = Set.of(
            "title", "label", "description", "message", "plan", "prompt", "summary", "text", "enum", "rows",
            "body", "value", "name", "statusLabel");
    private static final Set<String> INTERNAL_FIELDS = Set.of(
            "tool_wait", "schema_id", "schemaId", "id", "selector", "source", "toolHandle", "execution_id");
    private static final String PROPERTIES = "properties";

    private PublicPresentationTexts() { }

    public static List<String> read(JsonNode node) {
        var texts = new ArrayList<String>();
        collect(node, texts);
        return List.copyOf(texts);
    }

    private static void collect(JsonNode node, List<String> texts) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) { node.forEach(value -> collect(value, texts)); return; }
        if (!node.isObject()) return;
        node.fields().forEachRemaining(field -> {
            if (TEXT_FIELDS.contains(field.getKey())) decodedValues(field.getValue(), texts);
            else if (!INTERNAL_FIELDS.contains(field.getKey())) {
                // 表单缺少标题时用属性名称作可读标签，名称同样属于公开内容。
                if (PROPERTIES.equals(field.getKey()) && field.getValue().isObject())
                    field.getValue().fields().forEachRemaining(property -> {
                        if (property.getValue().path("title").asText().isBlank()) texts.add(property.getKey());
                    });
                collect(field.getValue(), texts);
            }
        });
    }

    private static void decodedValues(JsonNode node, List<String> texts) {
        if (node.isTextual()) texts.add(node.textValue());
        else if (node.isArray()) node.forEach(value -> decodedValues(value, texts));
        else if (node.isObject()) node.elements().forEachRemaining(value -> decodedValues(value, texts));
    }
}

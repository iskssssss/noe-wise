package com.skillforge.core.shared.web;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 按请求的 Jackson 属性定义逐段映射校验路径，保留集合下标与字典键。 */
final class JsonFieldPaths {
    private final ObjectMapper json;

    JsonFieldPaths(ObjectMapper json) {
        this.json = Objects.requireNonNull(json);
    }

    String externalPath(Object target, String path) {
        JavaType type = target == null ? null : json.constructType(target.getClass());
        var result = new StringBuilder();
        for (String segment : segments(path)) {
            int bracket = segment.indexOf('[');
            String property = bracket < 0 ? segment : segment.substring(0, bracket);
            String indexes = bracket < 0 ? "" : segment.substring(bracket);
            BeanPropertyDefinition definition = property(type, property);
            if (!result.isEmpty()) result.append('.');
            // 无请求目标的绑定错误没有可核对的 JSON 属性，保留原字段而不臆造别名。
            result.append(definition == null ? property : definition.getName()).append(indexes);
            type = definition == null ? null : definition.getPrimaryType();
            int indexDepth = 0;
            for (int index = 0; index < indexes.length(); index++) {
                char value = indexes.charAt(index);
                if (value == '[') {
                    if (indexDepth == 0 && type != null) type = type.getContentType();
                    indexDepth++;
                } else if (value == ']') indexDepth--;
            }
        }
        return result.toString();
    }

    private List<String> segments(String path) {
        var result = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < path.length(); index++) {
            char value = path.charAt(index);
            if (value == '[') depth++;
            else if (value == ']') depth--;
            else if (value == '.' && depth == 0) {
                result.add(path.substring(start, index));
                start = index + 1;
            }
        }
        result.add(path.substring(start));
        return result;
    }

    private BeanPropertyDefinition property(JavaType type, String name) {
        if (type == null) return null;
        if (type.isReferenceType()) type = type.getContentType();
        return json.getDeserializationConfig().introspect(type).findProperties().stream()
                .filter(property -> property.getInternalName().equals(name) || property.getName().equals(name))
                .findFirst().orElse(null);
    }
}

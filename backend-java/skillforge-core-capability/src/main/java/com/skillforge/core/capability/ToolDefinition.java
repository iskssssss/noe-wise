package com.skillforge.core.capability;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 可移植的 Tool 调用定义；远程 annotations 只是不可信提示，不参与宿主授权。 */
public record ToolDefinition(
        String name,
        Optional<String> title,
        String description,
        Map<String, Object> inputSchema,
        Optional<Map<String, Object>> outputSchema,
        List<Icon> icons,
        Optional<UntrustedAnnotations> annotations) {

    private static final int MAXIMUM_NAME_LENGTH = 128;

    public ToolDefinition {
        name = requireName(name);
        title = optionalText(title, "title");
        description = requireText(description, "description");
        inputSchema = immutableObject(inputSchema, "inputSchema");
        outputSchema = outputSchema == null
                ? Optional.empty()
                : outputSchema.map(value -> immutableObject(value, "outputSchema"));
        icons = immutableIcons(icons);
        annotations = annotations == null ? Optional.empty() : annotations;
    }

    /** MCP Tool 图标定义；保留协议字段而不把图标压扁为 URL 字符串。 */
    public record Icon(
            String src,
            Optional<String> mimeType,
            List<String> sizes,
            Optional<String> theme) {

        public Icon {
            src = requireAbsoluteUri(src, "icon.src");
            mimeType = optionalText(mimeType, "icon.mimeType");
            sizes = immutableTextList(sizes, "icon.sizes");
            theme = optionalText(theme, "icon.theme");
            theme.ifPresent(value -> {
                if (!value.equals("light") && !value.equals("dark")) {
                    throw new IllegalArgumentException("icon.theme must be light or dark");
                }
            });
        }
    }

    /** 明确标识远端 Tool annotations 的非授权语义。 */
    public record UntrustedAnnotations(Map<String, Object> value) {
        public UntrustedAnnotations {
            value = immutableObject(value, "annotations");
        }
    }

    private static String requireName(String value) {
        value = requireText(value, "name");
        if (!supportsName(value)) {
            throw new IllegalArgumentException(
                    "name must be a normalized MCP tool name of at most 128 characters");
        }
        return value;
    }

    /** 接入目录与发布校验共用同一远端工具名范围。 */
    public static boolean supportsName(String value) {
        return value != null && value.length() <= MAXIMUM_NAME_LENGTH
                && value.matches("[A-Za-z0-9._-]+");
    }

    private static Optional<String> optionalText(Optional<String> value, String field) {
        if (value == null) {
            return Optional.empty();
        }
        return value.map(text -> requireText(text, field));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " must be normalized non-blank text");
        }
        return value;
    }

    private static List<Icon> immutableIcons(List<Icon> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Icon> copy = new ArrayList<>(values.size());
        for (Icon value : values) {
            if (value == null) {
                throw new IllegalArgumentException("icons must not contain null");
            }
            copy.add(value);
        }
        if (copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException("icons must be distinct");
        }
        return List.copyOf(copy);
    }

    private static List<String> immutableTextList(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(requireText(value, field));
        }
        if (copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(field + " must contain distinct values");
        }
        return List.copyOf(copy);
    }

    private static String requireAbsoluteUri(String value, String field) {
        value = requireText(value, field);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a valid absolute URI", exception);
        }
        if (!uri.isAbsolute() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException(field + " must be absolute and omit user info");
        }
        return value;
    }

    private static Map<String, Object> immutableObject(
            Map<String, Object> source, String field) {
        if (source == null) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException(field + " keys must not be null");
            }
            copy.put(key, immutableJson(value, field));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJson(Object value, String field) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException(field + " contains a non-finite number");
            }
            return new BigDecimal(number.toString());
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException(field + " contains a non-finite number");
            }
            return new BigDecimal(number.toString());
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> object = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException(field + " object keys must be text");
                }
                object.put(text, immutableJson(child, field));
            });
            return Collections.unmodifiableMap(object);
        }
        if (value instanceof List<?> list) {
            List<Object> array = new ArrayList<>(list.size());
            for (Object child : list) {
                array.add(immutableJson(child, field));
            }
            return Collections.unmodifiableList(array);
        }
        throw new IllegalArgumentException(field + " must contain only JSON values");
    }
}

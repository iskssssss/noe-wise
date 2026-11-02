package com.skillforge.core.chat.tracing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 宿主侧把公开 Tool 结果和账本调用快照收成聊天/轨迹可用的入参与结果。
 * 不解码 Runtime 私有 record 类型，只提取 JSON 字符串并按 sensitiveFields 删键。
 */
public final class ToolCallIoProjection {
    private static final byte[] MAGIC = {'S', 'F', 'A', '1'};
    private static final int MAX_ENCODED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_COLLECTION_SIZE = 1_000_000;
    private static final int MAX_DEPTH = 128;
    private static final byte NULL = 0;
    private static final byte STRING = 1;
    private static final byte LONG = 2;
    private static final byte INTEGER = 3;
    private static final byte BOOLEAN = 4;
    private static final byte INSTANT = 5;
    private static final byte DURATION = 6;
    private static final byte ENUM = 7;
    private static final byte OPTIONAL = 8;
    private static final byte LIST = 9;
    private static final byte SET = 10;
    private static final byte RECORD = 11;
    private static final byte MAP = 12;

    private ToolCallIoProjection() {}

    /** 公开 tool-result 的安全 output；解析失败时保留 canonical 字符串。 */
    public static Optional<Object> result(JsonNode eventRoot, ObjectMapper mapper) {
        if (eventRoot == null || !eventRoot.isObject() || mapper == null) {
            return Optional.empty();
        }
        return payloadValue(eventRoot.get("output"), mapper);
    }

    /** 从 Action Ledger 的 execute_call_data 取出脱敏入参。 */
    public static Optional<Object> argumentsFromCallData(String executeCallData, ObjectMapper mapper) {
        if (executeCallData == null || executeCallData.isBlank() || mapper == null) {
            return Optional.empty();
        }
        String trimmed = executeCallData.trim();
        if (trimmed.startsWith("{")) {
            return argumentsFromInvocationSnapshot(trimmed, mapper);
        }
        for (String text : ledgerStrings(trimmed)) {
            Optional<Object> arguments = argumentsFromInvocationSnapshot(text, mapper);
            if (arguments.isPresent()) {
                return arguments;
            }
        }
        return Optional.empty();
    }

    /** invocation snapshot JSON 含 arguments.canonicalJson 与 target.sensitiveFields。 */
    public static Optional<Object> argumentsFromInvocationSnapshot(String snapshotJson, ObjectMapper mapper) {
        if (snapshotJson == null || snapshotJson.isBlank() || mapper == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(snapshotJson);
            if (root == null || !root.isObject() || !root.has("arguments")) {
                return Optional.empty();
            }
            Optional<Object> parsed = payloadValue(root.get("arguments"), mapper);
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            Object value = parsed.orElseThrow();
            if (value instanceof Map<?, ?> object) {
                return Optional.of(redact(object, sensitiveFields(root)));
            }
            return parsed;
        } catch (JsonProcessingException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Object> payloadValue(JsonNode payload, ObjectMapper mapper) {
        if (payload == null || payload.isNull() || !payload.isObject()) {
            return Optional.empty();
        }
        JsonNode canonical = payload.get("canonicalJson");
        if (canonical == null || !canonical.isTextual()) {
            return Optional.empty();
        }
        String json = canonical.textValue();
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, Object.class));
        } catch (JsonProcessingException ignored) {
            return Optional.of(json);
        }
    }

    private static Set<String> sensitiveFields(JsonNode snapshot) {
        JsonNode fields = snapshot.path("target").path("sensitiveFields");
        if (!fields.isArray() || fields.isEmpty()) {
            return Set.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode field : fields) {
            if (field != null && field.isTextual() && !field.textValue().isBlank()) {
                names.add(field.textValue());
            }
        }
        return Set.copyOf(names);
    }

    private static Map<String, Object> redact(Map<?, ?> source, Set<String> sensitiveFields) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || sensitiveFields.contains(key)) {
                continue;
            }
            copy.put(key, entry.getValue());
        }
        return Map.copyOf(copy);
    }

    private static List<String> ledgerStrings(String encoded) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length < MAGIC.length || bytes.length > MAX_ENCODED_BYTES) {
                return List.of();
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                for (byte expected : MAGIC) {
                    if (input.readByte() != expected) {
                        return List.of();
                    }
                }
                List<String> texts = new ArrayList<>();
                collectStrings(input, texts, 0);
                if (input.read() != -1) {
                    return List.of();
                }
                return List.copyOf(texts);
            }
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static void collectStrings(DataInputStream input, List<String> texts, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("codec depth exceeded");
        }
        byte tag = input.readByte();
        switch (tag) {
            case NULL -> {
            }
            case STRING -> texts.add(readText(input));
            case LONG -> input.readLong();
            case INTEGER -> input.readInt();
            case BOOLEAN -> input.readBoolean();
            case INSTANT -> {
                input.readLong();
                input.readInt();
            }
            case DURATION -> {
                input.readLong();
                input.readInt();
            }
            case ENUM -> {
                readText(input);
                readText(input);
            }
            case OPTIONAL -> {
                if (input.readBoolean()) {
                    collectStrings(input, texts, depth + 1);
                }
            }
            case LIST -> {
                int count = readCount(input);
                for (int index = 0; index < count; index++) {
                    collectStrings(input, texts, depth + 1);
                }
            }
            case SET -> {
                int count = readCount(input);
                for (int index = 0; index < count; index++) {
                    collectPrefixed(input, texts, depth + 1);
                }
            }
            case RECORD -> {
                readText(input);
                int count = readCount(input);
                for (int index = 0; index < count; index++) {
                    readText(input);
                    collectStrings(input, texts, depth + 1);
                }
            }
            case MAP -> {
                int count = readCount(input);
                for (int index = 0; index < count; index++) {
                    collectPrefixed(input, texts, depth + 1);
                    collectPrefixed(input, texts, depth + 1);
                }
            }
            default -> throw new IOException("unknown codec tag");
        }
    }

    private static void collectPrefixed(DataInputStream input, List<String> texts, int depth)
            throws IOException {
        int length = readLength(input, MAX_ENCODED_BYTES);
        byte[] nested = input.readNBytes(length);
        if (nested.length != length) {
            throw new EOFException("truncated codec blob");
        }
        try (DataInputStream nestedInput = new DataInputStream(new ByteArrayInputStream(nested))) {
            collectStrings(nestedInput, texts, depth);
            if (nestedInput.read() != -1) {
                throw new IOException("trailing codec blob");
            }
        }
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = readLength(input, MAX_TEXT_BYTES);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("truncated codec text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readCount(DataInputStream input) throws IOException {
        return readLength(input, MAX_COLLECTION_SIZE);
    }

    private static int readLength(DataInputStream input, int max) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > max) {
            throw new IOException("codec length exceeded");
        }
        return length;
    }
}

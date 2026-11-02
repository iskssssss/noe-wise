package com.skillforge.core.capability.integration.runtime.tool;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** SQL 投影的 Tool 策略集合编码；长度按 UTF-8 字节计算，避免分隔符与 Unicode 歧义。 */
public final class JdbcToolPolicyProjection {
    private JdbcToolPolicyProjection() {
    }

    public static Set<String> decodeTextSet(String encoded) {
        if (encoded == null) {
            throw invalid();
        }
        byte[] bytes = utf8(encoded);
        var values = new LinkedHashSet<String>();
        byte[] previous = null;
        int cursor = 0;
        while (cursor < bytes.length) {
            int digits = cursor;
            long length = 0;
            while (cursor < bytes.length && bytes[cursor] != ':') {
                int digit = bytes[cursor] - '0';
                if (digit < 0 || digit > 9 || (cursor == digits && digit == 0)) {
                    throw invalid();
                }
                length = Math.addExact(Math.multiplyExact(length, 10), digit);
                if (length > Integer.MAX_VALUE) {
                    throw invalid();
                }
                cursor++;
            }
            if (cursor == digits || cursor >= bytes.length || length <= 0) {
                throw invalid();
            }
            cursor++;
            if (length > bytes.length - cursor) {
                throw invalid();
            }
            byte[] valueBytes = Arrays.copyOfRange(bytes, cursor, cursor + (int) length);
            if (previous != null && Arrays.compareUnsigned(previous, valueBytes) >= 0) {
                throw invalid();
            }
            String value = decodeUtf8(valueBytes);
            if (value.isBlank() || !value.equals(value.trim()) || !values.add(value)) {
                throw invalid();
            }
            previous = valueBytes;
            cursor += (int) length;
        }
        return Set.copyOf(values);
    }

    /** 与 SQL 的 {@code ORDER BY convert_to(value,'UTF8')} 保持相同稳定顺序。 */
    public static List<String> canonicalTextSet(Set<String> source) {
        if (source == null) {
            throw invalid();
        }
        var values = new ArrayList<Value>(source.size());
        for (String value : source) {
            if (value == null || value.isBlank() || !value.equals(value.trim())) {
                throw invalid();
            }
            values.add(new Value(value, utf8(value)));
        }
        values.sort((left, right) -> Arrays.compareUnsigned(left.bytes(), right.bytes()));
        return values.stream().map(Value::text).toList();
    }

    private static byte[] utf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException rejected) {
            throw invalid();
        }
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException rejected) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("jdbc_tool_policy_projection_rejected");
    }

    private record Value(String text, byte[] bytes) {
    }
}

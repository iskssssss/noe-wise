package com.skillforge.runtime.memory;

import com.skillforge.runtime.api.TypedPayload;
import java.math.BigDecimal;
import java.util.Objects;

/** 在构造 Memory 规范 JSON 树之前，精确计量编码资源与 Unicode。 */
final class MemoryEncodingBudget {
    private final MemoryJson.Limits limits;
    private long codeUnits;
    private long nodes;

    MemoryEncodingBudget(MemoryJson.Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** 字段名计入节点；分隔符与成员数量按规范 JSON 精确计量。 */
    void object(int depth, String... names) {
        node(depth);
        units(2L + names.length + Math.max(0, names.length - 1));
        for (String name : names) {
            node(depth);
            text(name);
        }
    }

    /** 先用最小子节点数拒绝超大外部集合，再由调用方逐项计量。 */
    void array(int depth, int size) {
        node(depth);
        if (size < 0 || size > limits.maxNodes() - nodes
                || size > 0 && depth >= limits.maxDepth()) {
            invalid();
        }
        units(2L + Math.max(0L, (long) size - 1));
    }

    void string(int depth, String value) {
        node(depth);
        text(value);
    }

    void integer(int depth, long value) {
        string(depth, Long.toString(value));
    }

    void decimal(int depth, double value) {
        if (!Double.isFinite(value)) {
            invalid();
        }
        String canonical = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        string(depth, canonical.equals("-0") ? "0" : canonical);
    }

    void bool(int depth, boolean value) {
        node(depth);
        units(value ? 4 : 5);
    }

    void nil(int depth) {
        node(depth);
        units(4);
    }

    void payload(int depth, TypedPayload payload) {
        object(depth, "schemaId", "canonicalJson");
        string(depth + 1, payload.schemaId());
        string(depth + 1, payload.canonicalJson());
    }

    private void node(int depth) {
        if (depth < 1 || depth > limits.maxDepth() || ++nodes > limits.maxNodes()) {
            invalid();
        }
    }

    /** 逐 UTF-16 code unit 计算规范转义长度，不创建转义副本。 */
    private void text(String value) {
        Objects.requireNonNull(value, "value");
        units(2);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    invalid();
                }
                units(2);
            } else if (Character.isLowSurrogate(character)) {
                invalid();
            } else {
                units(switch (character) {
                    case '"', '\\', '\b', '\f', '\n', '\r', '\t' -> 2;
                    default -> character < 0x20 ? 6 : 1;
                });
            }
        }
    }

    private void units(long count) {
        if (count < 0 || codeUnits > limits.maxCodeUnits() - count) {
            invalid();
        }
        codeUnits += count;
    }

    private static void invalid() {
        throw new IllegalArgumentException("memory_event_encoding_resource_limit");
    }
}

package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.TenantId;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 集中实现 Port 公共值对象的构造期不变量。 */
final class PortContracts {

    /** 工具类不允许实例化。 */
    private PortContracts() {
    }

    /** 拒绝空引用。 */
    static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    /** 拒绝空白文本。 */
    static String requireText(String value, String field) {
        requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** 拒绝不能规范 JSON 编码的孤立 UTF-16 surrogate。 */
    static String requireUnicode(String value, String field) {
        requireNonNull(value, field);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException(field + " must be valid Unicode");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " must be valid Unicode");
            }
        }
        return value;
    }

    /** 拒绝非正版本、revision 或 fencing token。 */
    static long requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    /** 拒绝零值、负值或缺失的超时。 */
    static Duration requirePositive(Duration value, String field) {
        requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    /** 拒绝 null Optional，避免缺失语义被误写成空引用。 */
    static <T> Optional<T> requireOptional(Optional<T> value, String field) {
        return requireNonNull(value, field);
    }

    /** 防御性复制列表并拒绝 null 元素。 */
    static <T> List<T> immutableList(List<T> values, String field) {
        requireNonNull(values, field);
        for (T value : values) {
            requireNonNull(value, field + " element");
        }
        return List.copyOf(values);
    }

    /** 防御性复制列表并拒绝重复或 null 元素。 */
    static <T> List<T> immutableDistinctList(List<T> values, String field) {
        List<T> copy = immutableList(values, field);
        // 稳定引用集合不允许重复，否则重放与审计会出现歧义。
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
        return copy;
    }

    /** 防御性复制集合并拒绝 null 元素。 */
    static <T> Set<T> immutableSet(Set<T> values, String field) {
        requireNonNull(values, field);
        LinkedHashSet<T> copy = new LinkedHashSet<>();
        for (T value : values) {
            copy.add(requireNonNull(value, field + " element"));
        }
        return Set.copyOf(copy);
    }

    /** 拒绝 NaN、Infinity 与 0..1 之外的评分。 */
    static double requireUnitInterval(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be finite and within [0, 1]");
        }
        return value;
    }

    /** 防御性复制文本集合并拒绝空元素与空白值。 */
    static Set<String> immutableTextSet(Set<String> values, String field) {
        requireNonNull(values, field);
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            copy.add(requireText(value, field + " element"));
        }
        return Set.copyOf(copy);
    }

    /** 拒绝非 canonical 状态事件来源。 */
    static String requireSourceKind(String sourceKind) {
        sourceKind = requireText(sourceKind, "sourceKind");
        if (!Set.of("run", "execution", "action", "delegation").contains(sourceKind)) {
            throw new IllegalArgumentException("sourceKind must be canonical");
        }
        return sourceKind;
    }

    /** 拒绝跨租户组合。 */
    static void requireSameTenant(TenantId expected, TenantId actual, String field) {
        requireNonNull(expected, "expectedTenantId");
        requireNonNull(actual, field);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must match owner tenantId");
        }
    }
}

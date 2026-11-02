package com.skillforge.runtime.api;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 集中实现公开 record 的构造期不变量，避免各类型对空值、版本和租户规则产生分歧。
 */
final class ApiContracts {

    /** 工具类不允许实例化。 */
    private ApiContracts() {
    }

    /** 校验引用字段非空，并返回原值供紧凑的规范构造器赋值。 */
    static <T> T requireNonNull(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }

    /** 校验公开字符串字段存在可见内容。 */
    static String requireText(String value, String field) {
        requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** 校验可从零开始的 revision 或 sequence。 */
    static long requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    /** 校验已经提交事件使用的正 revision。 */
    static long requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    /** 校验公开整数预算使用正数。 */
    static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    /** 校验计数与耗时不能为负数。 */
    static int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    /** 将允许缺省的 Optional 规范化为空 Optional，并保留已经存在的值。 */
    static <T> Optional<T> normalize(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }

    /** 校验新 SDK 领域契约显式提供 Optional，禁止用 null 冒充 empty。 */
    static <T> Optional<T> requireOptional(Optional<T> value, String field) {
        return requireNonNull(value, field);
    }

    /** 防御性复制公开集合，并通过 List.copyOf 拒绝空元素。 */
    static <T> List<T> immutableList(List<T> value, String field) {
        requireNonNull(value, field);
        return List.copyOf(value);
    }

    /** 防御性复制需要唯一元素的公开集合，并拒绝重复身份。 */
    static <T> List<T> immutableDistinctList(List<T> value, String field) {
        List<T> copy = immutableList(value, field);
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
        return copy;
    }

    /** 防御性复制文本列表，并拒绝空白元素与重复身份。 */
    static List<String> immutableDistinctTextList(List<String> value, String field) {
        List<String> copy = immutableDistinctList(value, field);
        for (String item : copy) {
            requireText(item, field + " element");
        }
        return copy;
    }

    /** 防御性复制公开集合，并通过 Set.copyOf 拒绝空元素。 */
    static <T> Set<T> immutableSet(Set<T> value, String field) {
        requireNonNull(value, field);
        return Set.copyOf(value);
    }

    /** 防御性复制文本集合，并拒绝空白元素。 */
    static Set<String> immutableTextSet(Set<String> value, String field) {
        requireNonNull(value, field);
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String item : value) {
            copy.add(requireText(item, field + " element"));
        }
        return Set.copyOf(copy);
    }

    /** 校验归一化评分是有限的 0..1 数值。 */
    static double requireUnitInterval(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be finite and between 0 and 1");
        }
        return value;
    }

    /** 校验可选结束时间不早于起始时间。 */
    static Optional<java.time.Instant> requireNotBefore(
            Optional<java.time.Instant> value,
            java.time.Instant start,
            String field) {
        value = requireOptional(value, field);
        requireNonNull(start, "start");
        // 只有存在结束时间时才比较双时态边界。
        if (value.isPresent() && value.orElseThrow().isBefore(start)) {
            throw new IllegalArgumentException(field + " must not be before start");
        }
        return value;
    }

    /** 校验两个显式租户身份一致。 */
    static void requireSameTenant(TenantId expected, TenantId actual, String field) {
        requireNonNull(expected, "expectedTenantId");
        requireNonNull(actual, field);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must match tenantId");
        }
    }

    /** 校验同一公开对象中 Session 与 Run 的租户身份一致。 */
    static void requireSameTenant(SessionRef sessionRef, RunRef runRef) {
        requireNonNull(sessionRef, "sessionRef");
        requireNonNull(runRef, "runRef");
        if (!sessionRef.tenantId().equals(runRef.tenantId())) {
            throw new IllegalArgumentException("sessionRef and runRef tenantId must match");
        }
    }

    /** 校验 RuntimeEvent 的公共身份、正 revision、时间和租户边界。 */
    static void validateEvent(
            EventId eventId,
            SessionRef sessionRef,
            RunRef runRef,
            long sessionSequence,
            long runRevision,
            java.time.Instant occurredAt) {
        requireNonNull(eventId, "eventId");
        requireSameTenant(sessionRef, runRef);
        requirePositive(sessionSequence, "sessionSequence");
        requirePositive(runRevision, "runRevision");
        requireNonNull(occurredAt, "occurredAt");
    }
}

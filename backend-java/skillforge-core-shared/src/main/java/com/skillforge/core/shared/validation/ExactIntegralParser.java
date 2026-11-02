package com.skillforge.core.shared.validation;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 将持久化与入口边界的数值解析为精确整数，禁止截断、饱和或非有限浮点值。
 */
public final class ExactIntegralParser {

    /** 工具类不允许实例化。 */
    private ExactIntegralParser() {
    }

    /**
     * 解析 long；输入不是数学意义上的精确整数或超出 long 范围时返回 {@code null}。
     *
     * @param value 数字对象或十进制整数字符串
     * @return 精确 long，非法时返回 {@code null}
     */
    public static Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger integer) {
            try {
                return integer.longValueExact();
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        if (value instanceof BigDecimal decimal) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        if (value instanceof Number number) {
            // 浮点和自定义 Number 先转十进制，再使用 exact API 拒绝小数、NaN、Infinity 与越界值。
            try {
                return new BigDecimal(number.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                return null;
            }
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 要求输入是精确 long，供持久化 schema 在字段边界给出稳定错误。
     *
     * @param value 原始字段值
     * @param field 字段名称
     * @return 精确 long
     */
    public static long requireLong(Object value, String field) {
        Long parsed = parseLong(value);
        if (parsed == null) {
            throw new IllegalArgumentException(field + " must be an exact integer in the long range");
        }
        return parsed;
    }
}

package com.skillforge.core.foundation.identity;

import com.skillforge.core.shared.validation.ExactIntegralParser;
import java.util.Map;

/**
 * 入口终端用户身份的唯一解析边界。
 *
 * <p>Runtime 只能消费入口显式携带的 {@code end_user_id}。本类不查询
 * Session、租户默认用户或外部映射；缺失、非法或非正数一律拒绝。</p>
 */
public final class EndUserIdentity {

    /** 入口字段名。 */
    public static final String KEY = "end_user_id";

    private EndUserIdentity() {
    }

    /**
     * 从入口字段解析并校验终端用户 ID。
     *
     * @param value 入口字段值（数字或数字字符串）
     * @param source 错误消息中的边界名称
     * @return 正的终端用户 ID
     */
    public static Long require(Object value, String source) {
        Long parsed = parse(value);
        if (parsed == null || parsed <= 0) {
            throw new IllegalArgumentException(source + " requires a positive end_user_id");
        }
        return parsed;
    }

    /** 从 Map 入口字段解析并校验终端用户 ID。 */
    public static Long require(Map<?, ?> values, String source) {
        return require(values == null ? null : values.get(KEY), source);
    }

    /** 解析但不提供任何默认值；非法值返回 null。 */
    public static Long parse(Object value) {
        return ExactIntegralParser.parseLong(value);
    }
}

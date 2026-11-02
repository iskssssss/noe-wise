package com.skillforge.core.foundation.operations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** 审计附加数据的秘密字段过滤与资源预算；禁止把任意对象序列化进安全日志。 */
public final class AuditPayload {
    private AuditPayload() { }

    /** 返回独立数据树；密码、令牌、凭据、验证码和正文类字段统一遮盖。 */
    public static Map<String, Object> sanitize(Map<String, Object> source) {
        if (source == null) return Map.of();
        int[] budget = {0};
        return map(source, 0, budget);
    }

    /** 递归处理明确支持的 JSON 值，同时控制深度、条目和字符预算。 */
    private static Object value(Object item, int depth, int[] budget) {
        if (depth > 5 || ++budget[0] > 256) throw new IllegalArgumentException("audit_payload_limit");
        if (item == null || item instanceof Boolean || item instanceof Number) return item;
        if (item instanceof String text) {
            if (text.length() > 1024) throw new IllegalArgumentException("audit_payload_value_limit");
            return text;
        }
        if (item instanceof Map<?, ?> nested) return map(nested, depth, budget);
        if (item instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object child : list) copy.add(value(child, depth + 1, budget));
            return copy;
        }
        throw new IllegalArgumentException("audit_payload_type_invalid");
    }

    /** 不保留原始密钥名称的值；非字符串 JSON 键同样拒绝。 */
    private static Map<String, Object> map(Map<?, ?> source, int depth, int[] budget) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.length() > 128)
                throw new IllegalArgumentException("audit_payload_key_invalid");
            String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            boolean secret = normalized.matches(".*(password|passwd|token|secret|credential|authorization|cookie|otp|recoverycode|body|content).*" );
            copy.put(key, secret ? "[REDACTED]" : value(entry.getValue(), depth + 1, budget));
        }
        return copy;
    }
}

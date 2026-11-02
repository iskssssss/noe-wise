package com.skillforge.core.capability.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 连接器认证类型枚举。
 *
 * <p>与 {@code connector_instances.auth_type} 列取值一致，JSON 序列化输出
 * code 值，反序列化按 code 匹配。
 */
public enum ConnectorAuthType {

    /** 无认证。 */
    NONE("none"),

    /** API Key 认证。 */
    API_KEY("api_key"),

    /** Bearer Token 认证。 */
    BEARER("bearer");

    /** 认证类型字符串值。 */
    private final String code;

    /**
     * 构造认证类型枚举。
     *
     * @param code 认证类型字符串值
     */
    ConnectorAuthType(String code) {
        this.code = code;
    }

    /**
     * 获取认证类型字符串值。
     *
     * @return 认证类型字符串值
     */
    @JsonValue
    public String code() {
        return code;
    }

    /**
     * 按字符串值解析认证类型，未知值明确拒绝，避免被请求默认值解释为其他认证方式。
     *
     * @param code 认证类型字符串值
     * @return 认证类型枚举；null 输入保持 null
     */
    @JsonCreator
    public static ConnectorAuthType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ConnectorAuthType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的连接器认证方式");
    }
}

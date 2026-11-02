package com.skillforge.core.channel.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 渠道类型枚举。
 *
 * <p>与 {@code channels.channel_type} 列取值一致，JSON 序列化输出 code 值，
 * 反序列化按 code 匹配。
 */
public enum ChannelType {

    /** 网页组件渠道。 */
    WEB_WIDGET("web_widget"),

    /** OpenAPI 渠道。 */
    OPENAPI("openapi");

    /** 类型字符串值。 */
    private final String code;

    /**
     * 构造类型枚举。
     *
     * @param code 类型字符串值
     */
    ChannelType(String code) {
        this.code = code;
    }

    /**
     * 获取类型字符串值。
     *
     * @return 类型字符串值
     */
    @JsonValue
    public String code() {
        return code;
    }

    /**
     * 按字符串值解析类型，未知值返回 null。
     *
     * @param code 类型字符串值
     * @return 类型枚举；未匹配时返回 null
     */
    @JsonCreator
    public static ChannelType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ChannelType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}

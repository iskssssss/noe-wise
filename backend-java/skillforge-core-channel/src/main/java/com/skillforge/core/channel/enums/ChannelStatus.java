package com.skillforge.core.channel.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 渠道状态枚举。
 *
 * <p>与 {@code channels.status} 列取值一致，JSON 序列化输出 code 值，
 * 反序列化按 code 匹配。
 */
public enum ChannelStatus {

    /** 启用。 */
    ACTIVE("active"),

    /** 停用。 */
    DISABLED("disabled");

    /** 状态字符串值。 */
    private final String code;

    /**
     * 构造状态枚举。
     *
     * @param code 状态字符串值
     */
    ChannelStatus(String code) {
        this.code = code;
    }

    /**
     * 获取状态字符串值。
     *
     * @return 状态字符串值
     */
    @JsonValue
    public String code() {
        return code;
    }

    /**
     * 按字符串值解析状态，未知值返回 null。
     *
     * @param code 状态字符串值
     * @return 状态枚举；未匹配时返回 null
     */
    @JsonCreator
    public static ChannelStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ChannelStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}

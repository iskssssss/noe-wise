package com.skillforge.core.chat.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 转人工工单状态枚举。
 *
 * <p>与 {@code human_handoff_tickets.status} 列取值一致，JSON 序列化输出
 * code 值，反序列化按 code 匹配。
 */
public enum HandoffTicketStatus {

    /** 待分配。 */
    PENDING("pending"),

    /** 已接单。 */
    ASSIGNED("assigned"),

    /** 已解决。 */
    RESOLVED("resolved"),

    /** 已关闭。 */
    CLOSED("closed");

    /** 状态字符串值。 */
    private final String code;

    /**
     * 构造状态枚举。
     *
     * @param code 状态字符串值
     */
    HandoffTicketStatus(String code) {
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
    public static HandoffTicketStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (HandoffTicketStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}

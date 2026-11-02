package com.skillforge.core.billing.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 账单状态枚举。
 *
 * <p>与 {@code invoices.status} 列取值一致，JSON 序列化输出 code 值，
 * 反序列化按 code 匹配。
 */
public enum InvoiceStatus {

    /** 待支付。 */
    OPEN("open"),

    /** 已支付。 */
    PAID("paid"),

    /** 已逾期。 */
    OVERDUE("overdue"),

    /** 已作废。 */
    VOID("void");

    /** 状态字符串值。 */
    private final String code;

    /**
     * 构造状态枚举。
     *
     * @param code 状态字符串值
     */
    InvoiceStatus(String code) {
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
    public static InvoiceStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (InvoiceStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}

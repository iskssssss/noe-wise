package com.skillforge.core.billing.metering;

/** 金额事实状态；未知金额不允许用零伪装。 */
public enum AmountStatus {
    NOT_APPLICABLE("not_applicable"),
    PRICED("priced"),
    UNPRICED("unpriced"),
    UNRESOLVED("unresolved");

    private final String value;

    AmountStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

package com.skillforge.core.billing.metering;

/** 用量账本事件的事实类型；所有类型均为追加事件。 */
public enum UsageEventKind {
    MEASURED("measured"),
    USAGE_REPORT("usage_report"),
    RECONCILIATION("reconciliation"),
    ADJUSTMENT("adjustment");

    private final String value;

    UsageEventKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

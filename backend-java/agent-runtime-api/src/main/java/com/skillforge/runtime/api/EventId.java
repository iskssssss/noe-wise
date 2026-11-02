package com.skillforge.runtime.api;

/** 表示已提交事实或 Inbound 事件的稳定身份。 */
public record EventId(String value) {

    /** 拒绝缺失或空白的事件身份。 */
    public EventId {
        value = ApiContracts.requireText(value, "value");
    }
}

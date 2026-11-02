package com.skillforge.runtime.api;

/** 表示 Unified Inbound 的稳定幂等身份。 */
public record InputId(String value) {

    /** 拒绝缺失或空白的输入身份。 */
    public InputId {
        value = ApiContracts.requireText(value, "value");
    }
}

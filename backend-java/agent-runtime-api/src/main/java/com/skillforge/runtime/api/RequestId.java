package com.skillforge.runtime.api;

/** 表示一次只读 façade 调用的稳定请求身份。 */
public record RequestId(String value) {

    /** 拒绝缺失或空白的请求身份。 */
    public RequestId {
        value = ApiContracts.requireText(value, "value");
    }
}

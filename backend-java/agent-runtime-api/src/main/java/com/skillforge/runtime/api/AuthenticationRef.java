package com.skillforge.runtime.api;

/** 表示身份边界签发的不可逆认证事实引用，不承载可反解凭据。 */
public record AuthenticationRef(String value) {

    /** 拒绝缺失或空白的认证事实引用。 */
    public AuthenticationRef {
        value = ApiContracts.requireText(value, "value");
    }
}

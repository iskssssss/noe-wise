package com.skillforge.runtime.api;

/** 表示所有公开运行时对象的租户隔离身份。 */
public record TenantId(String value) {

    /** 拒绝缺失或空白的租户身份。 */
    public TenantId {
        value = ApiContracts.requireText(value, "value");
    }
}

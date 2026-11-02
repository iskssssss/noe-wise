package com.skillforge.runtime.api;

/** 表示公开结果或审计事实的类型化引用。 */
public record ResultRef(String value) {

    /** 拒绝缺失或空白的结果引用。 */
    public ResultRef {
        value = ApiContracts.requireText(value, "value");
    }
}

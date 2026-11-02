package com.skillforge.runtime.api;

/** 表示 Memory 治理策略中的保留类别引用。 */
public record RetentionClassRef(String value) {

    /** 拒绝空白保留类别引用。 */
    public RetentionClassRef {
        value = ApiContracts.requireText(value, "value");
    }
}

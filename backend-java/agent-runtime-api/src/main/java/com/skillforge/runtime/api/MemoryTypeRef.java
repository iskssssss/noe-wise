package com.skillforge.runtime.api;

/** 表示 Memory 类型注册表中的稳定类型引用。 */
public record MemoryTypeRef(String value) {

    /** 拒绝空白记忆类型引用。 */
    public MemoryTypeRef {
        value = ApiContracts.requireText(value, "value");
    }
}

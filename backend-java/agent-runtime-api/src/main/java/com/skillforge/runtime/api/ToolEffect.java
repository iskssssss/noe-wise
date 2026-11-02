package com.skillforge.runtime.api;

/** 描述 Tool 调用可能产生的外部副作用级别。 */
public enum ToolEffect {
    READ_ONLY,
    IDEMPOTENT_WRITE,
    NON_IDEMPOTENT_WRITE,
    DESTRUCTIVE
}

package com.skillforge.runtime.api.port;

/** 描述失败后已经证明安全的后续协调方式。 */
public enum RetryMode {
    NONE,
    IDEMPOTENT_BY_STABLE_KEY,
    RESULT_QUERYABLE,
    COORDINATED
}

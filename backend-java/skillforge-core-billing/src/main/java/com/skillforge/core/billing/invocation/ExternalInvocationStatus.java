package com.skillforge.core.billing.invocation;

/** 外部调用尝试的持久状态；UNKNOWN 结果只能以 UNRESOLVED 表示，不能伪造成功或零费用。 */
public enum ExternalInvocationStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
    UNRESOLVED
}

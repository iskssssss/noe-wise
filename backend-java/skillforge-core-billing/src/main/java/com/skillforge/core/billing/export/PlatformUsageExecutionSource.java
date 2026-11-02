package com.skillforge.core.billing.export;

/** 实际 typed Run 的请求者与原消息；不能由 Session 创建者或模型参数补造。 */
public record PlatformUsageExecutionSource(Long groupBindingId, String requesterKind, String requesterRef,
        String originSessionId, String originEventId) { }
